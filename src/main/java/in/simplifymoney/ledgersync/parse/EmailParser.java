package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails for HDFC and ICICI.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern HDFC = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>credited|debited) with "
                    + "(?:Rs\\.?|INR)\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{2})?)\\.?\\s*\\n"
                    + "Merchant / Remarks: (?<merchant>.+?)\\n"
                    + "Transaction reference:",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ICICI = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>credited|debited) with "
                    + "(?:Rs\\.?|INR)\\s*(?<amount>[0-9][0-9,]*(?:\\.[0-9]{2})?)\\.?\\s*\\n"
                    + "Merchant / Remarks: (?<merchant>.+?)\\n"
                    + "Transaction reference:",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final DateTimeFormatter EMAIL_DATE =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher hdfc = HDFC.matcher(m.body());
        if (hdfc.find()) {
            return build(m, hdfc.group("acct"), hdfc.group("dir"), hdfc.group("amount"),
                    hdfc.group("merchant"));
        }

        Matcher icici = ICICI.matcher(m.body());
        if (icici.find()) {
            return build(m, icici.group("acct"), icici.group("dir"), icici.group("amount"),
                    icici.group("merchant"));
        }
        return Optional.empty();
    }

    private Optional<ParsedTxn> build(RawMessage m, String acct, String dir, String amountText,
                                     String merchant) {
        BigDecimal amount = new BigDecimal(amountText.replace(",", "")).setScale(2);
        Direction d = "credited".equalsIgnoreCase(dir) ? Direction.CREDIT : Direction.DEBIT;

        Matcher dateMatch = Pattern.compile("Date:\\s*(.+?\\s[+-]\\d{4})", Pattern.DOTALL)
                .matcher(m.body());
        if (!dateMatch.find()) return Optional.empty();

        String dateText = dateMatch.group(1).trim();
        OffsetDateTime at;
        try {
            at = OffsetDateTime.parse(dateText, EMAIL_DATE);
        } catch (Exception ignored) {
            at = null;
        }
        if (at == null) return Optional.empty();

        return Optional.of(new ParsedTxn(acct, at.withOffsetSameInstant(Dates.IST), d, amount, merchant.trim(),
                Amounts.statedBalance(m.body()), m.messageId()));
    }
}
