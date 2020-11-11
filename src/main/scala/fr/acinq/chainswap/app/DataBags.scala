package fr.acinq.chainswap.app

import fr.acinq.eclair._
import scala.concurrent.duration._
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import scodec.bits.ByteVector
import akka.actor.ActorRef


case class PeerAndConnection(peer: ActorRef, connection: ActorRef)

case class AccountAndAddress(accountId: String, btcAddress: String)

case class ChainDepositReceived(accountId: String, amount: Satoshi, txid: String, depth: Long)

case class BTCDeposit(id: Long, btcAddress: String, outIndex: Long, txid: String, amount: Long, depth: Long, stamp: Long) {
  def toPendingDeposit: PendingDeposit = PendingDeposit(btcAddress, ByteVector32(ByteVector fromValidHex txid), Satoshi(amount), stamp)
}

// Protocol messages

sealed trait ChainSwapMessage

sealed trait IncomingMessage

sealed trait SwapIn

case object SwapInRequest extends SwapIn with ChainSwapMessage with IncomingMessage

case class SwapInResponse(btcAddress: String) extends SwapIn with ChainSwapMessage

case class SwapInWithdrawRequest(paymentRequest: String) extends SwapIn with ChainSwapMessage with IncomingMessage

case class SwapInWithdrawDenied(paymentRequest: String, reason: String) extends SwapIn with ChainSwapMessage

case class PendingDeposit(btcAddress: String, txid: ByteVector32, amount: Satoshi,
                          stamp: Long = System.currentTimeMillis.milliseconds.toSeconds)

case class SwapInState(balance: MilliSatoshi, maxWithdrawable: MilliSatoshi, activeFeeReserve: MilliSatoshi, inFlightAmount: MilliSatoshi,
                       pendingChainDeposits: List[PendingDeposit] = Nil) extends SwapIn with ChainSwapMessage


sealed trait SwapOut

case class BlockTargetAndFee(blockTarget: Int, fee: Satoshi)

case class SwapOutFeerates(feerates: List[BlockTargetAndFee] = Nil) extends SwapOut with ChainSwapMessage

case class SwapOutRequest(amount: Satoshi, btcAddress: String, blockTarget: Int) extends SwapOut with ChainSwapMessage with IncomingMessage

case class SwapOutResponse(amount: Satoshi, fee: Satoshi, paymentRequest: String) extends SwapOut with ChainSwapMessage

case class SwapOutDenied(btcAddress: String, reason: String) extends SwapOut with ChainSwapMessage