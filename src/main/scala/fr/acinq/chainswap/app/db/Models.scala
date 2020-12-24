package fr.acinq.chainswap.app.db

import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import fr.acinq.chainswap.app.db.Blocking._
import slick.lifted.{Index, Tag}

import slick.jdbc.PostgresProfile.backend.Database
import scala.concurrent.Await
import akka.util.Timeout
import slick.dbio.Effect


object Blocking {
  type RepInt = Rep[Int]
  type RepLong = Rep[Long]
  type RepString = Rep[String]
  type LNPaymentId = Option[String]

  val span: FiniteDuration = 25.seconds
  implicit val timeout: Timeout = Timeout(span)
  def txRead[T](act: DBIOAction[T, NoStream, Effect.Read], db: Database): T = Await.result(db.run(act.transactionally), span)
  def txWrite[T](act: DBIOAction[T, NoStream, Effect.Write], db: Database): T = Await.result(db.run(act.transactionally), span)

  def createTablesIfNotExist(db: Database): Unit = {
    val tables = Seq(Addresses.model, BTCDeposits.model).map(_.schema.createIfNotExists)
    Await.result(db.run(DBIO.sequence(tables).transactionally), span)
  }
}


object Addresses {
  final val tableName = "addresses"
  val model = TableQuery[Addresses]
  type DbType = (Long, String, String)
  private val insert = for (x <- model) yield (x.btcAddress, x.accountId)
  private def findByBtcAddress(btcAddress: RepString) = model.filter(_.btcAddress === btcAddress).map(_.accountId)
  private def findByAccountId(accountId: RepString) = model.filter(_.accountId === accountId).sortBy(_.id.desc).map(_.btcAddress)

  val findByBtcAddressCompiled = Compiled(findByBtcAddress _)
  val findByAccountIdCompiled = Compiled(findByAccountId _)
  val insertCompiled = Compiled(insert)
}

class Addresses(tag: Tag) extends Table[Addresses.DbType](tag, Addresses.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def btcAddress: Rep[String] = column[String]("btc_address", O.Unique)
  def accountId: Rep[String] = column[String]("account_id")

  // Same accountId may have multiple unique Bitcoin addresses, hence index is not unique
  def accountIdIdx: Index = index(s"${tableName}__account_id__idx", accountId, unique = false)
  def * = (id, btcAddress, accountId)
}


object BTCDeposits {
  final val tableName = "btc_deposits"
  val model = TableQuery[BTCDeposits]

  final val LN_IN_FLIGHT = 1L
  final val LN_SUCCEEDED = 2L
  final val LN_UNCLAIMED = 3L

  type DbType = (Long, LNPaymentId, Long, String, Long, String, Long, Long, Long)
  private def findDepthUpdatableById(id: RepLong) = model.filter(_.id === id).map(_.depth)
  private def findAllWaiting(threshold: RepLong, limit: RepLong) = model.filter(x => x.depth < threshold && x.stamp > limit)

  private def findFor(accountId: RepString) = Addresses.model.filter(_.accountId === accountId).join(BTCDeposits.model).on(_.btcAddress === _.btcAddress).map(_._2)
  private def findWaitingForAccount(accountId: RepString, threshold: RepLong, limit: RepLong) = findFor(accountId).filter(x => x.depth < threshold && x.stamp > limit)
  private def findWithdrawableForAccount(accountId: RepString, threshold: RepLong) = findFor(accountId).filter(x => x.depth >= threshold && x.stamp > 0L && x.lnStatus =!= LN_SUCCEEDED)
  private def findInFlightForAccount(accountId: RepString, threshold: RepLong) = findFor(accountId).filter(x => x.depth >= threshold && x.stamp > 0L && x.lnStatus === LN_IN_FLIGHT)

  private def findLNUpdatableById(id: RepLong) = model.filter(_.id === id).map(x => x.lnPaymentId -> x.lnStatus)
  private def findLNUpdatableByPaymentId(paymentId: RepString) = model.filter(_.lnPaymentId === paymentId).map(x => x.lnPaymentId -> x.lnStatus)
  private def findAccountIdByPaymentId(paymentId: RepString) = BTCDeposits.model.filter(_.lnPaymentId === paymentId).join(Addresses.model).on(_.btcAddress === _.btcAddress).map(_._2.accountId)

  def clearUp = sqlu"""
    DELETE FROM #${BTCDeposits.tableName} deposits WHERE NOT EXISTS
    (SELECT * FROM #${Addresses.tableName} accounts WHERE deposits.btc_address = accounts.btc_address)
  """

  val noId = Option.empty[String]
  // Insert which silently ignores duplicate records
  def insert(btcAddress: String, outIdx: Long, txid: String, sat: Long, depth: Long, stamp: Long = System.currentTimeMillis) = sqlu"""
    INSERT INTO #${BTCDeposits.tableName} (ln_payment_id, ln_status, btc_address, out_index, txid, sat, depth, stamp)
    VALUES ($noId, $LN_UNCLAIMED, $btcAddress, $outIdx, $txid, $sat, $depth, $stamp) ON CONFLICT DO NOTHING
  """

  val findDepthUpdatableByIdCompiled = Compiled(findDepthUpdatableById _)
  val findAllWaitingCompiled = Compiled(findAllWaiting _)

  val findWaitingForAccountCompiled = Compiled(findWaitingForAccount _)
  val findWithdrawableForAccountCompiled = Compiled(findWithdrawableForAccount _)
  val findInFlightForAccountCompiled = Compiled(findInFlightForAccount _)

  val findLNUpdatableByIdCompiled = Compiled(findLNUpdatableById _)
  val findLNUpdatableByPaymentIdCompiled = Compiled(findLNUpdatableByPaymentId _)
  val findAccountIdByPaymentIdCompiled = Compiled(findAccountIdByPaymentId _)
}

class BTCDeposits(tag: Tag) extends Table[BTCDeposits.DbType](tag, BTCDeposits.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def lnPaymentId: Rep[LNPaymentId] = column[LNPaymentId]("ln_payment_id")
  def lnStatus: Rep[Long] = column[Long]("ln_status")

  def btcAddress: Rep[String] = column[String]("btc_address")
  def outIndex: Rep[Long] = column[Long]("out_index")
  def txid: Rep[String] = column[String]("txid")
  def sat: Rep[Long] = column[Long]("sat")
  def depth: Rep[Long] = column[Long]("depth")
  def stamp: Rep[Long] = column[Long]("stamp")

  // We need this index to prevent double insertion (and double deposit) for txs which are seen in mempool first and then in a block
  def btcAddressOutIndexTxidIdx: Index = index("btc_deposits__btc_address__out_index__txid__idx", (btcAddress, outIndex, txid), unique = true)
  def depthStampLnStatusIdx: Index = index("btc_deposits__depth__stamp__ln_status__idx", (depth, stamp, lnStatus), unique = false)
  def paymentIdIdx: Index = index("btc_deposits__ln_payment_id__idx", lnPaymentId, unique = false)
  def * = (id, lnPaymentId, lnStatus, btcAddress, outIndex, txid, sat, depth, stamp)
}