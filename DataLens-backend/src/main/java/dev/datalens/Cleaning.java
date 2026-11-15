package dev.datalens;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
class Cleaning {
 final DatasetStore store;
 Cleaning(DatasetStore store){this.store=store;}
 static class Rule {
  public String operation,column,value,pattern,targetPattern,separator="",target="",keep="first",mode="fixed";
  public List<String> columns=new ArrayList<>();
  public int parts=2,scale=2;
  public boolean removeOriginal=false,includeEmpty=false;
  public String minimum,maximum;
 }
 @Transactional
 public Map<String,Object> run(long id,long user,Rule rule,boolean apply){
  if(rule==null||rule.columns==null||rule.columns.size()>100||rule.mode==null||rule.keep==null)throw DatasetStore.bad("清洗字段配置无效");
  var dataset=store.owned(id,user);
  if(!"READY".equals(dataset.get("status")))throw DatasetStore.bad("请先确认导入");
  if((rule.pattern!=null&&rule.pattern.length()>100)||(rule.targetPattern!=null&&rule.targetPattern.length()>100)||(rule.value!=null&&rule.value.length()>10000))throw DatasetStore.bad("清洗参数过长");
  List<String> original=store.columns(dataset),columns=new ArrayList<>(original);
  int index=original.indexOf(rule.column);
  Set<String> ops=Set.of("deduplicate","fill","date","money","split","merge","outlier");
  if(rule.operation==null||!ops.contains(rule.operation))throw DatasetStore.bad("不支持的清洗操作");
  if(!Set.of("deduplicate","merge").contains(rule.operation)&&index<0)throw DatasetStore.bad("请选择有效字段");
  if(rule.parts<2||rule.parts>20||rule.scale<0||rule.scale>12)throw DatasetStore.bad("拆分数量须为 2–20，金额精度须为 0–12");
  List<Integer> selected=new ArrayList<>();for(String c:rule.columns){int i=original.indexOf(c);if(i<0)throw DatasetStore.bad("未知字段："+c);selected.add(i);}
  if(Set.of("merge","deduplicate").contains(rule.operation)&&selected.isEmpty())throw DatasetStore.bad("请至少选择一个字段");
  if(!Set.of("first","last").contains(rule.keep))throw DatasetStore.bad("保留规则无效");
  if(rule.separator==null||rule.separator.length()>100)throw DatasetStore.bad("分隔符过长");
  if(rule.operation.equals("split")&&rule.separator.isEmpty())throw DatasetStore.bad("拆分分隔符不能为空");
  if(rule.operation.equals("split")){for(int i=1;i<=rule.parts;i++)columns.add(rule.target+"_"+i);}
  if(rule.operation.equals("merge")||rule.operation.equals("outlier"))columns.add(rule.target);
  if(rule.removeOriginal&&rule.operation.equals("split"))columns.remove(index);
  if(rule.removeOriginal&&rule.operation.equals("merge"))columns.removeAll(rule.columns);
  DatasetStore.validateColumns(columns);
  BigDecimal[] total={BigDecimal.ZERO};int[] numeric={0};
  if(rule.operation.equals("fill")&&rule.mode.equals("mean"))store.each(id,row->{String v=row.get(index);if(v!=null&&!v.isEmpty()){try{if(v.length()>100||!v.matches("[+-]?[0-9]{1,65}(?:\\.[0-9]{1,30})?"))throw new NumberFormatException();total[0]=total[0].add(new BigDecimal(v));numeric[0]++;}catch(NumberFormatException e){throw DatasetStore.bad("均值填充要求非空值均为数字");}}});
  final String fill=rule.mode.equals("mean")?(numeric[0]==0?null:total[0].divide(BigDecimal.valueOf(numeric[0]),12,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()):rule.value;
  if(rule.operation.equals("fill")&&(fill==null||!Set.of("fixed","mean").contains(rule.mode)))throw DatasetStore.bad("请指定填充值或有效的均值规则");
  Map<List<String>,Integer> last=new HashMap<>();int[] counter={0};
  if(rule.operation.equals("deduplicate")&&rule.keep.equals("last"))store.each(id,row->{List<String> key=new ArrayList<>();for(int i:selected)key.add(row.get(i));last.put(key,counter[0]++);});
  Set<List<String>> seen=new HashSet<>();counter[0]=0;
  long output=apply?store.create(user,"清洗 · "+dataset.get("name").toString().substring(0,Math.min(240,dataset.get("name").toString().length())),"cleaned",columns,"READY",id,store.encode(rule)):0;
  List<List<String>> batch=new ArrayList<>();List<Map<String,Object>> samples=new ArrayList<>();int[] counts={0,0};
  store.each(id,input->{
   int rowNumber=counter[0]++;List<String> row=new ArrayList<>(input);String v=index<0?null:row.get(index);boolean drop=false;
   try{
    switch(rule.operation){
     case "deduplicate":List<String> key=new ArrayList<>();for(int i:selected)key.add(row.get(i));drop=rule.keep.equals("last")?last.get(key)!=rowNumber:!seen.add(key);break;
     case "fill":if(v==null||(rule.includeEmpty&&v.isEmpty()))row.set(index,fill);break;
     case "date":if(v!=null&&!v.isEmpty()){DateTimeFormatter from=DateTimeFormatter.ofPattern(rule.pattern).withResolverStyle(ResolverStyle.STRICT);row.set(index,LocalDate.parse(v,from).format(DateTimeFormatter.ofPattern(rule.targetPattern)));}break;
     case "money":if(v!=null&&!v.isEmpty()){
      if(!v.matches("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?"))throw new IllegalArgumentException();
      row.set(index,new BigDecimal(v.replace(",","")).setScale(rule.scale,RoundingMode.HALF_UP).toPlainString());}break;
     case "split":String[] fields=v==null?new String[0]:v.split(Pattern.quote(rule.separator),-1);if(fields.length>rule.parts)throw new IllegalArgumentException();for(int i=0;i<rule.parts;i++)row.add(i<fields.length?fields[i]:null);if(rule.removeOriginal)row.remove(index);break;
     case "merge":List<String> pieces=new ArrayList<>();boolean missing=false;for(int i:selected){pieces.add(row.get(i));if(row.get(i)==null)missing=true;}row.add(missing?null:String.join(rule.separator,pieces));if(rule.removeOriginal){List<Integer> descending=new ArrayList<>(new HashSet<>(selected));descending.sort(Comparator.reverseOrder());for(int i:descending)row.remove(i);}break;
     case "outlier":if(rule.minimum==null||rule.maximum==null)throw new IllegalArgumentException();BigDecimal min=new BigDecimal(rule.minimum),max=new BigDecimal(rule.maximum);if(min.compareTo(max)>0)throw new IllegalArgumentException();row.add(v==null||v.isEmpty()?null:Boolean.toString(new BigDecimal(v).compareTo(min)<0||new BigDecimal(v).compareTo(max)>0));break;
    }
   }catch(RuntimeException e){throw DatasetStore.bad("第 "+(rowNumber+1)+" 行无法按规则处理；未保存任何清洗结果，请检查格式和参数");}
   if(drop||!row.equals(input)){counts[1]++;if(samples.size()<10){Map<String,Object> sample=new LinkedHashMap<>();sample.put("before",input);sample.put("after",drop?null:row);samples.add(sample);}}
   if(!drop){counts[0]++;if(apply){batch.add(row);if(batch.size()==500){store.append(output,counts[0]-batch.size(),batch);batch.clear();}}}
  });
  if(apply){store.append(output,counts[0]-batch.size(),batch);store.finish(output,counts[0]);}
  return Map.of("id",output,"changed",counts[1],"rowCount",counts[0],"columns",columns,"samples",samples);
 }
}
