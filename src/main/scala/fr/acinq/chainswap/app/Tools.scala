package fr.acinq.chainswap.app

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.typesafe.config.{Config => Configuration, ConfigFactory}
import wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient
import com.google.common.cache.CacheBuilder
import java.util.concurrent.TimeUnit
import slick.jdbc.PostgresProfile
import fr.acinq.bitcoin.Base58
import java.io.File


object Tools {
  def makeExpireAfterAccessCache(expiryMins: Int): CacheBuilder[AnyRef, AnyRef] = CacheBuilder.newBuilder.expireAfterAccess(expiryMins, TimeUnit.MINUTES)
  def makeExpireAfterWriteCache(expiryMins: Int): CacheBuilder[AnyRef, AnyRef] = CacheBuilder.newBuilder.expireAfterWrite(expiryMins, TimeUnit.MINUTES)
}

object Config {
  val config: Configuration = ConfigFactory parseFile new File(s"${System getProperty "user.dir"}/src/main/resources", "chainswap.conf")

  val db: PostgresProfile.backend.Database = PostgresProfile.backend.Database.forConfig("config.relationalDb", config)

  val vals: Vals = config.as[Vals]("config.vals")
}

case class Vals(btcRPCApi: String, btcZMQApi: String, rewindBlocks: Int, isTestnet: Boolean, minChainDepositSat: Long,
                lnMaxFeePct: Double, lnMinWithdrawMsat: Long, depthThreshold: Long, lookBackPeriodDays: Long,
                feePerKbDivider: Double, chainBalanceReserve: Int, chainMinWithdrawSat: Long) {

  val addressPrefix: Byte = if (isTestnet) Base58.Prefix.PubkeyAddressTestnet else Base58.Prefix.PubkeyAddress

  val bitcoinAPI: BitcoinJSONRPCClient = new BitcoinJSONRPCClient(btcRPCApi)

  val lookBackPeriodMsecs: Long = 1000L * 3600 * 24 * lookBackPeriodDays
}