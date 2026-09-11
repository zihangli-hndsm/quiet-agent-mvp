import app.quietagent.receipt.ReceiptManifest;
import app.quietagent.receipt.ReceiptParser;
import java.util.Arrays;
import java.util.Collections;

/** Pure JVM tests for offline receipt parsing and typed result serialization. */
public final class ReceiptTests {
    private static int assertions;
    private static final ReceiptParser.Bounds B = new ReceiptParser.Bounds(0,0,100,20);

    public static void main(String[] args) {
        testCleanChineseReceipt();
        testAmountAssociationAndIntegerCents();
        testConflictsAndMissingFields();
        testBlocksAndBoundsEvidence();
        testGeometryAssociatesShuffledAmountLine();
        testNoGuessingAndDateValidation();
        testBilingualReceiptLabels();
        testManifestAndDuplicateMapping();
        System.out.println("ReceiptTests OK (" + assertions + " assertions)");
    }

    private static void testCleanChineseReceipt() {
        ReceiptParser.ParsedReceipt r = parse(
                "销售方：北京示例咖啡有限公司",
                "开票日期：2024年8月15日",
                "价税合计（小写） ¥1,234.50");
        check("北京示例咖啡有限公司".equals(r.merchant), "merchant extracted");
        check("2024-08-15".equals(r.dateIso), "date normalized");
        check(Long.valueOf(123450L).equals(r.amountCents), "integer cents");
        check(r.isComplete() && !r.requiresReview(), "clean receipt complete");
        check(r.evidence.size() >= 3, "evidence retained");
    }

    private static void testAmountAssociationAndIntegerCents() {
        ReceiptParser.ParsedReceipt r = parse("商户：小店", "日期：2024/01/02", "合计", "￥0.99");
        check(Long.valueOf(99L).equals(r.amountCents), "adjacent total amount");
        ReceiptParser.ParsedReceipt whole = parse("商户：小店", "日期：2024/01/02", "总金额 12 元");
        check(Long.valueOf(1200L).equals(whole.amountCents), "whole yuan to cents");
        ReceiptParser.ParsedReceipt commas = parse("商户：小店", "日期：2024/01/02", "合计: 12,345.67元");
        check(Long.valueOf(1234567L).equals(commas.amountCents), "comma amount");
        ReceiptParser.ParsedReceipt due = parse("商户：小店", "日期：2024/01/02", "应付金额", "¥", "1O.50 元");
        check(Long.valueOf(1050L).equals(due.amountCents), "split label/currency and OCR zero");
        ReceiptParser.ParsedReceipt traditional = parse("商户：小店", "日期：2024/01/02", "應付金額：Ｙ8.80");
        check(Long.valueOf(880L).equals(traditional.amountCents), "traditional total label and OCR currency");
        ReceiptParser.ParsedReceipt badScale = parse("商户：小店", "日期：2024/01/02", "合计: 1.234");
        check(badScale.amountCents == null && badScale.reviewReason.contains("金额"), "no rounding of 3 decimals");
        ReceiptParser.ParsedReceipt unrelated = parse("商户：小店", "日期：2024/01/02", "商品 99.00", "编号 123456");
        check(unrelated.amountCents == null && unrelated.reviewReason.contains("明确合计"), "no arbitrary amount guess");
    }

    private static void testConflictsAndMissingFields() {
        ReceiptParser.ParsedReceipt conflict = parse("销售方：甲公司", "开票日期：2024-01-01", "合计 10.00", "总金额 12.00");
        check(conflict.amountCents == null && conflict.reviewReason.contains("冲突"), "amount conflict review");
        ReceiptParser.ParsedReceipt merchantConflict = parse("销售方：甲公司", "收款方：乙公司", "日期：2024-01-01", "合计 1");
        check(merchantConflict.merchant == null && merchantConflict.reviewReason.contains("商户冲突"), "merchant conflict review");
        ReceiptParser.ParsedReceipt missing = parse("商品 A", "合计");
        check(missing.merchant == null && missing.dateIso == null && missing.amountCents == null, "missing fields null");
        check(missing.reviewReasons.size() == 3, "all missing reasons retained");
        ReceiptParser.ParsedReceipt invalid = parse("商户：小店", "日期：2024-02-31", "合计 2.00");
        check(invalid.dateIso == null && invalid.reviewReason.contains("日期"), "invalid date review");
    }

    private static void testBlocksAndBoundsEvidence() {
        ReceiptParser.ParsedReceipt r = ReceiptParser.parseBlocks(Arrays.asList(
                new ReceiptParser.OcrBlock("销售方：块内商户\n开票日期：2023-12-31", new ReceiptParser.Bounds(4,5,80,40)),
                new ReceiptParser.OcrBlock("价税合计：8.00", new ReceiptParser.Bounds(4,45,80,65))));
        check("块内商户".equals(r.merchant) && Long.valueOf(800L).equals(r.amountCents), "block parsing");
        check(r.evidence.get(0).bounds.equals(new ReceiptParser.Bounds(4,5,80,40)), "evidence bounds preserved");
        check(r.evidence.get(0).lineIndex == 0, "block line index");
    }

    private static void testGeometryAssociatesShuffledAmountLine() {
        ReceiptParser.Bounds header = new ReceiptParser.Bounds(0, 0, 300, 30);
        ReceiptParser.Bounds item = new ReceiptParser.Bounds(0, 120, 300, 150);
        ReceiptParser.Bounds totalLabel = new ReceiptParser.Bounds(40, 220, 180, 252);
        ReceiptParser.Bounds totalValue = new ReceiptParser.Bounds(620, 222, 820, 254);
        ReceiptParser.ParsedReceipt r = ReceiptParser.parseLines(Arrays.asList(
                new ReceiptParser.OcrLine("日期：2024-01-02", header),
                new ReceiptParser.OcrLine("合计", totalLabel),
                new ReceiptParser.OcrLine("商品 99.00", item),
                new ReceiptParser.OcrLine("商户：小店", header),
                new ReceiptParser.OcrLine("18.80 元", totalValue)));
        check(Long.valueOf(1880L).equals(r.amountCents), "same-row shuffled amount line");
        check(r.evidence.stream().anyMatch(e -> "amount".equals(e.field) && e.lineIndex == 4), "amount geometry evidence");
    }

    private static void testNoGuessingAndDateValidation() {
        ReceiptParser.ParsedReceipt noLabel = parse("名称：小店", "2024-01-02", "应收 99.00");
        check(noLabel.amountCents == null, "amount requires total label");
        ReceiptParser.ParsedReceipt invalidMonth = parse("商户名称：小店", "交易日期：2024/13/01", "总金额：3");
        check(invalidMonth.dateIso == null, "invalid month rejected");
        ReceiptParser.ParsedReceipt duplicateSame = parse("商户：小店", "日期：2024-01-02", "合计 3.00", "总金额 3.00");
        check(Long.valueOf(300L).equals(duplicateSame.amountCents) && duplicateSame.reviewReason == null, "same total values do not conflict");
        ReceiptParser.ParsedReceipt subtotal = parse("商户：小店", "日期：2024-01-02", "小计 3.00", "合计 4.00");
        check(Long.valueOf(400L).equals(subtotal.amountCents), "final total wins over subtotal");
        ReceiptParser.ParsedReceipt subtotalOnly = parse("商户：小店", "日期：2024-01-02", "小计 3.00");
        check(subtotalOnly.amountCents == null, "subtotal alone is not the final payable amount");
        check(ReceiptParser.parseLines(Collections.<ReceiptParser.OcrLine>emptyList()).requiresReview(), "empty OCR needs review");
    }

    private static void testBilingualReceiptLabels() {
        ReceiptParser.ParsedReceipt r = parse(
                "MERCHANT: 青禾便利店", "DATE: 2024-03-01", "TOTAL: CNY 18.80");
        check("青禾便利店".equals(r.merchant), "bilingual merchant label");
        check("2024-03-01".equals(r.dateIso), "bilingual date label");
        check(Long.valueOf(1880L).equals(r.amountCents) && r.isComplete(), "bilingual explicit total");
    }

    private static void testManifestAndDuplicateMapping() {
        ReceiptParser.ParsedReceipt parsed = parse("商户：小店", "日期：2024-01-02", "合计：3.00");
        ReceiptManifest.Row first = new ReceiptManifest.Row("a", "a.jpg", "aa", 10, "receipt/a.jpg", true, null, parsed);
        ReceiptManifest.Row duplicate = new ReceiptManifest.Row("b", "b.jpg", "aa", 10, null, false, "a", parsed);
        ReceiptManifest manifest = new ReceiptManifest("本地照片", Arrays.asList(first, duplicate));
        check(manifest.uniqueCount() == 1 && manifest.duplicateCount() == 1, "duplicate mapping counts");
        check(manifest.recognizedTotalCents.equals(Long.valueOf(300L)), "recognized total excludes duplicate");
        check(manifest.toCsv().contains("duplicate_of") && manifest.toCsv().contains("\"a\""), "CSV headers and mapping");
        check(manifest.toJson().contains("quiet-agent-receipt-manifest-v1") && manifest.toJson().contains("duplicateOf"), "JSON schema and mapping");
        expectIllegal(new Runnable() { public void run() { new ReceiptManifest.Row("x","x","aa",1,"x",true,"a",parsed); } }, "included duplicate rejected");
    }

    private static ReceiptParser.ParsedReceipt parse(String... lines) {
        ReceiptParser.OcrLine[] out = new ReceiptParser.OcrLine[lines.length];
        for (int i=0;i<lines.length;i++) out[i] = new ReceiptParser.OcrLine(lines[i], B);
        return ReceiptParser.parseLines(Arrays.asList(out));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private static void expectIllegal(Runnable r, String message) { try { r.run(); throw new AssertionError(message); } catch (IllegalArgumentException expected) { assertions++; } }
}
