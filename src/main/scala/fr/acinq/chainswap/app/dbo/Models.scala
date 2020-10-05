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
  val longSpan: FiniteDuration = 5.minutes
  implicit val askTimeout: Timeout = Timeout(30.seconds)
  def txRead[T](act: DBIOAction[T, NoStream, Effect.Read], db: Database): T = Await.result(db.run(act.transactionally), span)
  def txWrite[T](act: DBIOAction[T, NoStream, Effect.Write], db: Database): T = Await.result(db.run(act.transactionally), span)
}


object Users {
  final val tableName = "users"
  val model = TableQuery[Users]
  type DbType = (Long, String, String)
  private val insert = for (x <- model) yield (x.btcAddress, x.accountId)
  private def findByBtcAddress(btcAddress: RepString) = model.filter(_.btcAddress === btcAddress).map(_.accountId)
  val findByBtcAddressCompiled = Compiled(findByBtcAddress _)
  val insertCompiled = Compiled(insert)
}

class Users(tag: Tag) extends Table[Users.DbType](tag, Users.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def btcAddress: Rep[String] = column[String]("btc_address", O.Unique)
  def accountId: Rep[String] = column[String]("account_id")
  def * = (id, btcAddress, accountId)
}


object BTCDeposits {
  val tableName = "btc_deposits"
  val model = TableQuery[BTCDeposits]

  type DbType = (Long, String, Long, String, Long, Long, Long)
  private def findByAddressComplete(btcAddress: RepString, threshold: RepLong) = model.filter(x => x.btcAddress === btcAddress && x.depth >= threshold).map(_.sat).sum
  private def findAllWaiting(threshold: RepLong, limit: RepLong) = model.filter(x => x.depth < threshold && x.stamp > limit)
  private def findDepthUpdatable(id: RepLong) = model.filter(_.id === id).map(_.depth)

  def clearUp = sqlu"""
     DELETE FROM #${BTCDeposits.tableName} B WHERE NOT EXISTS
     (SELECT * FROM #${Users.tableName} U WHERE B.btc_address = U.btc_address)
  """

  // Insert which silently ignores duplicate records
  def insert(btcAddress: String, outIdx: Long, txid: String, sat: Double, depth: Long) = sqlu"""
    INSERT INTO #${BTCDeposits.tableName}(btc_address, out_index, txid, sat, depth, stamp)
    VALUES ($btcAddress, $outIdx, $txid, $sat, $depth, ${System.currentTimeMillis})
    ON CONFLICT DO NOTHING
  """

  val findByAddressCompleteCompiled = Compiled(findByAddressComplete _)
  val findDepthUpdatableCompiled = Compiled(findDepthUpdatable _)
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
  private val insert = for (x <- model) yield (x.accountId, x.paymentId, x.reserveSat, x.paymentSat, x.stamp, x.status)
  private def findByAccountStatus(accountId: RepString, status: RepLong) = model.filter(x => x.accountId === accountId && x.status === status)
  val findByAccountStatusCompiled = Compiled(findByAccountStatus _)
  val insertCompiled = Compiled(insert)
}

class Account2LNWithdrawals(tag: Tag) extends Table[Account2LNWithdrawals.DbType](tag, "account_ln_withdrawals") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def accountId: Rep[String] = column[String]("account_id")
  def paymentId: Rep[String] = column[String]("payment_id", O.Unique)
  def reserveSat: Rep[Long] = column[Long]("reserve_sat")
  def paymentSat: Rep[Long] = column[Long]("payment_sat")
  def stamp: Rep[Long] = column[Long]("stamp")
  def status: Rep[Long] = column[Long]("status")

  def accountIdStatusIdx: Index = index("account_ln_withdrawals__account_id__status__idx", (accountId, status), unique = false)
  def * = (id, accountId, paymentId, reserveSat, paymentSat, stamp, status)
}


//object LN2BTCWithdrawals {
//  val model = TableQuery[LN2BTCWithdrawals]
//
//  final val WAITING = 1L
//  final val EXECUTED = 2L
//  final val CANCELLED = 3L
//
//  type DbType = (Long, String, String, Long, Long, Long, Long)
//  private val insert = for (u <- model) yield (u.accountId, u.paymentId, u.serviceFeeSat, u.baseSat, u.stamp, u.status)
//  private def findByAccountStatus(accountId: RepString, status: RepLong) = model.filter(u => u.accountId === accountId && u.status === status)
//  val findByAccountStatusCompiled = Compiled(findByAccountStatus _)
//  val insertCompiled = Compiled(insert)
//}
//
//class LN2BTCWithdrawals(tag: Tag) extends Table[LN2BTCWithdrawals.DbType](tag, "ln_btc_withdrawals") {
//  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
//  def accountId: Rep[String] = column[String]("account_id")
//  def paymentId: Rep[String] = column[String]("payment_id", O.Unique)
//  def serviceFeeSat: Rep[Long] = column[Long]("service_fee_sat")
//  def baseSat: Rep[Long] = column[Long]("base_sat")
//  def stamp: Rep[Long] = column[Long]("stamp")
//  def status: Rep[Long] = column[Long]("status")
//
//  def accountIdStatusIdx: Index = index("account_id__status__idx", (accountId, status), unique = false)
//  def * = (id, accountId, paymentId, serviceFeeSat, baseSat, stamp, status)
//}

/*
payment hash, send amount, user fee, target address, txid (may be empty), status (pending, executed, failed)
 */