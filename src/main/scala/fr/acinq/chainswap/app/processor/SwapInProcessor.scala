package fr.acinq.chainswap.app.processor

import fr.acinq.eclair._
import fr.acinq.chainswap.app._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.chainswap.app.processor.SwapInProcessor._

import scala.util.{Failure, Success, Try}
import fr.acinq.eclair.{Kit, MilliSatoshi}
import com.google.common.cache.{CacheLoader, LoadingCache}
import fr.acinq.eclair.payment.{PaymentFailed, PaymentRequest, PaymentSent}
import fr.acinq.chainswap.app.db.{Account2LNWithdrawals, BTCDeposits, Blocking, Addresses}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentRequest
import fr.acinq.chainswap.app.db.Blocking.timeout
import fr.acinq.eclair.router.RouteCalculation
import slick.jdbc.PostgresProfile
import fr.acinq.bitcoin.Satoshi
import grizzled.slf4j.Logging
import scala.concurrent.Await
import akka.actor.Actor
import akka.pattern.ask
import java.util.UUID


object SwapInProcessor {
  case class AccountStatusFrom(accountId: String)
  case class AccountStatusTo(state: SwapInState, accountId: String)

  case class SwapInRequestFrom(accountId: String)
  case class SwapInResponseTo(response: SwapInResponse, accountId: String)

  case class SwapInWithdrawRequestFrom(request: SwapInPaymentRequest, accountId: String)
  case class SwapInWithdrawRequestDeniedTo(paymentRequest: String, reason: String, accountId: String)
}

class SwapInProcessor(vals: Vals, kit: Kit, db: PostgresProfile.backend.Database) extends Actor with Logging {
  context.system.eventStream.subscribe(channel = classOf[PaymentFailed], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PaymentSent], subscriber = self)

  val completeDepositSumLoader: CacheLoader[String, Satoshi] =
    new CacheLoader[String, Satoshi] {
      def load(accountId: String): Satoshi = {
        val query = BTCDeposits.findSumCompleteForAccountCompiled(accountId, vals.depthThreshold)
        Blocking.txRead(query.result, db).map(Satoshi).getOrElse(0L.sat)
      }
    }

  type PendingDepositsList = List[PendingDeposit]
  val pendingDepositsLoader: CacheLoader[String, PendingDepositsList] =
    new CacheLoader[String, PendingDepositsList] {
      def load(accountId: String): PendingDepositsList = {
        val lookBackPeriod = System.currentTimeMillis - vals.lookBackPeriodMsecs
        val query = BTCDeposits.findWaitingForAccountCompiled(accountId, vals.depthThreshold, lookBackPeriod)
        Blocking.txRead(query.result, db).map(tuple => BTCDeposit.tupled(tuple).toPendingDeposit).toList
      }
    }

  val successfulWithdrawalSumLoader: CacheLoader[String, MilliSatoshi] =
    new CacheLoader[String, MilliSatoshi] {
      def load(accountId: String): MilliSatoshi = {
        val query = Account2LNWithdrawals.findSuccessfulWithdrawalSumCompiled(accountId)
        Blocking.txRead(query.result, db).map(MilliSatoshi.apply).getOrElse(0L.msat)
      }
    }

  val pendingWithdrawalsLoader: CacheLoader[String, MilliSatoshi] =
    new CacheLoader[String, MilliSatoshi] {
      def load(accountId: String): MilliSatoshi = {
        val query = Account2LNWithdrawals.findPendingWithdrawalsByAccountCompiled(accountId)
        Blocking.txRead(query.result, db).map(MilliSatoshi.apply).getOrElse(0L.msat)
      }
    }

  val completeDepositSum: LoadingCache[String, Satoshi] = Tools.makeExpireAfterAccessCache(1440 * 60).maximumSize(5000000).build(completeDepositSumLoader)
  val pendingDeposits: LoadingCache[String, PendingDepositsList] = Tools.makeExpireAfterAccessCache(1440 * 60).maximumSize(5000000).build(pendingDepositsLoader)
  val successfulWithdrawalSum: LoadingCache[String, MilliSatoshi] = Tools.makeExpireAfterAccessCache(1440 * 60).maximumSize(5000000).build(successfulWithdrawalSumLoader)
  val pendingWithdrawalSum: LoadingCache[String, MilliSatoshi] = Tools.makeExpireAfterAccessCache(1440 * 60).maximumSize(5000000).build(pendingWithdrawalsLoader)

  override def receive: Receive = {
    case AccountStatusFrom(accountId) =>
      val reply @ SwapInState(balance, inFlight, pendingChainDeposits) = getSwapInState(accountId)
      val shouldReply = balance > 0.msat || inFlight > 0.msat || pendingChainDeposits.nonEmpty
      if (shouldReply) context.parent ! AccountStatusTo(reply, accountId)

    case request @ SwapInRequestFrom(accountId) =>
      val query = Addresses.findByAccountIdCompiled(accountId)
      val addressOpt = Blocking.txRead(query.result, db).headOption

      addressOpt match {
        case Some(btcAddress) =>
          val response = SwapInResponse(btcAddress, vals.minChainDepositSat.sat)
          context.parent ! SwapInResponseTo(response, accountId)

        case None =>
          val tuple = (vals.bitcoinAPI.getNewAddress, accountId)
          Blocking.txWrite(Addresses.insertCompiled += tuple, db)
          self ! request
      }

    case message: ChainDepositReceived =>
      pendingDeposits.invalidate(message.accountId)
      val isComplete = message.depth >= vals.depthThreshold
      if (isComplete) completeDepositSum.invalidate(message.accountId)
      self ! AccountStatusFrom(message.accountId)

    case SwapInWithdrawRequestFrom(request, accountId) =>
      Try(PaymentRequest read request.paymentRequest) match {
        case Success(paymentRequest) if paymentRequest.amount.isEmpty =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=amount-less invoice, account=$accountId")
          context.parent ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, "Invoice should have an amount", accountId)

        case Success(pr) =>
          val finalAmount = pr.amount.get
          val swapInState = getSwapInState(accountId)

          if (finalAmount > swapInState.balance) {
            logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=invoice amount above balance ${swapInState.balance.truncateToSatoshi.toLong} sat, account=$accountId")
            context.parent ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, s"Invoice amount should not exceed balance ${swapInState.balance.truncateToSatoshi.toLong} sat", accountId)
          } else try {
            val routeParams = RouteCalculation.getDefaultRouteParams(kit.nodeParams.routerConf).copy(maxFeePct = 0D, maxFeeBase = 100L.msat)
            logger.info(s"PLGN ChainSwap, WithdrawBTCLN, validation success, trying to send LN with payment hash=${pr.paymentHash}, amount=${finalAmount.toLong} msat, account=$accountId")
            val spr = SendPaymentRequest(finalAmount, pr.paymentHash, pr.nodeId, kit.nodeParams.maxPaymentAttempts, paymentRequest = Some(pr), routeParams = Some(routeParams), assistedRoutes = pr.routingInfo)
            val tuple = (accountId, Await.result(kit.paymentInitiator ? spr, Blocking.span).asInstanceOf[UUID].toString, finalAmount.toLong, System.currentTimeMillis, Account2LNWithdrawals.PENDING)
            Blocking.txWrite(Account2LNWithdrawals.insertCompiled += tuple, db)
            pendingWithdrawalSum.invalidate(accountId)
            self ! AccountStatusFrom(accountId)
          } catch {
            case error: Throwable =>
              logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=${error.getMessage}, account=$accountId")
              context.parent ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, "Please try again later", accountId)
          }

        case Failure(error) =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=${error.getMessage}, account=$accountId")
          context.parent ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, "Please try again later", accountId)
      }

    case message: PaymentFailed =>
      Blocking.txRead(Account2LNWithdrawals.findAccountByIdCompiled(message.id.toString).result, db) foreach { accountId =>
        Blocking.txWrite(Account2LNWithdrawals.findStatusByIdUpdatableCompiled(message.id.toString).update(Account2LNWithdrawals.FAILED), db)
        pendingWithdrawalSum.invalidate(accountId)
        self ! AccountStatusFrom(accountId)
      }

    case message: PaymentSent =>
      Blocking.txRead(Account2LNWithdrawals.findAccountByIdCompiled(message.id.toString).result, db) foreach { accountId =>
        Blocking.txWrite(Account2LNWithdrawals.findStatusByIdUpdatableCompiled(message.id.toString).update(Account2LNWithdrawals.SUCCEEDED), db)
        successfulWithdrawalSum.invalidate(accountId)
        pendingWithdrawalSum.invalidate(accountId)
        self ! AccountStatusFrom(accountId)
      }
  }

  def getSwapInState(accountId: String): SwapInState = {
    val completeDepositSum1 = completeDepositSum.get(accountId)
    val successfulWithdrawalSum1 = successfulWithdrawalSum.get(accountId)
    val pendingWithdrawalSum1 = pendingWithdrawalSum.get(accountId)
    val pendingDeposits1 = pendingDeposits.get(accountId)

    val balance = completeDepositSum1 - successfulWithdrawalSum1
    SwapInState(balance, pendingWithdrawalSum1, pendingDeposits1)
  }
}