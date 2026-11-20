/* Real data workspace. The landing-page sample remains explicitly labelled as a demo. */
const dataState = { dataset: null, list: [], teams: [], page: 0, cleanRule: null, sql: null };
const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fieldOptions = () => (dataState.dataset?.columns || []).map(c => `<option value="${escapeHtml(c)}">${escapeHtml(c)}</option>`).join('');
const input = (label,id,value='',type='text') => `<label>${label}<input id="${id}" type="${type}" value="${escapeHtml(value)}"></label>`;
async function dataRequest(path='', body, method, binary=false) {
 const options={credentials:'same-origin',method:method || (body===undefined?'GET':'POST'),headers:{}};
 if(options.method!=='GET'){
  let csrf;
  try{const response=await fetch('/api/auth/csrf',{credentials:'same-origin'});if(!response.ok)throw new Error();csrf=await response.json();}catch{throw new Error('后端未连接或会话不可用，请确认 Java 服务已启动后重新登录');}
  options.headers[csrf.header]=csrf.token;
  if(body instanceof FormData)options.body=body;
  else if(body!==undefined){options.headers['Content-Type']='application/json';options.body=JSON.stringify(body);}
 }
 const response=await fetch('/api/data'+path,options);
 if(response.status===401){currentUser=null;demoLoggedIn=false;throw new Error('登录已过期，请返回首页重新登录');}
 if(!response.ok){let message='后端请求失败（'+response.status+'）';try{message=(await response.json()).message||message;}catch{}throw new Error(message);}
 return binary?response.blob():response.json();
}
function saveFile(blob,name){const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),2000);}
function realTable(dataset){return `<div class="tablewrap"><table><thead><tr>${dataset.columns.map(c=>'<th>'+escapeHtml(c)+'</th>').join('')}</tr></thead><tbody>${dataset.rows.map(row=>'<tr>'+row.map(v=>'<td>'+(v===null?'<em>NULL</em>':v===''?'<em>空字符串</em>':escapeHtml(v))+'</td>').join('')+'</tr>').join('')}</tbody></table></div>`;}
function dataHeader(){const d=dataState.dataset;return `<div class="panel"><label>数据集 <select id="dataset-choice"><option value="">选择数据集</option>${dataState.list.map(x=>`<option value="${x.id}" ${d?.id===x.id?'selected':''}>${escapeHtml(x.name)} · ${x.row_count} 行 · ${x.status==='DRAFT'?'待确认':'已保存'}</option>`).join('')}</select></label><button id="reload-datasets">刷新列表</button>${d&&!d.readOnly?'<button id="delete-dataset">删除所选数据集</button>':''}${d?`<p>${escapeHtml(d.source_name)} · ${d.row_count} 行 · ${d.columns.length} 字段 · ${escapeHtml(d.created_at)}${d.parent_id?' · 原始版本 #'+d.parent_id:''}</p>`:''}<p id="data-message" role="status"></p></div>`;}
function importPanel(){return `<div class="importgrid"><section class="importbox"><h3>导入 CSV / Excel</h3><p>最多 20 MB、10 万行、100 字段。保留文本和前导零。Excel 数字使用原始数值，日期序列号需明确清洗；不执行公式。</p><input id="data-file" type="file" accept=".csv,.xlsx" aria-label="选择文件"><label>CSV 编码<select id="data-encoding"><option>UTF-8</option><option>GB18030</option></select></label><label>CSV 分隔符<select id="data-delimiter"><option value=",">逗号</option><option value=";">分号</option><option value="tab">制表符</option></select></label>${input('Excel 工作表序号（从 1 开始）','data-sheet','1','number')}<p>默认使用首行作为字段名。</p>${input('NULL 标记（留空表示 CSV 空字段保留为空字符串）','data-null')}<button class="primary" id="upload-data">解析并预览</button></section><section class="importbox"><h3 id="crawl-title">网页数据采集</h3><p>输入公开网页地址，先识别标题并预览数据。支持静态表格、列表和正文；不自动翻页，不支持登录或 JavaScript 动态加载内容。</p>${input('来源网址','crawl-url','','url')}${input('最多采集条数','crawl-target','100','number')}<button id="inspect-crawl">识别标题与数据</button><div id="crawl-preview" role="status"></div><button id="start-crawl" disabled>开始采集</button><button id="refresh-jobs">刷新任务</button><h4>最近三条任务</h4><button id="delete-crawl-jobs">删除选中记录</button><p>仅删除记录，已采集的数据集保留。</p><div id="crawl-jobs" role="status"></div></section></div>`;}
function cleanPanel(){return `<p>先预览影响，再确认生成新版本。解析失败会取消本次操作，原始数据不变。</p><label>清洗操作<select id="clean-operation"><option value="deduplicate">去重</option><option value="fill">补缺失值</option><option value="date">日期格式统一</option><option value="money">金额格式统一</option><option value="split">拆分字段</option><option value="merge">合并字段</option><option value="outlier">异常值标记</option></select></label><label>单个字段<select id="clean-column">${fieldOptions()}</select></label><label>去重／合并字段（按住 Command 多选）<select id="clean-columns" multiple>${fieldOptions()}</select></label><label>去重保留<select id="clean-keep"><option value="first">第一条</option><option value="last">最后一条</option></select></label><label>填充方式<select id="clean-mode"><option value="fixed">固定值</option><option value="mean">均值（估算值，记录在规则中）</option></select></label>${input('固定填充值','clean-value')}<label><input id="clean-empty" type="checkbox"> 同时填充空字符串（默认仅 NULL）</label>${input('原日期格式（使用 uuuu 表示年份）','clean-pattern','uuuu-MM-dd')}${input('目标日期格式','clean-target-pattern','uuuu-MM-dd')}${input('金额小数位（四舍五入，不换算货币）','clean-scale','2','number')}${input('拆分／合并分隔符','clean-separator',',')}${input('新字段名／拆分前缀','clean-target','result')}${input('拆分字段数量','clean-parts','2','number')}<label><input id="clean-remove" type="checkbox"> 拆分／合并后移除原字段</label>${input('异常值最小值（含）','clean-minimum','0')}${input('异常值最大值（含）','clean-maximum','100')}<div class="actions"><button id="preview-clean">预览影响</button><button id="apply-clean" disabled>确认并生成新版本</button></div><pre id="clean-preview" role="status"></pre>`;}
function readRule(){return {operation:$('#clean-operation').value,column:$('#clean-column').value,columns:[...$('#clean-columns').selectedOptions].map(x=>x.value),keep:$('#clean-keep').value,mode:$('#clean-mode').value,value:$('#clean-value').value,includeEmpty:$('#clean-empty').checked,pattern:$('#clean-pattern').value,targetPattern:$('#clean-target-pattern').value,scale:Number($('#clean-scale').value),separator:$('#clean-separator').value,target:$('#clean-target').value,parts:Number($('#clean-parts').value),removeOriginal:$('#clean-remove').checked,minimum:$('#clean-minimum').value,maximum:$('#clean-maximum').value};}
function sqlPanel(){return `<p>生成 MySQL 8 脚本，不连接目标数据库执行。默认使用文本类型避免前导零和精度丢失。导出包含整个数据集，不受当前页限制。</p>${input('目标表名','data-table','dataset_'+dataState.dataset.id)}<label>SQL 类型<select id="data-sql-kind"><option value="create_insert">CREATE TABLE + INSERT</option><option value="create">CREATE TABLE</option><option value="insert">INSERT</option><option value="select">SELECT 全字段查询</option><option value="update">UPDATE 按匹配键更新其他字段</option></select></label><label>UPDATE 匹配键<select id="data-sql-key"><option value="">必须选择</option>${fieldOptions()}</select></label><p>UPDATE 要求目标表匹配键具有唯一约束。关联查询需选择已保存数据集并确认其目标表名与匹配字段；关联表字段名应与该数据集一致。自然语言查询等待 AI 接入。</p><details><summary>SELECT 筛选、分组、排序及关联</summary><label>筛选字段<select id="query-filter"><option value="">不筛选</option>${fieldOptions()}</select></label><label>操作符<select id="query-operator">${['=','<>','>','>=','<','<=','LIKE','IS NULL','IS NOT NULL'].map(x=>'<option>'+escapeHtml(x)+'</option>').join('')}</select></label>${input('筛选值','query-value')}<label>分组字段<select id="query-group"><option value="">不分组</option>${fieldOptions()}</select></label><label>聚合函数<select id="query-aggregate"><option value="">不聚合</option>${['COUNT','SUM','AVG','MIN','MAX'].map(x=>'<option>'+x+'</option>').join('')}</select></label><label>聚合字段<select id="query-metric">${fieldOptions()}</select></label><label>排序字段<select id="query-order"><option value="">不排序</option>${fieldOptions()}</select></label><label><input type="checkbox" id="query-desc">降序</label><label>关联数据集<select id="query-join"><option value="">不关联</option>${dataState.list.filter(x=>x.status==='READY'&&x.id!==dataState.dataset.id).map(x=>'<option value="'+x.id+'">'+escapeHtml(x.name)+'</option>').join('')}</select></label>${input('关联目标表名','query-join-table')}${input('本数据集匹配字段名','query-join-left')}${input('关联数据集匹配字段名','query-join-right')}<label>关联方式<select id="query-join-type"><option>LEFT</option><option>INNER</option></select></label></details><div class="tablewrap"><table><thead><tr><th>原字段</th><th>SQL 字段名</th><th>类型</th><th>允许 NULL</th><th>主键</th></tr></thead><tbody>${dataState.dataset.columns.map((c,i)=>`<tr class="sql-field"><td>${escapeHtml(c)}</td><td><input aria-label="SQL 字段名 ${i+1}" class="field-name" value="${escapeHtml(/^[\p{L}_][\p{L}\p{N}_]{0,63}$/u.test(c)?c:'column_'+(i+1))}"></td><td><input aria-label="SQL 类型 ${i+1}" class="field-type" value="LONGTEXT" list="sql-types"></td><td><input aria-label="允许 NULL ${i+1}" class="field-null" type="checkbox" checked></td><td><input aria-label="主键 ${i+1}" class="field-primary" type="checkbox"></td></tr>`).join('')}</tbody></table></div><datalist id="sql-types">${['LONGTEXT','TEXT','VARCHAR(255)','BIGINT','DECIMAL(20,6)','DATE','DATETIME'].map(t=>'<option>'+t+'</option>').join('')}</datalist><button id="generate-data-sql">校验并生成完整脚本</button><button id="save-data-sql" disabled>下载完整 SQL</button><p id="sql-size"></p><pre id="data-sql-preview"></pre>`;}
function visualPanel(){return `<p>ECharts 根据当前数据自动绘图。可切换图形及字段；不会修改原始数据。</p><div class="chart-controls"><label>横轴／类别<select id="visual-x">${fieldOptions()}</select></label><label>数值字段<select id="visual-y">${fieldOptions()}</select></label><label>图形<select id="visual-kind"><option value="bar">柱状图</option><option value="line">折线图</option><option value="rose">玫瑰图</option><option value="boxplot">箱形图</option><option value="scatter">散点图</option><option value="heatmap">热力图</option></select></label></div><p id="chart-status" role="status">正在读取图表数据…</p><div id="data-chart" role="img" aria-label="当前数据的图表"></div><button id="download-csv">下载完整 CSV</button>`;}
render = function(){
 const d=dataState.dataset;dataState.sql=null;dataState.cleanRule=null;
 $('#work').classList.toggle('workspace-home',stage===-1);
 let content=dataHeader();
 if(stage===-1)content+='<h3>真实数据工作台</h3><p>从导入开始，预览确认后保存数据集，再清洗、导出或查看可视化图表。数据按当前账号隔离。</p><button data-stage="0">开始导入</button>';
 else if(stage===0)content+=importPanel();
 else if(!d)content+='<p>请先选择或导入数据集。</p><button data-stage="0">导入数据</button>';
 else if(stage===1){content+=realTable(d)+`<p>第 ${d.page+1} / ${Math.max(1,Math.ceil(d.row_count/50))} 页 · 共 ${d.row_count} 行</p><button id="data-prev" ${d.page===0?'disabled':''}>上一页</button><button id="data-next" ${(d.page+1)*50>=d.row_count?'disabled':''}>下一页</button>`;if(d.status==='DRAFT')content+='<p>尚未确认导入；请核对字段及原始值。</p><button id="confirm-import">确认保存数据集</button>';if(!d.readOnly&&d.status==='READY')content+=teamSharePanel();}
 else if(d.status!=='READY')content+='<p>请先到数据预览中确认导入。</p><button data-stage="1">检查导入</button>';
 else if(stage===2)content+=d.readOnly?'<p>此数据集为团队只读共享，只有所有者可生成清洗版本。</p>':cleanPanel();else if(stage===3)content+=sqlPanel();else if(stage===4)content+=visualPanel();
 if(stage===-1)content+=teamPanel();$('#work').innerHTML=shell(content);$('#work .ai .muted').textContent='元宝搜索提供资料，DeepSeek 提供建议；数据清洗和 SQL 都需检查确认。';$('#work .ai .bubble').textContent='当前数据工作流';$('#work .ai h3').textContent='检索与 AI 建议';$('#work #answer').textContent='生成前确认外发内容；没有配置凭据时会明确报错。';$('#work .ai textarea').placeholder='说明查询、清洗或可视化目标…';$('#work .ai [data-ask]').setAttribute('aria-label','生成建议');dataState.ai=null;setupWorkbench();observeEntrance();
};
async function refreshDatasets(){dataState.list=await dataRequest();render();}
async function selectDataset(id,page=0){dataState.dataset=await dataRequest('/'+id+'?page='+page);dataState.page=page;render();}
function sqlOptions(){return {table:$('#data-table').value,kind:$('#data-sql-kind').value,key:$('#data-sql-key').value,filterColumn:$('#query-filter').value,operator:$('#query-operator').value,filterValue:$('#query-value').value,groupBy:$('#query-group').value,aggregate:$('#query-aggregate').value,aggregateColumn:$('#query-metric').value,orderBy:$('#query-order').value,descending:$('#query-desc').checked,joinDataset:$('#query-join').value?Number($('#query-join').value):null,joinTable:$('#query-join-table').value,joinLeft:$('#query-join-left').value,joinRight:$('#query-join-right').value,joinType:$('#query-join-type').value,fields:[...document.querySelectorAll('.sql-field')].map(row=>({name:row.querySelector('.field-name').value,type:row.querySelector('.field-type').value,nullable:row.querySelector('.field-null').checked,primary:row.querySelector('.field-primary').checked}))};}
document.addEventListener('change',async event=>{try{
 if(event.target.id==='dataset-choice'&&event.target.value){stage=1;await selectDataset(event.target.value);}
 if(event.target.closest('#work')&&event.target.id.startsWith('clean-')){dataState.cleanRule=null;const b=$('#apply-clean');if(b)b.disabled=true;}
 if(event.target.id.startsWith('query-')||event.target.closest('.sql-field')||['data-table','data-sql-kind','data-sql-key'].includes(event.target.id)){dataState.sql=null;$('#save-data-sql').disabled=true;}
}catch(e){toast(e.message);}});
document.addEventListener('click',async event=>{
 const b=event.target.closest('button');if(!b)return;
 if(b.hasAttribute('data-ask')&&b.closest('#work')){event.stopImmediatePropagation();await requestAdvice(b);return;}
 const id=b.id,d=dataState.dataset;
 const actions=['reload-datasets','upload-data','data-prev','data-next','confirm-import','delete-dataset','preview-clean','apply-clean','generate-data-sql','save-data-sql','download-csv'];
 if(!actions.includes(id))return;
 b.disabled=true;
 try{
  if(id==='reload-datasets')await refreshDatasets();
  if(id==='upload-data'){
   const file=$('#data-file').files[0];if(!file)throw new Error('请选择文件');if(file.size>20*1024*1024)throw new Error('文件超过 20 MB');
   const form=new FormData();form.append('file',file);form.append('encoding',$('#data-encoding').value);form.append('delimiter',$('#data-delimiter').value==='tab'?'\t':$('#data-delimiter').value);form.append('sheet',String(Number($('#data-sheet').value)-1));form.append('header','true');form.append('nullToken',$('#data-null').value);
   $('#data-message').textContent='正在解析并暂存，请等待；失败会显示原因。';const uploaded=await dataRequest('/upload',form);dataState.list=await dataRequest();stage=1;await selectDataset(uploaded.id);
  }
  if(id==='data-prev'||id==='data-next')await selectDataset(d.id,d.page+(id==='data-prev'?-1:1));
  if(id==='confirm-import'){await dataRequest('/'+d.id+'/confirm',{});dataState.list=await dataRequest();await selectDataset(d.id);toast('导入已确认');}
  if(id==='delete-dataset'&&confirm('确定永久删除当前数据集？其他已保存版本不受影响。')){await dataRequest('/'+d.id,undefined,'DELETE');dataState.dataset=null;await refreshDatasets();}
  if(id==='preview-clean'){const rule=readRule();const result=await dataRequest('/'+d.id+'/clean/preview',rule);dataState.cleanRule=JSON.stringify(rule);showCleanPreview(result);$('#apply-clean').disabled=false;}
  if(id==='apply-clean'){const rule=readRule();if(JSON.stringify(rule)!==dataState.cleanRule)throw new Error('规则已变化，请重新预览');const result=await dataRequest('/'+d.id+'/clean/apply',rule);dataState.list=await dataRequest();stage=1;await selectDataset(result.id);toast('已保存清洗版本，原始数据保留');}
  if(id==='generate-data-sql'){dataState.sql=await dataRequest('/'+d.id+'/sql',sqlOptions(),undefined,true);$('#data-sql-preview').textContent=await dataState.sql.slice(0,100000).text();$('#sql-size').textContent='完整脚本 '+dataState.sql.size+' 字节；预览最多 100 KB；未执行 SQL。';$('#save-data-sql').disabled=false;}
  if(id==='save-data-sql'&&dataState.sql)saveFile(dataState.sql,'dataset-'+d.id+'.sql');
  if(id==='download-csv')saveFile(await dataRequest('/'+d.id+'/csv',undefined,undefined,true),'dataset-'+d.id+'.csv');
 }catch(e){toast(e.message);const message=$('#data-message');if(message)message.textContent=e.message;}finally{if(b.isConnected)b.disabled=false;}
},true);
const oldShowWorkspace=showWorkspace;
showWorkspace=function(i=0){oldShowWorkspace(i);queueMicrotask(firstWorkflow);if(!dataState.list.length)dataRequest().then(list=>{dataState.list=list;return dataRequest('/teams');}).then(teams=>{dataState.teams=teams;if(!$('#work').hidden)render();}).catch(e=>toast(e.message));};
const originalLogout=$('#user-logout').onclick;
$('#user-logout').onclick=async()=>{await originalLogout();if(!demoLoggedIn){dataState.dataset=null;dataState.list=[];dataState.sql=null;dataState.cleanRule=null;}};
function teamOptions(){return dataState.teams.map(t=>`<option value="${t.id}">${escapeHtml(t.name)}</option>`).join('');}
function teamPanel(){return `<section class="panel"><h3>团队 · 独立账号、只读共享</h3><p>团队创建者可添加已注册成员。数据所有者选择共享的版本；成员只能查看、下载，不能删除或清洗原数据。</p>${input('新团队名称','new-team-name')}<button id="create-team">创建团队</button><label>选择团队<select id="manage-team">${teamOptions()}</select></label>${input('成员注册账号（邮箱／手机号）','member-account')}<button id="add-member">添加成员</button><button id="list-members">查看成员</button><div id="members"></div></section>`;}
function teamSharePanel(){return `<section class="panel"><label>共享到团队<select id="share-team">${teamOptions()}</select></label><button id="share-dataset">只读共享此版本</button><button id="unshare-dataset">撤销此团队的共享</button></section>`;}
async function loadJobs(){const target=$('#crawl-jobs');if(!target)return;const jobs=await dataRequest('/crawl');if(!target.isConnected)return;const selected=new Set([...target.querySelectorAll('[data-job-select]:checked')].map(x=>x.value));target.innerHTML=jobs.slice(0,3).map(j=>`<article><label><input type="checkbox" data-job-select value="${j.id}" ${selected.has(String(j.id))?'checked':''} ${['QUEUED','RUNNING'].includes(j.status)?'disabled':''}>选择此记录</label><b>任务 #${j.id} · ${escapeHtml(j.status)}</b><p>目标 ${j.target_count} 条，实际 ${j.actual_count} 条</p><p>${escapeHtml(j.reason||'正在等待或读取来源，没有可靠总量，不显示虚假进度百分比。')}</p>${j.dataset_id?`<button data-open-dataset="${j.dataset_id}">查看已保存结果</button>`:''}${['QUEUED','RUNNING'].includes(j.status)?`<button data-cancel-job="${j.id}">取消</button>`:''}</article>`).join('')||'暂无采集任务';}
document.addEventListener('click',async event=>{
 const b=event.target.closest('button');if(!b)return;const id=b.id;
 const actions=['create-team','add-member','list-members','share-dataset','unshare-dataset','inspect-crawl','start-crawl','refresh-jobs','delete-crawl-jobs'];
 if(!actions.includes(id)&&!b.dataset.removeMember&&!b.dataset.cancelJob&&!b.dataset.openDataset)return;
 b.disabled=true;
 try{
  if(id==='create-team'){await dataRequest('/teams',{name:$('#new-team-name').value});dataState.teams=await dataRequest('/teams');render();toast('团队已创建');}
  if(id==='add-member'){if(!$('#manage-team').value)throw new Error('请选择团队');await dataRequest('/teams/'+$('#manage-team').value+'/members',{account:$('#member-account').value});toast('成员已添加');}
  if(id==='list-members'){const team=$('#manage-team').value;if(!team)throw new Error('请选择团队');const members=await dataRequest('/teams/'+team+'/members');$('#members').innerHTML=members.map(m=>`<p>${escapeHtml(m.username)} · ${escapeHtml(m.account)} <button data-team="${team}" data-remove-member="${m.id}">移除成员</button></p>`).join('');}
  if(b.dataset.removeMember&&confirm('撤销此成员对团队共享数据的访问权限？')){await dataRequest('/teams/'+b.dataset.team+'/members/'+b.dataset.removeMember,undefined,'DELETE');b.closest('p').remove();}
  if(id==='share-dataset'||id==='unshare-dataset'){const team=$('#share-team').value;if(!team)throw new Error('请先创建或加入团队');const result=await dataRequest('/teams/'+team+'/datasets/'+dataState.dataset.id,id==='share-dataset'?{}:undefined,id==='share-dataset'?'POST':'DELETE');toast(result.message);}
  if(id==='inspect-crawl'){
   const url=$('#crawl-url').value.trim();if(!url)throw new Error('请输入网址');
   dataState.crawlUrl=null;$('#start-crawl').disabled=true;$('#crawl-preview').textContent='正在识别页面标题和数据…';
   const page=await dataRequest('/crawl/inspect',{url});
   if(!$('#crawl-url')||$('#crawl-url').value.trim()!==url)return;
   dataState.crawlUrl=url;$('#crawl-title').textContent=page.title;$('#crawl-preview').innerHTML='<p>'+escapeHtml(page.kind)+' · 识别到 '+page.count+' 条，预览前 5 条</p>'+'<fieldset id="crawl-fields"><legend>选择需要采集的字段（可多选）</legend>'+page.columns.map(c=>'<label><input type="checkbox" name="crawl-field" value="'+escapeHtml(c)+'" checked>'+escapeHtml(c)+'</label>').join('')+'</fieldset>'+realTable(page);$('#start-crawl').disabled=false;
  }
  if(id==='start-crawl'){if(dataState.crawlUrl!==$('#crawl-url').value.trim())throw new Error('网址已变化，请先重新识别');await dataRequest('/crawl',{url:$('#crawl-url').value,target:Number($('#crawl-target').value),fields:[...document.querySelectorAll('[name="crawl-field"]:checked')].map(x=>x.value)});await loadJobs();}
  if(id==='delete-crawl-jobs'){
   const ids=[...document.querySelectorAll('[data-job-select]:checked')].map(x=>x.value);if(!ids.length)throw new Error('请先勾选需要删除的记录');
   if(!confirm('删除选中的 '+ids.length+' 条任务记录？已采集数据保留。'))return;
   try{for(const id of ids)await dataRequest('/crawl/'+id,undefined,'DELETE');}finally{await loadJobs();}
  }
  if(id==='refresh-jobs')await loadJobs();
  if(b.dataset.cancelJob){await dataRequest('/crawl/'+b.dataset.cancelJob+'/cancel',{});await loadJobs();}
  if(b.dataset.openDataset){dataState.list=await dataRequest();stage=1;await selectDataset(b.dataset.openDataset);}
 }catch(e){toast(e.message);if(id==='inspect-crawl'&&$('#crawl-preview'))$('#crawl-preview').textContent=e.message;if($('#data-message'))$('#data-message').textContent=e.message;}finally{if(b.isConnected)b.disabled=false;}
},true);
setInterval(()=>{if(!document.hidden&&!$('#work').hidden&&stage===0)loadJobs().catch(()=>{});},5000);
function sourceLinks(pages){return (pages||[]).map((p,i)=>`<article><p>[${i+1}] ${escapeHtml(p.readStatus||'检索摘要')}</p><a target="_blank" rel="noopener noreferrer" href="${escapeHtml(p.url)}">${escapeHtml(p.title)}</a><p>${escapeHtml(p.summary)}</p><small>${escapeHtml(p.date)}</small></article>`).join('');}
async function requestAdvice(button){
 button.disabled=true;const answer=$('#work #answer'),dataset=dataState.dataset;
 try{
  const question=button.closest('.ai').querySelector('textarea').value;
  const purpose=dataState.aiMode==='search'?'search':inferAiPurpose(question,stage);
  if(purpose==='search'){answer.textContent='正在检索并读取网页，整理回答…';const result=await dataRequest('/search/answer',{query:question});if(answer.isConnected)answer.innerHTML='<p style="white-space:pre-wrap">'+escapeHtml(result.answer||result.message)+'</p>'+sourceLinks(result.pages);return;}
  if(!dataset||dataset.status!=='READY')throw new Error('请先选择已确认的数据集');
  if(dataset.readOnly)throw new Error('共享数据只读；只有所有者可以将其发送给外部模型');
  const request={purpose,question,useSearch:false,consent:true,includeSamples:true};

  answer.textContent='正在请求服务，数据尚未修改…';
  const result=await dataRequest('/'+dataset.id+'/ai',request);
  if(!answer.isConnected||dataState.dataset?.id!==dataset.id)return;
  dataState.ai={...result,datasetId:dataset.id};
  answer.innerHTML='<p>'+escapeHtml(result.explanation)+'</p>'+(result.needsClarification?'<p>需要补充：'+escapeHtml(result.needsClarification)+'</p>':'')+(result.sqlPreview?'<pre>'+escapeHtml(result.sqlPreview)+'</pre><button id="ai-download-sql">下载已校验的完整 SQL</button>':'')+(result.rule?'<pre>'+escapeHtml(JSON.stringify(result.preview,null,2))+'</pre><button id="ai-apply-clean">确认生成清洗版本</button>':'')+(result.reference?'<pre>'+escapeHtml(result.reference)+'</pre><button id="ai-download-reference">下载参考内容</button>':'')+'<p>'+escapeHtml(result.validation||'参考建议，未执行')+'</p>'+sourceLinks(result.sources);
 }catch(e){toast(e.message);if(answer.isConnected)answer.textContent=e.message;}finally{if(button.isConnected)button.disabled=false;}
}
document.addEventListener('click',async event=>{
 const b=event.target.closest('button');if(!b||!['ai-download-sql','ai-apply-clean','ai-download-reference'].includes(b.id))return;
 const ai=dataState.ai;if(!ai||dataState.dataset?.id!==ai.datasetId)return;b.disabled=true;
 try{
  if(b.id==='ai-download-sql')saveFile(await dataRequest('/'+ai.datasetId+'/sql',ai.options,undefined,true),'dataset-'+ai.datasetId+'-ai.sql');
  if(b.id==='ai-apply-clean'&&confirm('按刚才预览的规则生成新版本？原始数据将保留。')){const result=await dataRequest('/'+ai.datasetId+'/clean/apply',ai.rule);dataState.list=await dataRequest();stage=1;await selectDataset(result.id);}
  if(b.id==='ai-download-reference')saveFile(new Blob([ai.reference],{type:'text/plain;charset=utf-8'}),'ai-visualization-reference.txt');
 }catch(e){toast(e.message);}finally{if(b.isConnected)b.disabled=false;}
},true);

// Restore only after all workspace overrides and handlers are installed.
restoreSession();

document.addEventListener('input',event=>{if(event.target.id==='crawl-url'){dataState.crawlUrl=null;$('#start-crawl').disabled=true;$('#crawl-title').textContent='网页数据采集';$('#crawl-preview').textContent='网址已更新，请先识别标题与数据。';}});

document.addEventListener('change',event=>{if(event.target.name==='crawl-field')$('#start-crawl').disabled=!document.querySelector('[name="crawl-field"]:checked');});
