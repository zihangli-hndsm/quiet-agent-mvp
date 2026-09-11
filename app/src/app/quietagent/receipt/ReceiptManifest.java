package app.quietagent.receipt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Typed rows and deterministic serializers for the receipt result package. */
public final class ReceiptManifest {
    public final String schema;
    public final String sourceDescription;
    public final List<Row> rows;
    public final Long recognizedTotalCents;

    public ReceiptManifest(String sourceDescription, List<Row> rows) {
        this("quiet-agent-receipt-manifest-v1", sourceDescription, rows, calculateTotal(rows));
    }

    public ReceiptManifest(String schema, String sourceDescription, List<Row> rows, Long recognizedTotalCents) {
        this.schema = require(schema, "schema");
        this.sourceDescription = sourceDescription == null ? "" : sourceDescription;
        this.rows = Collections.unmodifiableList(new ArrayList<Row>(rows == null ? Collections.<Row>emptyList() : rows));
        this.recognizedTotalCents = recognizedTotalCents;
    }

    public int uniqueCount() { int count=0; for (Row r: rows) if (r.included) count++; return count; }
    public int duplicateCount() { int count=0; for (Row r: rows) if (r.duplicateOfSourceId != null) count++; return count; }
    public int reviewCount() { int count=0; for (Row r: rows) if (r.reviewReason != null && r.reviewReason.length()>0) count++; return count; }

    /** UTF-8 CSV content. Values are quoted according to RFC 4180. */
    public String toCsv() {
        StringBuilder b = new StringBuilder();
        b.append("source_id,source_name,sha256,size_bytes,archive_path,included,duplicate_of,merchant,date_iso,amount_cents,review_reason\r\n");
        for (Row r : rows) {
            appendCsv(b,r.sourceId); appendCsv(b,r.sourceName); appendCsv(b,r.sha256); appendCsv(b,Long.toString(r.sizeBytes));
            appendCsv(b,r.archivePath); appendCsv(b,Boolean.toString(r.included)); appendCsv(b,r.duplicateOfSourceId);
            appendCsv(b,r.merchant); appendCsv(b,r.dateIso); appendCsv(b,r.amountCents == null ? null : Long.toString(r.amountCents.longValue())); appendCsv(b,r.reviewReason);
            b.setLength(b.length()-1); b.append("\r\n");
        }
        return b.toString();
    }

    /** Small deterministic JSON manifest; OCR text is not serialized here. */
    public String toJson() {
        StringBuilder b = new StringBuilder("{\"schema\":"); quoteJson(b,schema); b.append(",\"source\":"); quoteJson(b,sourceDescription);
        b.append(",\"recognizedTotalCents\":"); if (recognizedTotalCents == null) b.append("null"); else b.append(recognizedTotalCents);
        b.append(",\"rows\":[");
        for (int i=0; i<rows.size(); i++) { if (i>0) b.append(','); rows.get(i).appendJson(b); }
        return b.append("]}").toString();
    }

    private static Long calculateTotal(List<Row> rows) {
        if (rows == null) return null;
        long total=0; boolean any=false;
        for (Row r: rows) if (r.included && r.reviewReason == null && r.amountCents != null) {
            if (Long.MAX_VALUE-r.amountCents.longValue()<total) return null;
            total += r.amountCents.longValue(); any=true;
        }
        return any ? Long.valueOf(total) : null;
    }
    private static String require(String value, String name) { if (value == null || value.length()==0) throw new IllegalArgumentException(name+" is empty"); return value; }
    private static void appendCsv(StringBuilder b, String value) { if (value==null) { b.append(""); } else { b.append('"').append(value.replace("\"","\"\"")).append('"'); } b.append(','); }
    private static void quoteJson(StringBuilder b, String value) { if (value == null) { b.append("null"); return; } b.append('"'); for (int i=0;i<value.length();i++) { char c=value.charAt(i); if (c=='"'||c=='\\') b.append('\\'); if(c=='\n') b.append("\\n"); else if(c=='\r') b.append("\\r"); else if(c=='\t') b.append("\\t"); else b.append(c); } b.append('"'); }

    public static final class Row {
        public final String sourceId; public final String sourceName; public final String sha256; public final long sizeBytes;
        public final String archivePath; public final boolean included; public final String duplicateOfSourceId;
        public final String merchant; public final String dateIso; public final Long amountCents; public final String reviewReason;
        public final List<ReceiptParser.Evidence> evidence;
        public Row(String sourceId, String sourceName, String sha256, long sizeBytes, String archivePath,
                   boolean included, String duplicateOfSourceId, ReceiptParser.ParsedReceipt parsed) {
            this(sourceId,sourceName,sha256,sizeBytes,archivePath,included,duplicateOfSourceId,
                    parsed==null?null:parsed.merchant, parsed==null?null:parsed.dateIso,
                    parsed==null?null:parsed.amountCents, parsed==null?"缺少解析结果":parsed.reviewReason,
                    parsed==null?Collections.<ReceiptParser.Evidence>emptyList():parsed.evidence);
        }
        public Row(String sourceId, String sourceName, String sha256, long sizeBytes, String archivePath,
                   boolean included, String duplicateOfSourceId, String merchant, String dateIso, Long amountCents, String reviewReason) {
            this(sourceId,sourceName,sha256,sizeBytes,archivePath,included,duplicateOfSourceId,
                    merchant,dateIso,amountCents,reviewReason,Collections.<ReceiptParser.Evidence>emptyList());
        }
        public Row(String sourceId, String sourceName, String sha256, long sizeBytes, String archivePath,
                   boolean included, String duplicateOfSourceId, String merchant, String dateIso, Long amountCents,
                   String reviewReason, List<ReceiptParser.Evidence> evidence) {
            this.sourceId=require(sourceId,"sourceId"); this.sourceName=sourceName==null?"":sourceName; this.sha256=require(sha256,"sha256");
            if(sizeBytes<0) throw new IllegalArgumentException("sizeBytes < 0"); this.sizeBytes=sizeBytes; this.archivePath=archivePath;
            this.included=included; this.duplicateOfSourceId=duplicateOfSourceId; this.merchant=merchant; this.dateIso=dateIso; this.amountCents=amountCents; this.reviewReason=reviewReason;
            this.evidence=Collections.unmodifiableList(new ArrayList<ReceiptParser.Evidence>(
                    evidence==null?Collections.<ReceiptParser.Evidence>emptyList():evidence));
            if (included && duplicateOfSourceId != null) throw new IllegalArgumentException("included duplicate cannot have duplicateOfSourceId");
            if (amountCents != null && amountCents.longValue() < 0) throw new IllegalArgumentException("amountCents < 0");
        }
        private void appendJson(StringBuilder b) {
            b.append("{\"sourceId\":");quoteJson(b,sourceId);b.append(",\"sourceName\":");quoteJson(b,sourceName);b.append(",\"sha256\":");quoteJson(b,sha256);
            b.append(",\"sizeBytes\":").append(sizeBytes).append(",\"archivePath\":");quoteJson(b,archivePath);b.append(",\"included\":").append(included);
            b.append(",\"duplicateOf\":");quoteJson(b,duplicateOfSourceId);b.append(",\"merchant\":");quoteJson(b,merchant);b.append(",\"dateIso\":");quoteJson(b,dateIso);
            b.append(",\"amountCents\":");if(amountCents==null)b.append("null");else b.append(amountCents);b.append(",\"reviewReason\":");quoteJson(b,reviewReason);
            b.append(",\"evidence\":[");
            for(int i=0;i<evidence.size();i++){if(i>0)b.append(',');ReceiptParser.Evidence e=evidence.get(i);
                b.append("{\"field\":");quoteJson(b,e.field);b.append(",\"lineIndex\":").append(e.lineIndex)
                        .append(",\"start\":").append(e.start).append(",\"end\":").append(e.end).append(",\"bounds\":");
                if(e.bounds==null)b.append("null");else b.append("{\"left\":").append(e.bounds.left).append(",\"top\":").append(e.bounds.top)
                        .append(",\"right\":").append(e.bounds.right).append(",\"bottom\":").append(e.bounds.bottom).append('}');
                b.append('}');}
            b.append("]}");
        }
    }
}
