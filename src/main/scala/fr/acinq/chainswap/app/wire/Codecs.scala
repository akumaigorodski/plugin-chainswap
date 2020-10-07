package fr.acinq.chainswap.app.wire

import scodec.codecs._
import fr.acinq.chainswap.app._
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.UnknownMessage
import scodec.Codec


object Codecs {
  private val swapInRequestCodec: Codec[SwapInRequest.type] =
    provide(SwapInRequest)

  private val swapInResponseCodec: Codec[SwapInResponse] =
    ("btcAddress" | variableSizeBytes(uint16, utf8)).as[SwapInResponse]

  private val pendingDepositCodec: Codec[PendingDeposit] = (
    ("btcAddress" | variableSizeBytes(uint16, utf8)) ::
      ("txid" | bytes32) ::
      ("amount" | satoshi) ::
      ("stamp" | uint32)
    ).as[PendingDeposit]

  private val swapInStateCodec: Codec[SwapInState] = (
    ("balance" | millisatoshi) ::
      ("maxWithdrawable" | millisatoshi) ::
      ("activeFeeReserve" | millisatoshi) ::
      ("inFlightAmount" | millisatoshi) ::
      ("pendingChainDeposits" | listOfN(uint16, pendingDepositCodec))
    ).as[SwapInState]

  //

  private val blockTargetAndFeeCodec: Codec[BlockTargetAndFee] = (
    ("blockTarget" | uint16) ::
      ("fee" | satoshi)
    ).as[BlockTargetAndFee]

  private val swapOutFeeratesCodec: Codec[SwapOutFeerates] =
    ("feerates" | listOfN(uint16, blockTargetAndFeeCodec)).as[SwapOutFeerates]

  private val swapOutRequestCodec: Codec[SwapOutRequest] = (
    ("amount" | satoshi) ::
      ("btcAddress" | variableSizeBytes(uint16, utf8)) ::
      ("blockTarget" | uint16)
    ).as[SwapOutRequest]

  private val SwapOutResponseCodec: Codec[SwapOutResponse] = (
    ("amount" | satoshi) ::
      ("fee" | satoshi) ::
      ("paymentRequest" | variableSizeBytes(uint16, utf8))
    ).as[SwapOutResponse]

  def decode(wrap: UnknownMessage): ProtocolMessage = wrap.tag match {
    case 55001 => swapInRequestCodec.decode(wrap.data.toBitVector).require.value
    case 55003 => swapInResponseCodec.decode(wrap.data.toBitVector).require.value
    case 55005 => swapInStateCodec.decode(wrap.data.toBitVector).require.value
    case 55007 => swapOutFeeratesCodec.decode(wrap.data.toBitVector).require.value
    case 55009 => swapOutRequestCodec.decode(wrap.data.toBitVector).require.value
    case 55011 => SwapOutResponseCodec.decode(wrap.data.toBitVector).require.value
  }

  def encode(message: ProtocolMessage): UnknownMessage = message match {
    case SwapInRequest => UnknownMessage(tag = 55001, swapInRequestCodec.encode(SwapInRequest).require.toByteVector)
    case msg: SwapInResponse => UnknownMessage(tag = 55003, swapInResponseCodec.encode(msg).require.toByteVector)
    case msg: SwapInState => UnknownMessage(tag = 55005, swapInStateCodec.encode(msg).require.toByteVector)
    case msg: SwapOutFeerates => UnknownMessage(tag = 55007, swapOutFeeratesCodec.encode(msg).require.toByteVector)
    case msg: SwapOutRequest => UnknownMessage(tag = 55009, swapOutRequestCodec.encode(msg).require.toByteVector)
    case msg: SwapOutResponse => UnknownMessage(tag = 55011, SwapOutResponseCodec.encode(msg).require.toByteVector)
  }
}
