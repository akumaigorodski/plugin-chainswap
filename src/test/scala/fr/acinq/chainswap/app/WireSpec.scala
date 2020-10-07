package fr.acinq.chainswap.app

import fr.acinq.eclair._
import fr.acinq.chainswap.app.wire.Codecs._
import org.scalatest.funsuite.AnyFunSuite


class WireSpec extends AnyFunSuite {
  test("Correctly process inner messages") {
    assert(decode(encode(SwapInRequest)) === SwapInRequest)

    val pd1 = PendingDeposit(btcAddress = "1RustyRX2oai4EYYDpQGWvEL62BBGqN9T", txid = randomBytes32, amount = 200000.sat)
    val pd2 = PendingDeposit(btcAddress = "1RustyRX2oai4EYYDpQGWvEL62BBGqN9T", txid = randomBytes32, amount = 400000.sat)
    val inner1 = SwapInState(balance = 1000.msat, maxWithdrawable = 890.msat, activeFeeReserve = 10.msat, inFlightAmount = 100.msat, List(pd1, pd2))
    assert(decode(encode(inner1)) === inner1)

    val btaf1 = BlockTargetAndFee(blockTarget = 6, 200.sat)
    val btaf2 = BlockTargetAndFee(blockTarget = 12, 100.sat)
    val inner2 = SwapOutFeerates(List(btaf1, btaf2))
    assert(decode(encode(inner2)) === inner2)

    val sor = SwapOutResponse(100000000000L.sat, 90000.sat, "00" * 512)
    assert(decode(encode(sor)) === sor)
  }
}
