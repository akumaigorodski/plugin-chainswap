package fr.acinq.chainswap.app.processor

import fr.acinq.eclair._
import fr.acinq.chainswap.app._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.chainswap.app.processor.SwapInProcessor._

import scala.util.{Failure, Success, Try}
import com.google.common.cache.{CacheLoader, LoadingCache}
import fr.acinq.chainswap.app.db.{BTCDeposits, Blocking, Addresses}
import fr.acinq.eclair.payment.{PaymentFailed, PaymentRequest, PaymentSent}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentRequest
import fr.acinq.chainswap.app.db.Blocking.timeout
import fr.acinq.eclair.router.RouteCalculation
import slick.jdbc.PostgresProfile
import grizzled.slf4j.Logging
import scala.concurrent.Await
import fr.acinq.eclair.Kit
import akka.actor.Actor
import akka.pattern.ask
import java.util.UUID


object SwapInProcessor {
  case class AccountStatusFrom(accountId: String)
  case class AccountStatusTo(state: SwapInState, accountId: String)

  case class SwapInRequestFrom(accountId: String)
  case class SwapInResponseTo(response: SwapInResponse, accountId: String)

  case class SwapInWithdrawRequestFrom(request: SwapInPaymentRequest, accountId: String)
  case class SwapInWithdrawRequestDeniedTo(paymentRequest: String, reason: Long, accountId: String)
}

class SwapInProcessor(vals: Vals, kit: Kit, db: PostgresProfile.backend.Database) extends Actor with Logging {
  context.system.eventStream.subscribe(channel = classOf[PaymentFailed], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PaymentSent], subscriber = self)

  type ChainDepositList = List[ChainDeposit]

  val waitingForAccountLoader: CacheLoader[String, ChainDepositList] =
    new CacheLoader[String, ChainDepositList] {
      def load(accountId: String): ChainDepositList = {
        val lookBackPeriod = System.currentTimeMillis - vals.lookBackPeriodMsecs
        val query = BTCDeposits.findWaitingForAccountCompiled(accountId, vals.depthThreshold, lookBackPeriod)
        Blocking.txRead(query.result, db).map(ChainDeposit.tupled).toList
      }
    }

  val withdrawableForAccountLoader: CacheLoader[String, ChainDepositList] =
    new CacheLoader[String, ChainDepositList] {
      def load(accountId: String): ChainDepositList = {
        val query = BTCDeposits.findWithdrawableForAccountCompiled(accountId, vals.depthThreshold)
        Blocking.txRead(query.result, db).map(ChainDeposit.tupled).toList
      }
    }

  val inFlightForAccountLoader: CacheLoader[String, ChainDepositList] =
    new CacheLoader[String, ChainDepositList] {
      def load(accountId: String): ChainDepositList = {
        val query = BTCDeposits.findInFlightForAccountCompiled(accountId, vals.depthThreshold)
        Blocking.txRead(query.result, db).map(ChainDeposit.tupled).toList
      }
    }

  val waitingForAccount: LoadingCache[String, ChainDepositList] = Tools.makeExpireAfterAccessCache(1440 * 60).maximumSize(5000000).build(waitingForAccountLoader)
  val withdrawableForAccount: LoadingCache[String, ChainDepositList] = Tools.makeExpireAfterAccessCache(1440 * 60).maximumSize(5000000).build(withdrawableForAccountLoader)
  val inFlightForAccount: LoadingCache[String, ChainDepositList] = Tools.makeExpireAfterAccessCache(1440 * 60).maximumSize(5000000).build(inFlightForAccountLoader)

  override def receive: Receive = {
    case AccountStatusFrom(accountId) =>
      val waiting: ChainDepositList = waitingForAccount.get(accountId)
      val withdrawable: ChainDepositList = withdrawableForAccount.get(accountId)
      val inFlight: ChainDepositList = inFlightForAccount.get(accountId)
      val state = SwapInState(waiting, withdrawable, inFlight)
      context.parent ! AccountStatusTo(state, accountId)

    case SwapInRequestFrom(accountId) =>
      val query = Addresses.findByAccountIdCompiled(accountId)
      val addressOpt = Blocking.txRead(query.result, db).headOption

      addressOpt match {
        case Some(btcAddress) =>
          val response = SwapInResponse(btcAddress, vals.minChainDepositSat.sat)
          context.parent ! SwapInResponseTo(response, accountId)

        case None =>
          val tuple = (vals.bitcoinAPI.getNewAddress, accountId)
          Blocking.txWrite(Addresses.insertCompiled += tuple, db)
          self ! SwapInRequestFrom(accountId)
      }

    case message: ChainDepositReceived =>
      waitingForAccount.invalidate(message.accountId)
      val isDeepEnough = message.depth >= vals.depthThreshold
      if (isDeepEnough) withdrawableForAccount.invalidate(message.accountId)
      self ! AccountStatusFrom(message.accountId)

    case SwapInWithdrawRequestFrom(request, accountId) =>
      Try(PaymentRequest read request.paymentRequest) match {
        case _ if inFlightForAccount.get(accountId).exists(_.id == request.id) =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=withdrawal already in-flight, id=${request.id}, account=$accountId")
          context.parent ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, SwapInPaymentDenied.WITHDRAWAL_ALREADY_IN_FLIGHT, accountId)

        case Success(pr) =>
          withdrawableForAccount.get(accountId).find(_.id == request.id) match {
            case Some(deposit) if pr.amount.exists(_.truncateToSatoshi == deposit.amountSat.sat) =>
              val routeParams = RouteCalculation.getDefaultRouteParams(kit.nodeParams.routerConf).copy(maxFeePct = 0D, maxFeeBase = 1000L.msat)
              logger.info(s"PLGN ChainSwap, WithdrawBTCLN, validation success, sending LN payment with hash=${pr.paymentHash}, amountMsat=${pr.amount.get.toLong}, account=$accountId")
              val spr = SendPaymentRequest(pr.amount.get, pr.paymentHash, pr.nodeId, kit.nodeParams.maxPaymentAttempts, paymentRequest = Some(pr), routeParams = Some(routeParams), assistedRoutes = Nil)
              Blocking.txWrite(BTCDeposits.findLNUpdatableByIdCompiled(deposit.id).update(Some(Await.result(kit.paymentInitiator ? spr, Blocking.span).asInstanceOf[UUID].toString) -> BTCDeposits.LN_IN_FLIGHT), db)
              withdrawableForAccount.invalidate(accountId)
              inFlightForAccount.invalidate(accountId)
              self ! AccountStatusFrom(accountId)

            case Some(deposit) =>
              logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=amount mismatch, amountMsat=${pr.amount}, txSat=${deposit.amountSat}, account=$accountId")
              context.parent ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, SwapInPaymentDenied.INVOICE_TX_AMOUNT_MISMATCH, accountId)

            case None =>
              logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=no withdrawable tx found, id=${request.id}, account=$accountId")
              context.parent ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, SwapInPaymentDenied.NO_WITHDRAWABLE_TX_FOUND, accountId)
          }

        case Failure(error) =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=${error.getMessage}, id=${request.id}, account=$accountId")
          context.parent ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, SwapInPaymentDenied.INVALID_INVOICE, accountId)
      }

    case message: PaymentFailed =>
      val paymentId = message.id.toString
      Blocking.txRead(BTCDeposits.findAccountIdByPaymentIdCompiled(paymentId).result, db) foreach { accountId =>
        Blocking.txWrite(BTCDeposits.findLNUpdatableByPaymentIdCompiled(paymentId).update(Some(paymentId) -> BTCDeposits.LN_UNCLAIMED), db)
        withdrawableForAccount.invalidate(accountId)
        inFlightForAccount.invalidate(accountId)
        self ! AccountStatusFrom(accountId)
      }

    case message: PaymentSent =>
      val paymentId = message.id.toString
      Blocking.txRead(BTCDeposits.findAccountIdByPaymentIdCompiled(paymentId).result, db) foreach { accountId =>
        Blocking.txWrite(BTCDeposits.findLNUpdatableByPaymentIdCompiled(paymentId).update(Some(paymentId) -> BTCDeposits.LN_SUCCEEDED), db)
        withdrawableForAccount.invalidate(accountId)
        inFlightForAccount.invalidate(accountId)
        self ! AccountStatusFrom(accountId)
      }
  }
}