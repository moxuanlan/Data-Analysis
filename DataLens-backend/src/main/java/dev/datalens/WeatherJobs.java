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
 static final String AGENT="DataLens/0.1 (local user-requested web data collection)";
 final DatasetStore store;final Auth auth;final ExecutorService worker=Executors.newSingleThreadExecutor();
 WeatherJobs(DatasetStore store,Auth auth){this.store=store;this.auth=auth;}
 @javax.annotation.PostConstruct void recover(){store.db.update("UPDATE crawl_jobs SET status='INTERRUPTED',reason='服务重启中断任务，已保存数据保留；请新建任务' WHERE status IN ('QUEUED','RUNNING')");}
 @PreDestroy void stop(){worker.shutdownNow();}
 @GetMapping List<Map<String,Object>> list(HttpServletRequest r){return store.db.queryForList("SELECT * FROM crawl_jobs WHERE owner_id=? ORDER BY id DESC LIMIT 3",auth.me(r).id);}
 static class Request {public String url;public int target=100;public List<String> fields;}
 @PostMapping("/inspect") Map<String,Object> inspect(@RequestBody Request body,HttpServletRequest r){
  auth.me(r);
  try{WebPageData page=WebPageData.fetch(body.url);return Map.of("title",page.title,"kind",page.kind,"columns",page.columns,"count",page.rows.size(),"rows",page.rows.subList(0,Math.min(5,page.rows.size())));}
  catch(Exception e){throw DatasetStore.bad(e instanceof IllegalArgumentException?e.getMessage():"网页识别失败，请检查网址、网络或访问限制");}
 }
 @PostMapping synchronized Map<String,Long> start(@RequestBody Request body,HttpServletRequest r){
  long user=auth.me(r).id;
  try{WebPageData.validate(body.url);}catch(Exception e){throw DatasetStore.bad(e instanceof IllegalArgumentException?e.getMessage():"网址无法解析");}
  if(body.fields==null||body.fields.isEmpty()||body.fields.size()>100||new HashSet<>(body.fields).size()!=body.fields.size()||body.fields.stream().anyMatch(Objects::isNull))throw DatasetStore.bad("请至少选择一个有效字段，不可重复");
  if(body.target<1||body.target>100000)throw DatasetStore.bad("目标数量须为 1–100000；实际数量以当前网页为准");
  if(store.db.queryForObject("SELECT COUNT(*) FROM crawl_jobs WHERE status IN ('QUEUED','RUNNING')",Integer.class)>=10)throw DatasetStore.bad("采集队列已满，请等待");
  if(store.db.queryForObject("SELECT COUNT(*) FROM crawl_jobs WHERE owner_id=? AND created_at>?",Integer.class,user,java.sql.Timestamp.from(Instant.now().minusSeconds(60)))>0)throw DatasetStore.bad("请至少间隔一分钟创建采集任务");
  var key=new GeneratedKeyHolder();store.db.update(c->{var p=c.prepareStatement("INSERT INTO crawl_jobs(owner_id,target_count,status) VALUES(?,?,'QUEUED')",new String[]{"id"});p.setLong(1,user);p.setInt(2,body.target);return p;},key);long id=Objects.requireNonNull(key.getKey()).longValue();worker.submit(()->run(id,user,body.target,body.url.trim(),List.copyOf(body.fields)));return Map.of("id",id);
 }
 @DeleteMapping("/{id}") Map<String,String> delete(@PathVariable long id,HttpServletRequest r){
  int count=store.db.update("DELETE FROM crawl_jobs WHERE id=? AND owner_id=? AND status NOT IN ('QUEUED','RUNNING')",id,auth.me(r).id);
  if(count==0)throw DatasetStore.bad("任务不存在、无权限或仍在运行；请先取消并等待结束");
  return Map.of("message","任务记录已删除，数据集保留");
 }
 @PostMapping("/{id}/cancel") Map<String,String> cancel(@PathVariable long id,HttpServletRequest r){int count=store.db.update("UPDATE crawl_jobs SET cancelled=TRUE WHERE id=? AND owner_id=? AND status IN ('QUEUED','RUNNING')",id,auth.me(r).id);if(count==0)throw DatasetStore.bad("任务不存在、无权限或已结束");return Map.of("message","已请求取消，已保存数据保留");}
 boolean cancelled(long id){return Thread.currentThread().isInterrupted()||Boolean.TRUE.equals(store.db.queryForObject("SELECT cancelled FROM crawl_jobs WHERE id=?",Boolean.class,id));}
 void finish(long id,String status,String reason){store.db.update("UPDATE crawl_jobs SET status=?,reason=? WHERE id=?",status,reason,id);}
 void run(long id,long user,int target,String url,List<String> fields){
  try{
   if(cancelled(id)){finish(id,"CANCELLED","开始前已取消");return;}
   store.db.update("UPDATE crawl_jobs SET status='RUNNING' WHERE id=?",id);
   WebPageData page=WebPageData.fetch(url).select(fields);List<List<String>> rows=page.rows;
   if(cancelled(id)){finish(id,"CANCELLED","已取消");return;}
   long dataset=store.create(user,page.title,url,page.columns,"READY",null,null);
   store.db.update("UPDATE crawl_jobs SET dataset_id=? WHERE id=?",dataset,id);int count=0;
   for(var row:rows){if(cancelled(id)||count>=target)break;store.append(dataset,count,List.of(row));count++;store.finish(dataset,count);store.db.update("UPDATE crawl_jobs SET actual_count=? WHERE id=?",count,id);}
   if(cancelled(id))finish(id,"CANCELLED","用户取消，已保存 "+count+" 条");
   else finish(id,count>=target?"COMPLETED":"PARTIAL",count>=target?page.title+"：达到目标数量":page.title+"：当前页面仅提取到 "+count+" 条；未自动翻页或重复补足数量");
  }catch(Exception e){finish(id,"FAILED","采集失败："+(e instanceof IllegalArgumentException?e.getMessage():"网站访问受限、网络不可用或页面结构变化；未伪造结果"));}
 }
 static String get(String url)throws Exception{return get(url,0);}
 static String get(String url,int redirects)throws Exception{
  WebPageData.validate(url);
  var connection=Jsoup.connect(url).userAgent(AGENT).timeout(20000).maxBodySize(2*1024*1024).followRedirects(false).ignoreContentType(true);
  String proxy=System.getenv("DATALENS_HTTP_PROXY");if(proxy!=null&&!proxy.isBlank()){URI p=URI.create(proxy);if(!"http".equals(p.getScheme())||p.getUserInfo()!=null||p.getHost()==null||p.getPort()<1)throw new IllegalArgumentException("代理配置无效");connection.proxy(p.getHost(),p.getPort());}
  var response=connection.execute();
  if(response.header("x-tengine-error")!=null&&response.header("x-tengine-error").toLowerCase(Locale.ROOT).contains("denied"))throw new IllegalArgumentException("网站明确拒绝自动访问（UA 黑名单），并可能跳转至 404；无法读取真实页面，请改用允许访问的来源或导入文件");
  if(Set.of(301,302,303,307,308).contains(response.statusCode())){
   String next=redirectTarget(url,response.header("Location"),redirects);
   return get(next,redirects+1);
  }
  if(response.statusCode()!=200)throw new IllegalArgumentException("网站未返回正常页面（HTTP "+response.statusCode()+"）");
  if(response.bodyAsBytes().length>=2*1024*1024)throw new IllegalArgumentException("来源页面超过 2 MB，未使用截断内容");return response.body();
 }
 static String redirectTarget(String url,String location,int redirects)throws Exception{
  if(redirects>=3||location==null||location.isBlank())throw new IllegalArgumentException("网页重定向过多或缺少目标地址");
  URI next=URI.create(url).resolve(location);WebPageData.validate(next.toString());
  if(!Objects.equals(URI.create(url).getHost(),next.getHost()))throw new IllegalArgumentException("网页跳转到其他网站，请直接填写目标网址重新识别");
  if(next.getPath().matches("(?i).*/(?:404|403|login|captcha)(?:[./].*)?"))throw new IllegalArgumentException("网站跳转到错误、登录或验证页面，不能当作真实数据采集");
  return next.toString();
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
