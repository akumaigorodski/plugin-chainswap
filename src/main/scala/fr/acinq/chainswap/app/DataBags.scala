package fr.acinq.chainswap.app

import fr.acinq.eclair._
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import scodec.bits.ByteVector
import akka.actor.ActorRef


case class UserIdAndAddress(userId: String, btcAddress: String)

case class ChainDepositReceived(userId: String, amount: Satoshi, txid: String, depth: Long)

case class BTCDeposit(id: Long, btcAddress: String, outIndex: Long, txid: String, amount: Long, depth: Long, stamp: Long) {
  def toPendingDeposit: PendingDeposit = PendingDeposit(btcAddress, ByteVector32(ByteVector fromValidHex txid), Satoshi(amount), stamp)
}

case class GetAccountStatus(replyTo: ActorRef, userId: String)

case class WithdrawBTCLN(userId: String, paymentRequest: String)

case class WithdrawBTCLNDenied(userId: String, paymentRequest: String, reason: String)

case class ChainFeeratesFrom(userId: String)

case class ChainFeeratesTo(feerates: List[BlockTargetAndFeePerKb], userId: String)

case class SwapOutRequestFrom(request: SwapOutRequest, userId: String)

case class SwapOutResponseTo(response: SwapOutResponse, userId: String)

// Protocol messages

case class PendingDeposit(btcAddress: String, txid: ByteVector32, amount: Satoshi, stamp: Long)

case class SwapInState(balance: MilliSatoshi, maxWithdrawable: MilliSatoshi, activeFeeReserve: MilliSatoshi, inFlightAmount: MilliSatoshi, pendingChainDeposits: List[PendingDeposit] = Nil)


sealed trait SwapOut

case class BlockTargetAndFeePerKb(blockTarget: Int, fee: Satoshi, feerate: Long)

case class SwapOutFeerates(feerates: List[BlockTargetAndFeePerKb] = Nil) extends SwapOut

case class SwapOutRequest(amountSatoshis: Satoshi, bitcoinAddress: String, feerate: BlockTargetAndFeePerKb) extends SwapOut {
  def totalInvoiceAmountToAsk: MilliSatoshi = (amountSatoshis + feerate.fee).toMilliSatoshi
}

case class SwapOutResponse(amountSatoshis: Satoshi, feeSatoshis: Satoshi, paymentRequest: String) extends SwapOut