package fr.acinq.chainswap.app.processor

import fr.acinq.eclair._
import fr.acinq.chainswap.app._
import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import scala.util.{Failure, Success, Try}
import fr.acinq.eclair.{Kit, MilliSatoshi}
import com.google.common.cache.{CacheLoader, LoadingCache}
import fr.acinq.eclair.payment.{PaymentFailed, PaymentRequest, PaymentSent}
import fr.acinq.chainswap.app.dbo.{Account2LNWithdrawals, BTCDeposits, Blocking, Users}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentRequest
import fr.acinq.eclair.router.RouteCalculation
import slick.jdbc.PostgresProfile
import fr.acinq.bitcoin.Satoshi
import grizzled.slf4j.Logging
import akka.util.Timeout
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
        val query = BTCDeposits.findSumCompleteForUserCompiled(accountId, vals.depthThreshold)
        Blocking.txRead(query.result, db).map(Satoshi) getOrElse Satoshi(0L)
      }
    }

  val pendingDepositsLoader: CacheLoader[String, PendingDepositsList] =
    new CacheLoader[String, PendingDepositsList] {
      def load(accountId: String): PendingDepositsList = {
        val lookBackPeriod = System.currentTimeMillis - vals.lookBackPeriodMsecs
        val query = BTCDeposits.findWaitingForUserCompiled(accountId, vals.depthThreshold, lookBackPeriod)
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
  implicit val timeout: Timeout = Timeout(30.seconds)

  override def receive: Receive = {
    case AccountStatusFrom(userId) =>
      val swapInState = getSwapInState(userId)
      val shouldReply = isImportant(swapInState)
      // Do not initiate a wire message if nothing of interest is happening
      if (shouldReply) context.parent ! AccountStatusTo(swapInState, userId)

    case request @ SwapInRequestFrom(userId) =>
      val query = Users.findByAccountIdCompiled(userId)
      val addressOpt = Blocking.txRead(query.result, db)

      addressOpt match {
        case btcAddress +: _ =>
          val response = SwapInResponse(btcAddress)
          context.parent ! SwapInResponseTo(response, userId)

        case _ =>
          val tuple = Tuple2(vals.bitcoinAPI.getNewAddress, userId)
          Blocking.txWrite(Users.insertCompiled += tuple, db)
          self ! request
      }

    case message: ChainDepositReceived =>
      pendingDeposits.invalidate(message.userId)
      val isComplete = message.depth >= vals.depthThreshold
      if (isComplete) completeDepositSum.invalidate(message.userId)
      self ! AccountStatusFrom(message.userId)

    case SwapInWithdrawRequestFrom(request, userId) =>
      Try(PaymentRequest read request.paymentRequest) match {
        case Success(paymentRequest) if paymentRequest.amount.isEmpty =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=amount-less invoice, account=$userId")
          sender ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, "Invoice should have an amount", userId)

        case Success(paymentRequest) if paymentRequest.amount.get < MilliSatoshi(vals.lnMinWithdrawMsat) =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=invoice amount below min ${vals.lnMinWithdrawMsat} msat, account=$userId")
          sender ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, s"Invoice should have an amount larger than ${vals.lnMinWithdrawMsat} msat", userId)

        case Success(pr) =>
          val finalAmount = pr.amount.get
          val swapInState = getSwapInState(userId)

          if (finalAmount > swapInState.maxWithdrawable) {
            logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=invoice amount above max withdrawable ${swapInState.maxWithdrawable.truncateToSatoshi.toLong} sat, account=$userId")
            sender ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, s"Invoice amount should not exceed max withdrawable ${swapInState.maxWithdrawable.truncateToSatoshi.toLong} sat", userId)
          } else try {
            val feeReserve = finalAmount - finalAmount * vals.lnMaxFeePct
            val routeParams = RouteCalculation.getDefaultRouteParams(kit.nodeParams.routerConf).copy(maxFeePct = vals.lnMaxFeePct)
            val spr = SendPaymentRequest(finalAmount, pr.paymentHash, pr.nodeId, kit.nodeParams.maxPaymentAttempts, paymentRequest = Some(pr), routeParams = Some(routeParams), assistedRoutes = pr.routingInfo)
            logger.info(s"PLGN ChainSwap, WithdrawBTCLN, validation success, trying to send LN with payment hash=${pr.paymentHash}, amount=${finalAmount.toLong} msat, account=$userId")
            val tuple = (userId, (kit.paymentInitiator ? spr).mapTo[UUID].toString, feeReserve.toLong, finalAmount.toLong, System.currentTimeMillis, Account2LNWithdrawals.PENDING)
            Blocking.txWrite(Account2LNWithdrawals.insertCompiled += tuple, db)
            pendingWithdrawals.invalidate(userId)
            self ! AccountStatusFrom(userId)
          } catch {
            case error: Throwable =>
              logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=${error.getMessage}, account=$userId")
              sender ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, "Please try again later", userId)
          }

        case Failure(error) =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN, fail=${error.getMessage}, account=$userId")
          sender ! SwapInWithdrawRequestDeniedTo(request.paymentRequest, "Please try again later", userId)
      }

    case message: PaymentFailed =>
      Blocking.txRead(Account2LNWithdrawals.findAccountByIdCompiled(message.id.toString).result, db) foreach { userId =>
        val query = Account2LNWithdrawals.findStatusFeeByIdUpdatableCompiled(message.id.toString)
        val updateTuple = Tuple2(Account2LNWithdrawals.FAILED, 0L)
        Blocking.txWrite(query.update(updateTuple), db)
        pendingWithdrawals.invalidate(userId)
        self ! AccountStatusFrom(userId)
      }

    case message: PaymentSent =>
      Blocking.txRead(Account2LNWithdrawals.findAccountByIdCompiled(message.id.toString).result, db) foreach { userId =>
        val query = Account2LNWithdrawals.findStatusFeeByIdUpdatableCompiled(message.id.toString)
        val updateTuple = Tuple2(Account2LNWithdrawals.SUCCEEDED, message.feesPaid.toLong)
        Blocking.txWrite(query.update(updateTuple), db)
        successfulWithdrawalSum.invalidate(userId)
        pendingWithdrawals.invalidate(userId)
        self ! AccountStatusFrom(userId)
      }
  }

  def getSwapInState(userId: String): SwapInState = {
    val completeDepositSum1 = completeDepositSum.get(userId)
    val successfulWithdrawalSum1 = successfulWithdrawalSum.get(userId)
    val (inFlightPayments, inFlightReserves) = pendingWithdrawals.get(userId).unzip
    val pendingDeposits1 = pendingDeposits.get(userId)

    val totalActiveReserve = MilliSatoshi(inFlightReserves.sum)
    val totalInFlightAmount = MilliSatoshi(inFlightPayments.sum)

    val balance = completeDepositSum1 - successfulWithdrawalSum1 - totalInFlightAmount - totalActiveReserve
    SwapInState(balance, balance - balance * vals.lnMaxFeePct, totalActiveReserve, totalInFlightAmount, pendingDeposits1)
  }

  def isImportant(state: SwapInState): Boolean =
    state.balance >= vals.lnMinWithdrawMsat.msat ||
      state.pendingChainDeposits.nonEmpty ||
      state.inFlightAmount > 0.msat
}