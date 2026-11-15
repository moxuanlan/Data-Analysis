package dev.datalens;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.util.function.Consumer;

@Service
class DatasetStore {
 final JdbcTemplate db;
 final ObjectMapper json;
 DatasetStore(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
 static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
 String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
 List<String> strings(String value){try{return json.readValue(value,new TypeReference<List<String>>(){});}catch(Exception e){throw new IllegalStateException("数据格式损坏",e);}}
 Map<String,Object> owned(long id,long user){return load(id,user,false);}
 Map<String,Object> readable(long id,long user){return load(id,user,true);}
 Map<String,Object> load(long id,long user,boolean shared){
  String allowed="owner_id=?";
  if(shared)allowed="("+allowed+" OR (status='READY' AND EXISTS (SELECT 1 FROM dataset_shares s JOIN team_members m ON s.team_id=m.team_id WHERE s.dataset_id=datasets.id AND m.user_id=?)))";
  var rows=shared?db.queryForList("SELECT * FROM datasets WHERE id=? AND "+allowed,id,user,user):db.queryForList("SELECT * FROM datasets WHERE id=? AND "+allowed,id,user);
  if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"数据集不存在或无访问权限");
  var result=rows.get(0);result.put("columns",strings((String)result.remove("columns_json")));result.put("readOnly",((Number)result.get("owner_id")).longValue()!=user);return result;
 }
 @SuppressWarnings("unchecked") List<String> columns(Map<String,Object> dataset){return (List<String>)dataset.get("columns");}
 long create(long user,String name,String source,List<String> columns,String status,Long parent,String rules){
  if(name==null||name.isBlank()||name.length()>255)throw bad("数据集名称须为 1–255 字符");
  validateColumns(columns);
  var key=new GeneratedKeyHolder();
  db.update(c->{var p=c.prepareStatement("INSERT INTO datasets(owner_id,name,source_name,columns_json,status,parent_id,rules_json) VALUES(?,?,?,?,?,?,?)",new String[]{"id"});p.setLong(1,user);p.setString(2,name);p.setString(3,source);p.setString(4,encode(columns));p.setString(5,status);p.setObject(6,parent);p.setString(7,rules);return p;},key);
  return Objects.requireNonNull(key.getKey()).longValue();
 }
 static void validateColumns(List<String> columns){
  if(columns==null||columns.isEmpty()||columns.size()>100)throw bad("字段数量须为 1–100");
  Set<String> unique=new HashSet<>();
  for(String c:columns)if(c==null||c.isBlank()||c.length()>64||!unique.add(c.toLowerCase(Locale.ROOT)))throw bad("字段名不能为空、重复或超过 64 字符");
 }
 void append(long id,int first,List<List<String>> rows){
  List<Object[]> args=new ArrayList<>();int n=first;
  for(var row:rows){String value=encode(row);if(value.length()>1_000_000)throw bad("单行内容超过 1 MB 字符限制");args.add(new Object[]{id,n++,value});}
  db.batchUpdate("INSERT INTO dataset_rows(dataset_id,`row_number`,cells_json) VALUES(?,?,?)",args);
 }
 List<List<String>> page(long id,int offset,int limit){return db.query("SELECT cells_json FROM dataset_rows WHERE dataset_id=? ORDER BY `row_number` LIMIT ? OFFSET ?",(r,n)->strings(r.getString(1)),id,limit,offset);}
 void each(long id,Consumer<List<String>> callback){
  int offset=0;
  while(true){var rows=page(id,offset,500);for(var row:rows)callback.accept(row);if(rows.size()<500)break;offset+=rows.size();}
 }
 void finish(long id,int count){db.update("UPDATE datasets SET row_count=? WHERE id=?",count,id);}
}
