package dev.datalens;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.profile.*;
import com.tencentcloudapi.wsa.v20250508.WsaClient;
import com.tencentcloudapi.wsa.v20250508.models.SearchProRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/data/search")
class YuanbaoSearch {
 final Auth auth;final DatasetStore store;final String secretId,secretKey;
 long window;int calls;
 YuanbaoSearch(Auth auth,DatasetStore store,@Value("${DATALENS_TENCENT_SECRET_ID:}") String secretId,@Value("${DATALENS_TENCENT_SECRET_KEY:}") String secretKey){this.auth=auth;this.store=store;this.secretId=secretId;this.secretKey=secretKey;}
 @GetMapping Map<String,Object> status(HttpServletRequest r){auth.me(r);return Map.of("configured",!secretId.isBlank()&&!secretKey.isBlank(),"service","元宝搜索 SearchPro","generativeModel",false);}
 static class Query {public String query;}
 @PostMapping Map<String,Object> search(@RequestBody Query query,HttpServletRequest request){
  auth.me(request);
  if(query.query==null||query.query.isBlank()||query.query.length()>1000)throw DatasetStore.bad("搜索词须为 1–1000 字符");
  if(secretId.isBlank()||secretKey.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"元宝搜索未配置：请在服务端设置新建的腾讯云凭据后重启；未发送搜索词");
  synchronized(this){long now=System.currentTimeMillis();if(now-window>=60000){window=now;calls=0;}if(calls>=5)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"本机每分钟最多 5 次搜索，请稍后再试");calls++;}
  try{
   HttpProfile http=new HttpProfile();http.setEndpoint("wsa.tencentcloudapi.com");http.setConnTimeout(10);http.setReadTimeout(20);
   ClientProfile profile=new ClientProfile();profile.setHttpProfile(http);
   WsaClient client=new WsaClient(new Credential(secretId,secretKey),"",profile);
   SearchProRequest input=new SearchProRequest();input.setQuery(query.query);input.setCnt(10L);
   var response=client.SearchPro(input);List<Map<String,String>> pages=new ArrayList<>();
   if(response.getPages()!=null)for(String raw:response.getPages()){
    var page=store.json.readTree(raw);String url=page.path("url").asText();
    try{URI uri=URI.create(url);if(!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null)continue;}catch(IllegalArgumentException e){continue;}
    String summary=page.path("passage").asText();if(summary.isEmpty())summary=page.path("content").asText();
    pages.add(Map.of("title",page.path("title").asText(),"url",url,"summary",summary.substring(0,Math.min(5000,summary.length())),"date",page.path("date").asText()));if(pages.size()==10)break;
   }
   return Map.of("pages",pages,"message","公开资料搜索结果，不是模型分析结论；仅发送了输入的搜索词");
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"元宝搜索调用失败，请检查额度、权限、网络和服务端凭据；数据集不受影响");}
 }
}
