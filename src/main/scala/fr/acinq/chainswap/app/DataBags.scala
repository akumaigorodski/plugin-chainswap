package fr.acinq.chainswap.app

import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import akka.actor.ActorRef


case class PeerAndConnection(peer: ActorRef, connection: ActorRef)

case class AccountAndAddress(accountId: String, btcAddress: String)

case class ChainDepositReceived(accountId: String, txid: String, amountSat: Long, depth: Long)

// Protocol messages

sealed trait ChainSwapMessage

sealed trait SwapIn

case object SwapInRequest extends SwapIn with ChainSwapMessage

case class SwapInResponse(btcAddress: String, minChainDeposit: Satoshi) extends SwapIn with ChainSwapMessage

case class SwapInPaymentRequest(paymentRequest: String, id: Long) extends SwapIn with ChainSwapMessage

object SwapInPaymentDenied {
  final val WITHDRAWAL_ALREADY_IN_FLIGHT = 1L
  final val INVOICE_TX_AMOUNT_MISMATCH = 2L
  final val NO_WITHDRAWABLE_TX = 3L
  final val INVALID_INVOICE = 4L
}

case class SwapInPaymentDenied(id: Long, reason: Long) extends SwapIn with ChainSwapMessage

case class ChainDeposit(id: Long, lnPaymentId: Option[String], lnStatus: Long, btcAddress: String, outIndex: Long, txid: String, amountSat: Long, depth: Long, stamp: Long)

case class SwapInState(pending: List[ChainDeposit], ready: List[ChainDeposit], processing: List[ChainDeposit] = Nil) extends SwapIn with ChainSwapMessage

sealed trait SwapOut

case object SwapOutRequest extends SwapOut with ChainSwapMessage

case class BlockTargetAndFee(blockTarget: Int, fee: Satoshi)

case class KeyedBlockTargetAndFee(feerates: List[BlockTargetAndFee], feerateKey: ByteVector32)

case class SwapOutFeerates(feerates: KeyedBlockTargetAndFee, providerCanHandle: Satoshi, minWithdrawable: Satoshi) extends SwapOut with ChainSwapMessage

case class SwapOutTransactionRequest(amount: Satoshi, btcAddress: String, blockTarget: Int, feerateKey: ByteVector32) extends SwapOut with ChainSwapMessage

case class SwapOutTransactionResponse(paymentRequest: String, amount: Satoshi, btcAddress: String, fee: Satoshi) extends SwapOut with ChainSwapMessage

object SwapOutTransactionDenied {
  final val INVALID_BITCOIN_ADDRESS = 1L
  final val UNKNOWN_CHAIN_FEERATES = 2L
  final val CAN_NOT_HANDLE_AMOUNT = 3L
  final val AMOUNT_TOO_SMALL = 4L
}

case class SwapOutTransactionDenied(btcAddress: String, reason: Long) extends SwapOut with ChainSwapMessage