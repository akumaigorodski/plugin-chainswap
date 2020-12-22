package fr.acinq.chainswap.app

import fr.acinq.eclair._
import akka.actor.Props


object ChainSwap {
  final val SWAP_IN_REQUEST_MESSAGE_TAG = 55037
  final val SWAP_IN_RESPONSE_MESSAGE_TAG = 55035
  final val SWAP_IN_PAYMENT_REQUEST_MESSAGE_TAG = 55033
  final val SWAP_IN_PAYMENT_DENIED_MESSAGE_TAG = 55031
  final val SWAP_IN_STATE_MESSAGE_TAG = 55029

  final val SWAP_OUT_REQUEST_MESSAGE_TAG = 55027
  final val SWAP_OUT_FEERATES_MESSAGE_TAG = 55025
  final val SWAP_OUT_TRANSACTION_REQUEST_MESSAGE_TAG = 55023
  final val SWAP_OUT_TRANSACTION_RESPONSE_MESSAGE_TAG = 55021
  final val SWAP_OUT_TRANSACTION_DENIED_MESSAGE_TAG = 55019

  val swapInOutTags: Set[Int] =
    Set(SWAP_IN_REQUEST_MESSAGE_TAG, SWAP_IN_RESPONSE_MESSAGE_TAG, SWAP_IN_PAYMENT_REQUEST_MESSAGE_TAG, SWAP_IN_PAYMENT_DENIED_MESSAGE_TAG,
      SWAP_IN_STATE_MESSAGE_TAG, SWAP_OUT_REQUEST_MESSAGE_TAG, SWAP_OUT_FEERATES_MESSAGE_TAG, SWAP_OUT_TRANSACTION_REQUEST_MESSAGE_TAG,
      SWAP_OUT_TRANSACTION_RESPONSE_MESSAGE_TAG, SWAP_OUT_TRANSACTION_DENIED_MESSAGE_TAG)
}

class ChainSwap extends Plugin {

  override def onSetup(setup: Setup): Unit = fr.acinq.chainswap.app.db.Blocking.createTablesIfNotExist(Config.db)

  override def onKit(kit: Kit): Unit = kit.system actorOf Props(classOf[Worker], Config.db, Config.vals, kit)

  override def params: PluginParams = new CustomFeaturePlugin {

    override def messageTags: Set[Int] = ChainSwap.swapInOutTags

    override def feature: Feature = ChainSwapFeature

    override def name: String = "ChainSwap"
  }
}

case object ChainSwapFeature extends Feature {
  val plugin: UnknownFeature = UnknownFeature(optional)
  val rfcName = "chain_swap"
  val mandatory = 32770
}
