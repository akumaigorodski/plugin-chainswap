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

  // This requires a running bitcoind testnet with balance of 0.1 BTC
  // This requires a locally running pg instance

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

    Blocking.txWrite(Addresses.insertCompiled += ("new-bitcoin-address", userId), Config.db) // Use a fresh address
    swapInProcessor ! SwapInProcessor.SwapInRequestFrom(userId)
    assert(eventListener.expectMsgType[SwapInProcessor.SwapInResponseTo].response.btcAddress === "new-bitcoin-address")
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
    val SwapInState(Nil, Nil, Nil) = eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state // We always send this message

    val rawTx1ConfirmedAtBlock = 1720707
    val rawTx1 = "0100000001d2ecbeeee1e307835be483f1c2c5671f1a107e10e07da84ab27e6957d3a283e5000000006b483045022100deee31270085e1ea00f6872f489c552e8c8ba5351433a070b51db382abfdfcd9" +
      "0220298ff1f3c5f42ffb08e3ceb3eb7243cb1296246433b05d53257c3a9b012ade9301210362a3ee21ef77f2ddda4a79a4864ba55b3e16e642af3e80c110925db5f4a340f3ffffffff0240523600000000001976a91" +
      "4f062977f425b6ce70a7b08869864c83664fcf60888ac0000000000000000226a2005990528fb62094aed94fa546d1990e8b2b3fb6613fea8aa779819132c5aa82900000000"

    val rawAddress1 = "n3RzaNTD8LnBGkREBjSkouy5gmd2dVf7jQ" // 3560000 sat
    Blocking.txWrite(Addresses.insertCompiled += (rawAddress1, userId), Config.db) // inject a known address for given user
    val listener = Await.result(incomingChainTxProcessor ? Symbol("processor"), Blocking.span).asInstanceOf[ZMQListener]
    incomingChainTxProcessor ! AccountAndAddress(userId, rawAddress1) // User asks for address, make incomingChainTxProcessor expect a mempool tx
    synchronized(wait(100))

    val tx = Transaction.read(rawTx1)
    listener.onNewTx(tx) // get an incoming tx
    val SwapInState(List(deposit), Nil, Nil) = eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state
    assert(deposit.txid == tx.txid.toHex)

    val oneMilSat = "lntb10m1p0cqg40rzjqges6y6c0dn6shq2xm4qq8zdhg2te4ydm2yc3aesxf6mqs9lld4t6q3xghlyujw62cqqqqlgqqqqqeqqjqdqqcqzgapp5e46hd8z4dwh9z6t8uecls9phc9txdkvym52qc4syz7qs5" +
      "3r5sspsxqyz5vp8xmsusrvjuqt4knl64s437txwnkrhexa2zs7arm9z0zujwlsd26sh6dy6p3kyfjkjrm0hg2jg43eaccfeut8cm26e36vchhwc7f8g3spev5720"

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(oneMilSat, id = deposit.id), userId) // User attempts to withdraw an unconfirmed tx
    assert(eventListener.expectMsgType[SwapInProcessor.SwapInWithdrawRequestDeniedTo].reason == SwapInPaymentDenied.NO_WITHDRAWABLE_TX)

    listener.onNewBlock(Config.vals.bitcoinAPI.getBlock(rawTx1ConfirmedAtBlock)) // get that tx confirmed, incomingChainTxProcessor sends a message to swapInProcessor
    val SwapInState(Nil, List(deposit1), Nil) = eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state // swapInProcessor in turn informs parent about updated account status
    assert(deposit1.txid == tx.txid.toHex && deposit1.amountSat == 3560000L)

    val amountLessPr = "lntb1p07yq02pp5e2x2qsha0wm8ndqe3v0xt9sgzv9c6suzeehh4p2uprplvflumftsdqqxqrrsscqp79qy9qsqsp5ysruu5npml3m9sx3l8t7twyek2ehac" +
      "zctjc64pm8ey3zyg605r3q72kjgftc6f8998wyhmhq2edr0mju9jq97nhyftjc9d8kej7g05apsl5xw7ruqmp65c8cwh38xrugh6cpc9q3mqyn4a7ed4jf0e9smnspnlq6qh"

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(amountLessPr, id = deposit.id), userId)
    assert(eventListener.expectMsgType[SwapInProcessor.SwapInWithdrawRequestDeniedTo].reason == SwapInPaymentDenied.INVOICE_TX_AMOUNT_MISMATCH)

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(oneMilSat, id = 2), userId) // Non-existing txid
    assert(eventListener.expectMsgType[SwapInProcessor.SwapInWithdrawRequestDeniedTo].reason == SwapInPaymentDenied.NO_WITHDRAWABLE_TX)

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(oneMilSat, id = deposit.id), "user-id-2") // Different user id
    assert(eventListener.expectMsgType[SwapInProcessor.SwapInWithdrawRequestDeniedTo].reason == SwapInPaymentDenied.NO_WITHDRAWABLE_TX)

    val exactAmountPr = "lntb35600u1p07fvlrpp5zlvgze2kh942uej28pa0zxsyf5tgfu3pt99g3yfe42mqe2frq9hsdqqxqrrsscqp79qy9qsqsp5vhgf96k9vc99ej6ac" +
      "hdezjthup3sk0kx93tuykwy56wg0s2nvzfsr8vkm80taak7q3zxwqy4r77eyrqldwu9qhhywzna28jr5lkg00gkd9jyl9x73aw6zal3mpdlt0x5qmtnexptvxclh3fn2uyukqs02tspga3m5v"

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(exactAmountPr, id = deposit.id), userId)
    val paymentId1 = eventListener.expectMsgType[UUID]
    val SwapInState(Nil, List(deposit2), List(deposit3)) = eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state
    assert(deposit2.txid == tx.txid.toHex && deposit2.amountSat == 3560000L && deposit2 == deposit3)

    val exactAmountPr2 = "lntb35600u1p07fd9epp559x2xsd9zy78duaepzdhwsuaulkvf7ngy7xas324ey3r3k2rescqdqqxqrrsscqp79qy9qsqsp5wzuw82zdt6ledswwq0249l7uc7lvyh6er07z9n" +
      "2hn4apz8u9ckzs60n0q6dwkf7n6f8myut8m4eaezugl7npdsc7fhq570265n2kn9k4ffct7tvy7rxtdtwta2v36heq3tcrln2m5afxkac9x0dd9xejwwqq5dknl3"

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(exactAmountPr2, id = deposit.id), userId) // User tries to withdraw the same tx for the second time
    assert(eventListener.expectMsgType[SwapInProcessor.SwapInWithdrawRequestDeniedTo].reason == SwapInPaymentDenied.WITHDRAWAL_ALREADY_IN_FLIGHT)

    system.eventStream.publish(PaymentFailed(paymentId1, ByteVector32.One, Nil)) // Payment has failed
    val SwapInState(Nil, List(deposit4), Nil) = eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state // swapInProcessor in turn informs parent about updated account status
    assert(deposit4.txid == tx.txid.toHex && deposit4.amountSat == 3560000L)

    swapInProcessor ! SwapInProcessor.SwapInWithdrawRequestFrom(SwapInPaymentRequest(exactAmountPr2, id = deposit.id), userId) // User tries to withdraw the same tx for the second time
    val paymentId2 = eventListener.expectMsgType[UUID]
    val SwapInState(Nil, List(deposit5), List(deposit6)) = eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state
    assert(deposit5.txid == tx.txid.toHex && deposit5.amountSat == 3560000L && deposit5 == deposit6)

    val pp = PartialPayment(UUID.randomUUID(), 3560000000L.msat, 10000L.msat, null, None) // Fulfilled for the second time
    system.eventStream.publish(PaymentSent(paymentId2, ByteVector32.One, ByteVector32.Zeroes, 3560000000L.msat, null, Seq(pp)))
    val SwapInState(Nil, Nil, Nil) = eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state // swapInProcessor in turn informs parent about updated account status

    // Simulate restart, cache has been emptied
    val swapInProcessor2 = eventListener childActorOf Props(classOf[SwapInProcessor], Config.vals, kit, Config.db)
    swapInProcessor2 ! SwapInProcessor.AccountStatusFrom(userId)
    val SwapInState(Nil, Nil, Nil) = eventListener.expectMsgType[SwapInProcessor.AccountStatusTo].state
  }
}
