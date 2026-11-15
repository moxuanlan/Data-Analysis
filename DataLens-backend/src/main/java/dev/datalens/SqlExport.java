package dev.datalens;

import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;

@Service
class SqlExport {
 final DatasetStore store;
 SqlExport(DatasetStore store){this.store=store;}
 static class Options {
  public String table="dataset",kind="create_insert",key;
  public List<Field> fields=new ArrayList<>();
  public String filterColumn,operator="=",filterValue,orderBy,groupBy,aggregate="",aggregateColumn;
  public boolean descending=false;
  public Long joinDataset;
  public String joinTable,joinLeft,joinRight,joinType="LEFT";
 }
 static class Field {public String name,type="LONGTEXT";public boolean nullable=true,primary=false;}
 static String identifier(String name){if(name==null||!name.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,63}"))throw DatasetStore.bad("SQL 名称须以字母或下划线开头，最多 64 字符");return "`"+name+"`";}
 static String literal(String value){
  if(value==null)return "NULL";
  if(value.isEmpty())return "''";
  // Hex UTF-8 literals do not depend on NO_BACKSLASH_ESCAPES or quote escaping modes.
  StringBuilder hex=new StringBuilder();for(byte b:value.getBytes(StandardCharsets.UTF_8))hex.append(Character.forDigit((b&255)>>>4,16)).append(Character.forDigit(b&15,16));
  return "CONVERT(X'"+hex+"' USING utf8mb4)";
 }
 static String type(String value){if(value==null||!value.matches("(?:LONGTEXT|TEXT|BIGINT|DATE|DATETIME|VARCHAR\\([1-9][0-9]{0,3}\\)|DECIMAL\\([1-9][0-9]?,[0-9]{1,2}\\))"))throw DatasetStore.bad("不支持的 SQL 字段类型");if(value.startsWith("DECIMAL")){String[] p=value.substring(8,value.length()-1).split(",");int a=Integer.parseInt(p[0]),b=Integer.parseInt(p[1]);if(a>65||b>30||b>a)throw DatasetStore.bad("DECIMAL 精度无效");}return value;}
 void write(long id,long user,Options options,Writer writer)throws IOException{
  var dataset=store.readable(id,user);if(!"READY".equals(dataset.get("status")))throw DatasetStore.bad("请先确认导入");
  if(options==null||options.fields==null||options.fields.size()>100||options.fields.stream().anyMatch(Objects::isNull))throw DatasetStore.bad("SQL 字段配置无效");
  List<String> columns=store.columns(dataset);String table=identifier(options.table);
  if(options.kind==null||!Set.of("create","insert","create_insert","select","update").contains(options.kind))throw DatasetStore.bad("SQL 类型无效");
  if(options.fields.isEmpty())for(String c:columns){Field f=new Field();f.name=c;options.fields.add(f);}
  if(options.fields.size()!=columns.size())throw DatasetStore.bad("字段映射数量不一致");
  List<String> names=new ArrayList<>(),definitions=new ArrayList<>(),primary=new ArrayList<>();Set<String> unique=new HashSet<>();
  for(Field f:options.fields){identifier(f.name);if(!unique.add(f.name.toLowerCase(Locale.ROOT)))throw DatasetStore.bad("字段名重复");String n=identifier(f.name);names.add(n);definitions.add(n+" "+type(f.type)+(f.nullable&&!f.primary?" NULL":" NOT NULL"));if(f.primary){if(f.type.endsWith("TEXT"))throw DatasetStore.bad("主键不能使用 TEXT 类型");primary.add(n);}}
  int key=options.key==null?-1:columns.indexOf(options.key);
  if(options.kind.equals("update")&&key<0)throw DatasetStore.bad("UPDATE 必须指定匹配键");
  if(options.kind.equals("update")&&columns.size()<2)throw DatasetStore.bad("没有可更新字段");
  Set<String> keys=new HashSet<>();Set<List<String>> primaryValues=new HashSet<>();
  store.each(id,row->{
   List<String> pk=new ArrayList<>();
   for(int i=0;i<row.size();i++){
    Field f=options.fields.get(i);String value=row.get(i);
    if(value==null&&(!f.nullable||f.primary))throw DatasetStore.bad("字段 "+f.name+" 包含 NULL，不能设为非空");
    if(value!=null)validateValue(value,f);
    if(f.primary)pk.add(canonical(value,f.type));
   }
   if(!pk.isEmpty()&&!primaryValues.add(pk))throw DatasetStore.bad("所选主键存在重复值");
   if(options.kind.equals("update")&&(row.get(key)==null||row.get(key).isEmpty()||!keys.add(canonical(row.get(key),options.fields.get(key).type))))throw DatasetStore.bad("匹配键为空或重复，拒绝生成 UPDATE");
  });
  writer.write("-- DataLens dataset "+id+"; generated "+java.time.Instant.now()+"\n-- MySQL 8. Select your database before execution. Generated only; not executed.\n-- UPDATE requires a UNIQUE matching key in the target database.\nSET NAMES utf8mb4;\n\n");
  if(options.kind.contains("create")){
   if(!primary.isEmpty())definitions.add("PRIMARY KEY ("+String.join(",",primary)+")");
   writer.write("CREATE TABLE "+table+" (\n "+String.join(",\n ",definitions)+"\n) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;\n\n");
  }
  if(options.kind.equals("select")){writer.write(select(dataset,user,options,names,table));return;}
  if(options.kind.equals("create"))return;
  try{store.each(id,row->{try{
   if(options.kind.equals("update")){
    List<String> sets=new ArrayList<>();for(int i=0;i<row.size();i++)if(i!=key)sets.add(names.get(i)+"="+literal(row.get(i)));
    writer.write("UPDATE "+table+" SET "+String.join(",",sets)+" WHERE "+names.get(key)+"="+literal(row.get(key))+";\n");
   }else{List<String> values=new ArrayList<>();for(String v:row)values.add(literal(v));writer.write("INSERT INTO "+table+" ("+String.join(",",names)+") VALUES ("+String.join(",",values)+");\n");}
  }catch(IOException e){throw new UncheckedIOException(e);}});}catch(UncheckedIOException e){throw e.getCause();}
 }
 String select(Map<String,Object> dataset,long user,Options o,List<String> names,String table){
  List<String> columns=store.columns(dataset);List<String> output=new ArrayList<>();for(String name:names)output.add("t."+name);
  String join="";
  if(o.joinDataset!=null){
   var other=store.readable(o.joinDataset,user);if(!"READY".equals(other.get("status")))throw DatasetStore.bad("关联数据集尚未确认导入");
   List<String> right=store.columns(other);int leftKey=columns.indexOf(o.joinLeft),rightKey=right.indexOf(o.joinRight);
   if(leftKey<0||rightKey<0||!Set.of("LEFT","INNER").contains(o.joinType))throw DatasetStore.bad("关联关系或关联类型无效");
   join=" "+o.joinType+" JOIN "+identifier(o.joinTable)+" j ON t."+names.get(leftKey)+"=j."+identifier(right.get(rightKey));
   for(int i=0;i<right.size();i++)output.add("j."+identifier(right.get(i))+" AS `joined_"+(i+1)+"`");
  }
  String group="";boolean aggregate=o.aggregate!=null&&!o.aggregate.isBlank();
  if(aggregate){
   if(!Set.of("COUNT","SUM","AVG","MIN","MAX").contains(o.aggregate))throw DatasetStore.bad("聚合函数无效");
   output.clear();int field=columns.indexOf(o.aggregateColumn);
   if(!o.aggregate.equals("COUNT")&&field<0)throw DatasetStore.bad("请选择聚合字段");
   if(Set.of("SUM","AVG").contains(o.aggregate)&&!(o.fields.get(field).type.equals("BIGINT")||o.fields.get(field).type.startsWith("DECIMAL")))throw DatasetStore.bad("求和或均值字段须先确认数值类型");
   if(o.groupBy!=null&&!o.groupBy.isEmpty()){int g=columns.indexOf(o.groupBy);if(g<0)throw DatasetStore.bad("分组字段无效");group=" GROUP BY t."+names.get(g);output.add("t."+names.get(g));}
   output.add(o.aggregate+"("+(o.aggregate.equals("COUNT")?"*":"t."+names.get(field))+") AS `metric`");
  }else if(o.groupBy!=null&&!o.groupBy.isEmpty())throw DatasetStore.bad("分组时请选择聚合函数");
  String where="";
  if(o.filterColumn!=null&&!o.filterColumn.isEmpty()){
   int f=columns.indexOf(o.filterColumn);if(f<0||!Set.of("=","<>",">",">=","<","<=","LIKE","IS NULL","IS NOT NULL").contains(o.operator))throw DatasetStore.bad("筛选字段或操作符无效");
   if(!o.operator.startsWith("IS ")&&o.filterValue==null)throw DatasetStore.bad("请提供筛选值；空值使用 IS NULL");
   where=" WHERE t."+names.get(f)+" "+o.operator+(o.operator.startsWith("IS ")?"":" "+literal(o.filterValue));
  }
  String order="";
  if(o.orderBy!=null&&!o.orderBy.isEmpty()){
   int f=columns.indexOf(o.orderBy);if(f<0)throw DatasetStore.bad("排序字段无效");
   if(aggregate&&!o.orderBy.equals(o.groupBy)&&!o.orderBy.equals(o.aggregateColumn))throw DatasetStore.bad("聚合查询只能按分组字段或指标排序");
   order=" ORDER BY "+(aggregate&&o.orderBy.equals(o.aggregateColumn)?"`metric`":"t."+names.get(f))+(o.descending?" DESC":" ASC");
  }
  return "SELECT "+String.join(",",output)+" FROM "+table+" t"+join+where+group+order+";\n";
 }
 static String canonical(String value,String type){
  if(value==null)return null;
  if(type.equals("BIGINT")||type.startsWith("DECIMAL"))try{return new BigDecimal(value).stripTrailingZeros().toPlainString();}catch(NumberFormatException e){throw DatasetStore.bad("匹配键不是有效数字");}
  if(type.equals("DATETIME"))return java.time.LocalDateTime.parse(value.replace(' ','T')).toString();
  // MySQL utf8mb4_bin uses PAD SPACE: trailing spaces cannot distinguish unique keys.
  return value.replaceAll(" +$","");
 }
 static void validateValue(String value,Field f){
  try{
   if(f.type.equals("BIGINT")){Long.parseLong(value);if(!value.equals(new BigDecimal(value).toPlainString()))throw new IllegalArgumentException();}
   if(f.type.startsWith("VARCHAR")){int size=Integer.parseInt(f.type.substring(8,f.type.length()-1));if(value.codePointCount(0,value.length())>size)throw new IllegalArgumentException();}
   if(f.type.equals("DATE"))java.time.LocalDate.parse(value);
   if(f.type.equals("DATETIME")&&java.time.LocalDateTime.parse(value.replace(' ','T')).getNano()!=0)throw new IllegalArgumentException();
   if(f.type.startsWith("DECIMAL")){String[] p=f.type.substring(8,f.type.length()-1).split(",");BigDecimal d=new BigDecimal(value);if(d.scale()>Integer.parseInt(p[1])||(long)d.precision()-d.scale()>Integer.parseInt(p[0])-Integer.parseInt(p[1]))throw new IllegalArgumentException();}
  }catch(RuntimeException e){throw DatasetStore.bad("字段 "+f.name+" 存在无法无损转换为 "+f.type+" 的值，请保留文本类型或先清洗");}
 }
}
