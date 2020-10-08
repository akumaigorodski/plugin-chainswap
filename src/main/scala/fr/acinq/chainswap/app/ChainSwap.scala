package fr.acinq.chainswap.app

import fr.acinq.eclair.{Feature, Kit, Plugin, PluginParams, Setup, UnknownFeature}
import fr.acinq.chainswap.app.ChainSwap.messageTags
import fr.acinq.chainswap.app.dbo.Blocking
import grizzled.slf4j.Logging
import akka.actor.Props


object ChainSwap {
  final val SWAP_IN_REQUEST_MESSAGE_TAG = 55021
  final val SWAP_IN_RESPONSE_MESSAGE_TAG = 55023
  final val SWAP_IN_WITHDRAW_REQUEST_MESSAGE_TAG = 55025
  final val SWAP_IN_WITHDRAW_DENIED_MESSAGE_TAG = 55027
  final val SWAP_IN_STATE_MESSAGE_TAG = 55029

  final val SWAP_OUT_FEERATES_MESSAGE_TAG = 55031
  final val SWAP_OUT_REQUEST_MESSAGE_TAG = 55033
  final val SWAP_OUT_RESPONSE_MESSAGE_TAG = 55035
  final val SWAP_OUT_DENIED_MESSAGE_TAG = 55037

  val messageTags: Set[Int] =
    Set(SWAP_IN_REQUEST_MESSAGE_TAG, SWAP_IN_RESPONSE_MESSAGE_TAG, SWAP_IN_WITHDRAW_REQUEST_MESSAGE_TAG, SWAP_IN_WITHDRAW_DENIED_MESSAGE_TAG,
      SWAP_IN_STATE_MESSAGE_TAG, SWAP_OUT_FEERATES_MESSAGE_TAG, SWAP_OUT_REQUEST_MESSAGE_TAG, SWAP_OUT_RESPONSE_MESSAGE_TAG, SWAP_OUT_DENIED_MESSAGE_TAG)
}

case object ChainSwapFeature extends Feature {
  val plugin: UnknownFeature = UnknownFeature(optional)
  val rfcName = "chain_swap"
  val mandatory = 32770
}

class ChainSwap extends Plugin with Logging {
  def onSetup(setup: Setup): Unit = Blocking.createTablesIfNotExist(Config.db)

  def onKit(kit: Kit): Unit = kit.system.actorOf(Props(new Worker(Config.db, Config.vals, kit)))

  def params: Option[PluginParams] = Some(PluginParams(messageTags, ChainSwapFeature))
}
