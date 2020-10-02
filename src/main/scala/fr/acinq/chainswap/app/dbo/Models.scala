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
  private val insert = for (u <- model) yield (u.btcAddress, u.btcAddress, u.accountId)
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
  type DbType = (Long, String, Long, String, Double, Long, Long)
  private def findUserSum(btcAddress: RepString, threshold: RepLong) = model.filter(d => d.btcAddress === btcAddress && d.depth >= threshold).map(_.sat).sum
  private def findUserWaiting(btcAddress: RepString, threshold: RepLong) = model.filter(d => d.btcAddress === btcAddress && d.depth < threshold).map(_.txid)
  private def recentForAddress(btcAddress: RepString) = model.filter(_.btcAddress === btcAddress).sortBy(_.id.desc) take 10

  private def findDepthUpdatable(id: RepLong) = model.filter(_.id === id).map(_.depth)
  private def findAllWaiting(threshold: RepLong, limit: RepLong) = model.filter(d => d.depth < threshold && d.stamp > limit)
  def clearUp = sqlu"DELETE FROM #${BTCDeposits.tableName} B WHERE NOT EXISTS (SELECT * FROM #${Users.tableName} U WHERE B.btc_address = U.btc_address)"

  // Insert which silently ignores duplicate records
  def insert(btcAddress: String, outIdx: Long, txid: String, tks: Double, depth: Long) = sqlu"""
    INSERT INTO #${BTCDeposits.tableName}(btc_address, out_index, txid, tokens, depth, stamp)
    VALUES ($btcAddress, $outIdx, $txid, $tks, $depth, ${System.currentTimeMillis})
    ON CONFLICT DO NOTHING
  """

  val findUserSumCompiled = Compiled(findUserSum _)
  val findUserWaitingCompiled = Compiled(findUserWaiting _)
  val recentForAddressCompiled = Compiled(recentForAddress _)
  val findDepthUpdatableCompiled = Compiled(findDepthUpdatable _)
  val findAllWaitingCompiled = Compiled(findAllWaiting _)
}

class BTCDeposits(tag: Tag) extends Table[BTCDeposits.DbType](tag, BTCDeposits.tableName) {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def btcAddress: Rep[String] = column[String]("btc_address")
  def outIndex: Rep[Long] = column[Long]("out_index")
  def txid: Rep[String] = column[String]("txid")
  def sat: Rep[Double] = column[Double]("sat")
  def depth: Rep[Long] = column[Long]("depth")
  def stamp: Rep[Long] = column[Long]("stamp")

  // We need this index to prevent double insertion (and double deposit) for txs which are seen in mempool first and then in a block
  def btcAddressOutIndexTxidIdx: Index = index("btc_deposits__btc_address__out_index__txid__idx", (btcAddress, outIndex, txid), unique = true)
  def depthStampIdx: Index = index("btc_deposits__depth__stamp__idx", (depth, stamp), unique = false)
  def * = (id, btcAddress, outIndex, txid, sat, depth, stamp)
}