package app.quietagent.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** A small deterministic plan for local file organization. */
public final class Plan {
    public enum Grouping { NONE, TYPE, MONTH }
    public enum Filter { ANY, PDF, IMAGE, DOCUMENT, AUDIO, VIDEO }

    public final boolean deduplicate;
    public final Grouping grouping;
    /** Primary filter for compatibility; use filters for a multi-type request. */
    public final Filter filter;
    /** Requested types. A set containing ANY means all types. */
    public final Set<Filter> filters;
    public final Integer recentDays;
    private final String original;

    private Plan(String original, boolean deduplicate, Grouping grouping,
                 Filter filter, Set<Filter> filters, Integer recentDays) {
        this.original = original;
        this.deduplicate = deduplicate;
        this.grouping = grouping;
        this.filter = filter;
        this.filters = Collections.unmodifiableSet(EnumSet.copyOf(filters));
        this.recentDays = recentDays;
    }

    /** Parse the supported local, non-destructive vocabulary. */
    public static Plan parse(String request) throws IllegalArgumentException {
        if (request == null || request.trim().isEmpty()) {
            throw new IllegalArgumentException("请求为空；例如：把这个目录里的文件去重，按类型归档");
        }
        String s = request.trim();
        String l = s.toLowerCase(Locale.ROOT);
        boolean preserveOriginal = containsAny(l, "保留原件", "不删除原件");
        String[] forbidden = {"删除", "删掉", "移到", "移动", "上传", "发送", "分享", "重命名", "rename",
                "delete", "move", "upload", "send", "share"};
        String actionScan = l.replace("保留原件", "").replace("不删除原件", "");
        for (String word : forbidden) {
            if (actionScan.contains(word)) throw new IllegalArgumentException("不支持删除、移动、上传、发送或分享文件");
        }

        boolean dedupeMention = containsAny(l, "去重", "查重", "重复", "dedup", "duplicate");
        boolean noDedupeMention = containsAny(l, "不去重", "无需去重", "不要去重", "不查重", "without dedup", "no dedup", "not dedup",
                "don't dedup", "do not dedup", "don't deduplicate", "do not deduplicate", "no duplicate", "保留重复");
        boolean recognized = dedupeMention || noDedupeMention || preserveOriginal;
        boolean dedupe = dedupeMention && !noDedupeMention;

        Grouping grouping = Grouping.NONE;
        boolean typeMention = containsAny(l, "按类型", "分类", "type", "by type");
        boolean monthMention = containsAny(l, "按月份", "按月", "月份", "month", "by month");
        boolean noGroupingMention = containsAny(l, "不分类", "不分组", "不归档", "without grouping", "no grouping");
        if (typeMention && monthMention) throw new IllegalArgumentException("同时指定按类型和按月份，归档方式不明确");
        recognized |= typeMention || monthMention || noGroupingMention;
        if (typeMention) grouping = Grouping.TYPE;
        if (monthMention) grouping = Grouping.MONTH;
        if (noGroupingMention) grouping = Grouping.NONE;

        if (hasUnsupportedTypeExclusion(l)) {
            throw new IllegalArgumentException("图片排除和‘除了 PDF’筛选暂不支持");
        }
        boolean pdfMention = containsAny(l, "pdf");
        boolean imageMention = containsAny(l, "图片", "图像", "照片", "image", "photo", "jpg", "jpeg", "png", "gif", "webp");
        boolean documentMention = containsAny(l, "文档", "document", "docx", "doc", "txt", "文本");
        boolean audioMention = containsAny(l, "音频", "音乐", "audio", "music", "mp3", "wav", "m4a");
        boolean videoMention = containsAny(l, "视频", "video", "movie", "mp4", "mkv", "mov");
        recognized |= pdfMention || imageMention || documentMention || audioMention || videoMention;
        EnumSet<Filter> filterSet = EnumSet.noneOf(Filter.class);
        if (pdfMention) filterSet.add(Filter.PDF);
        if (imageMention) filterSet.add(Filter.IMAGE);
        if (documentMention) filterSet.add(Filter.DOCUMENT);
        if (audioMention) filterSet.add(Filter.AUDIO);
        if (videoMention) filterSet.add(Filter.VIDEO);
        if (filterSet.isEmpty()) filterSet.add(Filter.ANY);
        Filter primary = filterSet.contains(Filter.ANY) ? Filter.ANY : filterSet.iterator().next();

        Integer days = null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:最近|近|last)\\s*(\\d+)\\s*(?:天|日|days?|d)?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(l);
        if (m.find()) {
            recognized = true;
            try { days = Integer.valueOf(m.group(1)); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("最近天数格式不正确", ex); }
            if (days < 1 || days > 3650) throw new IllegalArgumentException("最近天数必须在1到3650天之间");
        }
        if (!recognized) throw new IllegalArgumentException("无法理解请求；请说明去重、归档方式、文件类型或最近多少天");
        return new Plan(s, dedupe, grouping, primary, filterSet, days);
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static boolean hasUnsupportedTypeExclusion(String text) {
        String compact = text.replaceAll("\\s+", "");
        String[] prefixes = {"不要", "不含", "排除", "除了", "除", "exclude", "excluding", "except", "without", "no", "not", "dontinclude", "donotinclude"};
        String[] types = {"pdf", "图片", "图像", "照片", "image", "images", "picture", "pictures", "photo", "photos",
                "jpeg", "jpg", "png", "gif", "webp", "文档", "document", "documents", "音频", "audio", "视频", "video", "videos"};
        for (String prefix : prefixes) {
            for (String type : types) {
                if (compact.contains(prefix + type)) return true;
            }
        }
        return false;
    }

    public String summary() {
        StringBuilder b = new StringBuilder("已按本地规则处理：");
        b.append(deduplicate ? "去重" : "保留重复").append("；");
        if (grouping == Grouping.TYPE) b.append("按类型");
        else if (grouping == Grouping.MONTH) b.append("按月份");
        else b.append("不分类");
        b.append("；类型=");
        if (filters.contains(Filter.ANY)) b.append("全部");
        else {
            boolean first = true;
            for (Filter f : filters) { if (!first) b.append("、"); first = false; b.append(label(f)); }
        }
        if (recentDays != null) b.append("；最近").append(recentDays).append("天");
        return b.toString();
    }

    private static String label(Filter f) {
        switch (f) {
            case PDF: return "PDF";
            case IMAGE: return "图片";
            case DOCUMENT: return "文档";
            case AUDIO: return "音频";
            case VIDEO: return "视频";
            default: return "全部";
        }
    }

    @Override public String toString() { return summary(); }
}
