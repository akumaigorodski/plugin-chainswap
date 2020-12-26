package fr.acinq.chainswap.app.wire

import scodec.codecs._
import fr.acinq.chainswap.app._
import fr.acinq.chainswap.app.ChainSwap._
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.UnknownMessage
import scodec.Attempt


object Codecs {
  private val text = variableSizeBytes(uint16, utf8)
  private val optionalText = optional(bool, text)

  private val swapInResponseCodec = {
    ("btcAddress" | text) ::
      ("minChainDeposit" | satoshi)
  }.as[SwapInResponse]

  private val swapInPaymentRequestCodec = {
    ("paymentRequest" | text) ::
      ("id" | uint32)
  }.as[SwapInPaymentRequest]

  private val swapInPaymentDeniedCodec = {
    ("paymentRequest" | text) ::
      ("reason" | uint32)
  }.as[SwapInPaymentDenied]

  private val pendingDepositCodec = {
    ("id" | uint32) ::
      ("lnPaymentId" | optionalText) ::
      ("lnStatus" | uint32) ::
      ("btcAddress" | text) ::
      ("outIndex" | uint32) ::
      ("txid" | text) ::
      ("amountSat" | uint32) ::
      ("depth" | uint32) ::
      ("stamp" | uint32)
  }.as[ChainDeposit]

  private val swapInStateCodec = {
    ("pending" | listOfN(uint16, pendingDepositCodec)) ::
      ("ready" | listOfN(uint16, pendingDepositCodec)) ::
      ("processing" | listOfN(uint16, pendingDepositCodec))
  }.as[SwapInState]

  // SwapOut

  private val blockTargetAndFeeCodec = {
    ("blockTarget" | uint16) ::
      ("fee" | satoshi)
  }.as[BlockTargetAndFee]

  private val keyedBlockTargetAndFeeCodec = {
    ("feerates" | listOfN(uint16, blockTargetAndFeeCodec)) ::
      ("feerateKey" | bytes32)
  }.as[KeyedBlockTargetAndFee]

  private val swapOutFeeratesCodec = {
    ("feerates" | keyedBlockTargetAndFeeCodec) ::
      ("providerCanHandle" | satoshi) ::
      ("minWithdrawable" | satoshi)
  }.as[SwapOutFeerates]

  private val swapOutTransactionRequestCodec = {
    ("amount" | satoshi) ::
      ("btcAddress" | text) ::
      ("blockTarget" | uint16) ::
      ("feerateKey" | bytes32)
  }.as[SwapOutTransactionRequest]

  private val swapOutTransactionResponseCodec = {
    ("paymentRequest" | text) ::
      ("amount" | satoshi) ::
      ("fee" | satoshi)
  }.as[SwapOutTransactionResponse]

  private val swapOutTransactionDeniedCodec = {
    ("btcAddress" | text) ::
      ("reason" | uint32)
  }.as[SwapOutTransactionDenied]

  def decode(wrap: UnknownMessage): Attempt[ChainSwapMessage] = {
    val bitVector = wrap.data.toBitVector

    val decodeAttempt = wrap.tag match {
      case SWAP_IN_REQUEST_MESSAGE_TAG => provide(SwapInRequest).decode(bitVector)
      case SWAP_IN_RESPONSE_MESSAGE_TAG => swapInResponseCodec.decode(bitVector)
      case SWAP_IN_PAYMENT_REQUEST_MESSAGE_TAG => swapInPaymentRequestCodec.decode(bitVector)
      case SWAP_IN_PAYMENT_DENIED_MESSAGE_TAG => swapInPaymentDeniedCodec.decode(bitVector)
      case SWAP_IN_STATE_MESSAGE_TAG => swapInStateCodec.decode(bitVector)
      case SWAP_OUT_REQUEST_MESSAGE_TAG => provide(SwapOutRequest).decode(bitVector)
      case SWAP_OUT_FEERATES_MESSAGE_TAG => swapOutFeeratesCodec.decode(bitVector)
      case SWAP_OUT_TRANSACTION_REQUEST_MESSAGE_TAG => swapOutTransactionRequestCodec.decode(bitVector)
      case SWAP_OUT_TRANSACTION_RESPONSE_MESSAGE_TAG => swapOutTransactionResponseCodec.decode(bitVector)
      case SWAP_OUT_TRANSACTION_DENIED_MESSAGE_TAG => swapOutTransactionDeniedCodec.decode(bitVector)
    }

    decodeAttempt.map(_.value)
  }

  def toUnknownMessage(message: ChainSwapMessage): UnknownMessage = message match {
    case SwapInRequest => UnknownMessage(SWAP_IN_REQUEST_MESSAGE_TAG, provide(SwapInRequest).encode(SwapInRequest).require.toByteVector)
    case msg: SwapInResponse => UnknownMessage(SWAP_IN_RESPONSE_MESSAGE_TAG, swapInResponseCodec.encode(msg).require.toByteVector)
    case msg: SwapInState => UnknownMessage(SWAP_IN_STATE_MESSAGE_TAG, swapInStateCodec.encode(msg).require.toByteVector)
    case msg: SwapInPaymentRequest => UnknownMessage(SWAP_IN_PAYMENT_REQUEST_MESSAGE_TAG, swapInPaymentRequestCodec.encode(msg).require.toByteVector)
    case msg: SwapInPaymentDenied => UnknownMessage(SWAP_IN_PAYMENT_DENIED_MESSAGE_TAG, swapInPaymentDeniedCodec.encode(msg).require.toByteVector)
    case SwapOutRequest => UnknownMessage(SWAP_OUT_REQUEST_MESSAGE_TAG, provide(SwapOutRequest).encode(SwapOutRequest).require.toByteVector)
    case msg: SwapOutFeerates => UnknownMessage(SWAP_OUT_FEERATES_MESSAGE_TAG, swapOutFeeratesCodec.encode(msg).require.toByteVector)
    case msg: SwapOutTransactionRequest => UnknownMessage(SWAP_OUT_TRANSACTION_REQUEST_MESSAGE_TAG, swapOutTransactionRequestCodec.encode(msg).require.toByteVector)
    case msg: SwapOutTransactionResponse => UnknownMessage(SWAP_OUT_TRANSACTION_RESPONSE_MESSAGE_TAG, swapOutTransactionResponseCodec.encode(msg).require.toByteVector)
    case msg: SwapOutTransactionDenied => UnknownMessage(SWAP_OUT_TRANSACTION_DENIED_MESSAGE_TAG, swapOutTransactionDeniedCodec.encode(msg).require.toByteVector)
  }
}
