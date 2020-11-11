package fr.acinq.chainswap.app

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.testkit.TestProbe
import akka.pattern.ask
import fr.acinq.eclair._
import fr.acinq.bitcoin.{ByteVector32, Transaction}
import fr.acinq.chainswap.app.dbo.Blocking.askTimeout
import slick.jdbc.PostgresProfile.api._
import fr.acinq.chainswap.app.dbo.{Blocking, Accounts}
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
    val kit = ChainSwapTestUtils.testKit(eventListener.ref)(system)
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
    val kit = ChainSwapTestUtils.testKit(eventListener.ref)(system)
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
    Blocking.txWrite(Accounts.insertCompiled += (rawAddress1, userId), Config.db) // inject a known address for given user
    val listener = Await.result(incomingChainTxProcessor ? Symbol("processor"), Blocking.span).asInstanceOf[ZMQListener]
    incomingChainTxProcessor ! AccountAndAddress(userId, rawAddress1) // User asks for address, make incomingChainTxProcessor expect a mempool tx
    synchronized(wait(100))

    listener.onNewTx(Transaction.read(rawTx1)) // get an incoming tx
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state.pendingChainDeposits.size === 1)

    val oneMilSat = "lntb10m1p0cqg40rzjqges6y6c0dn6shq2xm4qq8zdhg2te4ydm2yc3aesxf6mqs9lld4t6q3xghlyujw62cqqqqlgqqqqqeqqjqdqqcqzgapp5e46hd8z4dwh9z6t8uecls9phc9txdkvym52qc4syz7qs5" +
      "3r5sspsxqyz5vp8xmsusrvjuqt4knl64s437txwnkrhexa2zs7arm9z0zujwlsd26sh6dy6p3kyfjkjrm0hg2jg43eaccfeut8cm26e36vchhwc7f8g3spev5720"

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInWithdrawRequest(oneMilSat), userId)
    eventListener.expectMsgType[SwapInProcessor.SwapInWithdrawRequestDeniedTo] // More than can be withdrawn

    listener.onNewBlock(Config.vals.bitcoinAPI.getBlock(rawTx1ConfirmedAtBlock)) // get that tx confirmed, incomingChainTxProcessor sends a message to swapInProcessor
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === SwapInState(3560000000L.msat, 3524752475L.msat, 0.msat, 0.msat, Nil)) // swapInProcessor in turn informs parent about updated account status

    val tooSmallPr = "lntb50n1p0cq8vgrzjqges6y6c0dn6shq2xm4qq8zdhg2te4ydm2yc3aesxf6mqs9lld4t6q3xghlyujw62cqqqqlgqqqqqeqqjqdqqcqzgapp5c2ldcl59gc9qcvl69mpk347a5t4k828jdx9tjfmx3lre" +
      "c2t6gsvqxqyz5vpmuvunmh8lpum23r9zutxlmasruqh4duj2tajhmt999kk3qhfx5jhqcfr0rg2mesfhpaqkw2et972m578mmjzx2smtuugmwxke7j7racp9rvddt"

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInWithdrawRequest(tooSmallPr), userId)
    eventListener.expectMsgType[SwapInProcessor.SwapInWithdrawRequestDeniedTo] // Too small withdraw amount

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInWithdrawRequest(oneMilSat), userId)
    val paymentId1 = eventListener.expectMsgType[UUID]
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === SwapInState(2550000000L.msat, 2524752475L.msat, 10000000.msat, 1000000000.msat, Nil)) // 1 payment in-flight

    val exactRemaining = "lntb25247520n1p0cqv5lrzjqges6y6c0dn6shq2xm4qq8zdhg2te4ydm2yc3aesxf6mqs9lld4t6q3xghlyujw62cqqqqlgqqqqqeqqjqdqqcqzgapp5e4egsc8yj0zsxgn2dmyk7wa336m73x6g9" +
      "frk30q8x6edd9edr9jqxqyz5vpqhept7ncmhf8c0mtcsad7lw9kjrjhsh54zayk4xdhnmak0vevpz3u57fxtmyv3jmhe5rzlktz7nkanumutasnhx5u74ku5rhy5x5ktgpeg7lvw"

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInWithdrawRequest(exactRemaining), userId)
    val paymentId2 = eventListener.expectMsgType[UUID]
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === SwapInState(480L.msat, 475L.msat, 35247520L.msat, 3524752000L.msat, Nil)) // 2 payments in-flight

    system.eventStream.publish(PaymentFailed(paymentId1, ByteVector32.One, Nil))
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === SwapInState(1010000480L.msat, 1000000475L.msat, 25247520L.msat, 2524752000L.msat, Nil)) // 1st payment failed, 1 left

    val pp = PartialPayment(UUID.randomUUID(), 2524752L.sat.toMilliSatoshi, 10000L.msat, null, None)
    system.eventStream.publish(PaymentSent(paymentId2, ByteVector32.One, ByteVector32.Zeroes, 2524752L.sat.toMilliSatoshi, null, Seq(pp)))
    val finalExpectedState = SwapInState(1035238000L.msat, 1024988118L.msat, 0L.msat, 0L.msat, Nil)
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === finalExpectedState) // 2nd payment fulfilled, none left

    // Simulate restart, cache has been emptied
    val swapInProcessor2 = eventListener childActorOf Props(classOf[SwapInProcessor], Config.vals, kit, Config.db)
    swapInProcessor2 ! SwapInProcessor.AccountStatusFrom(userId)
    assert(eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state === finalExpectedState)
  }
}
