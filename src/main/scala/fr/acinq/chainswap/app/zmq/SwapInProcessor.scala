package fr.acinq.chainswap.app.zmq

import fr.acinq.eclair._
import fr.acinq.chainswap.app._
import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import scala.util.{Failure, Success, Try}
import fr.acinq.eclair.{Kit, MilliSatoshi}
import com.google.common.cache.{CacheLoader, LoadingCache}
import fr.acinq.eclair.payment.{PaymentFailed, PaymentRequest, PaymentSent}
import fr.acinq.chainswap.app.dbo.{Account2LNWithdrawals, BTCDeposits, Blocking}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentRequest
import fr.acinq.eclair.router.RouteCalculation
import fr.acinq.bitcoin.Satoshi
import grizzled.slf4j.Logging
import akka.util.Timeout
import akka.actor.Actor
import akka.pattern.ask
import java.util.UUID


class SwapInProcessor(vals: Vals, kit: Kit) extends Actor with Logging {
  context.system.eventStream.subscribe(self, classOf[PaymentFailed])
  context.system.eventStream.subscribe(self, classOf[PaymentSent])

  type PendingAndReserve = (Long, Long)
  type PendingWithdrawalsSeq = Seq[PendingAndReserve]
  type PendingDepositsList = List[PendingDeposit]

  val completeDepositSumLoader: CacheLoader[String, Satoshi] =
    new CacheLoader[String, Satoshi] {
      def load(accountId: String): Satoshi = {
        val query = BTCDeposits.findSumCompleteForUserCompiled(accountId, vals.depthThreshold)
        Blocking.txRead(query.result, Config.db).map(Satoshi) getOrElse Satoshi(0L)
      }
    }

  val pendingDepositsLoader: CacheLoader[String, PendingDepositsList] =
    new CacheLoader[String, PendingDepositsList] {
      def load(accountId: String): PendingDepositsList = {
        val lookBackPeriod = System.currentTimeMillis - vals.lookBackPeriodMsecs
        val query = BTCDeposits.findWaitingForUserCompiled(accountId, vals.depthThreshold, lookBackPeriod)
        Blocking.txRead(query.result, Config.db).map(tuple => BTCDeposit.tupled(tuple).toPendingDeposit).toList
      }
    }

  val successfulWithdrawalSumLoader: CacheLoader[String, MilliSatoshi] =
    new CacheLoader[String, MilliSatoshi] {
      def load(accountId: String): MilliSatoshi = {
        val query = Account2LNWithdrawals.findSuccessfulWithdrawalSumCompiled(accountId)
        Blocking.txRead(query.result, Config.db).map(MilliSatoshi.apply) getOrElse MilliSatoshi(0L)
      }
    }

  val pendingWithdrawalsLoader: CacheLoader[String, PendingWithdrawalsSeq] =
    new CacheLoader[String, PendingWithdrawalsSeq] {
      def load(accountId: String): PendingWithdrawalsSeq = {
        val query = Account2LNWithdrawals.findPendingWithdrawalsByAccountCompiled(accountId)
        Blocking.txRead(query.result, Config.db)
      }
    }

  val completeDepositSum: LoadingCache[String, Satoshi] = Tools.makeExpireAfterAccessCache(1440 * 30).maximumSize(5000000).build(completeDepositSumLoader)
  val pendingDeposits: LoadingCache[String, PendingDepositsList] = Tools.makeExpireAfterAccessCache(1440 * 30).maximumSize(5000000).build(pendingDepositsLoader)
  val successfulWithdrawalSum: LoadingCache[String, MilliSatoshi] = Tools.makeExpireAfterAccessCache(1440 * 30).maximumSize(5000000).build(successfulWithdrawalSumLoader)
  val pendingWithdrawals: LoadingCache[String, PendingWithdrawalsSeq] = Tools.makeExpireAfterAccessCache(1440 * 30).maximumSize(5000000).build(pendingWithdrawalsLoader)
  implicit val timeout: Timeout = Timeout(30.seconds)

  override def receive: Receive = {
    case GetAccountStatus(replyTo, userId) =>
      replyTo ! getSwapInState(userId)

    case message: ChainDepositReceived =>
      pendingDeposits.invalidate(message.userId)
      val isComplete = message.depth >= vals.depthThreshold
      if (isComplete) completeDepositSum.invalidate(message.userId)
      self ! GetAccountStatus(context.parent, message.userId)

    case message: WithdrawBTCLN =>
      Try(PaymentRequest read message.paymentRequest) match {
        case Success(paymentRequest) if paymentRequest.amount.isEmpty =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN fail=amount-less invoice, account=${message.userId}")
          sender ! WithdrawBTCLNDenied(message.userId, message.paymentRequest, "Invoice should have an amount")

        case Success(paymentRequest) if paymentRequest.amount.get < MilliSatoshi(vals.lnMinWithdrawMsat) =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN fail=invoice amount below min ${vals.lnMinWithdrawMsat} msat, account=${message.userId}")
          sender ! WithdrawBTCLNDenied(message.userId, message.paymentRequest, s"Invoice should have an amount larger than ${vals.lnMinWithdrawMsat} msat")

        case Success(pr) =>
          val finalAmount = pr.amount.get
          val swapInState = getSwapInState(message.userId)
          val feeReserve = finalAmount - finalAmount * vals.lnMaxFeePct

          if (finalAmount > swapInState.maxWithdrawable) {
            logger.info(s"PLGN ChainSwap, WithdrawBTCLN fail=invoice amount above max withdrawable ${swapInState.maxWithdrawable.truncateToSatoshi.toLong} sat, account=${message.userId}")
            sender ! WithdrawBTCLNDenied(message.userId, message.paymentRequest, s"Invoice amount should not exceed max withdrawable ${swapInState.maxWithdrawable.truncateToSatoshi.toLong} sat")
          } else try {
            val routeParams = RouteCalculation.getDefaultRouteParams(kit.nodeParams.routerConf).copy(maxFeePct = vals.lnMaxFeePct)
            val spr = SendPaymentRequest(finalAmount, pr.paymentHash, pr.nodeId, kit.nodeParams.maxPaymentAttempts, paymentRequest = Some(pr), routeParams = Some(routeParams), assistedRoutes = pr.routingInfo)
            logger.info(s"PLGN ChainSwap, WithdrawBTCLN validation success, trying to send LN with payment hash=${pr.paymentHash}, amount=${finalAmount.toLong} msat, account=${message.userId}")
            val tuple = (message.userId, (kit.paymentInitiator ? spr).mapTo[UUID].toString, feeReserve.toLong, finalAmount.toLong, System.currentTimeMillis, Account2LNWithdrawals.PENDING)
            Blocking.txWrite(Account2LNWithdrawals.insertCompiled += tuple, Config.db)
            self ! GetAccountStatus(context.parent, message.userId)
            pendingWithdrawals.invalidate(message.userId)
          } catch {
            case error: Throwable =>
              logger.info(s"PLGN ChainSwap, WithdrawBTCLN fail=${error.getMessage}, account=${message.userId}")
              sender ! WithdrawBTCLNDenied(message.userId, message.paymentRequest, "Please try again later")
          }

        case Failure(error) =>
          logger.info(s"PLGN ChainSwap, WithdrawBTCLN fail=${error.getMessage}, account=${message.userId}")
          sender ! WithdrawBTCLNDenied(message.userId, message.paymentRequest, "Please try again later")
      }

    case message: PaymentFailed =>
      Blocking.txRead(Account2LNWithdrawals.findAccountByIdCompiled(message.id.toString).result, Config.db) foreach { userId =>
        val query = Account2LNWithdrawals.findStatusFeeByIdUpdatableCompiled(message.id.toString)
        val updateTuple = Tuple2(Account2LNWithdrawals.FAILED, 0L)
        Blocking.txWrite(query.update(updateTuple), Config.db)
        self ! GetAccountStatus(context.parent, userId)
        pendingWithdrawals.invalidate(userId)
      }

    case message: PaymentSent =>
      Blocking.txRead(Account2LNWithdrawals.findAccountByIdCompiled(message.id.toString).result, Config.db) foreach { userId =>
        val query = Account2LNWithdrawals.findStatusFeeByIdUpdatableCompiled(message.id.toString)
        val updateTuple = Tuple2(Account2LNWithdrawals.SUCCEEDED, message.feesPaid.toLong)
        Blocking.txWrite(query.update(updateTuple), Config.db)
        self ! GetAccountStatus(context.parent, userId)
        successfulWithdrawalSum.invalidate(userId)
        pendingWithdrawals.invalidate(userId)
      }
  }

  def getSwapInState(userId: String): SwapInState = {
    val (pendingPayments, pendingReserves) = pendingWithdrawals.get(userId).unzip
    val pendingDeposits1: PendingDepositsList = pendingDeposits.get(userId)

    val successfulWithdrawalSum1: MilliSatoshi = successfulWithdrawalSum.get(userId)
    val completeDepositSum1: Satoshi = completeDepositSum.get(userId)

    val totalPendingAmount = MilliSatoshi(pendingPayments.sum)
    val totalPendingReserve = MilliSatoshi(pendingReserves.sum)
    val balance = completeDepositSum1 - successfulWithdrawalSum1 - totalPendingReserve - totalPendingAmount
    SwapInState(balance, balance - balance * vals.lnMaxFeePct, totalPendingReserve, totalPendingAmount, pendingDeposits1)
  }
}