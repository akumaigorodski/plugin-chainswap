package fr.acinq.chainswap.app.processor

import fr.acinq.eclair._
import fr.acinq.chainswap.app._
import slick.jdbc.PostgresProfile.api._
import scala.util.{Failure, Success, Try}
import fr.acinq.eclair.{Kit, MilliSatoshi}
import com.google.common.cache.{CacheLoader, LoadingCache}
import fr.acinq.eclair.payment.{PaymentFailed, PaymentRequest, PaymentSent}
import fr.acinq.chainswap.app.dbo.{Account2LNWithdrawals, BTCDeposits, Blocking, Accounts}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentRequest
import fr.acinq.chainswap.app.dbo.Blocking.askTimeout
import fr.acinq.eclair.router.RouteCalculation
import slick.jdbc.PostgresProfile
import fr.acinq.bitcoin.Satoshi
import grizzled.slf4j.Logging
import scala.concurrent.Await
import akka.actor.Actor
import akka.pattern.ask
import java.util.UUID


class SwapInProcessor(vals: Vals, kit: Kit, db: PostgresProfile.backend.Database) extends Actor with Logging {
  context.system.eventStream.subscribe(channel = classOf[PaymentFailed], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PaymentSent], subscriber = self)

  type PendingAndReserve = (Long, Long)
  type PendingWithdrawalsSeq = Seq[PendingAndReserve]
  type PendingDepositsList = List[PendingDeposit]

  val completeDepositSumLoader: CacheLoader[String, Satoshi] =
    new CacheLoader[String, Satoshi] {
      def load(accountId: String): Satoshi = {
        val query = BTCDeposits.findSumCompleteForAccountCompiled(accountId, vals.depthThreshold)
        Blocking.txRead(query.result, db).map(Satoshi) getOrElse Satoshi(0L)
      }
    }

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
        Blocking.txRead(query.result, db).map(MilliSatoshi.apply) getOrElse MilliSatoshi(0L)
      }
    }

  val pendingWithdrawalsLoader: CacheLoader[String, PendingWithdrawalsSeq] =
    new CacheLoader[String, PendingWithdrawalsSeq] {
      def load(accountId: String): PendingWithdrawalsSeq = {
        val query = Account2LNWithdrawals.findPendingWithdrawalsByAccountCompiled(accountId)
        Blocking.txRead(query.result, db)
      }
    }

  val completeDepositSum: LoadingCache[String, Satoshi] = Tools.makeExpireAfterAccessCache(1440 * 30).maximumSize(5000000).build(completeDepositSumLoader)
  val pendingDeposits: LoadingCache[String, PendingDepositsList] = Tools.makeExpireAfterAccessCache(1440 * 30).maximumSize(5000000).build(pendingDepositsLoader)
  val successfulWithdrawalSum: LoadingCache[String, MilliSatoshi] = Tools.makeExpireAfterAccessCache(1440 * 30).maximumSize(5000000).build(successfulWithdrawalSumLoader)
  val pendingWithdrawals: LoadingCache[String, PendingWithdrawalsSeq] = Tools.makeExpireAfterAccessCache(1440 * 30).maximumSize(5000000).build(pendingWithdrawalsLoader)

  override def receive: Receive = {
    case AccountStatusFrom(accountId) =>
      val swapInState = getSwapInState(accountId)
      val shouldReply: Boolean = isImportant(swapInState)
      // Do not initiate a wire message if nothing of interest is happening
      if (shouldReply) context.parent ! AccountStatusTo(swapInState, accountId)

    case request @ SwapInRequestFrom(accountId) =>
      val query = Accounts.findByAccountIdCompiled(accountId)
      val addressOpt = Blocking.txRead(query.result, db).headOption

      addressOpt match {
        case Some(btcAddress) =>
          val response = SwapInResponse(btcAddress)
          context.parent ! SwapInResponseTo(response, accountId)

        case None =>
          val tuple = (vals.bitcoinAPI.getNewAddress, accountId)
          Blocking.txWrite(Accounts.insertCompiled += tuple, db)
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

        case Success(paymentRequest) if paymentRequest.amount.get < MilliSatoshi(vals.lnMinWithdrawMsat) =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=invoice amount below min ${vals.lnMinWithdrawMsat} msat, account=$accountId")
          context.parent ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, s"Invoice should have an amount larger than ${vals.lnMinWithdrawMsat} msat", accountId)

        case Success(pr) =>
          val finalAmount = pr.amount.get
          val swapInState = getSwapInState(accountId)

          if (finalAmount > swapInState.maxWithdrawable) {
            logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=invoice amount above max withdrawable ${swapInState.maxWithdrawable.truncateToSatoshi.toLong} sat, account=$accountId")
            context.parent ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, s"Invoice amount should not exceed max withdrawable ${swapInState.maxWithdrawable.truncateToSatoshi.toLong} sat", accountId)
          } else try {
            val feeReserve = finalAmount * vals.lnMaxFeePct
            val routeParams = RouteCalculation.getDefaultRouteParams(kit.nodeParams.routerConf).copy(maxFeePct = vals.lnMaxFeePct)
            logger.info(s"PLGN ChainSwap, WithdrawBTCLN, validation success, trying to send LN with payment hash=${pr.paymentHash}, amount=${finalAmount.toLong} msat, account=$accountId")
            val spr = SendPaymentRequest(finalAmount, pr.paymentHash, pr.nodeId, kit.nodeParams.maxPaymentAttempts, paymentRequest = Some(pr), routeParams = Some(routeParams), assistedRoutes = pr.routingInfo)
            val tuple = (accountId, Await.result(kit.paymentInitiator ? spr, Blocking.span).asInstanceOf[UUID].toString, feeReserve.toLong, finalAmount.toLong, System.currentTimeMillis, Account2LNWithdrawals.PENDING)
            Blocking.txWrite(Account2LNWithdrawals.insertCompiled += tuple, db)
            pendingWithdrawals.invalidate(accountId)
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
        val query = Account2LNWithdrawals.findStatusFeeByIdUpdatableCompiled(message.id.toString)
        val updateTuple = Tuple2(Account2LNWithdrawals.FAILED, 0L)
        Blocking.txWrite(query.update(updateTuple), db)
        pendingWithdrawals.invalidate(accountId)
        self ! AccountStatusFrom(accountId)
      }

    case message: PaymentSent =>
      Blocking.txRead(Account2LNWithdrawals.findAccountByIdCompiled(message.id.toString).result, db) foreach { accountId =>
        val query = Account2LNWithdrawals.findStatusFeeByIdUpdatableCompiled(message.id.toString)
        val updateTuple = Tuple2(Account2LNWithdrawals.SUCCEEDED, message.feesPaid.toLong)
        Blocking.txWrite(query.update(updateTuple), db)
        successfulWithdrawalSum.invalidate(accountId)
        pendingWithdrawals.invalidate(accountId)
        self ! AccountStatusFrom(accountId)
      }
  }

  def getSwapInState(accountId: String): SwapInState = {
    val completeDepositSum1 = completeDepositSum.get(accountId)
    val successfulWithdrawalSum1 = successfulWithdrawalSum.get(accountId)
    val (inFlightPayments, inFlightReserves) = pendingWithdrawals.get(accountId).unzip
    val pendingDeposits1 = pendingDeposits.get(accountId)

    val totalActiveReserve = MilliSatoshi(inFlightReserves.sum)
    val totalInFlightAmount = MilliSatoshi(inFlightPayments.sum)
    val maxWithdrawFactor = 100 / (100 + 100 * vals.lnMaxFeePct)

    val balance = completeDepositSum1 - successfulWithdrawalSum1 - totalInFlightAmount - totalActiveReserve
    SwapInState(balance, balance * maxWithdrawFactor, totalActiveReserve, totalInFlightAmount, pendingDeposits1)
  }

  def isImportant(state: SwapInState): Boolean =
    state.balance >= vals.lnMinWithdrawMsat.msat ||
      state.pendingChainDeposits.nonEmpty ||
      state.inFlightAmount > 0.msat
}