package fr.acinq.chainswap.app

import java.util.UUID
import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import akka.pattern.ask
import fr.acinq.eclair._
import fr.acinq.bitcoin.{ByteVector32, Transaction}
import fr.acinq.chainswap.app.db.Blocking.timeout
import slick.jdbc.PostgresProfile.api._
import fr.acinq.chainswap.app.db.{Blocking, Addresses}
import fr.acinq.chainswap.app.processor.{IncomingChainTxProcessor, SwapInProcessor, ZMQActor, ZMQListener}
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment.{PaymentFailed, PaymentSent}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.Await


class SwapInProcessorSpec extends AnyFunSuite {
  test("Create new address, reuse it") {
    ChainSwapTestUtils.resetEntireDatabase()
    implicit val system: ActorSystem = ActorSystem("test-actor-system")
    val eventListener = TestProbe()(system)
    val kit = ChainSwapTestUtils.testKit(eventListener.ref, paymentRequest = null)(system)
    val swapInProcessor = eventListener childActorOf Props(classOf[SwapInProcessor], Config.vals, kit, Config.db)
    val userId = "user-id-1"

    swapInProcessor ! SwapInProcessor.SwapInRequestFrom(userId)
    val address = eventListener.expectMsgType[SwapInProcessor.SwapInResponseTo].response.btcAddress // A new address is created

    swapInProcessor ! SwapInProcessor.SwapInRequestFrom(userId)
    assert(eventListener.expectMsgType[SwapInProcessor.SwapInResponseTo].response.btcAddress === address) // Existing address is reused
  }

  test("Deposit on-chain, withdraw off-chain") {
    ChainSwapTestUtils.resetEntireDatabase()
    implicit val system: ActorSystem = ActorSystem("test-actor-system")
    val eventListener = TestProbe()(system)
    val kit = ChainSwapTestUtils.testKit(eventListener.ref, paymentRequest = null)(system)
    val zmqActor = system actorOf Props(classOf[ZMQActor], Config.vals.bitcoinAPI, Config.vals.btcZMQApi, Config.vals.rewindBlocks)
    val swapInProcessor = eventListener childActorOf Props(classOf[SwapInProcessor], Config.vals, kit, Config.db)
    val incomingChainTxProcessor = system actorOf Props(classOf[IncomingChainTxProcessor], Config.vals, swapInProcessor, zmqActor, Config.db)
    val userId = "user-id-1"

    swapInProcessor ! SwapInProcessor.AccountStatusFrom(userId)
    eventListener.expectNoMessage() // Nothing interesting is happening

    val rawTx1ConfirmedAtBlock = 1720707
    val rawTx1 = "0100000001d2ecbeeee1e307835be483f1c2c5671f1a107e10e07da84ab27e6957d3a283e5000000006b483045022100deee31270085e1ea00f6872f489c552e8c8ba5351433a070b51db382abfdfcd9" +
      "0220298ff1f3c5f42ffb08e3ceb3eb7243cb1296246433b05d53257c3a9b012ade9301210362a3ee21ef77f2ddda4a79a4864ba55b3e16e642af3e80c110925db5f4a340f3ffffffff0240523600000000001976a91" +
      "4f062977f425b6ce70a7b08869864c83664fcf60888ac0000000000000000226a2005990528fb62094aed94fa546d1990e8b2b3fb6613fea8aa779819132c5aa82900000000"

    val rawAddress1 = "n3RzaNTD8LnBGkREBjSkouy5gmd2dVf7jQ" // 3560000 sat
    Blocking.txWrite(Addresses.insertCompiled += (rawAddress1, userId), Config.db) // inject a known address for given user
    val listener = Await.result(incomingChainTxProcessor ? Symbol("processor"), Blocking.span).asInstanceOf[ZMQListener]
    incomingChainTxProcessor ! AccountAndAddress(userId, rawAddress1) // User asks for address, make incomingChainTxProcessor expect a mempool tx
    synchronized(wait(100))

    listener.onNewTx(Transaction.read(rawTx1)) // get an incoming tx
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state.pendingChainDeposits.size === 1)

    val oneMilSat = "lntb10m1p0cqg40rzjqges6y6c0dn6shq2xm4qq8zdhg2te4ydm2yc3aesxf6mqs9lld4t6q3xghlyujw62cqqqqlgqqqqqeqqjqdqqcqzgapp5e46hd8z4dwh9z6t8uecls9phc9txdkvym52qc4syz7qs5" +
      "3r5sspsxqyz5vp8xmsusrvjuqt4knl64s437txwnkrhexa2zs7arm9z0zujwlsd26sh6dy6p3kyfjkjrm0hg2jg43eaccfeut8cm26e36vchhwc7f8g3spev5720"

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(oneMilSat), userId)
    eventListener.expectMsgType[SwapInProcessor.SwapInWithdrawRequestDeniedTo] // More than can be withdrawn

    listener.onNewBlock(Config.vals.bitcoinAPI.getBlock(rawTx1ConfirmedAtBlock)) // get that tx confirmed, incomingChainTxProcessor sends a message to swapInProcessor
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === SwapInState(3560000000L.msat, 0L.msat, Nil)) // swapInProcessor in turn informs parent about updated account status

    val tooSmallPr = "lntb1p07yq02pp5e2x2qsha0wm8ndqe3v0xt9sgzv9c6suzeehh4p2uprplvflumftsdqqxqrrsscqp79qy9qsqsp5ysruu5npml3m9sx3l8t7twyek2ehac" +
      "zctjc64pm8ey3zyg605r3q72kjgftc6f8998wyhmhq2edr0mju9jq97nhyftjc9d8kej7g05apsl5xw7ruqmp65c8cwh38xrugh6cpc9q3mqyn4a7ed4jf0e9smnspnlq6qh"

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(tooSmallPr), userId)
    eventListener.expectMsgType[SwapInProcessor.SwapInWithdrawRequestDeniedTo] // Too small withdraw amount

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(oneMilSat), userId)
    val paymentId1 = eventListener.expectMsgType[UUID]
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === SwapInState(3560000000L.msat, 1000000000L.msat, Nil)) // 1 payment in-flight

    val exactRemaining = "lntb25600u1p07yqk8pp5pk9578ct3wuswqwyvdz6wdstpvm7tq4vu54hhlhpv9xeeqhdynfsdqqxqrrsscqp79qy9qsqsp53rz94y3tgjvahf07yqwhq" +
      "vzftjx3nt5h8t484cnlpntaz6paeq8qqan5ezhsxy0mwevk3vdrky9xcjpfppmc9qsn7alnhqqxp6uwkzfpestm7duun7ktjc60n86gwg68wdsalkngtn6erwujyk6wq4yd02cpmyaa9s"

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(exactRemaining), userId)
    val paymentId2 = eventListener.expectMsgType[UUID]
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === SwapInState(3560000000L.msat, 3560000000L.msat, Nil)) // 2 payments in-flight, whole balance is used

    system.eventStream.publish(PaymentFailed(paymentId1, ByteVector32.One, Nil))
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === SwapInState(3560000000L.msat, 2560000000L.msat, Nil)) // 1st payment failed, 1 left

    val pp = PartialPayment(UUID.randomUUID(), 2560000000L.msat, 10000L.msat, null, None)
    system.eventStream.publish(PaymentSent(paymentId2, ByteVector32.One, ByteVector32.Zeroes, 2560000000L.msat, null, Seq(pp)))
    val finalExpectedState = SwapInState(1000000000L.msat, 0L.msat, Nil)
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === finalExpectedState) // 2nd payment fulfilled, none left in-flight

    // Simulate restart, cache has been emptied
    val swapInProcessor2 = eventListener childActorOf Props(classOf[SwapInProcessor], Config.vals, kit, Config.db)
    swapInProcessor2 ! SwapInProcessor.AccountStatusFrom(userId)
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === finalExpectedState)
  }
}
