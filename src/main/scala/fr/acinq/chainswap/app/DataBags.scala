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

case object UpdateChainFeerates

case class ChainFeeratesFrom(userId: String)

case class ChainFeeratesTo(feerates: List[BlockTargetAndFee], userId: String)

case class SwapOutRequestFrom(request: SwapOutRequest, userId: String)

case class SwapOutResponseTo(response: SwapOutResponse, userId: String)

case class SwapOutRequestAndFee(request: SwapOutRequest, userId: String, fee: Satoshi) {
  val totalAmount: MilliSatoshi = (request.amount + fee).toMilliSatoshi
}

case class WithdrawLNBTCDenied(userId: String, bitcoinAddress: String, reason: String)

// Protocol messages

sealed trait SwapIn

case object SwapInRequest extends SwapIn

case class SwapInResponse(btcAddress: String) extends SwapIn

case class PendingDeposit(btcAddress: String, txid: ByteVector32, amount: Satoshi, stamp: Long)

case class SwapInState(balance: MilliSatoshi, maxWithdrawable: MilliSatoshi, activeFeeReserve: MilliSatoshi, inFlightAmount: MilliSatoshi, pendingChainDeposits: List[PendingDeposit] = Nil) extends SwapIn


sealed trait SwapOut

case class BlockTargetAndFee(blockTarget: Int, fee: Satoshi)

case class SwapOutFeerates(feerates: List[BlockTargetAndFee] = Nil) extends SwapOut

case class SwapOutRequest(amount: Satoshi, btcAddress: String, blockTarget: Int) extends SwapOut

case class SwapOutResponse(amount: Satoshi, fee: Satoshi, paymentRequest: String) extends SwapOut