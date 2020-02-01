package com.opencrowd.dg.auction;

import com.hedera.hashgraph.sdk.TransactionRecord;
import com.hedera.hashgraph.sdk.account.AccountId;
import com.hedera.hashgraph.sdk.contract.ContractFunctionResult;
import com.hedera.hashgraph.sdk.contract.ContractId;
import com.hedera.hashgraph.sdk.contract.ContractLogInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Provides REST API to auction contract deployed on Hedera networks.
 */
@Controller
public class AuctionController {

  private String DEFAULT_CONTRACT;

  private final AtomicLong counter = new AtomicLong();
  private Map<String, Bid> history = new HashMap<>();
  private Map<String, String> users = null;
  private Map<String, String> accounts = null;
  private String[] userList = new String[]{"Bob", "Carol", "Alice"};

  private AuctionService auctionService;

  private final static Logger LOGGER = LoggerFactory.getLogger(AuctionController.class);

  @Autowired
  public AuctionController(AuctionService auctionService,
      @Value("${hedera.account.Alice.ID}") String aliceAccount,
      @Value("${hedera.account.Bob.ID}") String bobAccount,
      @Value("${hedera.account.Carol.ID}") String carolAccount,
      @Value("${hedera.account.manager.ID}") String managerAccount,
      @Value("${hedera.contract.default:}") String DEFAULT_CONTRACT
  ) throws Throwable {
    this.auctionService = auctionService;
    this.DEFAULT_CONTRACT = DEFAULT_CONTRACT;

    users = Map
        .of("Alice", aliceAccount, "Bob", bobAccount, "Carol", carolAccount);

    accounts = Map
        .of(aliceAccount, "Alice", bobAccount, "Bob", carolAccount, "Carol");
  }

  public static String account2Str(AccountId account) {
    return "0.0." + account.account;
  }

  /**
   * Endpoint for getting a past bid info.
   */
  @GetMapping("/getBid")
  @ResponseBody
  public Bid getBid(@RequestParam(name = "bidder", required = true) String bidder,
      @RequestParam(name = "id", required = true) long id) {
    Bid bid = history.get(getKey(bidder, id));
    return bid;
  }

  /**
   * Generate a key for bid history.
   *
   * @param bidder the name of the bidder
   * @param id     the id of the bid
   * @return generated key
   */
  private String getKey(String bidder, long id) {
    return bidder + "_" + id;
  }

  /**
   * Endpoint for making a auction end contract call.
   *
   * @param bidder       the name of the calling user
   * @param contractAddr the contract ID in the form of 0.0.x
   * @return the call transaction record
   * @throws Exception
   */
  @PostMapping("/endAuction/{bidder}/{contractAddr}")
  @ResponseStatus(HttpStatus.CREATED)
  @ResponseBody
  public String postAuctionEnd(@PathVariable String bidder, @PathVariable String contractAddr)
      throws Exception {
    String bidderAddr = users.get(bidder);

    if (contractAddr == null) {
      contractAddr = DEFAULT_CONTRACT;
    }

    TransactionRecord record = auctionService.endAuction(bidderAddr, contractAddr);

    return toString(record);
  }

  /**
   * Endpoint for making a single bid contract call.
   *
   * @param bidder       the name of the bidder, e.g. Alice, Bob, or Carol
   * @param amount       the bidding amount in tiny bars
   * @param contractAddr the contract ID in the form of 0.0.x
   * @return the transaction record of the call
   * @throws Exception
   */
  @PostMapping("/singleBid/{bidder}/{amount}/{contractAddr}")
  @ResponseStatus(HttpStatus.CREATED)
  @ResponseBody
  public String singleBid(@PathVariable String bidder, @PathVariable long amount,
      @PathVariable String contractAddr) throws Exception {
    String response = bidAuction(bidder, amount, contractAddr);
    return response;
  }

  /**
   * Endpoint for making a single bid contract call and subsequently starting random bidding for
   * demo purposes.
   *
   * @param bidder       the name of the bidder, e.g. Alice, Bob, or Carol
   * @param amount       the bidding amount in tiny bars
   * @param contractAddr the contract ID in the form of 0.0.x
   * @return the transaction record of the call
   * @throws Exception
   */
  @PostMapping("/bid/{bidder}/{amount}/{contractAddr}")
  @ResponseStatus(HttpStatus.CREATED)
  @ResponseBody
  public String postBid(@PathVariable String bidder, @PathVariable long amount,
      @PathVariable String contractAddr) throws Exception {
    String response = bidAuction(bidder, amount, contractAddr);
    Thread.sleep(1000);
    initiateRandomBidding(contractAddr);
    return response;
  }

  /**
   * Make a single bid contract call.
   *
   * @param bidder       the name of the bidder, e.g. Alice, Bob, or Carol
   * @param amount       the bidding amount in tiny bars
   * @param contractAddr the contract ID in the form of 0.0.x
   * @return the transaction record of the call
   * @throws Exception
   */
  public String bidAuction(String bidder,
      long amount,
      String contractAddr) throws Exception {
    String bidderAddr = users.get(bidder);

    if (contractAddr == null) {
      contractAddr = DEFAULT_CONTRACT;
    }

    long id = counter.getAndIncrement();
    Bid bid = new Bid(id, bidder, amount, bidderAddr, contractAddr);
    history.put(getKey(bidder, id), bid);
    TransactionRecord record = auctionService.bid(bid);
    LOGGER.info("path bid submitted: bid = " + bid);

    return toString(record);
  }

  /**
   * Endpoint for creating a new auction contract instance.
   *
   * @param biddingTimeSec  the auction duration in seconds
   * @param beneficiaryAddr the beneficiary account ID in the form of 0.0.x
   * @return contract ID of the created instance
   * @throws Exception
   */
  @PostMapping("/newAuction/{biddingTimeSec}/{beneficiaryAddr}")
  @ResponseStatus(HttpStatus.CREATED)
  @ResponseBody
  public String newAuction(@PathVariable long biddingTimeSec, @PathVariable String beneficiaryAddr)
      throws Exception {
    history.clear();
    ContractId auctionContract = auctionService.createAuction(beneficiaryAddr, biddingTimeSec);
    DEFAULT_CONTRACT = "0.0." + auctionContract.contract;
    LOGGER.info("set DEFAULT_CONTRACT = " + DEFAULT_CONTRACT);
    return auctionContract.toString();
  }

  /**
   * Endpoint for getting the account ID and name of the bidders for the demo.
   *
   * @return the account ID to bidder name map
   */
  @GetMapping("/bidders")
  public ResponseEntity<Map<String, String>> getAllBidders() {
    return ResponseEntity.ok(accounts);
  }

  /**
   * Endpoint for starting random bidding by Alice, Bob, and Carol.
   *
   * @param contractId the contract ID in the form of 0.0.x
   * @return status of success
   * @throws Exception
   */
  @PostMapping("/bid/{contractId}")
  public ResponseEntity<Map<String, String>> initiateRandomBidding(@PathVariable String contractId)
      throws Exception {
    final Context context = new Context(1000L);
    final Timer timer = new Timer();
    final TimerTask timerTask = new TimerTask() {
      private int index = 0;

      @Override
      public void run() {
        try {
          if (index >= userList.length) {
            index = 0;
          }
          final String bidder = userList[index];
          long bid = context.getBid();
          context.setBid(++bid);
          final String response = bidAuction(bidder, bid, contractId);
          LOGGER.info(response);
          if (response.contains("auctionEnd has already been called")) {
            cancel();
            timer.purge();
            LOGGER.info("Stopped auto bidding!");
            return;
          } else if (response.indexOf("SUCCESS") > -1) {
            index++;
          }
        } catch (Exception e) {
          LOGGER.error(e.getMessage(), e);
        }
      }
    };
    timer.schedule(timerTask, 2000, 3000);
    return ResponseEntity.ok(Map.of("status", "success"));
  }

  class Context {

    private long bid;

    public Context(long bid) {
      this.bid = bid;
    }

    public long getBid() {
      return bid;
    }

    public void setBid(long bid) {
      this.bid = bid;
    }
  }

  /**
   * Endpoint for resetting an existing auction instance by restoring the state to when the auction
   * instance was first deployed.
   *
   * @param contractId the contract ID in the form of 0.0.x
   * @return transaction record of this reset contract call
   * @throws Exception
   */
  @PostMapping("/resetAuction/{contractId}")
  @ResponseStatus(HttpStatus.CREATED)
  @ResponseBody
  public String resetAuction(@PathVariable String contractId) throws Exception {
    history.clear();
    TransactionRecord record = auctionService.resetAuction(contractId);
    LOGGER.info("reset contract = " + contractId);
    return toString(record);
  }

  /**
   * Endpoint for starting an auction.
   *
   * @param contractId the contract ID in the form of 0.0.x
   * @return transaction record of this contract call
   * @throws Exception
   */
  @PostMapping("/startTimer/{contractId}")
  @ResponseStatus(HttpStatus.CREATED)
  @ResponseBody
  public String startTimer(@PathVariable String contractId) throws Exception {
    TransactionRecord record = auctionService.startTimer(contractId);
    LOGGER.info("restarted timer for contract = " + contractId);
    return toString(record);
  }

  /**
   * Convert transaction record to string
   *
   * @param record transaction record to be converted
   * @return converted string
   */
  private String toString(TransactionRecord record) {
    StringBuffer sb = new StringBuffer();
    String ln = "\n";
    sb
        .append("receipt status: " + record.receipt.status.name()).append(ln)
        .append("consensusTimestamp: " + record.consensusTimestamp).append(ln)
        .append("transactionID: " + record.transactionId).append(ln)
        .append("transactionFee: " + record.transactionFee).append(ln);

    ContractFunctionResult execResult = record.getContractExecuteResult();
    if (execResult != null) {
      sb.append("contractCallResult {\n\tgasUsed: " + execResult.gasUsed).append(ln);
      if (execResult.contractId.contract != 0) {
        sb.append("\tcontractId: " + execResult.contractId).append(ln);
      }
      if (execResult.errorMessage != null) {
        sb.append("\terrorMessage: " + execResult.errorMessage).append(ln);
        sb.append("\tcontractCallResult: " + escapeBytes(execResult.asBytes())).append(ln);
      }

      List<ContractLogInfo> logs = execResult.logs;
      if (logs != null) {
        for (ContractLogInfo log : logs) {
          sb.append("\tlogInfo {\n");
          sb.append("\t\tcontractId" + log.contractId).append(ln);
          sb.append("\t\tbloom" + escapeBytes(log.bloom)).append(ln);
          sb.append("\t\tdata" + escapeBytes(log.data)).append(ln);
          sb.append("\t\ttopic" + escapeBytes(log.topics.get(0))).append(ln);
          sb.append("\t}\n");
        }
      }
      sb.append("}\n");
    }

//		sb.append("transferList: " + record.transfers).append(ln);

    String rv = sb.toString();
    return rv;
  }

  /**
   * Escape bytes for printing purpose.
   *
   * @param input bytes to escape
   * @return escaped string
   */
  public static String escapeBytes(final byte[] input) {
    return escapeBytes(
        new ByteSequence() {
          @Override
          public int size() {
            return input.length;
          }

          @Override
          public byte byteAt(int offset) {
            return input[offset];
          }
        });
  }

  private interface ByteSequence {

    int size();

    byte byteAt(int offset);
  }

  /**
   * Escapes bytes in the format used in protocol buffer text format, which is the same as the
   * format used for C string literals. All bytes that are not printable 7-bit ASCII characters are
   * escaped, as well as backslash, single-quote, and double-quote characters. Characters for which
   * no defined short-hand escape sequence is defined will be escaped using 3-digit octal
   * sequences.
   */
  static String escapeBytes(final ByteSequence input) {
    final StringBuilder builder = new StringBuilder(input.size());
    for (int i = 0; i < input.size(); i++) {
      final byte b = input.byteAt(i);
      switch (b) {
        // Java does not recognize \a or \v, apparently.
        case 0x07:
          builder.append("\\a");
          break;
        case '\b':
          builder.append("\\b");
          break;
        case '\f':
          builder.append("\\f");
          break;
        case '\n':
          builder.append("\\n");
          break;
        case '\r':
          builder.append("\\r");
          break;
        case '\t':
          builder.append("\\t");
          break;
        case 0x0b:
          builder.append("\\v");
          break;
        case '\\':
          builder.append("\\\\");
          break;
        case '\'':
          builder.append("\\\'");
          break;
        case '"':
          builder.append("\\\"");
          break;
        default:
          // Only ASCII characters between 0x20 (space) and 0x7e (tilde) are
          // printable.  Other byte values must be escaped.
          if (b >= 0x20 && b <= 0x7e) {
            builder.append((char) b);
          } else {
            builder.append('\\');
            builder.append((char) ('0' + ((b >>> 6) & 3)));
            builder.append((char) ('0' + ((b >>> 3) & 7)));
            builder.append((char) ('0' + (b & 7)));
          }
          break;
      }
    }
    return builder.toString();
  }
}
