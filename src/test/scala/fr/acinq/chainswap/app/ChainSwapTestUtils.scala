package fr.acinq.chainswap.app

import java.util.UUID
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import scala.concurrent.ExecutionContext.Implicits.global
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.{Kit, TestConstants}
import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.Await


object ChainSwapTestUtils {
  def resetEntireDatabase(): Unit = {
    val setup = DBIO.seq(
      fr.acinq.chainswap.app.db.Addresses.model.schema.dropIfExists,
      fr.acinq.chainswap.app.db.BTCDeposits.model.schema.dropIfExists,
      fr.acinq.chainswap.app.db.Account2LNWithdrawals.model.schema.dropIfExists,
      fr.acinq.chainswap.app.db.Addresses.model.schema.create,
      fr.acinq.chainswap.app.db.BTCDeposits.model.schema.create,
      fr.acinq.chainswap.app.db.Account2LNWithdrawals.model.schema.create
    )
    Await.result(Config.db.run(setup.transactionally), 10.seconds)
  }

  def testKit(listener: ActorRef, paymentRequest: PaymentRequest)(implicit system: ActorSystem): Kit = {
    val watcher = TestProbe()
    val paymentHandler = system actorOf Props(classOf[TestPaymentHandler], paymentRequest)
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
      paymentHandler,
      register.ref,
      relayer.ref,
      router.ref,
      switchboard.ref,
      testPaymentInitiator,
      server.ref,
      new BitcoinCoreWallet(null))
  }

  class TestPaymentInitiator(copyReceiver: ActorRef) extends Actor {
    // Always reply with random UUID as if payment sending has been started
    override def receive: Receive = { case _ =>
      val copy = UUID.randomUUID()
      copyReceiver ! copy
      sender ! copy
    }
  }

  class TestPaymentHandler(paymentRequest: PaymentRequest) extends Actor {
    // Always reply with random UUID as if payment sending has been started
    override def receive: Receive = { case _ =>
      sender ! paymentRequest
    }
  }
}
