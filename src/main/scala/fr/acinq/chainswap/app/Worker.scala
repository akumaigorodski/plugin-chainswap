package fr.acinq.chainswap.app

import scala.concurrent.duration._
import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props}
import fr.acinq.eclair.io.{PeerConnected, PeerDisconnected, UnknownMessageReceived}
import fr.acinq.chainswap.app.processor.{IncomingChainTxProcessor, SwapInProcessor, SwapOutProcessor, ZMQActor}
import fr.acinq.eclair.channel.Channel.OutgoingMessage
import akka.actor.SupervisorStrategy.Resume
import fr.acinq.chainswap.app.wire.Codecs
import slick.jdbc.PostgresProfile

import scala.collection.mutable
import grizzled.slf4j.Logging
import fr.acinq.eclair.Kit

import scala.util.{Failure, Success, Try}


class Worker(db: PostgresProfile.backend.Database, vals: Vals, kit: Kit) extends Actor with Logging {
  context.system.eventStream.subscribe(channel = classOf[UnknownMessageReceived], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerDisconnected], subscriber = self)
  context.system.eventStream.subscribe(channel = classOf[PeerConnected], subscriber = self)
  val userId2Connection = mutable.Map.empty[String, PeerAndConnection]

  val swapOutProcessor: ActorRef = context actorOf Props(classOf[SwapOutProcessor], vals, kit)
  val swapInProcessor: ActorRef = context actorOf Props(classOf[SwapInProcessor], vals, kit, db)
  val zmqActor: ActorRef = context actorOf Props(classOf[ZMQActor], vals.bitcoinAPI, vals.btcZMQApi, vals.rewindBlocks)
  val incomingChainTxProcessor: ActorRef = context actorOf Props(classOf[IncomingChainTxProcessor], vals, swapInProcessor, zmqActor, db)

  override def receive: Receive = {
    case peerMessage: PeerDisconnected =>
      userId2Connection.remove(peerMessage.nodeId.toString)

    case PeerConnected(peer, remoteNodeId, info) if info.remoteInit.features.hasPluginFeature(ChainSwapFeature.plugin) =>
      userId2Connection(remoteNodeId.toString) = PeerAndConnection(peer, info.peerConnection)
      swapOutProcessor ! ChainFeeratesFrom(remoteNodeId.toString)
      swapInProcessor ! AccountStatusFrom(remoteNodeId.toString)

    case ChainFeeratesTo(swapOutFeerates, userId) =>
      userId2Connection.get(userId) foreach { case PeerAndConnection(peer, connection) =>
        peer ! OutgoingMessage(Codecs toUnknownMessage swapOutFeerates, connection)
      }

    case AccountStatusTo(swapInState, userId) =>
      // May not be sent back by `swapInProcessor` if account is empty and nothing is happening
      userId2Connection.get(userId) foreach { case PeerAndConnection(peer, connection) =>
        peer ! OutgoingMessage(Codecs toUnknownMessage swapInState, connection)
      }

    case peerMessage: UnknownMessageReceived =>
      Try(Codecs decode peerMessage.message) match {
        case Success(SwapInRequest) => swapInProcessor ! SwapInRequestFrom(peerMessage.nodeId.toString)
        case Success(msg: SwapInWithdrawRequest) => swapInProcessor ! SwapInWithdrawRequestFrom(msg, peerMessage.nodeId.toString)
        case Success(msg: SwapOutRequest) => swapOutProcessor ! SwapOutRequestFrom(msg, peerMessage.nodeId.toString)
        case Failure(_) => logger.info(s"PLGN ChainSwap, parsing fail, tag=${peerMessage.message.tag}")
        case _ => // Do nothing
      }

    case SwapInResponseTo(swapInResponse, userId) =>
      // Always works, each account is guaranteed to have at least one chain address
      userId2Connection.get(userId) foreach { case PeerAndConnection(peer, connection) =>
        incomingChainTxProcessor ! UserIdAndAddress(userId, swapInResponse.btcAddress)
        peer ! OutgoingMessage(Codecs toUnknownMessage swapInResponse, connection)
      }

    case SwapInWithdrawRequestDeniedTo(paymentRequest, reason, userId) =>
      // Either deny right away or silently attempt to fulfill a payment request
      userId2Connection.get(userId) foreach { case PeerAndConnection(peer, connection) =>
        peer ! OutgoingMessage(Codecs toUnknownMessage SwapInWithdrawDenied(paymentRequest, reason), connection)
      }

    case SwapOutResponseTo(swapOutResponse, userId) =>
      // May not be sent back if `paymentInitiator` fails somehow, client should timeout
      userId2Connection.get(userId) foreach { case PeerAndConnection(peer, connection) =>
        peer ! OutgoingMessage(Codecs toUnknownMessage swapOutResponse, connection)
      }

    case SwapOutDeniedTo(btcAddress, reason, userId) =>
      // Either deny right away or silently send a chain transaction on LN payment
      userId2Connection.get(userId) foreach { case PeerAndConnection(peer, connection) =>
        peer ! OutgoingMessage(Codecs toUnknownMessage SwapOutDenied(btcAddress, reason), connection)
      }
  }

  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy(-1, 5.seconds) {
    // ZMQ connection may be lost or an exception may be thrown while processing data
    case _: Throwable => Resume
  }
}
