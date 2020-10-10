package fr.acinq.chainswap.app

import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.{Kit, TestConstants}

import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.Await

object ChainSwapTestUtils {
  def resetEntireDatabase(): Unit = {
    val setup = DBIO.seq(
      fr.acinq.chainswap.app.dbo.Users.model.schema.dropIfExists,
      fr.acinq.chainswap.app.dbo.BTCDeposits.model.schema.dropIfExists,
      fr.acinq.chainswap.app.dbo.Account2LNWithdrawals.model.schema.dropIfExists,
      fr.acinq.chainswap.app.dbo.Users.model.schema.create,
      fr.acinq.chainswap.app.dbo.BTCDeposits.model.schema.create,
      fr.acinq.chainswap.app.dbo.Account2LNWithdrawals.model.schema.create
    )
    Await.result(Config.db.run(setup.transactionally), 10.seconds)
  }

  def testKit(listener: ActorRef)(implicit system: ActorSystem): Kit = {
    val watcher = TestProbe()
    val paymentHandler = TestProbe()
    val register = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val switchboard = TestProbe()
    val testPaymentInitiator = system actorOf Props(classOf[TestPaymentInitiator], listener)
    val server = TestProbe()
    Kit(
      TestConstants.Alice.nodeParams,
      system,
      watcher.ref,
      paymentHandler.ref,
      register.ref,
      relayer.ref,
      router.ref,
      switchboard.ref,
      testPaymentInitiator,
      server.ref,
      new TestWallet())
  }

  class TestPaymentInitiator(copyReceiver: ActorRef) extends Actor {
    // Always reply with random UUID as if payment sending has been started
    override def receive: Receive = { case _ =>
      val copy = UUID.randomUUID()
      copyReceiver ! copy
      sender ! copy
    }
  }
}
