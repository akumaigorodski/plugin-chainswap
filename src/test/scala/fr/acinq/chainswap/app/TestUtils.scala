package fr.acinq.chainswap.app

import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.Await

object TestUtils {
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
}
