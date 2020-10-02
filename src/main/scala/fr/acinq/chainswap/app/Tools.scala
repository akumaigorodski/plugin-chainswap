package fr.acinq.chainswap.app

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.typesafe.config.{Config, ConfigFactory}

import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import com.google.common.cache.CacheBuilder
import org.postgresql.util.PSQLException
import java.util.concurrent.TimeUnit
import slick.jdbc.PostgresProfile
import fr.acinq.bitcoin.Base58
import java.io.File


object Tools {
  def makeExpireAfterAccessCache(expiryMins: Int): CacheBuilder[AnyRef, AnyRef] = CacheBuilder.newBuilder.expireAfterAccess(expiryMins, TimeUnit.MINUTES)
  def makeExpireAfterWriteCache(expiryMins: Int): CacheBuilder[AnyRef, AnyRef] = CacheBuilder.newBuilder.expireAfterWrite(expiryMins, TimeUnit.MINUTES)

  abstract class DuplicateInsertMatcher[T] {
    val matcher: PartialFunction[Throwable, T] = {
      case dup: PSQLException if "23505" == dup.getSQLState => onDuplicateError
      case otherDatabaseError: Throwable => onOtherError(otherDatabaseError)
    }

    def onDuplicateError: T
    def onOtherError(error: Throwable): T
  }
}

object Config {
  val config: Config = ConfigFactory parseFile new File(s"${System getProperty "user.dir"}/src/main/resources", "chainswap.conf")
  val db: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.relationalDb", config)
  val vals: Vals = config.as[Vals]("config.vals")
}

case class Vals(btcRPCApi: String, btcZMQApi: String, rewindBlocks: Int, isTestnet: Boolean, depthThreshold: Long, lookBackPeriodDays: Long) {
  val addressPrefix: Byte = if (isTestnet) Base58.Prefix.PubkeyAddressTestnet else Base58.Prefix.PubkeyAddress
  val bitcoinAPI: BitcoinJSONRPCClient = new BitcoinJSONRPCClient(btcRPCApi)
}