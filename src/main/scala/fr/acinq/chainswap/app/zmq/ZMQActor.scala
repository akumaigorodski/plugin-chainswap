package fr.acinq.chainswap.app.zmq

import akka.actor.{Actor, Cancellable}
import org.zeromq.{SocketType, ZContext, ZMQ, ZMsg}
import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.Block
import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import fr.acinq.bitcoin.Transaction


class ZMQActor(api: BitcoinJSONRPCClient, btcZMQApi: String, rewindBlocks: Int) extends Actor {
  var lastProcessedBlockHeight: Int = api.getBlockCount - rewindBlocks
  var listeners = Set.empty[ZMQListener]

  def receive: Receive = {
    case m: ZMsg =>
      m.popString match {
        case "hashblock" =>
          rescanUnseenBlocks()
          self ! Symbol("checkMsg")

        case "rawtx" =>
          val transaction = Transaction.read(m.pop.getData)
          for (lst <- listeners) lst onNewTx transaction
          self ! Symbol("checkMsg")

        case _ =>
      }

    case event: ZMQ.Event => event.getEvent match {
      case ZMQ.EVENT_DISCONNECTED => throw new Exception("ZMQ connection lost")
      case ZMQ.EVENT_CONNECTED => println("ZMQ connection established")
      case _ =>
    }

    case listener: ZMQListener =>
      listeners += listener

    case ZMQActorInit =>
      rescanUnseenBlocks()
      self ! Symbol("checkEvent")
      self ! Symbol("checkMsg")

    case Symbol("checkEvent") =>
      val event = ZMQ.Event.recv(monitor, ZMQ.DONTWAIT)
      if (event != null) self ! event else reScheduleEvent

    case Symbol("checkMsg") =>
      val msg = ZMsg.recvMsg(subscriber, ZMQ.DONTWAIT)
      if (msg != null) self ! msg else reScheduleMsg
  }

  def reScheduleEvent: Cancellable = context.system.scheduler.scheduleOnce(2.seconds, self, Symbol("checkEvent"))
  def reScheduleMsg: Cancellable = context.system.scheduler.scheduleOnce(2.seconds, self, Symbol("checkMsg"))

  def rescanUnseenBlocks(): Unit = {
    val currentHeight = api.getBlockCount
    val blocks = lastProcessedBlockHeight to currentHeight drop 1 map api.getBlock
    println(s"Rescanning chain, from=$lastProcessedBlockHeight, to=$currentHeight")
    for (block <- blocks) for (lst <- listeners) lst onNewBlock block
    lastProcessedBlockHeight = currentHeight
  }

  val ctx = new ZContext
  val subscriber: ZMQ.Socket = ctx.createSocket(SocketType.SUB)
  subscriber.monitor("inproc://events", ZMQ.EVENT_CONNECTED | ZMQ.EVENT_DISCONNECTED)
  subscriber.subscribe("hashblock" getBytes ZMQ.CHARSET)
  subscriber.subscribe("rawtx" getBytes ZMQ.CHARSET)
  subscriber.connect(btcZMQApi)

  val monitor: ZMQ.Socket = ctx.createSocket(SocketType.PAIR)
  monitor.connect("inproc://events")
}

case object ZMQActorInit

trait ZMQListener {
  def onNewBlock(block: Block): Unit = ()
  def onNewTx(tx: Transaction): Unit = ()
}