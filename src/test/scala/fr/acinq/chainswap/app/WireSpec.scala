package fr.acinq.chainswap.app

import fr.acinq.eclair._
import fr.acinq.chainswap.app.wire.Codecs._
import fr.acinq.chainswap.app.db.BTCDeposits
import org.scalatest.funsuite.AnyFunSuite


class WireSpec extends AnyFunSuite {
  test("Correctly process inner messages") {
    assert(decode(toUnknownMessage(SwapInRequest)).require === SwapInRequest)

    val pd1 = ChainDeposit(1L, Some("payment-id"), BTCDeposits.LN_IN_FLIGHT, "1RustyRX2oai4EYYDpQGWvEL62BBGqN9T", outIndex = 1L, txid = "txid", amountSat = 100000000L, depth = 4L, stamp = 10000000L)
    val pd2 = ChainDeposit(2L, None, BTCDeposits.LN_UNCLAIMED, "1RustyRX2oai4EYYDpQGWvEL62BBGqN9T", outIndex = 100L, txid = "txid", amountSat = 100000000L, depth = 4L, stamp = 10000000L)
    val inner1 = SwapInState(Nil, List(pd1, pd2), List(pd1))
    assert(decode(toUnknownMessage(inner1)).require === inner1)

    val b1 = BlockTargetAndFee(blockTarget = 6, 200.sat)
    val b2 = BlockTargetAndFee(blockTarget = 12, 100.sat)
    val inner2 = SwapOutFeerates(KeyedBlockTargetAndFee(List(b1, b2), randomBytes32), 10000000000L.sat, 50000L.sat)
    assert(decode(toUnknownMessage(inner2)).require === inner2)

    val sor = SwapOutTransactionResponse("00" * 512, 100000000000L.sat, "1RustyRX2oai4EYYDpQGWvEL62BBGqN9T", 90000.sat)
    assert(decode(toUnknownMessage(sor)).require === sor)

    val swd = SwapInPaymentDenied(paymentRequest = "00" * 512, reason = SwapInPaymentDenied.NO_WITHDRAWABLE_TX)
    assert(decode(toUnknownMessage(swd)).require === swd)

    val sod = SwapOutTransactionDenied(btcAddress = "00" * 32, reason = SwapOutTransactionDenied.AMOUNT_TOO_SMALL)
    assert(decode(toUnknownMessage(sod)).require === sod)
  }
}
