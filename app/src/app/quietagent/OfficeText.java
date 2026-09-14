package app.quietagent;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Bounded, offline OOXML extraction. Never follows relationships or external entities. */
public final class OfficeText {
    public static final int DEFAULT_PAGE_SIZE = 8000;
    public static final int MAX_PAGE_SIZE = 24000;
    public static final class Page {
        public final String text; public final int offset; public final int nextOffset; public final boolean truncated;
        Page(String t,int o,int n,boolean x){text=t;offset=o;nextOffset=n;truncated=x;}
    }
    public static String extract(File file, String name) throws IOException {
        return extractPage(file,name,0,DEFAULT_PAGE_SIZE).text;
    }
    /** Bounded continuation API. The old extract method remains a first-page alias. */
    public static Page extractPage(File file, String name, int offset, int pageSize) throws IOException {
        if (offset < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) throw new IOException("分页参数无效");
        String complete = extractBounded(file,name,MAX_PAGE_SIZE * 64);
        int from=Math.min(offset,complete.length()), to=Math.min(complete.length(),from+pageSize);
        return new Page(complete.substring(from,to),from,to,to<complete.length());
    }
    public static List<Page> extractPages(File file,String name,int pageSize) throws IOException {
        ArrayList<Page> out=new ArrayList<>(); int at=0; Page p;
        do { p=extractPage(file,name,at,pageSize); out.add(p); at=p.nextOffset; } while(p.truncated);
        return out;
    }
    private static String extractBounded(File file, String name, int limit) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\.(txt|md|csv)$")) {
            try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                StringBuilder b=new StringBuilder(); char[] chars = new char[4096]; int n;
                while((n=r.read(chars))!=-1){b.append(chars,0,n);if(b.length()>=limit)break;} return b.substring(0,Math.min(b.length(),limit));
            }
        }
        if (!lower.matches(".*\\.(docx|xlsx|pptx)$")) return "";
        StringBuilder text = new StringBuilder();
        try (ZipFile zip = new ZipFile(file)) {
            List<ZipEntry> entries = new ArrayList<>();
            Enumeration<? extends ZipEntry> all = zip.entries();
            int count = 0;
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (++count > 2000) throw new IOException("Office 内部条目过多");
                String p = e.getName();
                if ((lower.endsWith(".docx") && p.equals("word/document.xml"))
                    || (lower.endsWith(".xlsx") && (p.equals("xl/sharedStrings.xml") || p.matches("xl/worksheets/sheet[0-9]+\\.xml")))
                    || (lower.endsWith(".pptx") && p.matches("ppt/slides/slide[0-9]+\\.xml"))) entries.add(e);
            }
            Collections.sort(entries, (a,b) -> a.getName().compareTo(b.getName()));
            long bytes = 0;
            for (ZipEntry e : entries) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) != -1) {
                        bytes += n; if (bytes > 2 * 1024 * 1024) throw new IOException("Office 展开内容超过限制");
                        data.write(buf, 0, n);
                    }
                }
                String xml = new String(data.toByteArray(), StandardCharsets.UTF_8);
                if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IOException("不支持带实体声明的 Office 文件");
                XmlPullParser parser = Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                parser.setInput(new StringReader(xml));
                boolean capture = false;
                for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                    if (event == XmlPullParser.START_TAG) capture = "t".equals(parser.getName());
                    if (event == XmlPullParser.TEXT && capture) text.append(parser.getText()).append(' ');
                    if (event == XmlPullParser.END_TAG) capture = false;
                    if (text.length() >= limit) return text.substring(0, limit);
                }
            }
        } catch (org.xmlpull.v1.XmlPullParserException e) { throw new IOException("Office XML 无法解析", e); }
        return text.toString();
    }
    /** Host bridge used after WorkspaceStore has materialized one authorized snapshot. */
    public static String extractBoundedForHost(File file, String name, int limit) throws IOException {
        if (limit < 1 || limit > MAX_PAGE_SIZE * 64) throw new IOException("提取上限无效");
        return extractBounded(file, name, limit);
    }

    public static String redact(String text) {
        return text.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[邮箱已遮罩]")
            .replaceAll("(?<![0-9])[0-9][0-9 ()+-]{6,}[0-9Xx](?![0-9])", "[号码已遮罩]")
            .replaceAll("(?m)(姓名|联系人|地址|住址)\\s*[:：]\\s*[^\\n\\r]{1,60}", "$1：[已遮罩]");
    }
}
