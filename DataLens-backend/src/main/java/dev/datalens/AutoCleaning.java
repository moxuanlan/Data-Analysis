package dev.datalens;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

@Service
class AutoCleaning {
 final DatasetStore store;
 AutoCleaning(DatasetStore store){this.store=store;}
 static class Plan {public List<Field> fields=new ArrayList<>();}
 static class Field {
  public String column,kind="keep",missing="keep",minimum,maximum,reason="";
 }
 Map<String,Object> profile(long id,long user){
  var dataset=store.owned(id,user);var columns=store.columns(dataset);
  List<Set<String>> examples=new ArrayList<>();int[] missing=new int[columns.size()];
  for(String c:columns)examples.add(new LinkedHashSet<>());
  store.each(id,row->{for(int i=0;i<columns.size();i++){String v=row.get(i);if(v==null||v.isBlank())missing[i]++;else if(examples.get(i).size()<12)examples.get(i).add(v.substring(0,Math.min(v.length(),120)));}});
  List<Object> fields=new ArrayList<>();for(int i=0;i<columns.size();i++)fields.add(Map.of("column",columns.get(i),"missing",missing[i],"examples",examples.get(i)));
  return Map.of("rows",dataset.get("row_count"),"fields",fields);
 }
 @Transactional
 public Map<String,Object> run(long id,long user,Plan plan,boolean apply){
  var dataset=store.owned(id,user);var columns=store.columns(dataset);
  if(!"READY".equals(dataset.get("status")))throw DatasetStore.bad("请先确认导入");
  if(plan==null||plan.fields==null||plan.fields.size()!=columns.size())throw DatasetStore.bad("自动清洗方案必须覆盖全部字段");
  Set<String> used=new HashSet<>();List<Integer> indices=new ArrayList<>();
  for(Field f:plan.fields){
   if(f==null||!columns.contains(f.column)||!used.add(f.column)||f.kind==null||!Set.of("keep","date","money","number").contains(f.kind)||f.missing==null||!Set.of("keep","drop").contains(f.missing)||f.reason==null||f.reason.length()>1000)throw DatasetStore.bad("自动清洗字段或处理规则无效");
   if(f.missing.equals("drop")&&f.reason.isBlank())throw DatasetStore.bad("移除缺失行需要说明依据");
   if((f.minimum!=null||f.maximum!=null)&&(!f.kind.equals("number")||f.reason.isBlank()))throw DatasetStore.bad("异常范围仅支持数值字段，且需要说明判断依据");
   try{BigDecimal min=bound(f.minimum),max=bound(f.maximum);if(min!=null&&max!=null&&min.compareTo(max)>0)throw DatasetStore.bad("异常值范围无效");}catch(IllegalArgumentException e){throw DatasetStore.bad("异常值范围必须是有效十进制数");}
   indices.add(columns.indexOf(f.column));
  }
  long output=apply?store.create(user,"智能清洗 · "+dataset.get("name").toString().substring(0,Math.min(230,dataset.get("name").toString().length())),"cleaned",columns,"READY",id,store.encode(plan)):0;
  int[] counts={0,0,0,0};List<List<String>> batch=new ArrayList<>();List<Map<String,Object>> samples=new ArrayList<>(),issues=new ArrayList<>();
  store.each(id,input->{
   int rowNo=++counts[0];List<String> row=new ArrayList<>(input);boolean drop=false;
   for(int n=0;n<plan.fields.size();n++){
    Field f=plan.fields.get(n);int i=indices.get(n);String value=row.get(i);
    if(value==null||value.isBlank()){if(f.missing.equals("drop"))drop=true;else {counts[3]++;issue(issues,rowNo,f.column,"缺失值已保留，不能可靠推断");}continue;}
    try{
     if(f.kind.equals("date"))row.set(i,date(value));
     else if(f.kind.equals("money"))row.set(i,money(value));
     else if(f.kind.equals("number")){
      BigDecimal number=bound(value.strip()),min=bound(f.minimum),max=bound(f.maximum);
      if(min!=null&&number.compareTo(min)<0||max!=null&&number.compareTo(max)>0)drop=true;
     }
    }catch(IllegalArgumentException|DateTimeException e){counts[3]++;issue(issues,rowNo,f.column,"格式无法可靠识别，原值已保留");}
   }
   if(drop||!row.equals(input)){counts[2]++;if(samples.size()<10){Map<String,Object> sample=new LinkedHashMap<>();sample.put("before",input);sample.put("after",drop?null:row);samples.add(sample);}}
   if(!drop){counts[1]++;if(apply){batch.add(row);if(batch.size()==500){store.append(output,counts[1]-batch.size(),batch);batch.clear();}}}
  });
  if(apply){store.append(output,counts[1]-batch.size(),batch);store.finish(output,counts[1]);}
  Map<String,Object> result=new LinkedHashMap<>();result.put("id",output);result.put("changed",counts[2]);result.put("rowCount",counts[1]);result.put("removed",counts[0]-counts[1]);result.put("unresolvedCells",counts[3]);result.put("issues",issues);result.put("columns",columns);result.put("samples",samples);return result;
 }
 static void issue(List<Map<String,Object>> issues,int row,String column,String reason){if(issues.size()<20)issues.add(Map.of("row",row,"column",column,"reason",reason));}
 static BigDecimal bound(String value){if(value==null)return null;if(value.length()>100||!value.matches("[+-]?[0-9]{1,65}(?:\\.[0-9]{1,30})?"))throw new IllegalArgumentException("数值格式无效");return new BigDecimal(value);}
 static String date(String value){
  String v=value.strip();
  try{return OffsetDateTime.parse(v).toString();}catch(DateTimeException ignored){}
  try{return LocalDateTime.parse(v.replace(' ','T')).toString();}catch(DateTimeException ignored){}
  for(String pattern:List.of("uuuu-M-d","uuuu/M/d","uuuu.M.d","uuuu年M月d日","uuuuMMdd"))try{return LocalDate.parse(v,DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)).toString();}catch(DateTimeException ignored){}
  throw new IllegalArgumentException("日期不明确");
 }
 static String money(String value){
  String v=value.strip();String currency="";
  if(v.matches("^(人民币|￥|¥|CNY|RMB).*")){currency="CNY ";v=v.replaceFirst("^(人民币|￥|¥|CNY|RMB)\\s*","");}
  else if(v.startsWith("USD")){currency="USD ";v=v.substring(3).strip();}
  else if(v.startsWith("EUR")||v.startsWith("€")){currency="EUR ";v=v.replaceFirst("^(EUR|€)\\s*","");}
  if(v.endsWith("元")){if(!currency.isEmpty()&&!currency.equals("CNY "))throw new IllegalArgumentException();currency="CNY ";v=v.substring(0,v.length()-1).strip();}
  if(v.length()>100||!v.matches("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?"))throw new IllegalArgumentException();
  BigDecimal number=new BigDecimal(v.replace(",",""));return currency+number.setScale(Math.max(2,number.stripTrailingZeros().scale())).toPlainString();
 }
}
