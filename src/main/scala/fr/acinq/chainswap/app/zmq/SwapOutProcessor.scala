package fr.acinq.chainswap.app.zmq

import akka.actor.{Actor, Status}
import akka.util.Timeout
import com.google.common.cache.Cache
import fr.acinq.bitcoin.{ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair._
import fr.acinq.chainswap.app._

import scala.concurrent.duration._
import fr.acinq.eclair.blockchain.CurrentFeerates
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import fr.acinq.eclair.db.PaymentType

import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRequest}
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivePayment
import grizzled.slf4j.Logging

import scala.util.{Failure, Success}


class SwapOutProcessor(vals: Vals, kit: Kit) extends Actor with Logging {
  context.system.eventStream.subscribe(channel = classOf[PaymentReceived], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[CurrentFeerates], subscriber = self)

  var currentFeerates: List[BlockTargetAndFeePerKb] = Nil
  val expiry: Int = kit.nodeParams.paymentRequestExpiry.toMinutes.toInt + 1 // One extra minute in case of timer disparity with Eclair's pending invoice remover
  val requestsAlreadyMade: Cache[String, java.lang.Integer] = Tools.makeExpireAfterAccessCache(expiry).maximumSize(5000000).build[String, java.lang.Integer]
  val pendingRequests: Cache[ByteVector32, SwapOutRequestFrom] = Tools.makeExpireAfterAccessCache(expiry).maximumSize(5000000).build[ByteVector32, SwapOutRequestFrom]
  implicit val timeout: Timeout = Timeout(30.seconds)

  override def receive: Receive = {
    case ChainFeeratesFrom(userId) =>
      sender ! ChainFeeratesTo(currentFeerates, userId)

      // IF address is valid
      // IF not too many requests
      // IF we have enough money on-chain (3x much)

    case message @ SwapOutRequestFrom(request, userId) =>
      val alreadyKnownPreimage: ByteVector32 = randomBytes32
      val paymentHash: ByteVector32 = Crypto.sha256(alreadyKnownPreimage)
      val description = s"Payment to address ${request.bitcoinAddress} with amount: ${request.amountSatoshis.toLong}sat and attached fee: ${request.feerate.fee.toLong}sat"
      logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, address=${request.bitcoinAddress}, amountSat=${request.amountSatoshis.toLong}, feeSat=${request.feerate.fee.toLong}, paymentHash=${paymentHash.toHex}")
      kit.paymentHandler ! ReceivePayment(Some(request.totalInvoiceAmountToAsk), description, Some(kit.nodeParams.paymentRequestExpiry.toSeconds), paymentPreimage = Some(alreadyKnownPreimage), paymentType = PaymentType.SwapOut)
      requestsAlreadyMade.put(userId, Option(requestsAlreadyMade getIfPresent userId).getOrElse(0: java.lang.Integer) + 1)
      pendingRequests.put(paymentHash, message)

    case message: PaymentRequest =>
      Option(pendingRequests getIfPresent message.paymentHash) foreach { case SwapOutRequestFrom(request, userId) =>
        val response = SwapOutResponse(request.amountSatoshis, request.feerate.fee, PaymentRequest write message)
        context.parent ! SwapOutResponseTo(response, userId)
      }

    case message: Status.Failure =>
      // Payment handler replied with an error, make sure this properly times out on client side
      logger.info(s"PLGN ChainSwap, SwapOutRequestFrom, fail=${message.cause.getMessage}")

    case message: PaymentReceived =>
      logger.info(s"PLGN ChainSwap, PaymentReceived, paymentHash=${message.paymentHash.toHex}")
      Option(pendingRequests getIfPresent message.paymentHash).filter(_.request.totalInvoiceAmountToAsk <= message.amount) zip Option(kit.wallet) foreach {
        case (from, coreWallet: BitcoinCoreWallet) => sendTxAndLog(request = from.request, wallet = coreWallet, paymentHash = message.paymentHash.toHex)
        case _ => logger.info(s"PLGN ChainSwap, PaymentReceived, fail=this call is only available with a bitcoin core backend")
      }

    case message: CurrentFeerates =>
      val targets = List(6, 144, 1008)
      val feerates = targets.map(message.feeratesPerKw.feePerBlock)
      val fees = feerates.map(_ / vals.feePerKbDivider).map(_.toLong).map(Satoshi)
      currentFeerates = targets.lazyZip(fees).lazyZip(feerates).toList.map(BlockTargetAndFeePerKb.tupled)
  }

  def sendTxAndLog(request: SwapOutRequest, wallet: BitcoinCoreWallet, paymentHash: String): Unit =
    wallet.sendToAddress(request.bitcoinAddress, request.amountSatoshis, request.feerate.blockTarget) onComplete {
      case Success(txid) => logger.info(s"PLGN ChainSwap, sendToAddress, success txid=${txid.toHex}, paymentHash=$paymentHash")
      case Failure(err) => logger.info(s"PLGN ChainSwap, sendToAddress, fail=could not send tx, reason=${err.getMessage}, paymentHash=$paymentHash")
    }
}
