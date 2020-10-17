package fr.acinq.chainswap.app.dbo

import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.chainswap.app.dbo.Blocking._
import slick.lifted.{Index, Tag}

import slick.jdbc.PostgresProfile.backend.Database
import scala.concurrent.Await
import akka.util.Timeout
import slick.dbio.Effect


object Blocking {
  type RepInt = Rep[Int]
  type RepLong = Rep[Long]
  type RepString = Rep[String]

  val span: FiniteDuration = 25.seconds
  implicit val askTimeout: Timeout = Timeout(30.seconds)
  def txRead[T](act: DBIOAction[T, NoStream, Effect.Read], db: Database): T = Await.result(db.run(act.transactionally), span)
  def txWrite[T](act: DBIOAction[T, NoStream, Effect.Write], db: Database): T = Await.result(db.run(act.transactionally), span)

  def createTablesIfNotExist(db: Database): Unit = {
    val tables = Seq(Accounts.model, BTCDeposits.model, Account2LNWithdrawals.model).map(_.schema.createIfNotExists)
    Await.result(db.run(DBIO.sequence(tables).transactionally), span)
  }
}


object Accounts {
  final val tableName = "accounts"
  val model = TableQuery[Accounts]
  type DbType = (Long, String, String)
  private val insert = for (x <- model) yield (x.btcAddress, x.accountId)
  private def findByBtcAddress(btcAddress: RepString) = model.filter(_.btcAddress === btcAddress).map(_.accountId)
  private def findByAccountId(accountId: RepString) = model.filter(_.accountId === accountId).map(_.btcAddress)

  val findByBtcAddressCompiled = Compiled(findByBtcAddress _)
  val findByAccountIdCompiled = Compiled(findByAccountId _)
  val insertCompiled = Compiled(insert)
}

class Accounts(tag: Tag) extends Table[Accounts.DbType](tag, Accounts.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def btcAddress: Rep[String] = column[String]("btc_address", O.Unique)
  def accountId: Rep[String] = column[String]("account_id")

  def accountIdIdx: Index = index(s"${tableName}__account_id__idx", accountId, unique = false)
  def * = (id, btcAddress, accountId)
}


object BTCDeposits {
  final val tableName = "btc_deposits"
  val model = TableQuery[BTCDeposits]

  type DbType = (Long, String, Long, String, Long, Long, Long)
  private def findAllWaiting(threshold: RepLong, limit: RepLong) = model.filter(x => x.depth < threshold && x.stamp > limit)
  private def findAllDepthUpdatable(id: RepLong) = model.filter(_.id === id).map(_.depth)

  private def findFor(accountId: RepString) = Accounts.model.filter(_.accountId === accountId).join(BTCDeposits.model).on(_.btcAddress === _.btcAddress).map(_._2)
  private def findWaitingForAccount(accountId: RepString, threshold: RepLong, limit: RepLong) = findFor(accountId).filter(deposit => deposit.depth < threshold && deposit.stamp > limit)
  private def findSumCompleteForAccount(accountId: RepString, threshold: RepLong) = findFor(accountId).filter(_.depth >= threshold).map(_.sat).sum

  def clearUp = sqlu"""
     DELETE FROM #${BTCDeposits.tableName} B WHERE NOT EXISTS
     (SELECT * FROM #${Accounts.tableName} U WHERE B.btc_address = U.btc_address)
  """

  // Insert which silently ignores duplicate records
  def insert(btcAddress: String, outIdx: Long, txid: String, sat: Double, depth: Long) = sqlu"""
    INSERT INTO #${BTCDeposits.tableName} (btc_address, out_index, txid, sat, depth, stamp)
    VALUES ($btcAddress, $outIdx, $txid, $sat, $depth, ${System.currentTimeMillis})
    ON CONFLICT DO NOTHING
  """

  val findSumCompleteForAccountCompiled = Compiled(findSumCompleteForAccount _)
  val findAllDepthUpdatableCompiled = Compiled(findAllDepthUpdatable _)
  val findWaitingForAccountCompiled = Compiled(findWaitingForAccount _)
  val findAllWaitingCompiled = Compiled(findAllWaiting _)
}

class BTCDeposits(tag: Tag) extends Table[BTCDeposits.DbType](tag, BTCDeposits.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def btcAddress: Rep[String] = column[String]("btc_address")
  def outIndex: Rep[Long] = column[Long]("out_index")
  def txid: Rep[String] = column[String]("txid")
  def sat: Rep[Long] = column[Long]("sat")
  def depth: Rep[Long] = column[Long]("depth")
  def stamp: Rep[Long] = column[Long]("stamp")

  // We need this index to prevent double insertion (and double deposit) for txs which are seen in mempool first and then in a block
  def btcAddressOutIndexTxidIdx: Index = index("btc_deposits__btc_address__out_index__txid__idx", (btcAddress, outIndex, txid), unique = true)
  def depthStampIdx: Index = index("btc_deposits__depth__stamp__idx", (depth, stamp), unique = false)
  def * = (id, btcAddress, outIndex, txid, sat, depth, stamp)
}


object Account2LNWithdrawals {
  val model = TableQuery[Account2LNWithdrawals]

  final val PENDING = 1L
  final val SUCCEEDED = 2L
  final val FAILED = 3L

  type DbType = (Long, String, String, Long, Long, Long, Long)
  private val insert = for (x <- model) yield (x.accountId, x.paymentId, x.feeReserveMsat, x.paymentMsat, x.stamp, x.status)
  private def findStatusFeeByIdUpdatable(paymentId: RepString) = model.filter(x => x.paymentId === paymentId).map(w => w.status -> w.feeReserveMsat)
  private def findSuccessfulWithdrawalSum(accountId: RepString) = model.filter(x => x.status === SUCCEEDED && x.accountId === accountId).map(w => w.paymentMsat + w.feeReserveMsat).sum
  private def findPendingWithdrawalsByAccount(accountId: RepString) = model.filter(x => x.status === PENDING && x.accountId === accountId).map(w => w.paymentMsat -> w.feeReserveMsat)
  private def findAccountById(paymentId: RepString) = model.filter(_.paymentId === paymentId).map(_.accountId)

  val insertCompiled = Compiled(insert)
  val findStatusFeeByIdUpdatableCompiled = Compiled(findStatusFeeByIdUpdatable _)
  val findSuccessfulWithdrawalSumCompiled = Compiled(findSuccessfulWithdrawalSum _)
  val findPendingWithdrawalsByAccountCompiled = Compiled(findPendingWithdrawalsByAccount _)
  val findAccountByIdCompiled = Compiled(findAccountById _)
}

class Account2LNWithdrawals(tag: Tag) extends Table[Account2LNWithdrawals.DbType](tag, "account_ln_withdrawals") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def accountId: Rep[String] = column[String]("account_id")
  def paymentId: Rep[String] = column[String]("payment_id", O.Unique)
  def feeReserveMsat: Rep[Long] = column[Long]("fee_reserve_msat")
  def paymentMsat: Rep[Long] = column[Long]("payment_msat")
  def stamp: Rep[Long] = column[Long]("stamp")
  def status: Rep[Long] = column[Long]("status")

  def statusAccountIdIdx: Index = index("account_ln_withdrawals__status__account_id__idx", (status, accountId), unique = false)
  def * = (id, accountId, paymentId, feeReserveMsat, paymentMsat, stamp, status)
}
