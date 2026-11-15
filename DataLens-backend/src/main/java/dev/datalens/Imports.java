package dev.datalens;

import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

@Service
class Imports {
 final DatasetStore store;
 Imports(DatasetStore store){this.store=store;}
 // shortcut: XLSX workbooks are memory-backed and capped at 20 MB; switch to SAX before raising this limit.
 @Transactional(rollbackFor=Exception.class)
 public long upload(long user,MultipartFile file,String encoding,String delimiter,int sheet,boolean header,String nullToken) throws IOException {
  if(file.isEmpty()||file.getSize()>20*1024*1024)throw DatasetStore.bad("文件为空或超过 20 MB");
  String name=Objects.toString(file.getOriginalFilename(),"upload");
  name=name.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
  if(name.length()>255)throw DatasetStore.bad("文件名过长");
  final String source=name;String lower=name.toLowerCase(Locale.ROOT);
  if(!lower.endsWith(".csv")&&!lower.endsWith(".xlsx"))throw DatasetStore.bad("仅支持 CSV 和 XLSX 文件");
  Sink sink=new Sink(user,source,header,nullToken);
  if(lower.endsWith(".csv")){
   if(!Set.of("UTF-8","GB18030").contains(encoding))throw DatasetStore.bad("编码仅支持 UTF-8 或 GB18030");
   if(!Set.of(",",";","\t").contains(delimiter))throw DatasetStore.bad("分隔符仅支持逗号、分号或制表符");
   CharsetDecoder decoder=Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
   try(var reader=new PushbackReader(new InputStreamReader(file.getInputStream(),decoder),1)){
    int first=reader.read();if(first!=-1&&first!=0xfeff)reader.unread(first);
    try(CSVParser parser=CSVFormat.DEFAULT.builder().setDelimiter(delimiter.charAt(0)).setIgnoreEmptyLines(false).get().parse(reader)){
     for(CSVRecord record:parser){List<String> row=new ArrayList<>();record.forEach(row::add);sink.accept(row);}
    }
   }catch(UncheckedIOException e){throw DatasetStore.bad("CSV 解析失败，请检查编码、引号及分隔符");}
  }else{
   try(var in=file.getInputStream();Workbook book=WorkbookFactory.create(in)){
    if(sheet<0||sheet>=book.getNumberOfSheets())throw DatasetStore.bad("工作表序号不存在，序号从 1 开始");
    Sheet tab=book.getSheetAt(sheet);int width=-1;
    for(int i=0;i<=tab.getLastRowNum();i++){
     Row r=tab.getRow(i);if(width<0)width=r==null?0:r.getLastCellNum();
     if(r!=null&&r.getLastCellNum()>width)throw DatasetStore.bad("第 "+(i+1)+" 行字段数量超过首行");
     List<String> values=new ArrayList<>();
     for(int c=0;c<width;c++){
      Cell cell=r==null?null:r.getCell(c,Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
      if(cell==null){values.add(null);continue;}
      switch(cell.getCellType()){
       case STRING:values.add(cell.getStringCellValue());break;
       case BOOLEAN:values.add(Boolean.toString(cell.getBooleanCellValue()));break;
       case NUMERIC:
        // Preserve Excel's raw numeric representation; do not infer dates or apply rounding formats.
        values.add(((org.apache.poi.xssf.usermodel.XSSFCell)cell).getRawValue());break;
       case FORMULA:throw DatasetStore.bad("第 "+(i+1)+" 行含公式；请先粘贴为值，不执行或读取公式缓存");
       default:throw DatasetStore.bad("第 "+(i+1)+" 行包含不支持的单元格类型");
      }
     }
     sink.accept(values);
    }
   }catch(org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException e){throw DatasetStore.bad("文件并非有效 XLSX");}
  }
  return sink.finish();
 }
 class Sink {
  final long user;final String source;final boolean header;final String nullToken;
  long id;int width,count;List<List<String>> batch=new ArrayList<>();
  Sink(long user,String source,boolean header,String nullToken){this.user=user;this.source=source;this.header=header;this.nullToken=nullToken;}
  void accept(List<String> row){
   if(id==0){width=row.size();List<String> columns=new ArrayList<>();for(int i=0;i<width;i++)columns.add(header?row.get(i):"column_"+(i+1));id=store.create(user,source,source,columns,"DRAFT",null,null);if(header)return;}
   if(row.size()!=width)throw DatasetStore.bad("第 "+(count+1+(header?1:0))+" 行字段数量不一致，整个导入已取消");
   if(++count>100000)throw DatasetStore.bad("超过 10 万行限制，整个导入已取消");
   List<String> converted=new ArrayList<>();for(String v:row)converted.add(nullToken!=null&&!nullToken.isEmpty()&&nullToken.equals(v)?null:v);
   batch.add(converted);if(batch.size()==500)flush();
  }
  void flush(){store.append(id,count-batch.size(),batch);batch.clear();}
  long finish(){if(id==0||count==0)throw DatasetStore.bad("文件中没有数据行");flush();store.finish(id,count);return id;}
 }
}
