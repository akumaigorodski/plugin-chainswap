package fr.acinq.chainswap.app

import akka.actor.{ActorSystem, Props}
import fr.acinq.bitcoin._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.chainswap.app.db.{BTCDeposits, Blocking, Addresses}
import fr.acinq.chainswap.app.processor.{IncomingChainTxProcessor, ZMQActor, ZMQActorInit, ZMQListener}
import org.scalatest.funsuite.AnyFunSuite
import akka.pattern.ask
import scala.concurrent.Await
import Blocking._
import akka.testkit.TestProbe


class IncomingChainTxProcessorSpec extends AnyFunSuite {

  // This requires a running bitcoind testnet with balance of 0.1 BTC
  // This requires a locally running pg instance

  test("Database integrity") {
    ChainSwapTestUtils.resetEntireDatabase()
  }

  test("Removing address-unmatched btc records from db") {
    ChainSwapTestUtils.resetEntireDatabase()
    Blocking.txWrite(Addresses.insertCompiled += ("btc-address", "account-id-1"), Config.db)
    Blocking.txWrite(Addresses.insertCompiled += ("not-reverse-matched-btc-address", "account-id-2"), Config.db)
    Blocking.txWrite(BTCDeposits.insert("btc-address", 1L, "txid1", 12D, 0L), Config.db)
    Blocking.txWrite(BTCDeposits.insert("btc-address", 1L, "txid1", 24D, 0L), Config.db)
    Blocking.txWrite(BTCDeposits.insert("not-matched-btc-address", 1L, "txid1", 12D, 0L), Config.db)
    val txs1 = Blocking.txRead(BTCDeposits.findAllWaitingCompiled(10, System.currentTimeMillis - 1000000L).result, Config.db).map(_._2)
    assert(txs1 == Vector("btc-address", "not-matched-btc-address"))
    Blocking.txWrite(BTCDeposits.clearUp, Config.db)
    val txs2 = Blocking.txRead(BTCDeposits.findAllWaitingCompiled(10, System.currentTimeMillis - 1000000L).result, Config.db).map(_._2)
    assert(txs2 == Vector("btc-address"))
  }

  test("Bitcoin stuff") {
    val raw = "020000000001015b183c70e939390c94e1c9a3473ebe9f72585ed024ff343be9fe08a91e740d450100000017160014f6805441b9d04dc32056ea8dfee35fe9475a3e80feffffff02d0070000000000" +
      "001976a914a4857797987879ef8e4c4b04f6fa2552cf5b473a88ac7a3909ad0100000017a9143f59bad48c2edb3804a4482c9fb2d3c32e9f258987024730440220277c443a682bce52e98d2f7bed9f0ff79bcf" +
      "f24d0c95ba330d8aa1aca62ab0320220106eb9a1ddd048050e32b92fd21caf10da9241947d267820b36f65837a6e58730121026ebef7b7bc5286225649c17f3fa6164221574af0efa23f1f12ad5d8a1b928fbe45411a00"

    val address = "mvWrzyVe1QMCcX3XrMEicXbt81Wd3Un35F"
    val List(OP_DUP, OP_HASH160, OP_PUSHDATA(hash160, _), OP_EQUALVERIFY, OP_CHECKSIG) = Script.parse(Transaction.read(raw).txOut.head.publicKeyScript)
    val address1 = Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, hash160)
    assert(address1 == address)
  }

  // This test requires a running testnet bitcoind

  test("On-chain deposits are processed correctly") {
    ChainSwapTestUtils.resetEntireDatabase()
    implicit val system: ActorSystem = ActorSystem("test-actor-system")
    val eventListener = TestProbe()
    val zmqActor = system actorOf Props(classOf[ZMQActor], Config.vals.bitcoinAPI, Config.vals.btcZMQApi, Config.vals.rewindBlocks)
    val incomingChainTxProcessor = system actorOf Props(classOf[IncomingChainTxProcessor], Config.vals, eventListener.ref, zmqActor, Config.db)
    zmqActor ! ZMQActorInit

    val rawTx1ConfirmedAtBlock = 1720707
    val rawTx1 = "0100000001d2ecbeeee1e307835be483f1c2c5671f1a107e10e07da84ab27e6957d3a283e5000000006b483045022100deee31270085e1ea00f6872f489c552e8c8ba5351433a070b51db382abfdfcd9" +
      "0220298ff1f3c5f42ffb08e3ceb3eb7243cb1296246433b05d53257c3a9b012ade9301210362a3ee21ef77f2ddda4a79a4864ba55b3e16e642af3e80c110925db5f4a340f3ffffffff0240523600000000001976a91" +
      "4f062977f425b6ce70a7b08869864c83664fcf60888ac0000000000000000226a2005990528fb62094aed94fa546d1990e8b2b3fb6613fea8aa779819132c5aa82900000000"
    val accountId1 =  "account-id-1"
    val rawAddress1 = "n3RzaNTD8LnBGkREBjSkouy5gmd2dVf7jQ" // 3560000 sat
    Blocking.txWrite(Addresses.insertCompiled += (rawAddress1, accountId1), Config.db)

    val rawTx2 = "01000000000101b2f18998bf4b8aa19c08c78e160069eae2682753a2b5b0fa784097f4a25a712e0100000023220020016b82e8225c2fe3ee4c61a8660dc63e9cf4b55343fc46536fb212aa76af5cfcfff" +
      "fffff0310270000000000001976a9148eb446f809f526fb37059a32cf8255c4cb43d2da88ac10270000000000001976a9149f9a7abd600c0caa03983a77c8c3df8e062cb2fa88ac8d618c000000000017a914763943a" +
      "151f5ab0be94d8e13401d3983a9aff35b87040047304402205ed15c689590dbdd31de829c562809edb808ae0182d87a7b4f3e18543da8c380022044397bc853650096692d141d50a4abdce192683a95a933058cd032b" +
      "806c76b490147304402207f79cc0d055d505d2544a1b5ae43c72dbcdbf15a9a79e0a9e68dd8e9cd2bc83c02200beb8f4828b5e4fe1203f8ca40106e47b896bcbfdd15b0add1b7188fa8b529ca0169522102f2438b260" +
      "4e0dd999a91f34a203f23431a5b3c9a142741233c43aad695e2bb442102436417dd00e432efaeea1c053593cfb9e0a55e35f3089d25c51d44ed0de55884210239fb974a143b44b435777fa118e34f4abd2cbd3dd7c57" +
      "d7bee2740928862cad353ae00000000"
    val accountId2 =  "account-id-2"
    val rawAddress2 = "mv4rnyY3Su5gjcDNzbMLKBQkBicCtHUtFB" // 10000 sat, but THIS UTXO IS NOT PRESENT ANYMORE (as if it was thrown out of mempool after pending long enough)
    Blocking.txWrite(Addresses.insertCompiled += (rawAddress2, accountId2), Config.db)
    val listener = Await.result(incomingChainTxProcessor ? Symbol("processor"), Blocking.span).asInstanceOf[ZMQListener]

    incomingChainTxProcessor ! AccountAndAddress(accountId1, rawAddress1)
    incomingChainTxProcessor ! AccountAndAddress(accountId2, rawAddress2)
    synchronized(wait(500))
    listener.onNewTx(Transaction.read(rawTx1))
    listener.onNewTx(Transaction.read(rawTx2))
    synchronized(wait(500))

    val event1 = eventListener.expectMsgType[ChainDepositReceived]
    val event2 = eventListener.expectMsgType[ChainDepositReceived]
    assert(event1.txid == Transaction.read(rawTx1).txid.toHex)
    assert(event2.txid == Transaction.read(rawTx2).txid.toHex)
    assert(event1.depth == 0L)
    assert(event2.depth == 0L)

    // Simulate some busy work
    assert(Blocking.txRead(BTCDeposits.findSumCompleteForAccountCompiled(accountId1, 1L).result, Config.db).isEmpty)
    (171868 to 172068).foreach(num => listener.onNewBlock(Config.vals.bitcoinAPI.getBlock(num)))
    listener.onNewBlock(Config.vals.bitcoinAPI.getBlock(rawTx1ConfirmedAtBlock))
    assert(eventListener.expectMsgType[ChainDepositReceived].amount == Satoshi(3560000))

    assert(Blocking.txRead(BTCDeposits.findSumCompleteForAccountCompiled(accountId1, 1L).result, Config.db).contains(3560000))
    assert(Blocking.txRead(BTCDeposits.findAllWaitingCompiled(2L, 0L).result, Config.db).size == 1)

    val cleanedResult = Blocking.txRead(BTCDeposits.model.map(_.depth).result, Config.db)
    assert(cleanedResult.count(_ > Config.vals.depthThreshold) == 1 && cleanedResult.size == 2) // One confirmed, one still waiting

    // Simulate malfuction: same block sent twice
    listener.onNewBlock(Config.vals.bitcoinAPI.getBlock(rawTx1ConfirmedAtBlock))
    assert(Blocking.txRead(BTCDeposits.findSumCompleteForAccountCompiled(accountId1, 1L).result, Config.db).contains(3560000))
    assert(Blocking.txRead(BTCDeposits.findAllWaitingCompiled(2L, 0L).result, Config.db).size == 1)
    val cleanedResult1 = Blocking.txRead(BTCDeposits.model.map(_.depth).result, Config.db)
    assert(cleanedResult.count(_ > Config.vals.depthThreshold) == 1 && cleanedResult1.size == 2)

    // Simulate rescan
    (171868 to 172068).foreach(num => listener.onNewBlock(Config.vals.bitcoinAPI.getBlock(num)))
    listener.onNewBlock(Config.vals.bitcoinAPI.getBlock(rawTx1ConfirmedAtBlock))
    synchronized(wait(500L))
    assert(Blocking.txRead(BTCDeposits.findSumCompleteForAccountCompiled(accountId1, 1L).result, Config.db).contains(3560000))
    assert(Blocking.txRead(BTCDeposits.findSumCompleteForAccountCompiled(accountId2, 1L).result, Config.db).isEmpty)
    assert(Blocking.txRead(BTCDeposits.findAllWaitingCompiled(2L, 0L).result, Config.db).size == 1)
    val cleanedResult2 = Blocking.txRead(BTCDeposits.model.map(_.depth).result, Config.db)
    assert(cleanedResult.count(_ > Config.vals.depthThreshold) == 1 && cleanedResult2.size == 2)

    assert(Blocking.txRead(BTCDeposits.findWaitingForAccountCompiled(accountId2, 1L, 0L).result, Config.db).map(BTCDeposit.tupled).map(_.toPendingDeposit).head.btcAddress == rawAddress2)
  }
}