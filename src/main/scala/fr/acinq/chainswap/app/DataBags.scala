package fr.acinq.chainswap.app

import akka.actor.ActorRef
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.MilliSatoshi
import scodec.bits.ByteVector


case class UserIdAndAddress(userId: String, btcAddress: String)

case class ChainDepositReceived(userId: String, amount: Satoshi, txid: String, depth: Long)

case class BTCDeposit(id: Long, btcAddress: String, outIndex: Long, txid: String, amount: Long, depth: Long, stamp: Long) {
  def toPendingDeposit: PendingDeposit = PendingDeposit(btcAddress, ByteVector32(ByteVector fromValidHex txid), Satoshi(amount), stamp)
}

case class GetAccountStatus(replyTo: ActorRef, userId: String)

case class WithdrawBTCLN(userId: String, paymentRequest: String)

case class WithdrawBTCLNDenied(userId: String, paymentRequest: String, reason: String)

// Protocol messages

case class PendingDeposit(btcAddress: String, txid: ByteVector32, amount: Satoshi, stamp: Long)

case class SwapInState(balance: MilliSatoshi, maxWithdrawable: MilliSatoshi, activeFeeReserve: MilliSatoshi, inFlightAmount: MilliSatoshi, pendingChainDeposits: List[PendingDeposit] = Nil)