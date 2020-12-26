package fr.acinq.chainswap.app.processor

import fr.acinq.eclair._
import fr.acinq.chainswap.app._
import scala.concurrent.duration._
import fr.acinq.chainswap.app.processor.SwapOutProcessor._
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRequest}
import fr.acinq.bitcoin.{Btc, ByteVector32, Crypto, Satoshi}
import fr.acinq.chainswap.app.db.Blocking.{span, timeout}
import scala.util.{Failure, Success, Try}

import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivePayment
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.db.PaymentType
import com.google.common.cache.Cache
import grizzled.slf4j.Logging
import scala.concurrent.Await
import akka.actor.Actor
import akka.pattern.ask


object SwapOutProcessor {
  case object UpdateChainFeerates

  case class ChainInfoRequestFrom(accountId: String)
  case class ChainInfoResponseTo(info: SwapOutFeerates, accountId: String)

  case class SwapOutRequestFrom(request: SwapOutTransactionRequest, accountId: String)
  case class SwapOutResponseTo(response: SwapOutTransactionResponse, accountId: String)
  case class SwapOutDeniedTo(bitcoinAddress: String, reason: Long, accountId: String)

  case class SwapOutRequestAndFee(request: SwapOutTransactionRequest, accountId: String, fee: Satoshi) {
    val totalAmount: MilliSatoshi = (request.amount + fee).toMilliSatoshi
  }

  val minChainFee: Satoshi = 253.sat
  val blockTargets = List(36, 144, 1008)
}

class SwapOutProcessor(vals: Vals, kit: Kit, getPreimage: String => ByteVector32) extends Actor with Logging {
  context.system.scheduler.scheduleWithFixedDelay(0.seconds, 60.minutes, self, UpdateChainFeerates)
  context.system.eventStream.subscribe(channel = classOf[PaymentReceived], subscriber = self)
  val wallet: BitcoinCoreWallet = kit.wallet.asInstanceOf[BitcoinCoreWallet]

  val requestExpiry: Int = kit.nodeParams.paymentRequestExpiry.toMinutes.toInt + 1
  val pendingRequests: Cache[ByteVector32, SwapOutRequestAndFee] = Tools.makeExpireAfterAccessCache(requestExpiry).maximumSize(5000000).build[ByteVector32, SwapOutRequestAndFee]
  val activeFeerates: Cache[ByteVector32, KeyedBlockTargetAndFee] = Tools.makeExpireAfterWriteCache(60 * 4).build[ByteVector32, KeyedBlockTargetAndFee]
  var lastFeerate: KeyedBlockTargetAndFee = _

  /**
   * 1. User sends SwapOutRequest, gets current feerates and how much a provider can handle
   * 2. User selects an amount, conf block target, sends SwapOutTransactionRequest with a target address
   * - if our feerates have changed while user was selecting then we choose the ones from recent history
   * - in any case user can not provide arbitrary feerates but only the ones we offered previously
   * 3. We remember the chosen feerate and other params, then send user a payment request
   * 4. On receiving an LN payment we obtain saved parameters and initiate a tx
   */

  override def receive: Receive = {
    case ChainInfoRequestFrom(accountId) =>
      val reply = SwapOutFeerates(lastFeerate, providerCanHandle, vals.chainMinWithdrawSat.sat)
      context.parent ! ChainInfoResponseTo(reply, accountId)

    case SwapOutRequestFrom(request, accountId) =>
      val feeOpt = findFee(request.feerateKey, request.blockTarget)
      val addressCheck = Try(addressToPublicKeyScript(request.btcAddress, kit.nodeParams.chainHash).head)



      if (addressCheck.isFailure) {
        logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, fail=invalid chain address, address=${request.btcAddress}, account=$accountId")
        context.parent ! SwapOutDeniedTo(request.btcAddress, SwapOutTransactionDenied.INVALID_BITCOIN_ADDRESS, accountId)
      } else if (feeOpt.isEmpty) {
        logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, fail=could not find a chain feerate, account=$accountId")
        context.parent ! SwapOutDeniedTo(request.btcAddress, SwapOutTransactionDenied.UNKNOWN_CHAIN_FEERATES, accountId)
      } else if (request.amount > providerCanHandle) {
        logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, fail=depleted chain wallet, balance=${vals.bitcoinAPI.getBalance} btc, account=$accountId")
        context.parent ! SwapOutDeniedTo(request.btcAddress, SwapOutTransactionDenied.CAN_NOT_HANDLE_AMOUNT, accountId)
      } else if (request.amount < vals.chainMinWithdrawSat.sat) {
        logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, fail=too small amount, asked=${request.amount}, account=$accountId")
        context.parent ! SwapOutDeniedTo(request.btcAddress, SwapOutTransactionDenied.AMOUNT_TOO_SMALL, accountId)
      } else {
        val preimage = getPreimage(accountId)
        val paymentHash = Crypto.sha256(preimage)
        val fixedRequest = SwapOutRequestAndFee(request, accountId, feeOpt.get.fee)
        val description = s"Payment to ${request.btcAddress}\n\nAmount: ${request.amount.toLong} sat\n\nFee: ${feeOpt.get.fee.toLong} sat"
        val invoiceAsk = ReceivePayment(Some(fixedRequest.totalAmount), description, Some(kit.nodeParams.paymentRequestExpiry.toSeconds), paymentPreimage = Some(preimage), paymentType = PaymentType.SwapOut)
        logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, success address=${request.btcAddress}, amountSat=${request.amount.toLong}, feeSat=${feeOpt.get.fee.toLong}, hash=$paymentHash, account=$accountId")

        val invoice = Await.result(kit.paymentHandler ? invoiceAsk, span).asInstanceOf[PaymentRequest]
        val reply = SwapOutTransactionResponse(PaymentRequest.write(invoice), request.amount, feeOpt.get.fee)
        context.parent ! SwapOutResponseTo(reply, accountId)
        pendingRequests.put(paymentHash, fixedRequest)
      }

    case message: PaymentReceived =>
      Option(pendingRequests getIfPresent message.paymentHash).filter(message.amount >= _.totalAmount) foreach { swapOutRequest =>
        logger.info(s"PLGN ChainSwap, sendToAddress, got complete amount, starting chain tx, account=${swapOutRequest.accountId}")
        wallet.sendToAddress(swapOutRequest.request.btcAddress, swapOutRequest.request.amount, swapOutRequest.request.blockTarget) onComplete {
          case Success(txid) => logger.info(s"PLGN ChainSwap, sendToAddress, txid=${txid.toHex}, paymentHash=${message.paymentHash.toHex}, account=${swapOutRequest.accountId}")
          case Failure(err) => logger.info(s"PLGN ChainSwap, sendToAddress, fail reason=${err.getMessage}, paymentHash=${message.paymentHash.toHex}, account=${swapOutRequest.accountId}")
        }
      }

    case UpdateChainFeerates =>
      // Get feerate/kb for a given block target, then reduce it to get fee per average expected tx size in kb, then convert to satoshi (user will see this)
      val fees = blockTargets.map(vals.bitcoinAPI.getEstimateSmartFee).map(btcPerKb => Btc(btcPerKb / vals.feePerKbDivider).toSatoshi max minChainFee)
      lastFeerate = KeyedBlockTargetAndFee(blockTargets.lazyZip(fees).toList.map(BlockTargetAndFee.tupled), feerateKey = randomBytes32)
      activeFeerates.put(lastFeerate.feerateKey, lastFeerate)
  }

  def findFee(feerateKey: ByteVector32, blockTarget: Int): Option[BlockTargetAndFee] =
    Option(activeFeerates getIfPresent feerateKey).toList.flatMap(_.feerates).find(_.blockTarget == blockTarget)

  def providerCanHandle: Satoshi = {
    val walletBalance = Btc(vals.bitcoinAPI.getBalance)
    walletBalance.toSatoshi / vals.chainBalanceReserve
  }
}
