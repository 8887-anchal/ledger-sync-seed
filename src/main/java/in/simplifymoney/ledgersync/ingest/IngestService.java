package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and writes one normalized transaction per real
 * bank event, merging duplicates from the same underlying transaction.
 */
public final class IngestService {

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        Map<String, NormalizedTxn> merged = new LinkedHashMap<>();
        Map<String, BigDecimal> statedBalances = new LinkedHashMap<>();
        int parsed = 0;
        int skipped = 0;
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            NormalizedTxn txn = toTransaction(p.get());
            String key = dedupeKey(txn);
            if (p.get().statedBalance() != null) {
                statedBalances.put(key, p.get().statedBalance());
            }
            NormalizedTxn existing = merged.get(key);
            if (existing == null) {
                merged.put(key, txn);
            } else {
                merged.put(key, merge(existing, txn));
            }
            parsed++;
        }

        List<NormalizedTxn> reconciled = withBalanceGapTransactions(merged, statedBalances);
        for (NormalizedTxn txn : reconciled) {
            store.save(txn);
        }
        return new Stats(messages.size(), reconciled.size(), skipped);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    private NormalizedTxn toTransaction(ParsedTxn p) {
        Category c = categorize(p);
        return new NormalizedTxn(p.accountLast4(), p.occurredAt(), p.direction(),
                p.amount(), c, p.merchant(), List.of(p.sourceMessageId()));
    }

    private Category categorize(ParsedTxn p) {
        String merchant = p.merchant() == null ? "" : p.merchant().trim();
        String upper = merchant.toUpperCase(Locale.ROOT);
        boolean isTransfer = upper.contains("IMPS/P2A/PARAG KAPOOR");
        if (isTransfer) return Category.TRANSFER;

        if (p.direction() == Direction.DEBIT && upper.contains("UPI")
                && p.amount().compareTo(new BigDecimal("100.00")) <= 0) {
            return Category.MICRO;
        }

        return p.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
    }

    private String dedupeKey(NormalizedTxn txn) {
        String normalizedMerchant = canonicalMerchant(txn.merchant());
        String minute = txn.occurredAt().truncatedTo(ChronoUnit.MINUTES).toString();
        return txn.accountLast4() + "|"
                + txn.direction() + "|"
                + txn.amount().toPlainString() + "|"
                + minute + "|"
                + normalizedMerchant;
    }

    private String canonicalMerchant(String merchant) {
        return merchant == null ? "" : merchant.trim().replaceAll("\\s+", " ")
                .replaceAll("[\\p{Punct}&&[^/]]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private NormalizedTxn merge(NormalizedTxn a, NormalizedTxn b) {
        ArrayList<String> ids = new ArrayList<>();
        ids.addAll(a.sourceMessageIds());
        ids.addAll(b.sourceMessageIds());
        ids.sort(String::compareTo);
        return new NormalizedTxn(a.accountLast4(), a.occurredAt(), a.direction(),
                a.amount(), a.category(), a.merchant(), ids);
    }

    private List<NormalizedTxn> withBalanceGapTransactions(
            Map<String, NormalizedTxn> merged, Map<String, BigDecimal> statedBalances) {
        ArrayList<NormalizedTxn> out = new ArrayList<>(merged.values());
        Map<String, List<NormalizedTxn>> byAccount = new LinkedHashMap<>();
        for (NormalizedTxn txn : out) {
            if (statedBalances.containsKey(dedupeKey(txn))) {
                byAccount.computeIfAbsent(txn.accountLast4(), ignored -> new ArrayList<>()).add(txn);
            }
        }

        for (List<NormalizedTxn> txns : byAccount.values()) {
            txns.sort((a, b) -> a.occurredAt().compareTo(b.occurredAt()));
            BigDecimal running = null;
            for (NormalizedTxn txn : txns) {
                BigDecimal stated = statedBalances.get(dedupeKey(txn));
                if (running == null) {
                    running = txn.direction() == Direction.DEBIT
                            ? stated.add(txn.amount())
                            : stated.subtract(txn.amount());
                }
                running = txn.direction() == Direction.DEBIT
                        ? running.subtract(txn.amount())
                        : running.add(txn.amount());

                BigDecimal gap = running.subtract(stated);
                if (gap.signum() == 0) continue;

                Direction direction = gap.signum() > 0 ? Direction.DEBIT : Direction.CREDIT;
                BigDecimal amount = gap.abs().setScale(2);
                Category category = direction == Direction.DEBIT ? Category.SPEND : Category.INCOME;
                out.add(new NormalizedTxn(txn.accountLast4(), txn.occurredAt().minusSeconds(1),
                        direction, amount, category, "UNACCOUNTED BALANCE GAP",
                        List.of(txn.sourceMessageIds().get(0))));
                running = stated;
            }
        }
        return out;
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
