package dev.datalens;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import javax.servlet.http.HttpServletRequest;
import javax.annotation.PreDestroy;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

@RestController
@RequestMapping("/api/data/crawl")
class WeatherJobs {
 static final String SOURCE="https://www.tianqi.com/province/anhui/";
 static final String AGENT="DataLens/0.1 (local user-requested weather data collection)";
 final DatasetStore store;final Auth auth;final ExecutorService worker=Executors.newSingleThreadExecutor();
 WeatherJobs(DatasetStore store,Auth auth){this.store=store;this.auth=auth;}
 @javax.annotation.PostConstruct void recover(){store.db.update("UPDATE crawl_jobs SET status='INTERRUPTED',reason='服务重启中断任务，已保存数据保留；请新建任务' WHERE status IN ('QUEUED','RUNNING')");}
 @PreDestroy void stop(){worker.shutdownNow();}
 @GetMapping List<Map<String,Object>> list(HttpServletRequest r){return store.db.queryForList("SELECT * FROM crawl_jobs WHERE owner_id=? ORDER BY id DESC LIMIT 100",auth.me(r).id);}
 static class Request {public String url;public int target=100;}
 @PostMapping synchronized Map<String,Long> start(@RequestBody Request body,HttpServletRequest r){
  long user=auth.me(r).id;
  if(!SOURCE.equals(body.url))throw DatasetStore.bad("目前仅支持已确认的安徽省天气页，不支持任意 URL、动态页面或登录页面");
  if(body.target<1||body.target>100000)throw DatasetStore.bad("目标数量须为 1–100000；该页面通常只有十余个城市，无法保证达到目标");
  if(store.db.queryForObject("SELECT COUNT(*) FROM crawl_jobs WHERE status IN ('QUEUED','RUNNING')",Integer.class)>=10)throw DatasetStore.bad("采集队列已满，请等待");
  if(store.db.queryForObject("SELECT COUNT(*) FROM crawl_jobs WHERE owner_id=? AND created_at>?",Integer.class,user,java.sql.Timestamp.from(Instant.now().minusSeconds(60)))>0)throw DatasetStore.bad("请至少间隔一分钟创建采集任务");
  var key=new GeneratedKeyHolder();store.db.update(c->{var p=c.prepareStatement("INSERT INTO crawl_jobs(owner_id,target_count,status) VALUES(?,?,'QUEUED')",new String[]{"id"});p.setLong(1,user);p.setInt(2,body.target);return p;},key);long id=Objects.requireNonNull(key.getKey()).longValue();worker.submit(()->run(id,user,body.target));return Map.of("id",id);
 }
 @PostMapping("/{id}/cancel") Map<String,String> cancel(@PathVariable long id,HttpServletRequest r){int count=store.db.update("UPDATE crawl_jobs SET cancelled=TRUE WHERE id=? AND owner_id=? AND status IN ('QUEUED','RUNNING')",id,auth.me(r).id);if(count==0)throw DatasetStore.bad("任务不存在、无权限或已结束");return Map.of("message","已请求取消，已保存数据保留");}
 boolean cancelled(long id){return Thread.currentThread().isInterrupted()||Boolean.TRUE.equals(store.db.queryForObject("SELECT cancelled FROM crawl_jobs WHERE id=?",Boolean.class,id));}
 void finish(long id,String status,String reason){store.db.update("UPDATE crawl_jobs SET status=?,reason=? WHERE id=?",status,reason,id);}
 void run(long id,long user,int target){
  try{
   if(cancelled(id)){finish(id,"CANCELLED","开始前已取消");return;}
   store.db.update("UPDATE crawl_jobs SET status='RUNNING' WHERE id=?",id);
   String robots=get("https://www.tianqi.com/robots.txt");
   if(!robotsAllow(robots,"/province/anhui/")){finish(id,"FAILED","网站 robots.txt 不允许本次访问");return;}
   if(cancelled(id)){finish(id,"CANCELLED","已取消");return;}
   Document page=Jsoup.parse(get(SOURCE),SOURCE);String captured=Instant.now().toString();List<List<String>> rows=parse(page,captured);
   long dataset=store.create(user,"安徽城市天气 · "+LocalDate.now(ZoneId.of("Asia/Shanghai")),SOURCE,List.of("城市","天气","最低温度_C","最高温度_C","来源网址","采集时间_UTC"),"READY",null,null);
   store.db.update("UPDATE crawl_jobs SET dataset_id=? WHERE id=?",dataset,id);int count=0;
   for(var row:rows){if(cancelled(id)||count>=target)break;store.append(dataset,count,List.of(row));count++;store.finish(dataset,count);store.db.update("UPDATE crawl_jobs SET actual_count=? WHERE id=?",count,id);}
   if(cancelled(id))finish(id,"CANCELLED","用户取消，已保存 "+count+" 条");
   else finish(id,count>=target?"COMPLETED":"PARTIAL",count>=target?"达到目标数量":"已遍历约定省份页面，仅有 "+count+" 条城市天气；没有分页或历史记录，不重复补足数量");
  }catch(Exception e){finish(id,"FAILED","采集失败："+(e instanceof IllegalArgumentException?e.getMessage():"网站访问受限、网络不可用或页面结构变化；未伪造结果"));}
 }
 static String get(String url)throws Exception{
  URI uri=URI.create(url);if(!"https".equals(uri.getScheme())||!"www.tianqi.com".equals(uri.getHost())||!(uri.getPath().equals("/robots.txt")||uri.getPath().equals("/province/anhui/")))throw new IllegalArgumentException("来源地址不在允许范围");
  for(InetAddress address:InetAddress.getAllByName(uri.getHost()))if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()||(address.getAddress().length==16&&(address.getAddress()[0]&0xfe)==0xfc))throw new IllegalArgumentException("来源解析到非公网地址，拒绝访问");
  var connection=Jsoup.connect(url).userAgent(AGENT).timeout(20000).maxBodySize(2*1024*1024).followRedirects(false).ignoreContentType(true);
  String proxy=System.getenv("DATALENS_HTTP_PROXY");if(proxy!=null&&!proxy.isBlank()){URI p=URI.create(proxy);if(!"http".equals(p.getScheme())||p.getUserInfo()!=null||p.getHost()==null||p.getPort()<1)throw new IllegalArgumentException("代理配置无效");connection.proxy(p.getHost(),p.getPort());}
  var response=connection.execute();if(response.statusCode()!=200)throw new IllegalArgumentException("网站未返回正常页面（HTTP "+response.statusCode()+"），不跟随重定向或绕过限制");if(response.bodyAsBytes().length>=2*1024*1024)throw new IllegalArgumentException("来源页面超过 2 MB，未使用截断内容");return response.body();
 }
 static List<List<String>> parse(Document document,String captured){
  List<List<String>> result=new ArrayList<>();Set<String> cities=new HashSet<>();Pattern temperature=Pattern.compile("(-?\\d+)\\s*~\\s*(-?\\d+)\\s*℃");
  for(var item:document.select("ul.raweather760 > li")){
   var city=item.selectFirst("h5");if(city==null)continue;var weather=item.selectFirst("p b");var p=item.selectFirst("p");var link=item.selectFirst("a[href]");
   if(weather==null||p==null||link==null)throw new IllegalArgumentException("城市天气字段不完整，未跳过异常行");Matcher m=temperature.matcher(p.text());if(!m.find())throw new IllegalArgumentException("温度格式变化，停止解析");
   String name=city.text();if(!cities.add(name))throw new IllegalArgumentException("页面存在重复城市，停止采集以避免重复数据");
   String path=link.attr("href");if(!path.matches("/[a-z0-9]+/"))throw new IllegalArgumentException("城市来源链接格式变化");
   result.add(List.of(name,weather.text(),m.group(1),m.group(2),"https://www.tianqi.com"+path,captured));
  }
  if(result.isEmpty())throw new IllegalArgumentException("页面没有城市天气数据，可能结构变化或访问受限");return result;
 }
 static boolean robotsAllow(String text,String path){
  boolean applies=false;int best=-1;boolean allowed=true;
  for(String raw:text.split("\\R")){
   String line=raw.split("#",2)[0].trim();int colon=line.indexOf(':');if(colon<0)continue;String key=line.substring(0,colon).trim().toLowerCase(Locale.ROOT),value=line.substring(colon+1).trim();
   if(key.equals("user-agent")){applies=value.equals("*")||value.toLowerCase(Locale.ROOT).startsWith("datalens");continue;}
   if(applies&&(key.equals("disallow")||key.equals("allow"))&&!value.isEmpty()){
    String regex=Pattern.quote(value).replace("*","\\E.*\\Q");if(value.endsWith("$"))regex=Pattern.quote(value.substring(0,value.length()-1)).replace("*","\\E.*\\Q")+"$";else regex+=".*";
    if(path.matches(regex)&&value.length()>=best){best=value.length();allowed=key.equals("allow");}
   }
  }return allowed;
 }
}
