package fr.acinq.chainswap.app.wire

import scodec.codecs._
import fr.acinq.chainswap.app._
import fr.acinq.chainswap.app.ChainSwap._
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.UnknownMessage
import scodec.Codec


object Codecs {
  private val swapInRequestCodec: Codec[SwapInRequest.type] =
    provide(SwapInRequest)

  private val swapInResponseCodec: Codec[SwapInResponse] =
    ("btcAddress" | variableSizeBytes(uint16, utf8)).as[SwapInResponse]

  private val swapInWithdrawRequestCodec: Codec[SwapInWithdrawRequest] =
    ("paymentRequest" | variableSizeBytes(uint16, utf8)).as[SwapInWithdrawRequest]

  private val swapInWithdrawDeniedCodec: Codec[SwapInWithdrawDenied] = (
    ("paymentRequest" | variableSizeBytes(uint16, utf8)) ::
      ("reason" | variableSizeBytes(uint16, utf8))
    ).as[SwapInWithdrawDenied]

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

  private val swapOutResponseCodec: Codec[SwapOutResponse] = (
    ("amount" | satoshi) ::
      ("fee" | satoshi) ::
      ("paymentRequest" | variableSizeBytes(uint16, utf8))
    ).as[SwapOutResponse]

  private val swapOutDeniedCodec: Codec[SwapOutDenied] = (
    ("btcAddress" | variableSizeBytes(uint16, utf8)) ::
      ("reason" | variableSizeBytes(uint16, utf8))
    ).as[SwapOutDenied]


  def decode(wrap: UnknownMessage): ProtocolMessage = wrap.tag match {
    case SWAP_IN_REQUEST_MESSAGE_TAG => swapInRequestCodec.decode(wrap.data.bits).require.value
    case SWAP_IN_RESPONSE_MESSAGE_TAG => swapInResponseCodec.decode(wrap.data.toBitVector).require.value
    case SWAP_IN_WITHDRAW_REQUEST_MESSAGE_TAG => swapInWithdrawRequestCodec.decode(wrap.data.toBitVector).require.value
    case SWAP_IN_WITHDRAW_DENIED_MESSAGE_TAG => swapInWithdrawDeniedCodec.decode(wrap.data.toBitVector).require.value
    case SWAP_IN_STATE_MESSAGE_TAG => swapInStateCodec.decode(wrap.data.toBitVector).require.value
    case SWAP_OUT_FEERATES_MESSAGE_TAG => swapOutFeeratesCodec.decode(wrap.data.toBitVector).require.value
    case SWAP_OUT_REQUEST_MESSAGE_TAG => swapOutRequestCodec.decode(wrap.data.toBitVector).require.value
    case SWAP_OUT_RESPONSE_MESSAGE_TAG => swapOutResponseCodec.decode(wrap.data.toBitVector).require.value
    case SWAP_OUT_DENIED_MESSAGE_TAG => swapOutDeniedCodec.decode(wrap.data.toBitVector).require.value
  }

  def toUnknownMessage(message: ProtocolMessage): UnknownMessage = message match {
    case SwapInRequest => UnknownMessage(SWAP_IN_REQUEST_MESSAGE_TAG, swapInRequestCodec.encode(SwapInRequest).require.toByteVector)
    case msg: SwapInResponse => UnknownMessage(SWAP_IN_RESPONSE_MESSAGE_TAG, swapInResponseCodec.encode(msg).require.toByteVector)
    case msg: SwapInState => UnknownMessage(SWAP_IN_STATE_MESSAGE_TAG, swapInStateCodec.encode(msg).require.toByteVector)
    case msg: SwapInWithdrawRequest => UnknownMessage(SWAP_IN_WITHDRAW_REQUEST_MESSAGE_TAG, swapInWithdrawRequestCodec.encode(msg).require.toByteVector)
    case msg: SwapInWithdrawDenied => UnknownMessage(SWAP_IN_WITHDRAW_DENIED_MESSAGE_TAG, swapInWithdrawDeniedCodec.encode(msg).require.toByteVector)
    case msg: SwapOutFeerates => UnknownMessage(SWAP_OUT_FEERATES_MESSAGE_TAG, swapOutFeeratesCodec.encode(msg).require.toByteVector)
    case msg: SwapOutRequest => UnknownMessage(SWAP_OUT_REQUEST_MESSAGE_TAG, swapOutRequestCodec.encode(msg).require.toByteVector)
    case msg: SwapOutResponse => UnknownMessage(SWAP_OUT_RESPONSE_MESSAGE_TAG, swapOutResponseCodec.encode(msg).require.toByteVector)
    case msg: SwapOutDenied => UnknownMessage(SWAP_OUT_DENIED_MESSAGE_TAG, swapOutDeniedCodec.encode(msg).require.toByteVector)
  }
}
