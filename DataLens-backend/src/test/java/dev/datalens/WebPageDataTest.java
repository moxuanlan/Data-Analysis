package dev.datalens;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class WebPageDataTest {
 @Test void recognizesPlainCellHeaders(){
  var p=WebPageData.parse(Jsoup.parse("<table><tr><td>商品</td><td>价格</td></tr><tr><td>苹果</td><td>10</td></tr><tr><td>商品</td><td>价格</td></tr></table>"));
  assertEquals(List.of("商品","价格"),p.columns);assertEquals(1,p.rows.size());
 }
 @Test void excludesRepeatedHeadersFootersAndUnrelatedRows(){
  var p=WebPageData.parse(Jsoup.parse("<table><thead><tr><th>商品</th><th>价格</th></tr></thead><tbody><tr><td>苹果</td><td>10</td></tr><tr><td>商品</td><td>价格</td></tr><tr><th>商品</th><th>价格</th></tr><tr><td colspan='2'>广告</td></tr></tbody><tfoot><tr><td>免责声明</td><td>更多</td></tr></tfoot></table>"));
  assertEquals(List.of("商品","价格"),p.columns);assertEquals(List.of(List.of("苹果","10")),p.rows);
 }
 @Test void doesNotMixNavigationWithContentLists(){
  var p=WebPageData.parse(Jsoup.parse("<div class='navigation'><ul><li>首页</li><li>登录</li><li>注册</li></ul></div><main><ul><li><a href='/a'>产品 A</a></li><li><a href='/b'>产品 B</a></li></ul></main>","https://example.com"));
  assertEquals(2,p.rows.size());assertEquals("产品 A",p.rows.get(0).get(0));
  assertThrows(IllegalArgumentException.class,()->WebPageData.parse(Jsoup.parse("<div class='navigation'><ul><li>首页</li></ul></div>")));
 }
 @Test void fieldSelectionPreservesOrderAndNulls(){
  var p=new WebPageData("Title","table",List.of("a","b","c"),List.of(Arrays.asList("001",null,"0")));
  var selected=p.select(List.of("c","b"));assertEquals(List.of("c","b"),selected.columns);assertEquals(Arrays.asList("0",null),selected.rows.get(0));
  assertThrows(IllegalArgumentException.class,()->p.select(List.of()));assertThrows(IllegalArgumentException.class,()->p.select(List.of("missing")));assertThrows(IllegalArgumentException.class,()->p.select(List.of("a","a")));
 }
 @Test void redirectsRejectPrivateAndErrorDestinations(){
  assertThrows(IllegalArgumentException.class,()->WeatherJobs.redirectTarget("https://example.com/","http://127.0.0.1/",0));
  assertThrows(IllegalArgumentException.class,()->WeatherJobs.redirectTarget("https://example.com/","/next",3));
  assertThrows(IllegalArgumentException.class,()->WeatherJobs.redirectTarget("https://example.com/",null,0));
 }
 @Test void tableTitleHeadersAndMissingCells(){
  var p=WebPageData.parse(Jsoup.parse("<title>库存报表</title><table><tr><th>名称</th><th>名称</th><th></th></tr><tr><td>001</td><td>0</td></tr></table>"));
  assertEquals("库存报表",p.title);assertEquals(List.of("名称","名称_2","字段3"),p.columns);assertEquals(Arrays.asList("001","0",null),p.rows.get(0));
 }
 @Test void listAndArticleFallback(){
  var p=WebPageData.parse(Jsoup.parse("<h1>文章列表</h1><nav><li>导航</li></nav><main><ul><li><a href='/news'>新闻</a></li></ul></main>","https://example.com"));
  assertEquals("文章列表",p.title);assertEquals(List.of("新闻","https://example.com/news"),p.rows.get(0));
  var article=WebPageData.parse(Jsoup.parse("<title>正文</title><article><p>第一段</p><p>第二段</p></article>"));assertEquals(2,article.rows.size());
 }
 @Test void rejectUnsupportedStructuresAndUnsafeUrls(){
  assertThrows(IllegalArgumentException.class,()->WebPageData.parse(Jsoup.parse("<script>dynamic()</script>")));
  assertThrows(IllegalArgumentException.class,()->WebPageData.parse(Jsoup.parse("<table><tr><td colspan='2'>合并</td></tr><tr><td>值</td></tr></table>")));
  for(String url:List.of("file:///etc/passwd","http://127.0.0.1/","http://192.168.1.1/","http://169.254.169.254/","http://100.64.0.1/","http://[::1]/","https://user:pass@example.com/","http://example.com:8080/"))assertThrows(IllegalArgumentException.class,()->WebPageData.validate(url));
 }
 @Test void largestTableAndHeaderlessRows(){
  var p=WebPageData.parse(Jsoup.parse("<table><tr><td>装饰</td></tr></table><table><tr><td>a</td><td>1</td></tr><tr><td>b</td><td>2</td></tr></table>"));assertEquals(List.of("字段1","字段2"),p.columns);assertEquals(2,p.rows.size());
 }
}
