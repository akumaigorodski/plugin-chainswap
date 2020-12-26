/*
 * BitcoindRpcClient-JSON-RPC-Client License
 * 
 * Copyright (c) 2013, Mikhail Yevchenko.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the 
 * Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject
 * to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
 * ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
 * THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
 /*
 * Repackaged with simple additions for easier maven usage by Alessandro Polverini
 */
package wf.bitcoin.javabitcoindrpcclient;

import wf.bitcoin.krotjson.Base64Coder;
import wf.bitcoin.krotjson.JSON;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static wf.bitcoin.javabitcoindrpcclient.MapWrapper.*;

/**
 *
 * @author Mikhail Yevchenko m.ṥῥẚɱ.ѓѐḿởύḙ at azazar.com Small modifications by
 * Alessandro Polverini polverini at gmail.com
 */
public class BitcoinJSONRPCClient implements BitcoindRpcClient {

  private static final Logger logger = Logger.getLogger(BitcoinJSONRPCClient.class.getCanonicalName());

  public final URL rpcURL;

  private URL noAuthURL;
  private String authStr;

  public BitcoinJSONRPCClient(String rpcUrl) throws MalformedURLException {
    this(new URL(rpcUrl));
  }

  public BitcoinJSONRPCClient(URL rpc) {
    this.rpcURL = rpc;
    try {
      noAuthURL = new URI(rpc.getProtocol(), null, rpc.getHost(), rpc.getPort(), rpc.getPath(), rpc.getQuery(), null).toURL();
    } catch (MalformedURLException | URISyntaxException ex) {
      throw new IllegalArgumentException(rpc.toString(), ex);
    }
    authStr = rpc.getUserInfo() == null ? null : String.valueOf(Base64Coder.encode(rpc.getUserInfo().getBytes(Charset.forName("ISO8859-1"))));
  }

  private HostnameVerifier hostnameVerifier = null;
  private SSLSocketFactory sslSocketFactory = null;

  public static final Charset QUERY_CHARSET = Charset.forName("ISO8859-1");

  public byte[] prepareRequest(final String method, final Object... params) {
    return JSON.stringify(new LinkedHashMap() {
      {
        put("method", method);
        put("params", params);
        put("id", "1");
      }
    }).getBytes(QUERY_CHARSET);
  }

  private static byte[] loadStream(InputStream in, boolean close) throws IOException {
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    for (;;) {
      int nr = in.read(buffer);

      if (nr == -1)
        break;
      if (nr == 0)
        throw new IOException("Read timed out");

      o.write(buffer, 0, nr);
    }
    return o.toByteArray();
  }

  public Object loadResponse(InputStream in, Object expectedID, boolean close) throws IOException, BitcoinRpcException {
    try {
      String r = new String(loadStream(in, close), QUERY_CHARSET);
      logger.log(Level.FINE, "Bitcoin JSON-RPC response:\n{0}", r);
      try {
        Map response = (Map) JSON.parse(r);

        if (!expectedID.equals(response.get("id")))
          throw new BitcoinRPCException("Wrong response ID (expected: " + String.valueOf(expectedID) + ", response: " + response.get("id") + ")");

        if (response.get("error") != null)
          throw new BitcoinRpcException(JSON.stringify(response.get("error")));

        return response.get("result");
      } catch (ClassCastException ex) {
        throw new BitcoinRPCException("Invalid server response format (data: \"" + r + "\")");
      }
    } finally {
      if (close)
        in.close();
    }
  }

  public Object query(String method, Object... o) throws BitcoinRpcException {
    HttpURLConnection conn;
    try {
      conn = (HttpURLConnection) noAuthURL.openConnection();

      conn.setDoOutput(true);
      conn.setDoInput(true);

      if (conn instanceof HttpsURLConnection) {
        if (hostnameVerifier != null)
          ((HttpsURLConnection) conn).setHostnameVerifier(hostnameVerifier);
        if (sslSocketFactory != null)
          ((HttpsURLConnection) conn).setSSLSocketFactory(sslSocketFactory);
      }

      conn.setRequestProperty("Authorization", "Basic " + authStr);
      byte[] r = prepareRequest(method, o);
      logger.log(Level.FINE, "Bitcoin JSON-RPC request:\n{0}", new String(r, QUERY_CHARSET));
      conn.getOutputStream().write(r);
      conn.getOutputStream().close();
      int responseCode = conn.getResponseCode();
      if (responseCode != 200)
        throw new BitcoinRPCException(method, Arrays.deepToString(o), responseCode, conn.getResponseMessage(), new String(loadStream(conn.getErrorStream(), true)));
      return loadResponse(conn.getInputStream(), "1", true);
    } catch (IOException ex) {
      throw new BitcoinRPCException(method, Arrays.deepToString(o), ex);
    }
  }

  @Override
  public String dumpPrivKey(String address) throws BitcoinRpcException {
    return (String) query("dumpprivkey", address);
  }

  @Override
  public String getAccount(String address) throws BitcoinRpcException {
    return (String) query("getaccount", address);
  }

  @Override
  public String getAccountAddress(String address) throws BitcoinRpcException {
    return (String) query("getaccountaddress", address);
  }

  @Override
  public double getBalance() throws BitcoinRpcException {
    return ((Number) query("getbalance")).doubleValue();
  }

  @Override
  public double getBalance(String account) throws BitcoinRpcException {
    return ((Number) query("getbalance", account)).doubleValue();
  }

  @Override
  public double getBalance(String account, int minConf) throws BitcoinRpcException {
    return ((Number) query("getbalance", account, minConf)).doubleValue();
  }

  private class AddressWrapper extends MapWrapper implements Address, Serializable {

    public AddressWrapper(Map m) { super(m); }

    @Override
    public String address() {
      return mapStr("address");
    }

    @Override
    public String connected() {
      return mapStr("connected");
    }
  }


  private class TxOutWrapper extends MapWrapper implements TxOut, Serializable {

    public TxOutWrapper(Map m) {
      super(m);
    }

    @Override
    public String bestBlock() {
      return mapStr("bestblock");
    }

    @Override
    public long confirmations() {
      return mapLong("confirmations");
    }

    @Override
    public BigDecimal value() {
      return mapBigDecimal("value");
    }

    @Override
    public ScriptPubKey scriptPubKey() {
      return new ScriptPubKeyImpl((Map) m.get("scriptPubKey"));
    }

    @Override
    public long reqSigs() {
      return mapLong("reqSigs");
    }

    @Override
    public List<String> addresses() {
      return (List<String>) m.get("addresses");
    }

    @Override
    public long version() {
      return mapLong("version");
    }

    @Override
    public boolean coinBase() {
      return mapBool("coinbase");
    }
  }

  private class BlockChainInfoMapWrapper extends MapWrapper implements BlockChainInfo, Serializable {

    public BlockChainInfoMapWrapper(Map m) {
      super(m);
    }

    @Override
    public String chain() {
      return mapStr("chain");
    }

    @Override
    public int blocks() {
      return mapInt("blocks");
    }

    @Override
    public String bestBlockHash() {
      return mapStr("bestblockhash");
    }

    @Override
    public double difficulty() {
      return mapDouble("difficulty");
    }

    @Override
    public double verificationProgress() {
      return mapDouble("verificationprogress");
    }

    @Override
    public String chainWork() {
      return mapStr("chainwork");
    }
  }

  private class BlockMapWrapper extends MapWrapper implements Block, Serializable {

    public BlockMapWrapper(Map m) {
      super(m);
    }

    @Override
    public String hash() {
      return mapStr("hash");
    }

    @Override
    public int confirmations() {
      return mapInt("confirmations");
    }

    @Override
    public int size() {
      return mapInt("size");
    }

    @Override
    public int height() {
      return mapInt("height");
    }

    @Override
    public int version() {
      return mapInt("version");
    }

    @Override
    public String merkleRoot() {
      return mapStr("merkleroot");
    }

    @Override
    public String chainwork() {
      return mapStr("chainwork");
    }

    @Override
    public List<String> tx() {
      return (List<String>) m.get("tx");
    }

    @Override
    public Date time() {
      return mapCTime("time");
    }

    @Override
    public long nonce() {
      return mapLong("nonce");
    }

    @Override
    public String bits() {
      return mapStr("bits");
    }

    @Override
    public double difficulty() {
      return mapDouble("difficulty");
    }

    @Override
    public String previousHash() {
      return mapStr("previousblockhash");
    }

    @Override
    public String nextHash() {
      return mapStr("nextblockhash");
    }

    @Override
    public Block previous() throws BitcoinRpcException {
      if (!m.containsKey("previousblockhash"))
        return null;
      return getBlock(previousHash());
    }

    @Override
    public Block next() throws BitcoinRpcException {
      if (!m.containsKey("nextblockhash"))
        return null;
      return getBlock(nextHash());
    }

  }

  @Override
  public Block getBlock(int height) throws BitcoinRpcException {
    String hash = (String) query("getblockhash", height);
    return getBlock(hash);
  }

  @Override
  public Block getBlock(String blockHash) throws BitcoinRpcException {
    return new BlockMapWrapper((Map) query("getblock", blockHash));
  }

  @Override
  public String getBlockHash(int height) throws BitcoinRpcException {
    return (String) query("getblockhash", height);
  }

  @Override
  public BlockChainInfo getBlockChainInfo() throws BitcoinRpcException {
    return new BlockChainInfoMapWrapper((Map) query("getblockchaininfo"));
  }

  @Override
  public int getBlockCount() throws BitcoinRpcException {
    return ((Number) query("getblockcount")).intValue();
  }

  @Override
  public String getNewAddress() throws BitcoinRpcException {
    return (String) query("getnewaddress");
  }

  @Override
  public String getNewAddress(String account) throws BitcoinRpcException {
    return (String) query("getnewaddress", account);
  }

  @Override
  public String getBestBlockHash() throws BitcoinRpcException {
    return (String) query("getbestblockhash");
  }

  @Override
  public String getRawTransactionHex(String txId) throws BitcoinRpcException {
    return (String) query("getrawtransaction", txId);
  }

  private class ScriptPubKeyImpl extends MapWrapper implements ScriptPubKey, Serializable {

    public ScriptPubKeyImpl(Map m) {
      super(m);
    }

    @Override
    public String asm() {
      return mapStr("asm");
    }

    @Override
    public String hex() {
      return mapStr("hex");
    }

    @Override
    public int reqSigs() {
      return mapInt("reqSigs");
    }

    @Override
    public String type() {
      return mapStr("type");
    }

    @Override
    public List<String> addresses() {
      return (List) m.get("addresses");
    }

  }

  private class RawTransactionImpl extends MapWrapper implements RawTransaction, Serializable {

    public RawTransactionImpl(Map<String, Object> tx) {
      super(tx);
    }

    @Override
    public String hex() {
      return mapStr("hex");
    }

    @Override
    public String txId() {
      return mapStr("txid");
    }

    @Override
    public int version() {
      return mapInt("version");
    }

    @Override
    public long lockTime() {
      return mapLong("locktime");
    }

    @Override
    public String hash() {
      return mapStr("hash");
    }

    @Override
    public long size() {
      return mapLong("size");
    }

    @Override
    public long vsize() {
      return mapLong("vsize");
    }

    private class InImpl extends MapWrapper implements In, Serializable {

      public InImpl(Map m) {
        super(m);
      }

      @Override
      public String txid() {
        return mapStr("txid");
      }

      @Override
      public int vout() {
        return mapInt("vout");
      }

      @Override
      public Map<String, Object> scriptSig() {
        return (Map) m.get("scriptSig");
      }

      @Override
      public long sequence() {
        return mapLong("sequence");
      }

      @Override
      public RawTransaction getTransaction() {
        try {
          return getRawTransaction(mapStr("txid"));
        } catch (BitcoinRpcException ex) {
          throw new RuntimeException(ex);
        }
      }

      @Override
      public Out getTransactionOutput() {
        return getTransaction().vOut().get(mapInt("vout"));
      }

      @Override
      public String scriptPubKey() {
        return mapStr("scriptPubKey");
      }

    }

    @Override
    public List<In> vIn() {
      final List<Map<String, Object>> vIn = (List<Map<String, Object>>) m.get("vin");
      return new AbstractList<In>() {

        @Override
        public In get(int index) {
          return new InImpl(vIn.get(index));
        }

        @Override
        public int size() {
          return vIn.size();
        }
      };
    }

    private class OutImpl extends MapWrapper implements Out, Serializable {

      public OutImpl(Map m) {
        super(m);
      }

      @Override
      public double value() {
        return mapDouble("value");
      }

      @Override
      public int n() {
        return mapInt("n");
      }

      @Override
      public ScriptPubKey scriptPubKey() {
        return new ScriptPubKeyImpl((Map) m.get("scriptPubKey"));
      }

      @Override
      public TxInput toInput() {
        return new BasicTxInput(transaction().txId(), n());
      }

      @Override
      public RawTransaction transaction() {
        return RawTransactionImpl.this;
      }

    }

    @Override
    public List<Out> vOut() {
      final List<Map<String, Object>> vOut = (List<Map<String, Object>>) m.get("vout");
      return new AbstractList<Out>() {

        @Override
        public Out get(int index) {
          return new OutImpl(vOut.get(index));
        }

        @Override
        public int size() {
          return vOut.size();
        }
      };
    }

    @Override
    public String blockHash() {
      return mapStr("blockhash");
    }

    @Override
    public int confirmations() {
      return mapInt("confirmations");
    }

    @Override
    public Date time() {
      return mapCTime("time");
    }

    @Override
    public Date blocktime() {
      return mapCTime("blocktime");
    }

  }

  public class DecodedScriptImpl extends MapWrapper implements DecodedScript, Serializable {

    public DecodedScriptImpl(Map m) {
      super(m);
    }


    @Override
    public String asm() {
      return mapStr("asm");
    }

    @Override
    public String hex() {
      return mapStr("hex");
    }

    @Override
    public String type() {
      return mapStr("type");
    }

    @Override
    public int reqSigs() {
      return mapInt("reqSigs");
    }

    @Override
    public List<String> addresses() {
      return (List) m.get("addresses");
    }

    @Override
    public String p2sh() {
      return mapStr("p2sh");
    }
  }

  @Override
  public RawTransaction getRawTransaction(String txId) throws BitcoinRpcException {
    return new RawTransactionImpl((Map) query("getrawtransaction", txId, 1));
  }

  @Override
  public double getReceivedByAddress(String address) throws BitcoinRpcException {
    return ((Number) query("getreceivedbyaddress", address)).doubleValue();
  }

  @Override
  public double getReceivedByAddress(String address, int minConf) throws BitcoinRpcException {
    return ((Number) query("getreceivedbyaddress", address, minConf)).doubleValue();
  }

  @Override
  public void importPrivKey(String bitcoinPrivKey) throws BitcoinRpcException {
    query("importprivkey", bitcoinPrivKey);
  }

  @Override
  public void importPrivKey(String bitcoinPrivKey, String label) throws BitcoinRpcException {
    query("importprivkey", bitcoinPrivKey, label);
  }

  @Override
  public void importPrivKey(String bitcoinPrivKey, String label, boolean rescan) throws BitcoinRpcException {
    query("importprivkey", bitcoinPrivKey, label, rescan);
  }

  @Override
  public Map<String, Number> listAccounts() throws BitcoinRpcException {
    return (Map) query("listaccounts");
  }

  @Override
  public Map<String, Number> listAccounts(int minConf) throws BitcoinRpcException {
    return (Map) query("listaccounts", minConf);
  }

  private static class ReceivedAddressListWrapper extends AbstractList<ReceivedAddress> {

    private final List<Map<String, Object>> wrappedList;

    public ReceivedAddressListWrapper(List<Map<String, Object>> wrappedList) {
      this.wrappedList = wrappedList;
    }

    @Override
    public ReceivedAddress get(int index) {
      final Map<String, Object> e = wrappedList.get(index);
      return new ReceivedAddress() {

        @Override
        public String address() {
          return (String) e.get("address");
        }

        @Override
        public String account() {
          return (String) e.get("account");
        }

        @Override
        public double amount() {
          return ((Number) e.get("amount")).doubleValue();
        }

        @Override
        public int confirmations() {
          return ((Number) e.get("confirmations")).intValue();
        }

        @Override
        public String toString() {
          return e.toString();
        }

      };
    }

    @Override
    public int size() {
      return wrappedList.size();
    }
  }

  @Override
  public List<ReceivedAddress> listReceivedByAddress() throws BitcoinRpcException {
    return new ReceivedAddressListWrapper((List) query("listreceivedbyaddress"));
  }

  @Override
  public List<ReceivedAddress> listReceivedByAddress(int minConf) throws BitcoinRpcException {
    return new ReceivedAddressListWrapper((List) query("listreceivedbyaddress", minConf));
  }

  @Override
  public List<ReceivedAddress> listReceivedByAddress(int minConf, boolean includeEmpty) throws BitcoinRpcException {
    return new ReceivedAddressListWrapper((List) query("listreceivedbyaddress", minConf, includeEmpty));
  }

  private class TransactionListMapWrapper extends ListMapWrapper<Transaction> {

    public TransactionListMapWrapper(List<Map> list) {
      super(list);
    }

    @Override
    protected Transaction wrap(final Map m) {
      return new Transaction() {

        @Override
        public String account() {
          return mapStr(m, "account");
        }

        @Override
        public String address() {
          return mapStr(m, "address");
        }

        @Override
        public String category() {
          return mapStr(m, "category");
        }

        @Override
        public double amount() {
          return mapDouble(m, "amount");
        }

        @Override
        public double fee() {
          return mapDouble(m, "fee");
        }

        @Override
        public int confirmations() {
          return mapInt(m, "confirmations");
        }

        @Override
        public String blockHash() {
          return mapStr(m, "blockhash");
        }

        @Override
        public int blockIndex() {
          return mapInt(m, "blockindex");
        }

        @Override
        public Date blockTime() {
          return mapCTime(m, "blocktime");
        }

        @Override
        public String txId() {
          return mapStr(m, "txid");
        }

        @Override
        public Date time() {
          return mapCTime(m, "time");
        }

        @Override
        public Date timeReceived() {
          return mapCTime(m, "timereceived");
        }

        @Override
        public String comment() {
          return mapStr(m, "comment");
        }

        @Override
        public String commentTo() {
          return mapStr(m, "to");
        }

        private RawTransaction raw = null;

        @Override
        public RawTransaction raw() {
          if (raw == null)
            try {
              raw = getRawTransaction(txId());
            } catch (BitcoinRpcException ex) {
              throw new RuntimeException(ex);
            }
          return raw;
        }

        @Override
        public String toString() {
          return m.toString();
        }

      };
    }

  }

  @Override
  public List<Transaction> listTransactions() throws BitcoinRpcException {
    return new TransactionListMapWrapper((List) query("listtransactions"));
  }

  @Override
  public List<Transaction> listTransactions(String account) throws BitcoinRpcException {
    return new TransactionListMapWrapper((List) query("listtransactions", account));
  }

  @Override
  public List<Transaction> listTransactions(String account, int count) throws BitcoinRpcException {
    return new TransactionListMapWrapper((List) query("listtransactions", account, count));
  }

  @Override
  public List<Transaction> listTransactions(String account, int count, int from) throws BitcoinRpcException {
    return new TransactionListMapWrapper((List) query("listtransactions", account, count, from));
  }

  private class UnspentListWrapper extends ListMapWrapper<Unspent> {

    public UnspentListWrapper(List<Map> list) {
      super(list);
    }

    @Override
    protected Unspent wrap(final Map m) {
      return new Unspent() {

        @Override
        public String txid() {
          return mapStr(m, "txid");
        }

        @Override
        public int vout() {
          return mapInt(m, "vout");
        }

        @Override
        public String address() {
          return mapStr(m, "address");
        }

        @Override
        public String scriptPubKey() {
          return mapStr(m, "scriptPubKey");
        }

        @Override
        public String account() {
          return mapStr(m, "account");
        }

        @Override
        public double amount() {
          return MapWrapper.mapDouble(m, "amount");
        }

        @Override
        public int confirmations() {
          return mapInt(m, "confirmations");
        }

      };
    }
  }

  @Override
  public List<Unspent> listUnspent() throws BitcoinRpcException {
    return new UnspentListWrapper((List) query("listunspent"));
  }

  @Override
  public List<Unspent> listUnspent(int minConf) throws BitcoinRpcException {
    return new UnspentListWrapper((List) query("listunspent", minConf));
  }

  @Override
  public List<Unspent> listUnspent(int minConf, int maxConf) throws BitcoinRpcException {
    return new UnspentListWrapper((List) query("listunspent", minConf, maxConf));
  }

  @Override
  public List<Unspent> listUnspent(int minConf, int maxConf, String... addresses) throws BitcoinRpcException {
    return new UnspentListWrapper((List) query("listunspent", minConf, maxConf, addresses));
  }

  @Override
  public String move(String fromAccount, String toBitcoinAddress, double amount) throws BitcoinRpcException {
    return (String) query("move", fromAccount, toBitcoinAddress, amount);
  }

  @Override
  public String move(String fromAccount, String toBitcoinAddress, double amount, int minConf) throws BitcoinRpcException {
    return (String) query("move", fromAccount, toBitcoinAddress, amount, minConf);
  }

  @Override
  public String move(String fromAccount, String toBitcoinAddress, double amount, int minConf, String comment) throws BitcoinRpcException {
    return (String) query("move", fromAccount, toBitcoinAddress, amount, minConf, comment);
  }

  @Override
  public String sendFrom(String fromAccount, String toBitcoinAddress, double amount) throws BitcoinRpcException {
    return (String) query("sendfrom", fromAccount, toBitcoinAddress, amount);
  }

  @Override
  public String sendFrom(String fromAccount, String toBitcoinAddress, double amount, int minConf) throws BitcoinRpcException {
    return (String) query("sendfrom", fromAccount, toBitcoinAddress, amount, minConf);
  }

  @Override
  public String sendFrom(String fromAccount, String toBitcoinAddress, double amount, int minConf, String comment) throws BitcoinRpcException {
    return (String) query("sendfrom", fromAccount, toBitcoinAddress, amount, minConf, comment);
  }

  @Override
  public String sendFrom(String fromAccount, String toBitcoinAddress, double amount, int minConf, String comment, String commentTo) throws BitcoinRpcException {
    return (String) query("sendfrom", fromAccount, toBitcoinAddress, amount, minConf, comment, commentTo);
  }

  @Override
  public String sendRawTransaction(String hex) throws BitcoinRpcException {
    return (String) query("sendrawtransaction", hex);
  }

  @Override
  public String sendToAddress(String toAddress, double amount) throws BitcoinRpcException {
    return (String) query("sendtoaddress", toAddress, amount);
  }

  @Override
  public String sendToAddress(String toAddress, double amount, String comment) throws BitcoinRpcException {
    return (String) query("sendtoaddress", toAddress, amount, comment);
  }

  @Override
  public String sendToAddress(String toAddress, double amount, String comment, String commentTo) throws BitcoinRpcException {
    return (String) query("sendtoaddress", toAddress, amount, comment, commentTo);
  }

  public String signRawTransaction(String hex) throws BitcoinRpcException {
    Map result = (Map) query("signrawtransaction", hex, null, null, "ALL");
    if ((Boolean) result.get("complete"))
      return (String) result.get("hex");
    else
      throw new BitcoinRpcException("Incomplete");
  }

  public String fundRawTransaction(String hex) {
    Map options = new LinkedHashMap() {
      {
        put("change_type", "bech32");
        put("lockUnspents", true);
        put("replaceable", true);
      }
    };

    Map result = (Map) query("fundrawtransaction", hex, options);
    return (String) result.get("hex");
  }

  public RawTransaction decodeRawTransaction(String hex) throws BitcoinRpcException {
    Map result = (Map) query("decoderawtransaction", hex);
    RawTransaction rawTransaction = new RawTransactionImpl(result);
    return rawTransaction.vOut().get(0).transaction();
  }

  public Boolean lockUnspent(RawTransaction rawTx, boolean unlock) {
    List<Map> unspents = new ArrayList<>();
    int i = 0;

    for (RawTransaction.In in : rawTx.vIn()) {

      final int temp = i;
      unspents.add(new LinkedHashMap() {
        {
          put("txid", in.getTransaction().txId());
          put("vout", temp);
        }
      });

      i++;
    }

    return (Boolean) query("lockunspent", unlock, unspents);
  }

  @Override
  public double getEstimateFee(int nBlocks) throws BitcoinRpcException {
    return ((Number) query("estimatefee", nBlocks)).doubleValue();
  }

  @Override
  public double getEstimateSmartFee(int nBlocks) throws BitcoinRpcException {
    return mapDouble(((Map) query("estimatesmartfee", nBlocks)), "feerate");
  }

  @Override
  public double getEstimatePriority(int nBlocks) throws BitcoinRpcException {
    return ((Number) query("estimatepriority", nBlocks)).doubleValue();
  }

  @Override
  public String getRawChangeAddress() throws BitcoinRpcException {
    return (String) query("getrawchangeaddress");
  }

  @Override
  public long getConnectionCount() throws BitcoinRpcException {
    return (long) query("getconnectioncount");
  }

  @Override
  public double getUnconfirmedBalance() throws BitcoinRpcException {
    return (double) query("getunconfirmedbalance");
  }

  @Override
  public double getDifficulty() throws BitcoinRpcException {
    if (query("getdifficulty") instanceof Long) {
      return ((Long) query("getdifficulty")).doubleValue();
    } else {
      return (double) query("getdifficulty");
    }
  }

  @Override
  public DecodedScript decodeScript(String hex) throws BitcoinRpcException {
    return new DecodedScriptImpl((Map) query("decodescript", hex));
  }

  @Override
  public boolean setTxFee(BigDecimal amount) throws BitcoinRpcException {
    return (boolean) query("settxfee", amount);
  }

  @Override
  public void backupWallet(String destination) throws BitcoinRpcException {
    query("backupwallet", destination);
  }

  @Override
  public void dumpWallet(String filename) throws BitcoinRpcException {
    query("dumpwallet", filename);
  }

  @Override
  public void importWallet(String filename) throws BitcoinRpcException {
    query("dumpwallet", filename);
  }

  @Override
  public void keyPoolRefill() throws BitcoinRpcException {
    keyPoolRefill(100); //default is 100 if you don't send anything
  }

  public void keyPoolRefill(long size) throws BitcoinRpcException {
    query("keypoolrefill", size);
  }

  @Override
  public BigDecimal getReceivedByAccount(String account) throws BitcoinRpcException {
    return getReceivedByAccount(account, 1);
  }

  public BigDecimal getReceivedByAccount(String account, int minConf) throws BitcoinRpcException {
    return new BigDecimal((String)query("getreceivedbyaccount", account, minConf));
  }

  @Override
  public void encryptWallet(String passPhrase) throws BitcoinRpcException {
    query("encryptwallet", passPhrase);
  }

  @Override
  public void walletPassPhrase(String passPhrase, long timeOut) throws BitcoinRpcException {
    query("walletpassphrase", passPhrase, timeOut);
  }

  @Override
  public TxOut getTxOut(String txId, long vout) throws BitcoinRpcException {
    return new TxOutWrapper((Map) query("gettxout", txId, vout, true));
  }

  public TxOut getTxOut(String txId, long vout, boolean includemempool) throws BitcoinRpcException {
    return new TxOutWrapper((Map) query("gettxout", txId, vout, includemempool));
  }
}