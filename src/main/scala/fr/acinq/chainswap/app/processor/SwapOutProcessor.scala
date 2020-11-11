package fr.acinq.chainswap.app.processor

import fr.acinq.eclair._
import fr.acinq.chainswap.app._
import scala.concurrent.duration._
import fr.acinq.chainswap.app.processor.SwapOutProcessor._

import akka.actor.{Actor, Status}
import scala.util.{Failure, Success, Try}
import fr.acinq.bitcoin.{Btc, ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRequest}

import grizzled.slf4j.Logging
import com.google.common.cache.Cache
import fr.acinq.eclair.db.PaymentType
import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivePayment


object SwapOutProcessor {
  case object UpdateChainFeerates

  case class ChainFeeratesFrom(accountId: String)
  case class ChainFeeratesTo(feerates: SwapOutFeerates, accountId: String)

  case class SwapOutRequestFrom(request: SwapOutRequest, accountId: String)
  case class SwapOutResponseTo(response: SwapOutResponse, accountId: String)
  case class SwapOutDeniedTo(bitcoinAddress: String, reason: String, accountId: String)

  case class SwapOutRequestAndFee(request: SwapOutRequest, accountId: String, fee: Satoshi) {
    val totalAmount: MilliSatoshi = (request.amount + fee).toMilliSatoshi
  }
}

class SwapOutProcessor(vals: Vals, kit: Kit, getPreimage: String => ByteVector32) extends Actor with Logging {
  context.system.scheduler.scheduleWithFixedDelay(0.seconds, 60.minutes, self, UpdateChainFeerates)
  context.system.eventStream.subscribe(channel = classOf[PaymentReceived], subscriber = self)
  var currentFeerates: List[BlockTargetAndFee] = Nil
  val blockTargets = List(36, 144, 1008)

  val pendingRequests: Cache[ByteVector32, SwapOutRequestAndFee] = {
    val expiry = kit.nodeParams.paymentRequestExpiry.toMinutes.toInt + 1 // One extra minute in case of timer disparity with invoice remover
    Tools.makeExpireAfterAccessCache(expiry).maximumSize(5000000).build[ByteVector32, SwapOutRequestAndFee]
  }

  override def receive: Receive = {
    case ChainFeeratesFrom(accountId) =>
      val swapOutFeerates = SwapOutFeerates(currentFeerates)
      context.parent ! ChainFeeratesTo(swapOutFeerates, accountId)

    case SwapOutRequestFrom(request, accountId) =>
      val chainFee = selectedBlockTarget(request).fee
      val totalAmount = chainFee + request.amount

      if (Try(addressToPublicKeyScript(request.btcAddress, kit.nodeParams.chainHash).head).isFailure) {
        logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, fail=invalid chain address, address=${request.btcAddress}, account=$accountId")
        context.parent ! SwapOutDeniedTo(request.btcAddress, "Provided bitcoin address should be valid", accountId)
      } else if (totalAmount * vals.chainBalanceReserve > Btc(vals.bitcoinAPI.getBalance).toSatoshi) {
        logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, fail=depleted chain wallet, balance=${vals.bitcoinAPI.getBalance}btc, account=$accountId")
        context.parent ! SwapOutDeniedTo(request.btcAddress, "Currently we don't have enough chain funds to handle your order, please try again later", accountId)
      } else if (Satoshi(vals.chainMinWithdrawSat) > totalAmount) {
        logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, fail=too small amount, asked=${request.amount}, account=$accountId")
        context.parent ! SwapOutDeniedTo(request.btcAddress, s"Payment amount should be larger than ${vals.chainMinWithdrawSat}sat", accountId)
      } else {
        val preimage = getPreimage(accountId)
        val paymentHash = Crypto.sha256(preimage)
        val requestWithFixedFee = SwapOutRequestAndFee(request, accountId, chainFee)
        val description = s"Payment to address ${request.btcAddress} with amount: ${request.amount.toLong}sat and fee: ${chainFee.toLong}sat"
        logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, success address=${request.btcAddress}, amountSat=${request.amount.toLong}, feeSat=${chainFee.toLong}, paymentHash=${paymentHash.toHex}, account=$accountId")
        kit.paymentHandler ! ReceivePayment(Some(totalAmount.toMilliSatoshi), description, Some(kit.nodeParams.paymentRequestExpiry.toSeconds), paymentPreimage = Some(preimage), paymentType = PaymentType.SwapOut)
        pendingRequests.put(paymentHash, requestWithFixedFee)
      }

    case message: PaymentRequest =>
      Option(pendingRequests getIfPresent message.paymentHash) foreach { case SwapOutRequestAndFee(request, accountId, agreedUponFee) =>
        context.parent ! SwapOutResponseTo(SwapOutResponse(request.amount, agreedUponFee, PaymentRequest write message), accountId)
      }

    case message: Status.Failure =>
      // Payment handler replied with an error, make sure this properly times out on client side
      logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, fail=${message.cause.getMessage}")

    case message: PaymentReceived =>
      val askedWrapOpt = Option(pendingRequests getIfPresent message.paymentHash)
      val enoughWrapOpt = askedWrapOpt.filter(askedFor => message.amount >= askedFor.totalAmount)

      (enoughWrapOpt, kit.wallet) match {
        case (Some(wrap), wallet: BitcoinCoreWallet) =>
          wallet.sendToAddress(wrap.request.btcAddress, wrap.request.amount, wrap.request.blockTarget) onComplete {
            case Success(txid) => logger.info(s"PLGN ChainSwap, sendToAddress, success txid=${txid.toHex}, paymentHash=${message.paymentHash.toHex}, account=${wrap.accountId}")
            case Failure(err) => logger.info(s"PLGN ChainSwap, sendToAddress, fail reason=${err.getMessage}, paymentHash=${message.paymentHash.toHex}, account=${wrap.accountId}")
          }

        case (Some(wrap), wallet) =>
          context.parent ! SwapOutDeniedTo(wrap.request.btcAddress, s"Transaction send failure, please contact support", wrap.accountId)
          logger.info(s"PLGN ChainSwap, sendToAddress, fail reason=wrong wallet, type=${wallet.getClass.getName}")

        case _ =>
          // Do nothing
      }

    case UpdateChainFeerates =>
      // Get feerate/kb for a given block target, then reduce it to get fee per average expected tx size in kb, then convert to satoshi
      val fees = blockTargets.map(vals.bitcoinAPI.getEstimateSmartFee).map(btcPerKb => Btc(btcPerKb / vals.feePerKbDivider).toSatoshi)
      currentFeerates = blockTargets.lazyZip(fees).toList.map(BlockTargetAndFee.tupled)
  }

  def selectedBlockTarget(request: SwapOutRequest): BlockTargetAndFee =
    currentFeerates.find(_.blockTarget == request.blockTarget)
      .getOrElse(currentFeerates.head)
}
