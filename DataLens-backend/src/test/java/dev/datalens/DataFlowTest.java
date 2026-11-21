package dev.datalens;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.*;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(properties={"DATALENS_TENCENT_SECRET_ID=","DATALENS_TENCENT_SECRET_KEY=","DATALENS_DEEPSEEK_API_KEY=","spring.datasource.url=jdbc:h2:mem:dataflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.datasource.driver-class-name=org.h2.Driver"})
@AutoConfigureMockMvc
@Transactional
class DataFlowTest {
 @Autowired Auth auth;@Autowired DataApi dataApi;@Autowired YuanbaoSearch search;
 @Autowired MockMvc mvc;@Autowired DatasetStore store;@Autowired Imports imports;@Autowired Cleaning cleaning;@Autowired SqlExport sql;
 MockHttpSession session;
 @BeforeEach void setup(){
  store.db.update("INSERT INTO users(id,username,account,password_hash) VALUES(90001,'QA','qa@example.test','unused')");
  session=new MockHttpSession();session.setAttribute("user",new Auth.User(90001,"QA","qa@example.test"));
 }
 long upload(String csv)throws Exception{return imports.upload(90001,new MockMultipartFile("file","test.csv","text/csv",csv.getBytes(StandardCharsets.UTF_8)),"UTF-8",",",0,true,"\\N");}
 void confirm(long id){store.db.update("UPDATE datasets SET status='READY' WHERE id=?",id);}
 @Test void memberLookupHandlesLegacyEmailAndDoesNotListOwner()throws Exception{
  store.db.update("INSERT INTO users(id,username,account,password_hash) VALUES(90002,'member',' Member@Example.test ','unused')");
  var req=new MockHttpServletRequest();req.setSession(session);var teams=new Teams(store,auth);
  long team=teams.create(Map.of("name","Team"),req).get("id");
  assertThrows(Exception.class,()->teams.add(team,Map.of("account","qa@example.test"),req));
  teams.add(team,Map.of("account","MEMBER@example.test"),req);
  var members=teams.members(team,req);assertEquals(1,members.size());assertEquals(90002,((Number)members.get(0).get("id")).longValue());
  var other=new MockHttpSession();other.setAttribute("user",new Auth.User(90002,"member","member@example.test"));var memberReq=new MockHttpServletRequest();memberReq.setSession(other);
  assertThrows(Exception.class,()->teams.add(team,Map.of("account","qa@example.test"),memberReq));
  assertTrue(teams.create(Map.of("name","Own team"),memberReq).get("id")>0);
 }
 @Test void automaticCleaningProcessesAllRowsAndPreservesOriginal()throws Exception{
  long id=upload("date,amount,age\n2026/1/2,￥1200元,25\n2026年1月3日,10.125,200\n2026-1-4,20,\nambiguous,30,30\n");confirm(id);
  AutoCleaning service=new AutoCleaning(store);AutoCleaning.Plan plan=new AutoCleaning.Plan();
  AutoCleaning.Field date=new AutoCleaning.Field();date.column="date";date.kind="date";
  AutoCleaning.Field amount=new AutoCleaning.Field();amount.column="amount";amount.kind="money";
  AutoCleaning.Field age=new AutoCleaning.Field();age.column="age";age.kind="number";age.maximum="120";age.missing="drop";age.reason="年龄超出确认范围";
  plan.fields=List.of(date,amount,age);var preview=service.run(id,90001,plan,false);
  assertEquals(2,preview.get("removed"));assertEquals(1,preview.get("unresolvedCells"));assertEquals(4,store.page(id,0,50).size());
  var applied=service.run(id,90001,plan,true);var rows=store.page(((Number)applied.get("id")).longValue(),0,50);
  assertEquals(List.of("2026-01-02","CNY 1200.00","25"),rows.get(0));assertEquals("ambiguous",rows.get(1).get(0));assertEquals(4,store.page(id,0,50).size());
  assertEquals("10.125",AutoCleaning.money("10.125"));assertThrows(Exception.class,()->AutoCleaning.date("01/02/2026"));
  plan.fields=List.of(date);assertThrows(Exception.class,()->service.run(id,90001,plan,true));
 }
 @Test void automaticModelPlanScansBeyondFirstPageAndNeverUsesSearch()throws Exception{
  StringBuilder csv=new StringBuilder("date\n");for(int i=0;i<505;i++)csv.append("2026/1/2\n");csv.append("2026/1/3\n");long id=upload(csv.toString());confirm(id);
  var service=new DeepSeekAdvice(store,auth,sql,cleaning,dataApi,search,new AutoCleaning(store),"fixture-token","fixture-model");
  var server=org.springframework.test.web.client.MockRestServiceServer.createServer(service.http);
  String answer=store.encode(Map.of("cleanPlan",Map.of("fields",List.of(Map.of("column","date","kind","date","reason","统一日期")))));
  String response=store.encode(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",answer)))));
  server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.deepseek.com/chat/completions")).andExpect(request->{String sent=((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString();assertTrue(sent.contains("2026/1/3"));assertTrue(sent.contains("fullDatasetProfile"));}).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(response,org.springframework.http.MediaType.APPLICATION_JSON));
  var body=new DeepSeekAdvice.Request();body.purpose="clean_auto";body.question="自动清洗";body.consent=true;
  var request=new MockHttpServletRequest();request.setSession(session);var result=service.advise(id,body,request);
  assertEquals(506,((Map<?,?>)result.get("preview")).get("changed"));assertEquals("2026/1/3",store.page(id,505,1).get(0).get(0));assertEquals(true,result.get("samplesSent"));server.verify();
 }
 @Test void authenticationCsrfAndOwnershipAreEnforced()throws Exception{
  long id=upload("id,text\n001,hello\n");
  mvc.perform(get("/api/data")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/data/"+id+"/confirm").session(session)).andExpect(status().isForbidden());
  mvc.perform(post("/api/data/"+id+"/confirm").session(session).with(csrf())).andExpect(status().isOk());
  MockHttpSession other=new MockHttpSession();other.setAttribute("user",new Auth.User(90002,"other","other@example.test"));
  String rename="/api/data/"+id+"/rename";
  String body="{\"name\":\" 新名称 \"}";
  mvc.perform(post(rename).session(other).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNotFound());
  mvc.perform(post(rename).session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
  assertEquals("新名称",store.owned(id,90001).get("name"));
  mvc.perform(get("/api/data/"+id).session(other)).andExpect(status().isNotFound());
  mvc.perform(get("/api/data/"+id+"/csv").session(other)).andExpect(status().isNotFound());
  mvc.perform(post("/api/data/"+id+"/sql").session(other).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isNotFound());
 }
 @Test void chartRespectsOwnershipConfirmationAndSampleLimit()throws Exception{
  StringBuilder csv=new StringBuilder("id,value\n");for(int i=0;i<1005;i++)csv.append(i).append(",0\n");
  long id=upload(csv.toString());
  mvc.perform(get("/api/data/"+id+"/chart").session(session)).andExpect(status().isBadRequest());
  confirm(id);
  mvc.perform(get("/api/data/"+id+"/chart")).andExpect(status().isUnauthorized());
  MockHttpSession other=new MockHttpSession();other.setAttribute("user",new Auth.User(90002,"other","other@example.test"));
  mvc.perform(get("/api/data/"+id+"/chart").session(other)).andExpect(status().isNotFound());
  mvc.perform(get("/api/data/"+id+"/chart").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1000)).andExpect(jsonPath("$.total").value(1005));
 }
 @Test void crawlHistoryLimitsAndDeletionPreserveDatasets()throws Exception{
  long dataset=upload("id\n001\n");confirm(dataset);
  for(int i=0;i<5;i++)store.db.update("INSERT INTO crawl_jobs(owner_id,target_count,status,dataset_id) VALUES(90001,1,'COMPLETED',?)",dataset);
  long latest=store.db.queryForObject("SELECT MAX(id) FROM crawl_jobs WHERE owner_id=90001",Long.class);
  mvc.perform(get("/api/data/crawl").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[0].id").value(latest));
  MockHttpSession other=new MockHttpSession();other.setAttribute("user",new Auth.User(90002,"other","other@example.test"));
  mvc.perform(delete("/api/data/crawl/"+latest).session(other).with(csrf())).andExpect(status().isBadRequest());
  mvc.perform(delete("/api/data/crawl/"+latest).session(session)).andExpect(status().isForbidden());
  store.db.update("UPDATE crawl_jobs SET status='RUNNING' WHERE id=?",latest);
  mvc.perform(delete("/api/data/crawl/"+latest).session(session).with(csrf())).andExpect(status().isBadRequest());
  store.db.update("UPDATE crawl_jobs SET status='COMPLETED' WHERE id=?",latest);
  mvc.perform(delete("/api/data/crawl/"+latest).session(session).with(csrf())).andExpect(status().isOk());
  assertEquals(1,store.page(dataset,0,50).size());assertEquals(4,store.db.queryForObject("SELECT COUNT(*) FROM crawl_jobs WHERE owner_id=90001",Integer.class));
 }
 @Test void passwordChangeValidatesOriginalAndNewLogin()throws Exception{
  store.db.update("UPDATE users SET password_hash=? WHERE id=90001",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("OldPassword123"));
  String body="{\"currentPassword\":\"OldPassword123\",\"newPassword\":\"NewPassword456\",\"confirmPassword\":\"NewPassword456\"}";
  mvc.perform(post("/api/auth/password").session(session).contentType("application/json").content(body)).andExpect(status().isForbidden());
  mvc.perform(post("/api/auth/password").session(session).with(csrf()).contentType("application/json").content(body.replace("OldPassword123","WrongPassword123"))).andExpect(status().isBadRequest());
  mvc.perform(post("/api/auth/password").session(session).with(csrf()).contentType("application/json").content(body)).andExpect(status().isOk());
  assertTrue(session.isInvalid());
  mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"account\":\"qa@example.test\",\"password\":\"OldPassword123\"}")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content("{\"account\":\"qa@example.test\",\"password\":\"NewPassword456\"}")).andExpect(status().isOk());
 }
 @Test void csvPreservesZerosNullEmptyUnicodeQuotesAndNewlines()throws Exception{
  long id=upload("id,text,optional\n001,\"中文 ' \\\"\"quoted\"\"\nnext\",\\N\n002,,0\n");
  var rows=store.page(id,0,50);assertEquals("001",rows.get(0).get(0));assertNull(rows.get(0).get(2));assertEquals("",rows.get(1).get(1));assertEquals("0",rows.get(1).get(2));assertTrue(rows.get(0).get(1).contains("\n"));
  confirm(id);StringWriter result=new StringWriter();sql.write(id,90001,new SqlExport.Options(),result);
  assertTrue(result.toString().contains("CONVERT(X'303031' USING utf8mb4)"));assertTrue(result.toString().contains("NULL"));assertFalse(result.toString().contains("'NULL'"));
 }
 @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
 void invalidRowsAreNotSilentlySkipped()throws Exception{
  try{
   assertThrows(Exception.class,()->upload("id,name\n1,a\n2,b,extra\n"));
   assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM datasets WHERE owner_id=90001",Integer.class));
   ByteArrayOutputStream malformed=new ByteArrayOutputStream();malformed.write("id,name\n".getBytes(StandardCharsets.UTF_8));for(int i=0;i<2000;i++)malformed.write((i+",valid-data\n").getBytes(StandardCharsets.UTF_8));malformed.write(255);
   assertThrows(Exception.class,()->imports.upload(90001,new MockMultipartFile("file","bad.csv","text/csv",malformed.toByteArray()),"UTF-8",",",0,true,""));
   assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM datasets WHERE owner_id=90001",Integer.class));
  }finally{store.db.update("DELETE FROM datasets WHERE owner_id=90001");store.db.update("DELETE FROM users WHERE id=90001");}
 }
 @Test void cleaningPreviewLeavesOriginalAndCreatesNewVersion()throws Exception{
  long id=upload("id,amount\n1,\\N\n1,\\N\n2,12.55\n");confirm(id);
  Cleaning.Rule r=new Cleaning.Rule();r.operation="deduplicate";r.columns=List.of("id");
  var preview=cleaning.run(id,90001,r,false);assertEquals(1,preview.get("changed"));assertEquals(3,store.page(id,0,50).size());
  var applied=cleaning.run(id,90001,r,true);long newer=((Number)applied.get("id")).longValue();assertEquals(2,store.page(newer,0,50).size());assertEquals(3,store.page(id,0,50).size());assertEquals(id,((Number)store.owned(newer,90001).get("parent_id")).longValue());
  r=new Cleaning.Rule();r.operation="fill";r.column="amount";r.value="0";var filled=cleaning.run(newer,90001,r,true);assertEquals("0",store.page(((Number)filled.get("id")).longValue(),0,50).get(0).get(1));assertNull(store.page(id,0,50).get(0).get(1));
 }
 @Test void updateRejectsMissingOrDuplicateKeys()throws Exception{
  long id=upload("id,amount\n1,10\n1,20\n");confirm(id);var options=new SqlExport.Options();options.kind="update";
  assertThrows(Exception.class,()->sql.write(id,90001,options,new StringWriter()));options.key="id";assertThrows(Exception.class,()->sql.write(id,90001,options,new StringWriter()));
 }
 @Test void xlsxRejectsFormulasAndPreservesText()throws Exception{
  try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var bytes=new ByteArrayOutputStream()){
   var sheet=book.createSheet("data");sheet.createRow(0).createCell(0).setCellValue("id");sheet.createRow(1).createCell(0).setCellValue("001");book.write(bytes);
   long id=imports.upload(90001,new MockMultipartFile("file","test.xlsx","application/octet-stream",bytes.toByteArray()),"UTF-8",",",0,true,"");assertEquals("001",store.page(id,0,1).get(0).get(0));
   sheet.getRow(1).getCell(0).setCellFormula("1+1");bytes.reset();book.write(bytes);
   assertThrows(Exception.class,()->imports.upload(90001,new MockMultipartFile("file","test.xlsx","application/octet-stream",bytes.toByteArray()),"UTF-8",",",0,true,""));
  }
 }
 @Test void dateMoneySplitMergeAndOutlierRules()throws Exception{
  long id=upload("id,date,amount,parts\n1,2024/02/29,12.555,a|b\n");confirm(id);
  Cleaning.Rule rule=new Cleaning.Rule();rule.operation="date";rule.column="date";rule.pattern="uuuu/MM/dd";rule.targetPattern="uuuu-MM-dd";
  long date=((Number)cleaning.run(id,90001,rule,true).get("id")).longValue();assertEquals("2024-02-29",store.page(date,0,1).get(0).get(1));
  rule.operation="money";rule.column="amount";rule.scale=2;long money=((Number)cleaning.run(id,90001,rule,true).get("id")).longValue();assertEquals("12.56",store.page(money,0,1).get(0).get(2));
  rule.operation="split";rule.column="parts";rule.separator="|";rule.target="part";long split=((Number)cleaning.run(id,90001,rule,true).get("id")).longValue();assertEquals(List.of("a","b"),store.page(split,0,1).get(0).subList(4,6));
  rule.operation="merge";rule.columns=List.of("id","parts");rule.separator=":";rule.target="joined";long merge=((Number)cleaning.run(id,90001,rule,true).get("id")).longValue();assertEquals("1:a|b",store.page(merge,0,1).get(0).get(4));
  rule.operation="outlier";rule.column="amount";rule.minimum="0";rule.maximum="10";rule.target="abnormal";long flagged=((Number)cleaning.run(id,90001,rule,true).get("id")).longValue();assertEquals("true",store.page(flagged,0,1).get(0).get(4));
 }
 @Test void sharedMembersCanReadButCannotMutateAndRevocationWorks()throws Exception{
  store.db.update("INSERT INTO users(id,username,account,password_hash) VALUES(90002,'member','member@example.test','unused')");
  long dataset=upload("id,name\n1,a\n");confirm(dataset);
  String teamJson=mvc.perform(post("/api/data/teams").session(session).with(csrf()).contentType("application/json").content("{\"name\":\"QA team\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();long team=store.json.readTree(teamJson).get("id").asLong();
  mvc.perform(post("/api/data/teams/"+team+"/members").session(session).with(csrf()).contentType("application/json").content("{\"account\":\"member@example.test\"}")).andExpect(status().isOk());
  mvc.perform(post("/api/data/teams/"+team+"/datasets/"+dataset).session(session).with(csrf())).andExpect(status().isOk());
  MockHttpSession member=new MockHttpSession();member.setAttribute("user",new Auth.User(90002,"member","member@example.test"));
  mvc.perform(get("/api/data/"+dataset).session(member)).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true));
  mvc.perform(delete("/api/data/"+dataset).session(member).with(csrf())).andExpect(status().isNotFound());
  mvc.perform(post("/api/data/"+dataset+"/clean/apply").session(member).with(csrf()).contentType("application/json").content("{\"operation\":\"fill\",\"column\":\"name\",\"value\":\"x\"}")).andExpect(status().isNotFound());
  mvc.perform(delete("/api/data/teams/"+team+"/members/90002").session(session).with(csrf())).andExpect(status().isOk());
  mvc.perform(get("/api/data/"+dataset).session(member)).andExpect(status().isNotFound());
 }
 @Test void weatherParserUsesActualFieldsAndRejectsMissingTemperatures(){
  String html="<ul class='raweather760'><li><a href='/hefei/'><h5>合肥</h5><p><b>阴</b>-1 ~ <em>17</em>℃</p></a></li></ul>";
  var rows=WeatherJobs.parse(org.jsoup.Jsoup.parse(html),"2026-11-15T00:00:00Z");assertEquals(List.of("合肥","阴","-1","17","https://www.tianqi.com/hefei/","2026-11-15T00:00:00Z"),rows.get(0));
  assertThrows(IllegalArgumentException.class,()->WeatherJobs.parse(org.jsoup.Jsoup.parse(html.replace("17","未知")),"now"));
  assertFalse(WeatherJobs.robotsAllow("User-agent: *\nDisallow: /province/", "/province/anhui/"));
  assertTrue(WeatherJobs.robotsAllow("User-agent: *\nDisallow: /admin/", "/province/anhui/"));
 }
 @Test void selectValidatesFieldsAndBuildsAggregation()throws Exception{
  long id=upload("city,amount\n合肥,12.5\n杭州,20\n");confirm(id);var o=new SqlExport.Options();o.kind="select";o.aggregate="AVG";o.aggregateColumn="amount";o.groupBy="city";o.orderBy="amount";o.descending=true;
  var a=new SqlExport.Field();a.name="city";var b=new SqlExport.Field();b.name="amount";b.type="DECIMAL(20,2)";o.fields=List.of(a,b);StringWriter out=new StringWriter();sql.write(id,90001,o,out);assertTrue(out.toString().contains("AVG(t.`amount`) AS `metric`"));assertTrue(out.toString().contains("GROUP BY t.`city` ORDER BY `metric` DESC"));
  o.filterColumn="unknown";assertThrows(Exception.class,()->sql.write(id,90001,o,new StringWriter()));
 }
 @Test void unconfiguredSearchFailsClearlyWithoutLosingData()throws Exception{
  long id=upload("id,name\n1,a\n");
  mvc.perform(post("/api/data/search").session(session).with(csrf()).contentType("application/json").content("{\"query\":\"天气数据分析\"}")).andExpect(status().isServiceUnavailable());
  mvc.perform(get("/api/data/"+id).session(session)).andExpect(status().isOk());
 }
 @Test void modelUsesConsentAndValidatedPlansWithoutSendingRowsByDefault()throws Exception{
  long id=upload("id,private_text\n001,PRIVATE_SAMPLE_MUST_NOT_LEAVE\n");confirm(id);
  var service=new DeepSeekAdvice(store,auth,sql,cleaning,dataApi,search,new AutoCleaning(store),"fixture-token","fixture-model");
  var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(service.http).build();
  String answer=store.encode(Map.of("explanation","按字段查询","sqlOptions",Map.of("kind","select")));
  String response=store.encode(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",answer)))));
  server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.deepseek.com/chat/completions")).andExpect(request->{String sent=((org.springframework.mock.http.client.MockClientHttpRequest)request).getBodyAsString();assertFalse(sent.contains("PRIVATE_SAMPLE_MUST_NOT_LEAVE"));assertTrue(sent.contains("private_text"));}).andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(response,org.springframework.http.MediaType.APPLICATION_JSON));
  var body=new DeepSeekAdvice.Request();body.question="查看所有字段";body.consent=true;
  var request=new MockHttpServletRequest();request.setSession(session);var result=service.advise(id,body,request);
  assertTrue(result.get("sqlPreview").toString().contains("SELECT"));assertEquals(false,result.get("samplesSent"));server.verify();
  body.consent=false;assertThrows(Exception.class,()->service.advise(id,body,request));
  var malicious=store.json.readTree("{\"sqlOptions\":{\"kind\":\"update\"}}");assertThrows(Exception.class,()->service.validate(id,90001,"sql",malicious));
  var invented=store.json.readTree("{\"sqlOptions\":{\"kind\":\"select\",\"filterColumn\":\"invented\"}}");assertThrows(Exception.class,()->service.validate(id,90001,"sql",invented));
 }
 @Test void modelCanSuggestAddedTimeColumnWithoutChangingDataset()throws Exception{
  long id=upload("name,url\nitem,https://example.com\n");confirm(id);
  var service=new DeepSeekAdvice(store,auth,sql,cleaning,dataApi,search,new AutoCleaning(store),"fixture-token","fixture-model");
  var proposal=store.json.valueToTree(Map.of("sqlOptions",Map.of("kind","create","fields",List.of(Map.of("name","name","type","TEXT"),Map.of("name","url","type","TEXT"),Map.of("name","created_at","type","DATETIME")))));
  var result=service.validate(id,90001,"sql",proposal);
  assertTrue(result.get("reference").toString().contains("`created_at` DATETIME NULL"));
  assertEquals(2,store.columns(store.owned(id,90001)).size());
  assertFalse(result.containsKey("options"));
 }
 @Test void modelCleaningSuggestionsNeverMutateUntilConfirmation()throws Exception{
  long id=upload("id,amount\n1,\\N\n");confirm(id);
  var service=new DeepSeekAdvice(store,auth,sql,cleaning,dataApi,search,new AutoCleaning(store),"fixture-token","fixture-model");
  var proposal=store.json.readTree("{\"explanation\":\"建议填充\",\"cleanRule\":{\"operation\":\"fill\",\"column\":\"amount\",\"value\":\"0\"}}");
  var result=service.validate(id,90001,"clean",proposal);assertNotNull(result.get("preview"));assertNull(store.page(id,0,1).get(0).get(1));
 }
 @Test void sqlRejectsKeysThatMySqlTreatsAsDuplicates()throws Exception{
  long id=upload("id,value\na,1\na ,2\n");confirm(id);var o=new SqlExport.Options();o.kind="update";o.key="id";assertThrows(Exception.class,()->sql.write(id,90001,o,new StringWriter()));
  var huge=new SqlExport.Field();huge.name="value";huge.type="DECIMAL(10,2)";assertThrows(Exception.class,()->SqlExport.validateValue("1e2147483647",huge));
  long decimal=upload("id,value\n1.0,a\n1.00,b\n");confirm(decimal);var a=new SqlExport.Field();a.name="id";a.type="DECIMAL(10,2)";a.primary=true;var b=new SqlExport.Field();b.name="value";var options=new SqlExport.Options();options.fields=List.of(a,b);assertThrows(Exception.class,()->sql.write(decimal,90001,options,new StringWriter()));
 }
 @Test void hundredThousandRowsRemainPagedAndExportCompletely()throws Exception{
  StringBuilder csv=new StringBuilder("id,value\n");for(int i=0;i<100000;i++)csv.append(i).append(",value\n");
  long id=upload(csv.toString());confirm(id);assertEquals(100000,((Number)store.owned(id,90001).get("row_count")).intValue());assertEquals("99999",store.page(id,99999,1).get(0).get(0));
  var options=new SqlExport.Options();options.kind="insert";
  class Counter extends Writer{int inserts;public void write(char[] b,int o,int n){String s=new String(b,o,n);if(s.startsWith("INSERT INTO"))inserts++;}public void flush(){}public void close(){}}
  Counter out=new Counter();sql.write(id,90001,options,out);assertEquals(100000,out.inserts);
 }
}
