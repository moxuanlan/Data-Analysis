package dev.datalens;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.net.*;
import java.time.Instant;
import java.util.*;

final class WebPageData {
 final String title,kind;final List<String> columns;final List<List<String>> rows;
 WebPageData(String title,String kind,List<String> columns,List<List<String>> rows){this.title=title;this.kind=kind;this.columns=columns;this.rows=rows;}
 WebPageData select(List<String> fields){
  if(fields==null||fields.isEmpty()||new HashSet<>(fields).size()!=fields.size()||!columns.containsAll(fields))throw new IllegalArgumentException("所选字段不存在或页面结构已变化，请重新识别");
  List<Integer> indices=new ArrayList<>();for(String field:fields)indices.add(columns.indexOf(field));
  List<List<String>> selected=new ArrayList<>();for(List<String> row:rows){List<String> cells=new ArrayList<>();for(int index:indices)cells.add(row.get(index));selected.add(cells);}
  return new WebPageData(title,kind,List.copyOf(fields),selected);
 }
 static URI validate(String url)throws Exception{
  URI uri;
  try{uri=URI.create(url==null?"":url.trim());}catch(Exception e){throw new IllegalArgumentException("请输入完整的 HTTP 或 HTTPS 网址");}
  if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=80&&uri.getPort()!=443)throw new IllegalArgumentException("仅支持公开 HTTP/HTTPS 网页，不支持账号、片段或非标准端口");
  for(InetAddress a:InetAddress.getAllByName(uri.getHost())){
   byte[] bytes=a.getAddress();int first=bytes[0]&255,second=bytes[1]&255;
   if(a.isAnyLocalAddress()||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isSiteLocalAddress()||a.isMulticastAddress()||bytes.length==16&&(first&0xe0)!=0x20||bytes.length==4&&(first==0||first>=224||first==100&&second>=64&&second<=127||first==198&&(second==18||second==19)))throw new IllegalArgumentException("不能采集本机、内网或保留地址");
  }
  return uri;
 }
 static WebPageData fetch(String url)throws Exception{
  URI uri=validate(url);String robots;
  try{robots=WeatherJobs.get(uri.resolve("/robots.txt").toString());}catch(org.jsoup.HttpStatusException e){if(e.getStatusCode()!=404)throw e;robots="";}
  if(!WeatherJobs.robotsAllow(robots,uri.getRawPath().isEmpty()?"/":uri.getRawPath()))throw new IllegalArgumentException("网站 robots.txt 不允许本次访问");
  return parse(Jsoup.parse(WeatherJobs.get(uri.toString()),uri.toString()));
 }
 static WebPageData parse(Document doc){
  String title=doc.title().strip();if(title.isEmpty()){Element h=doc.selectFirst("h1");title=h==null?"网页数据":h.text();}if(title.length()>200)title=title.substring(0,200);
  doc.select("script,style,noscript,nav,footer,header,form,tfoot,aside,[role=navigation],.navigation,.nav,.menu,.breadcrumb,.pagination,.advertisement,.advert,[hidden],[aria-hidden=true]").remove();
  if(!doc.select("ul.raweather760 > li").isEmpty())return new WebPageData(title,"城市天气",List.of("城市","天气","最低温度_C","最高温度_C","来源网址","采集时间_UTC"),WeatherJobs.parse(doc,Instant.now().toString()));
  Element chosen=null;int count=0;
  for(Element table:doc.select("table")){int n=table.select("tr").size();if(n>count&&table.select("table").size()==1){chosen=table;count=n;}}
  if(chosen!=null&&count>1){
   List<List<String>> rows=new ArrayList<>();List<String> columns=new ArrayList<>();boolean first=true;int width=0;
   for(Element tr:chosen.select("tr")){
    if(!first&&!columns.isEmpty()&&!tr.select("[colspan]:not([colspan=1]),[rowspan]:not([rowspan=1])").isEmpty())continue;
    List<String> cells=new ArrayList<>();boolean heading=false;
    for(Element cell:tr.children())if(cell.tagName().equals("th")||cell.tagName().equals("td")){
     if(!cell.attr("rowspan").matches("|1")||!cell.attr("colspan").matches("|1"))throw new IllegalArgumentException("检测到合并单元格，请先导出为 CSV/Excel 后导入");
     cells.add(cell.text());heading|=cell.tagName().equals("th");
    }
    if(cells.isEmpty())continue;
    if(first&&cells.size()>1&&cells.stream().allMatch(v->v.matches("(?i)(名称|姓名|日期|时间|价格|金额|数量|编号|内容|链接|城市|商品|产品|name|date|time|price|amount|quantity|id|url|title)")))heading=true;
    if(!first&&(cells.equals(columns)||heading&&tr.children().stream().allMatch(c->c.tagName().equals("th"))))continue;
    if(!columns.isEmpty()&&cells.size()>columns.size())continue;
    width=Math.max(width,cells.size());
    if(first&&heading)columns.addAll(cells);else rows.add(cells);first=false;
   }
   if(width>100)throw new IllegalArgumentException("网页表格超过 100 列");
   Set<String> used=new HashSet<>();for(int i=0;i<width;i++){String name=i<columns.size()?columns.get(i):"字段"+(i+1);if(name.isBlank())name="字段"+(i+1);name=name.substring(0,Math.min(50,name.length()));String base=name;int suffix=2;while(!used.add(name.toLowerCase(Locale.ROOT)))name=base+"_"+suffix++;if(i<columns.size())columns.set(i,name);else columns.add(name);}
   for(List<String> row:rows)while(row.size()<width)row.add(null);
   if(!rows.isEmpty())return new WebPageData(title,"网页表格（行数最多的一张）",columns,rows);
  }
  Element main=doc.selectFirst("main,article,[role=main]");if(main==null)main=doc.body();
  List<List<String>> rows=new ArrayList<>();
  Element list=null;int largest=0;
  for(Element candidate:main.select("ul,ol")){int size=(int)candidate.children().stream().filter(e->e.tagName().equals("li")&&e.select("li").size()==1).count();if(size>largest){list=candidate;largest=size;}}
  if(list!=null)for(Element item:list.children()){
   if(!item.tagName().equals("li")||item.select("li").size()!=1)continue;
   String text=item.text();if(text.isBlank())continue;Element link=item.selectFirst("a[href]");
   rows.add(List.of(text,link==null?"":link.absUrl("href")));
  }
  if(!rows.isEmpty())return new WebPageData(title,"已识别的单一内容列表",List.of("内容","链接"),rows);
  for(Element paragraph:main.select("p")){if(!paragraph.text().isBlank())rows.add(List.of(paragraph.text()));}
  if(!rows.isEmpty())return new WebPageData(title,"正文段落",List.of("正文"),rows);
  throw new IllegalArgumentException("没有识别到静态表格、列表或正文；页面可能需要 JavaScript 或登录");
 }
}
