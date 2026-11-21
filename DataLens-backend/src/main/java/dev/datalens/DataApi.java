package dev.datalens;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/data")
class DataApi {
 final AutoCleaning autoCleaning;
 final DatasetStore store;final Imports imports;final Cleaning cleaning;final SqlExport sql;final Auth auth;
 DataApi(DatasetStore store,Imports imports,Cleaning cleaning,SqlExport sql,Auth auth,AutoCleaning autoCleaning){this.autoCleaning=autoCleaning;this.store=store;this.imports=imports;this.cleaning=cleaning;this.sql=sql;this.auth=auth;}
 long user(HttpServletRequest request){return auth.me(request).id;}
 @GetMapping List<Map<String,Object>> list(HttpServletRequest request){return store.db.queryForList("SELECT id,name,source_name,row_count,status,parent_id,created_at FROM datasets WHERE owner_id=? OR (status='READY' AND EXISTS (SELECT 1 FROM dataset_shares s JOIN team_members m ON s.team_id=m.team_id WHERE s.dataset_id=datasets.id AND m.user_id=?)) ORDER BY id DESC LIMIT 500",user(request),user(request));}
 @GetMapping("/{id}") Map<String,Object> page(@PathVariable long id,@RequestParam(defaultValue="0") int page,HttpServletRequest request){
  var result=store.readable(id,user(request));int count=((Number)result.get("row_count")).intValue();
  if(page<0||page>Math.max(0,(count-1)/50))throw DatasetStore.bad("页码超出范围");
  result.put("rows",store.page(id,page*50,50));result.put("page",page);result.put("pageSize",50);return result;
 }
 @PostMapping("/upload") Map<String,Object> upload(@RequestParam MultipartFile file,@RequestParam(defaultValue="UTF-8") String encoding,@RequestParam(defaultValue=",") String delimiter,@RequestParam(defaultValue="0") int sheet,@RequestParam(defaultValue="true") boolean header,@RequestParam(defaultValue="") String nullToken,HttpServletRequest request)throws IOException{
  long owner=user(request);long id=imports.upload(owner,file,encoding,delimiter,sheet,header,nullToken);return store.owned(id,owner);
 }
 @PostMapping("/{id}/confirm") Map<String,Object> confirm(@PathVariable long id,HttpServletRequest request){long owner=user(request);store.owned(id,owner);store.db.update("UPDATE datasets SET status='READY' WHERE id=? AND owner_id=?",id,owner);return store.owned(id,owner);}
 @PostMapping("/{id}/rename") @Transactional Map<String,String> rename(@PathVariable long id,@RequestBody Map<String,String> body,HttpServletRequest request){
  long owner=user(request);store.owned(id,owner);
  String name=Objects.toString(body.get("name"),"").strip();
  if(name.isBlank()||name.codePointCount(0,name.length())>255)throw DatasetStore.bad("名称须为 1 至 255 个字符");
  store.db.update("UPDATE datasets SET name=? WHERE id=? AND owner_id=?",name,id,owner);
  return Map.of("name",name);
 }
 @DeleteMapping("/{id}") @Transactional Map<String,String> delete(@PathVariable long id,HttpServletRequest request){long owner=user(request);store.owned(id,owner);store.db.update("DELETE FROM datasets WHERE id=? AND owner_id=?",id,owner);return Map.of("message","数据集已删除");}
 @PostMapping("/{id}/clean/auto/apply") Map<String,Object> autoApply(@PathVariable long id,@RequestBody AutoCleaning.Plan plan,HttpServletRequest request){return autoCleaning.run(id,user(request),plan,true);}
 @PostMapping("/{id}/clean/preview") Map<String,Object> preview(@PathVariable long id,@RequestBody Cleaning.Rule rule,HttpServletRequest request){return cleaning.run(id,user(request),rule,false);}
 @PostMapping("/{id}/clean/apply") Map<String,Object> apply(@PathVariable long id,@RequestBody Cleaning.Rule rule,HttpServletRequest request){return cleaning.run(id,user(request),rule,true);}
 @PostMapping("/{id}/sql") void sql(@PathVariable long id,@RequestBody SqlExport.Options options,HttpServletRequest request,HttpServletResponse response)throws IOException{
  long owner=user(request);store.readable(id,owner);
  Path file=Files.createTempFile("datalens-export-",".sql");
  try{
   try(Writer writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){sql.write(id,owner,options,writer);}
   response.setContentType("application/sql;charset=UTF-8");response.setHeader("Content-Disposition","attachment; filename=dataset-"+id+".sql");response.setContentLengthLong(Files.size(file));Files.copy(file,response.getOutputStream());
  }finally{Files.deleteIfExists(file);}
 }
 @GetMapping("/{id}/csv") void csv(@PathVariable long id,HttpServletRequest request,HttpServletResponse response)throws IOException{
  var dataset=store.readable(id,user(request));if(!"READY".equals(dataset.get("status")))throw DatasetStore.bad("请先确认导入");
  Path file=Files.createTempFile("datalens-export-",".csv");
  try{
   try(var out=Files.newBufferedWriter(file,StandardCharsets.UTF_8);var printer=new org.apache.commons.csv.CSVPrinter(out,org.apache.commons.csv.CSVFormat.DEFAULT.builder().setQuoteMode(org.apache.commons.csv.QuoteMode.ALL_NON_NULL).setNullString("\\N").get())){
    printer.printRecord(store.columns(dataset));
    store.each(id,row->{try{if(row.contains("\\N"))throw DatasetStore.bad("数据包含 NULL 标记 \\N，不能无损导出 CSV；请使用 SQL 导出");printer.printRecord(row);}catch(IOException e){throw new UncheckedIOException(e);}});
   }
   response.setContentType("text/csv;charset=UTF-8");response.setHeader("Content-Disposition","attachment; filename=dataset-"+id+".csv");response.setContentLengthLong(Files.size(file));Files.copy(file,response.getOutputStream());
  }finally{Files.deleteIfExists(file);}
 }
 @GetMapping("/{id}/chart") Map<String,Object> chart(@PathVariable long id,HttpServletRequest request){
  var dataset=store.readable(id,user(request));
  if(!"READY".equals(dataset.get("status")))throw DatasetStore.bad("请先确认导入");
  // shortcut: chart the first 1000 rows; add full-dataset aggregation when larger charts are needed.
  return Map.of("columns",store.columns(dataset),"rows",store.page(id,0,1000),"total",dataset.get("row_count"));
 }
 @GetMapping("/{id}/summary") Map<String,Object> summary(@PathVariable long id,HttpServletRequest request){
  var dataset=store.readable(id,user(request));List<String> columns=store.columns(dataset);int size=columns.size();int[] missing=new int[size],empty=new int[size],numeric=new int[size];java.math.BigDecimal[] sums=new java.math.BigDecimal[size];Arrays.fill(sums,java.math.BigDecimal.ZERO);
  store.each(id,row->{for(int i=0;i<size;i++){String v=row.get(i);if(v==null)missing[i]++;else if(v.isEmpty())empty[i]++;else if(v.length()<=100&&v.matches("[+-]?[0-9]{1,65}(?:\\.[0-9]{1,30})?"))try{sums[i]=sums[i].add(new java.math.BigDecimal(v));numeric[i]++;}catch(NumberFormatException ignored){}}});
  List<Map<String,Object>> fields=new ArrayList<>();for(int i=0;i<size;i++){Map<String,Object> field=new LinkedHashMap<>();field.put("name",columns.get(i));field.put("nulls",missing[i]);field.put("empty",empty[i]);field.put("numeric",numeric[i]);field.put("mean",numeric[i]==0?null:sums[i].divide(java.math.BigDecimal.valueOf(numeric[i]),12,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());fields.add(field);}
  return Map.of("rowCount",dataset.get("row_count"),"fields",fields);
 }
}
