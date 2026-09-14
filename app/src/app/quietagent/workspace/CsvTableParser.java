package app.quietagent.workspace;

import java.io.IOException;
import java.util.*;

/** Small RFC 4180 style parser for bounded on-device table previews. */
public final class CsvTableParser {
  public static final class Result {public final List<List<String>> rows;public final boolean truncated;Result(List<List<String>> r,boolean t){rows=r;truncated=t;}}
  private CsvTableParser(){}
  public static Result parse(String text,int maxRows,int maxColumns)throws IOException{if(text==null)text="";if(maxRows<1||maxColumns<1)throw new IllegalArgumentException("limits");List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false,truncated=false;for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(quoted){if(ch=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=false;}else cell.append(ch);continue;}if(ch=='"'&&cell.length()==0){quoted=true;continue;}if(ch==','){addCell(row,cell,maxColumns);continue;}if(ch=='\r'||ch=='\n'){if(ch=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;addCell(row,cell,maxColumns);if(!emptyRow(row)){if(rows.size()<maxRows)rows.add(row);else truncated=true;}row=new ArrayList<>();continue;}cell.append(ch);if(cell.length()>8192)throw new IOException("CSV 单元格过长");}if(quoted)throw new IOException("CSV 引号未闭合");if(cell.length()>0||!row.isEmpty()){addCell(row,cell,maxColumns);if(!emptyRow(row)){if(rows.size()<maxRows)rows.add(row);else truncated=true;}}return new Result(rows,truncated);}
  private static void addCell(List<String> row,StringBuilder cell,int max)throws IOException{if(row.size()>=max)throw new IOException("CSV 列数超过预览限制");row.add(cell.toString());cell.setLength(0);}private static boolean emptyRow(List<String> row){if(row.isEmpty())return true;for(String value:row)if(!value.isEmpty())return false;return true;}
}
