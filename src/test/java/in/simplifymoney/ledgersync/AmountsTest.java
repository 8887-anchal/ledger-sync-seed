package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Amounts;
import in.simplifymoney.ledgersync.parse.EmailParser;
import in.simplifymoney.ledgersync.parse.IciciSmsParser;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Amount extraction.
 *
 * This suite is green. It has been green since it was written.
 */
class AmountsTest {

    @Test
    void readsRupeesWithADot() {
        assertEquals(new BigDecimal("2499.50"),
                Amounts.first("Rs.2,499.50 debited from a/c **4821 on 04-07-26 at "
                        + "20:24 to AMAZON PAY. Avl Bal: Rs.89,032.61."));
    }

    @Test
    void readsInrPrefix() {
        assertEquals(new BigDecimal("333.33"),
                Amounts.first("Dear Customer, Acct XX9075 is debited with INR 333.33 "
                        + "on 04/07/2026 07:54. Info: SWIGGY. Avl Bal Rs.49,857.25"));
    }

    @Test
    void readsThousandsSeparators() {
        assertEquals(new BigDecimal("45000.00"),
                Amounts.first("Rs.45,000.00 credited to a/c **4821 on 01-07-26 at "
                        + "09:02 by SALARY CREDIT. Avl Bal: Rs.93,211.40"));
    }

    @Test
    void readsTheStatedBalance() {
        assertEquals(new BigDecimal("89032.61"),
                Amounts.statedBalance("Rs.2,499.50 debited from a/c **4821 on "
                        + "04-07-26 at 20:24 to AMAZON PAY. Avl Bal: Rs.89,032.61."));
    }

    @Test
    void readsWholeRupeesWithoutDecimals() {
        assertEquals(new BigDecimal("5.00"),
                Amounts.first("Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to "
                        + "UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161"));
    }

    @Test
    void ignoresAMessageWithNoAmountAtAll() {
        assertEquals(null, Amounts.first("Your Swiggy order is on the way!"));
    }

    @Test
    void parsesHdfcEmailAlerts() {
        RawMessage raw = new RawMessage("m-email-1", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:47:00+05:30"), "dev-x",
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\nSubject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\nYour account ending 4821 has been credited with INR 45,000.\n"
                        + "Merchant / Remarks: SALARY CREDIT\nTransaction reference: 1597155421\n\n"
                        + "This is a system generated email.");

        var parsed = new EmailParser().parse(raw);
        assertTrue(parsed.isPresent());
        assertEquals("4821", parsed.get().accountLast4());
        assertEquals(new BigDecimal("45000.00"), parsed.get().amount());
        assertEquals("SALARY CREDIT", parsed.get().merchant());
    }

    @Test
    void normalizesEmailAlertDatesToIst() {
        RawMessage raw = new RawMessage("m-email-utc", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-19T00:26:00+05:30"), "dev-x",
                "Date: Sat, 18 Jul 2026 18:50:00 +0000\nSubject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\nYour account ending 4821 has been debited with INR 412.67.\n"
                        + "Merchant / Remarks: UBER INDIA\nTransaction reference: 4190129089\n\n"
                        + "This is a system generated email.");

        var parsed = new EmailParser().parse(raw);
        assertTrue(parsed.isPresent());
        assertEquals(OffsetDateTime.parse("2026-07-19T00:20:00+05:30"), parsed.get().occurredAt());
    }

    @Test
    void parsesMandateEmailWithoutMerchantSpecialCases() {
        RawMessage raw = new RawMessage("m-mandate-email", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-22T06:40:00+05:30"), "dev-x",
                "Date: Wed, 22 Jul 2026 06:15:00 +0530\nSubject: Transaction alert on your account\n\n"
                        + "Dear Customer,\n\nYour account ending 4821 has been debited with Rs.649.00.\n"
                        + "Merchant / Remarks: NETFLIX ENTERTAINMENT\nTransaction reference: 9201732154\n\n"
                        + "This is a system generated email.");

        var parsed = new EmailParser().parse(raw);
        assertTrue(parsed.isPresent());
        assertEquals("NETFLIX ENTERTAINMENT", parsed.get().merchant());
        assertEquals(new BigDecimal("649.00"), parsed.get().amount());
    }

    @Test
    void parsesIciciBankAcctAltFormat() {
        RawMessage raw = new RawMessage("m-alt-icici", "sms", "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T16:52:00+05:30"), "dev-x",
                "ICICI Bank Acct XX9075 Cr INR 1250.33 on 23-Jul-2026 16:52; INTEREST CREDIT ref no 424353460512. BalAvl Rs 52,846.30");

        var parsed = new IciciSmsParser().parse(raw);
        assertTrue(parsed.isPresent());
        assertEquals("9075", parsed.get().accountLast4());
        assertEquals(new BigDecimal("1250.33"), parsed.get().amount());
        assertEquals("INTEREST CREDIT", parsed.get().merchant());
    }

    @Test
    void summaryExcludesTransfersAndMicroFromSpend() {
        OffsetDateTime when = OffsetDateTime.parse("2026-07-04T20:24:00+05:30");
        List<NormalizedTxn> ledger = List.of(
                new NormalizedTxn("4821", when, Direction.DEBIT,
                        new BigDecimal("75.00"), Category.MICRO, "UPI/WATER CAN",
                        List.of("m-micro")),
                new NormalizedTxn("4821", when, Direction.DEBIT,
                        new BigDecimal("250.00"), Category.TRANSFER, "IMPS/P2A/PARAG",
                        List.of("m-transfer-out")),
                new NormalizedTxn("4821", when, Direction.DEBIT,
                        new BigDecimal("100.00"), Category.SPEND, "SHOP",
                        List.of("m-spend")),
                new NormalizedTxn("4821", when, Direction.CREDIT,
                        new BigDecimal("95.00"), Category.INCOME, "CLIENT",
                        List.of("m-income")),
                new NormalizedTxn("4821", when, Direction.CREDIT,
                        new BigDecimal("5000.00"), Category.TRANSFER, "IMPS/P2A/PARAG",
                        List.of("m-transfer-in"))
        );

        Map<String, Object> summary = Reports.summary(ledger);
        @SuppressWarnings("unchecked")
        Map<String, Object> account = (Map<String, Object>) ((Map<String, Object>) summary.get("accounts")).get("4821");

        assertEquals("100.00", account.get("spend"));
        assertEquals("95.00", account.get("income"));
        assertEquals(1, account.get("micro_count"));
        assertEquals("75.00", account.get("micro_total"));
        assertEquals("250.00", account.get("transferred_out"));
        assertEquals("5000.00", account.get("transferred_in"));
    }

    @Test
    void deduplicatesSameTransactionAcrossSmsAndEmailWhenTimesDrift() throws Exception {
        Path file = Files.createTempFile("corpus-dedupe", ".jsonl");
        Files.writeString(file,
                String.join(System.lineSeparator(),
                        "{\"message_id\":\"m-sms\",\"channel\":\"sms\",\"sender\":\"AD-HDFCBK-S\",\"received_at\":\"2026-07-04T12:29:00+05:30\",\"device_id\":\"dev-1\",\"body\":\"Rs.154.49 debited from a/c **4821 on 04-07-26 at 12:24 to RELIANCE SMART. Avl Bal: Rs.89,340.60. Not you? Call 18002586161\"}",
                        "{\"message_id\":\"m-email\",\"channel\":\"email\",\"sender\":\"alerts@hdfcbank.net\",\"received_at\":\"2026-07-04T12:31:00+05:30\",\"device_id\":\"dev-1\",\"body\":\"Date: Sat, 04 Jul 2026 12:24:00 +0530\\nSubject: Transaction alert on your account\\n\\nDear Customer,\\n\\nYour account ending 4821 has been debited with Rs.154.49.\\nMerchant / Remarks: RELIANCE SMART\\nTransaction reference: 719123\\n\\nThis is a system generated email.\"}")
                        + System.lineSeparator());

        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService.Stats stats = new IngestService(new Parsers(), store).ingestFile(file);

        assertEquals(1, stats.transactionsWritten());
        assertEquals(1, store.all().size());
        assertEquals(List.of("m-email", "m-sms"), store.all().get(0).sourceMessageIds());
    }
}
