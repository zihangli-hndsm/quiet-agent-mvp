package app.quietagent.receipt;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, offline parser for OCR output from Chinese receipts/invoices.
 *
 * <p>The parser deliberately accepts a total only when it is attached to an
 * explicit total label such as 合计, 价税合计 or 总金额.  It never guesses a
 * total from an arbitrary number in the OCR text.</p>
 */
public final class ReceiptParser {
    private ReceiptParser() {}

    private static final String TOTAL_LABELS = "(?:价税合计|总金额|应付合计|合计|TOTAL)";
    private static final Pattern TOTAL_LABEL = Pattern.compile(TOTAL_LABELS, Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT = Pattern.compile(
            "(?<![A-Za-z0-9])(?:人民币|RMB|CNY|¥|￥)?\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?|[0-9]+(?:\\.[0-9]{1,2})?)(?:\\s*元)?(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile(
            "(?<![0-9])((?:19|20|21)[0-9]{2})\\s*(?:年|[-/.])\\s*(0?[1-9]|1[0-2])\\s*(?:月|[-/.])\\s*(0?[1-9]|[12][0-9]|3[01])\\s*(?:日)?(?![0-9])");
    private static final Pattern COMPACT_DATE = Pattern.compile("(?<![0-9])((?:19|20|21)[0-9]{2})(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])(?![0-9])");
    private static final Pattern MERCHANT_LABEL = Pattern.compile(
            "(?:商户名称|开票方名称|销售方名称|销售方|开票方|收款方|商户|店名|MERCHANT)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_LABEL = Pattern.compile("(?:开票日期|交易日期|消费日期|日期|时间|DATE)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIELD_LABEL = Pattern.compile("(?:商户名称|销售方|开票方|收款方|商户|店名|MERCHANT|开票日期|交易日期|消费日期|日期|时间|DATE|合计|价税合计|总金额|TOTAL)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_SEPARATOR = Pattern.compile("^[\\s:：=\\-—]+|[\\s:：=\\-—]+$");

    /** Parse a list of OCR lines, retaining their supplied geometry. */
    public static ParsedReceipt parseLines(List<OcrLine> lines) {
        if (lines == null) lines = Collections.emptyList();
        List<OcrLine> copy = new ArrayList<OcrLine>();
        for (OcrLine line : lines) if (line != null) copy.add(line);
        return parseInternal(copy);
    }

    /** Parse OCR blocks whose text may contain embedded newlines. */
    public static ParsedReceipt parseBlocks(List<OcrBlock> blocks) {
        if (blocks == null) blocks = Collections.emptyList();
        List<OcrLine> lines = new ArrayList<OcrLine>();
        for (OcrBlock block : blocks) {
            if (block == null) continue;
            String text = block.text == null ? "" : block.text;
            String[] split = text.replace("\r\n", "\n").replace('\r', '\n').split("\\n", -1);
            for (String part : split) lines.add(new OcrLine(part, block.bounds));
        }
        return parseInternal(lines);
    }

    private static ParsedReceipt parseInternal(List<OcrLine> lines) {
        List<String> reasons = new ArrayList<String>();
        List<Evidence> evidence = new ArrayList<Evidence>();

        FieldValue merchant = findMerchant(lines, evidence);
        FieldValue date = findDate(lines, evidence);
        AmountValue amount = findAmount(lines, evidence);

        if (merchant.conflict) reasons.add("商户冲突");
        else if (merchant.value == null) reasons.add("缺少商户");
        if (date.conflict) reasons.add("日期冲突");
        else if (date.value == null) reasons.add("缺少日期");
        if (amount.conflict) reasons.add("合计金额冲突");
        else if (amount.value == null) reasons.add(amount.labelFound ? "合计金额缺失" : "缺少明确合计金额");

        // Deduplicate evidence while preserving source order and field order.
        evidence = dedupeEvidence(evidence);
        String reviewReason = joinReasons(reasons);
        return new ParsedReceipt(
                merchant.value,
                date.value,
                amount.value,
                reviewReason,
                reasons,
                evidence);
    }

    private static FieldValue findMerchant(List<OcrLine> lines, List<Evidence> evidence) {
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (int i = 0; i < lines.size(); i++) {
            String normalized = compact(lines.get(i).text);
            Matcher matcher = MERCHANT_LABEL.matcher(normalized);
            if (!matcher.find()) continue;
            String value = valueAfterLabel(normalized, matcher.end());
            int evidenceIndex = i;
            if (isUsableMerchant(value)) {
                candidates.add(new Candidate(value, i, "merchant", matcher.start(), matcher.end()));
            } else {
                Candidate next = adjacentValue(lines, i, true);
                if (next != null && isUsableMerchant(next.value)) {
                    candidates.add(new Candidate(next.value, next.lineIndex, "merchant", 0, next.value.length()));
                    evidence.add(new Evidence("merchant", i, lines.get(i).text, lines.get(i).bounds, matcher.start(), matcher.end()));
                    evidence.add(new Evidence("merchant", next.lineIndex, lines.get(next.lineIndex).text, lines.get(next.lineIndex).bounds, 0, lines.get(next.lineIndex).text.length()));
                    continue;
                }
            }
        }
        String chosen = chooseUnique(candidates);
        boolean conflict = hasConflict(candidates);
        for (Candidate c : candidates) {
            OcrLine line = lines.get(c.lineIndex);
            evidence.add(new Evidence("merchant", c.lineIndex, line.text, line.bounds, c.start, c.end));
        }
        return new FieldValue(chosen, conflict);
    }

    private static FieldValue findDate(List<OcrLine> lines, List<Evidence> evidence) {
        List<Candidate> candidates = new ArrayList<Candidate>();
        for (int i = 0; i < lines.size(); i++) {
            String normalized = compact(lines.get(i).text);
            Matcher label = DATE_LABEL.matcher(normalized);
            if (!label.find()) continue;
            List<DateMatch> same = dateMatches(normalized, label.end());
            if (same.isEmpty()) {
                Candidate adjacent = adjacentDate(lines, i, label.end());
                if (adjacent != null) {
                    candidates.add(adjacent);
                    evidence.add(new Evidence("date", i, lines.get(i).text, lines.get(i).bounds, label.start(), label.end()));
                }
            } else {
                for (DateMatch d : same) candidates.add(new Candidate(d.value, i, "date", d.start, d.end));
            }
        }
        String chosen = chooseUnique(candidates);
        boolean conflict = hasConflict(candidates);
        for (Candidate c : candidates) {
            OcrLine line = lines.get(c.lineIndex);
            evidence.add(new Evidence("date", c.lineIndex, line.text, line.bounds, c.start, c.end));
        }
        return new FieldValue(chosen, conflict);
    }

    private static AmountValue findAmount(List<OcrLine> lines, List<Evidence> evidence) {
        List<Candidate> candidates = new ArrayList<Candidate>();
        boolean labelFound = false;
        for (int i = 0; i < lines.size(); i++) {
            String normalized = compact(lines.get(i).text);
            Matcher label = TOTAL_LABEL.matcher(normalized);
            while (label.find()) {
                labelFound = true;
                List<AmountMatch> same = amountMatches(normalized, label.end());
                if (same.isEmpty()) {
                    Candidate adjacent = adjacentAmount(lines, i);
                    if (adjacent != null) {
                        candidates.add(adjacent);
                        evidence.add(new Evidence("amount", i, lines.get(i).text, lines.get(i).bounds, label.start(), label.end()));
                    }
                } else {
                    for (AmountMatch a : same) candidates.add(new Candidate(a.cents.toString(), i, "amount", a.start, a.end));
                }
            }
        }
        Set<String> unique = new LinkedHashSet<String>();
        for (Candidate c : candidates) unique.add(c.value);
        Long value = unique.size() == 1 ? Long.valueOf(unique.iterator().next()) : null;
        boolean conflict = unique.size() > 1;
        for (Candidate c : candidates) {
            OcrLine line = lines.get(c.lineIndex);
            evidence.add(new Evidence("amount", c.lineIndex, line.text, line.bounds, c.start, c.end));
        }
        return new AmountValue(value, conflict, labelFound);
    }

    private static Candidate adjacentValue(List<OcrLine> lines, int labelLine, boolean forwardOnly) {
        int start = labelLine + 1;
        int end = Math.min(lines.size(), labelLine + 2);
        for (int i = start; i < end; i++) {
            String value = compact(lines.get(i).text);
            if (value.isEmpty() || !isUsableMerchant(value)) continue;
            return new Candidate(value, i, "", 0, value.length());
        }
        return null;
    }

    private static Candidate adjacentDate(List<OcrLine> lines, int labelLine, int labelEnd) {
        if (labelLine + 1 < lines.size()) {
            List<DateMatch> matches = dateMatches(compact(lines.get(labelLine + 1).text), 0);
            if (matches.size() == 1) {
                DateMatch d = matches.get(0);
                return new Candidate(d.value, labelLine + 1, "date", d.start, d.end);
            }
        }
        return null;
    }

    private static Candidate adjacentAmount(List<OcrLine> lines, int labelLine) {
        if (labelLine + 1 < lines.size()) {
            String next = compact(lines.get(labelLine + 1).text);
            List<AmountMatch> matches = amountMatches(next, 0);
            if (matches.size() == 1 && isAmountOnly(next, matches.get(0))) {
                AmountMatch a = matches.get(0);
                return new Candidate(a.cents.toString(), labelLine + 1, "amount", a.start, a.end);
            }
        }
        if (labelLine > 0) {
            String previous = compact(lines.get(labelLine - 1).text);
            List<AmountMatch> matches = amountMatches(previous, 0);
            if (matches.size() == 1 && isAmountOnly(previous, matches.get(0))) {
                AmountMatch a = matches.get(0);
                return new Candidate(a.cents.toString(), labelLine - 1, "amount", a.start, a.end);
            }
        }
        return null;
    }

    private static boolean isAmountOnly(String text, AmountMatch match) {
        String remainder = (text.substring(0, match.start) + text.substring(match.end))
                .replace("人民币", "").replace("RMB", "").replace("CNY", "")
                .replace("¥", "").replace("￥", "").replace("元", "")
                .replace("小写", "").trim();
        return remainder.length() == 0;
    }

    private static List<AmountMatch> amountMatches(String text, int from) {
        List<AmountMatch> out = new ArrayList<AmountMatch>();
        Matcher m = AMOUNT.matcher(text);
        m.region(Math.max(0, from), text.length());
        while (m.find()) {
            try {
                String raw = m.group(1).replace(",", "");
                BigDecimal amount = new BigDecimal(raw);
                if (amount.signum() < 0 || amount.scale() > 2) continue;
                BigDecimal cents = amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY);
                if (cents.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) continue;
                out.add(new AmountMatch(cents.longValue(), m.start(1), m.end(1)));
            } catch (ArithmeticException ex) {
                // A value with more than two decimal places is ambiguous; do not round it.
            } catch (NumberFormatException ex) {
                // Ignore malformed OCR candidates.
            }
        }
        return out;
    }

    private static List<DateMatch> dateMatches(String text, int from) {
        List<DateMatch> out = new ArrayList<DateMatch>();
        Matcher m = DATE.matcher(text);
        m.region(Math.max(0, from), text.length());
        while (m.find()) {
            String value = normalizeDate(m.group(1), m.group(2), m.group(3));
            if (value != null) out.add(new DateMatch(value, m.start(), m.end()));
        }
        Matcher compact = COMPACT_DATE.matcher(text);
        compact.region(Math.max(0, from), text.length());
        while (compact.find()) {
            String value = normalizeDate(compact.group(1), compact.group(2), compact.group(3));
            if (value != null) out.add(new DateMatch(value, compact.start(), compact.end()));
        }
        Collections.sort(out, new Comparator<DateMatch>() {
            public int compare(DateMatch a, DateMatch b) { return Integer.compare(a.start, b.start); }
        });
        return out;
    }

    private static String normalizeDate(String y, String m, String d) {
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(y), Integer.parseInt(m), Integer.parseInt(d));
            return date.toString();
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static String chooseUnique(List<Candidate> candidates) {
        String value = null;
        for (Candidate c : candidates) {
            if (value == null) value = c.value;
            else if (!normalizeValue(value).equals(normalizeValue(c.value))) return null;
        }
        return value;
    }

    private static boolean hasConflict(List<Candidate> candidates) {
        if (candidates.isEmpty()) return false;
        String first = normalizeValue(candidates.get(0).value);
        for (int i = 1; i < candidates.size(); i++) {
            if (!first.equals(normalizeValue(candidates.get(i).value))) return true;
        }
        return false;
    }

    private static String normalizeValue(String value) {
        return compact(value).toLowerCase(Locale.ROOT);
    }

    private static String compact(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('：', ':').replace('￥', '¥').replace('—', '-');
        return normalized.replaceAll("\\s+", "").trim();
    }

    private static String valueAfterLabel(String text, int end) {
        String value = end < text.length() ? text.substring(end) : "";
        value = LEADING_SEPARATOR.matcher(value).replaceAll("");
        Matcher trailing = FIELD_LABEL.matcher(value);
        if (trailing.find() && trailing.start() == 0) return "";
        return value;
    }

    private static boolean isUsableMerchant(String value) {
        if (value == null || value.length() == 0 || value.length() > 256) return false;
        Matcher field = FIELD_LABEL.matcher(value);
        if (field.find() && field.start() == 0 && (field.end() == value.length() || field.end() >= value.length() - 1)) return false;
        return !value.matches("[0-9.\\-_/]+") && !value.equals("¥");
    }

    private static List<Evidence> dedupeEvidence(List<Evidence> input) {
        List<Evidence> out = new ArrayList<Evidence>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Evidence e : input) {
            String key = e.field + "|" + e.lineIndex + "|" + e.start + "|" + e.end + "|" + e.text;
            if (seen.add(key)) out.add(e);
        }
        return out;
    }

    private static String joinReasons(List<String> reasons) {
        if (reasons.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String reason : reasons) {
            if (b.length() > 0) b.append("；");
            b.append(reason);
        }
        return b.toString();
    }

    private static final class Candidate {
        final String value; final int lineIndex; final String field; final int start; final int end;
        Candidate(String value, int lineIndex, String field, int start, int end) {
            this.value = value; this.lineIndex = lineIndex; this.field = field; this.start = start; this.end = end;
        }
    }
    private static final class DateMatch { final String value; final int start; final int end; DateMatch(String v, int s, int e) { value=v; start=s; end=e; } }
    private static final class AmountMatch { final Long cents; final int start; final int end; AmountMatch(long c, int s, int e) { cents=Long.valueOf(c); start=s; end=e; } }
    private static final class FieldValue { final String value; final boolean conflict; FieldValue(String v, boolean c) { value=v; conflict=c; } }
    private static final class AmountValue { final Long value; final boolean conflict; final boolean labelFound; AmountValue(Long v, boolean c, boolean f) { value=v; conflict=c; labelFound=f; } }

    public static final class Bounds {
        public final int left; public final int top; public final int right; public final int bottom;
        public Bounds(int left, int top, int right, int bottom) {
            if (right < left || bottom < top) throw new IllegalArgumentException("invalid OCR bounds");
            this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
        public int width() { return right - left; }
        public int height() { return bottom - top; }
        @Override public boolean equals(Object o) { if (!(o instanceof Bounds)) return false; Bounds b=(Bounds)o; return left==b.left&&top==b.top&&right==b.right&&bottom==b.bottom; }
        @Override public int hashCode() { int h=left; h=31*h+top; h=31*h+right; return 31*h+bottom; }
    }

    public static class OcrLine {
        public final String text; public final Bounds bounds;
        public OcrLine(String text, Bounds bounds) { this.text=text == null ? "" : text; this.bounds=bounds; }
    }

    public static final class OcrBlock extends OcrLine {
        public OcrBlock(String text, Bounds bounds) { super(text, bounds); }
    }

    public static final class Evidence {
        public final String field; public final int lineIndex; public final String text; public final Bounds bounds; public final int start; public final int end;
        public Evidence(String field, int lineIndex, String text, Bounds bounds, int start, int end) {
            this.field=field; this.lineIndex=lineIndex; this.text=text == null ? "" : text; this.bounds=bounds; this.start=start; this.end=end;
        }
    }

    public static final class ParsedReceipt {
        public final String merchant;
        public final String dateIso;
        public final Long amountCents;
        public final String reviewReason;
        public final List<String> reviewReasons;
        public final List<Evidence> evidence;
        public ParsedReceipt(String merchant, String dateIso, Long amountCents, String reviewReason,
                             List<String> reviewReasons, List<Evidence> evidence) {
            this.merchant=merchant; this.dateIso=dateIso; this.amountCents=amountCents; this.reviewReason=reviewReason;
            this.reviewReasons=Collections.unmodifiableList(new ArrayList<String>(reviewReasons));
            this.evidence=Collections.unmodifiableList(new ArrayList<Evidence>(evidence));
        }
        public boolean requiresReview() { return reviewReason != null; }
        public boolean isComplete() { return !requiresReview() && merchant != null && dateIso != null && amountCents != null; }
    }
}
