/* Real data workspace. The landing-page sample remains explicitly labelled as a demo. */
const dataState = { dataset: null, list: [], teams: [], page: 0, sql: null };
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
function dataHeader(){const d=dataState.dataset;return `<div class="panel"><label>数据集 <select id="dataset-choice"><option value="">选择数据集</option>${dataState.list.map(x=>`<option value="${x.id}" ${d?.id===x.id?'selected':''}>${escapeHtml(x.name)} · ${x.row_count} 行 · ${x.status==='DRAFT'?'待确认':'已保存'}</option>`).join('')}</select></label><button id="reload-datasets">刷新列表</button>${d&&!d.readOnly?'<button id="rename-dataset">重命名</button><button id="delete-dataset">删除所选数据集</button>':''}${d?`<p>${escapeHtml(d.source_name)} · ${d.row_count} 行 · ${d.columns.length} 字段 · ${escapeHtml(d.created_at)}${d.parent_id?' · 原始版本 #'+d.parent_id:''}</p>`:''}<p id="data-message" role="status"></p></div>`;}
function importPanel(){return `<div class="importgrid"><section class="importbox"><h3>导入 CSV / Excel</h3><p>最多 20 MB、10 万行、100 字段。保留文本和前导零。Excel 数字使用原始数值，日期序列号需明确清洗；不执行公式。</p><input id="data-file" type="file" accept=".csv,.xlsx" aria-label="选择文件"><label>CSV 编码<select id="data-encoding"><option>UTF-8</option><option>GB18030</option></select></label><label>CSV 分隔符<select id="data-delimiter"><option value=",">逗号</option><option value=";">分号</option><option value="tab">制表符</option></select></label>${input('Excel 工作表序号（从 1 开始）','data-sheet','1','number')}<p>默认使用首行作为字段名。</p>${input('NULL 标记（留空表示 CSV 空字段保留为空字符串）','data-null')}<button class="primary" id="upload-data">解析并预览</button></section><section class="importbox"><h3 id="crawl-title">网页数据采集</h3><p>输入公开网页地址，先识别标题并预览数据。支持静态表格、列表和正文；不自动翻页，不支持登录或 JavaScript 动态加载内容。</p>${input('来源网址','crawl-url','','url')}${input('最多采集条数','crawl-target','100','number')}<button id="inspect-crawl">识别标题与数据</button><div id="crawl-preview" role="status"></div><button id="start-crawl" disabled>开始采集</button><button id="refresh-jobs">刷新任务</button><h4>最近三条任务</h4><button id="delete-crawl-jobs">删除选中记录</button><p>仅删除记录，已采集的数据集保留。</p><div id="crawl-jobs" role="status"></div></section></div>`;}
function cleanPanel(){return `<section class="panel"><h3>DeepSeek 智能清洗</h3><p>一次识别缺失值、异常值、日期和金额格式，预览整个数据集的处理结果，确认后保存新版本。</p><p>点击识别会向 DeepSeek 发送字段名、全量统计及每列最多 12 个不同样例（每格最多 120 字符）。无法可靠判断的值会保留并说明，金额不换算币种。</p><button class="primary" id="preview-clean">智能识别并预览</button><button id="apply-clean" disabled>确认保存新版本</button><p id="clean-status" role="status"></p><div id="clean-plan"></div></section><section id="clean-preview" class="panel" role="status"><h3>清洗前后对比</h3><p>识别后展示处理方案、影响行数及样例。原始数据始终保留。</p></section>`;}
function sqlPanel(){return `<p>选择语句类型，点击获取即可查看并下载完整 SQL。请先在本地创建并选择数据库；脚本不会自动执行。</p><div class="sql-toolbar"><label>SQL 语句<select id="data-sql-kind"><option value="create">CREATE · 建表</option><option value="update">UPDATE · 更新</option><option value="insert">INSERT · 导入</option><option value="select">SELECT · 查询</option></select></label>${input('目标表名','data-table','dataset_'+dataState.dataset.id)}<label id="sql-key-label" hidden>UPDATE 匹配字段<input id="data-sql-key" placeholder="填写具有唯一约束的原字段名"></label><button class="primary" id="generate-data-sql">获取 SQL</button><button id="save-data-sql" disabled>下载 .sql</button></div><p id="sql-key-help" hidden>可用字段：${dataState.dataset.columns.map(escapeHtml).join('、')}。匹配字段须在目标数据库有唯一约束；空值或重复值会阻止生成。</p><p>字段默认使用文本类型，保留前导零和原始精度。SELECT 查询全部字段，INSERT / UPDATE 包含整个数据集。</p><p id="sql-size" role="status"></p><pre id="data-sql-preview">选择语句类型后点击“获取 SQL”。</pre>`;}
function visualPanel(){return `<p>ECharts 根据当前数据自动绘图。可切换图形及字段；不会修改原始数据。</p><div class="chart-controls"><label>横轴／类别<select id="visual-x">${fieldOptions()}</select></label><label>数值字段<select id="visual-y">${fieldOptions()}</select></label><label>图形<select id="visual-kind"><option value="bar">柱状图</option><option value="line">折线图</option><option value="rose">玫瑰图</option><option value="boxplot">箱形图</option><option value="scatter">散点图</option><option value="heatmap">热力图</option></select></label></div><p id="chart-status" role="status">正在读取图表数据…</p><div id="data-chart" role="img" aria-label="当前数据的图表"></div><button id="download-csv">下载完整 CSV</button>`;}
render = function(){
 const d=dataState.dataset;dataState.sql=null;dataState.autoClean=null;
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
async function refreshDatasets(){
 const user=currentUser?.id;
 const [list,teams]=await Promise.all([dataRequest(),dataRequest('/teams')]);
 if(!user||currentUser?.id!==user)return;
 dataState.list=list;dataState.teams=teams;
 if(dataState.dataset&&!list.some(d=>d.id===dataState.dataset.id))dataState.dataset=null;
 render();
}
async function selectDataset(id,page=0){dataState.dataset=await dataRequest('/'+id+'?page='+page);dataState.page=page;render();}
function sqlOptions(){const used=new Set();return {table:$('#data-table').value,kind:$('#data-sql-kind').value,key:$('#data-sql-key').value,fields:dataState.dataset.columns.map((c,i)=>{let name=/^[\p{L}_][\p{L}\p{N}_]{0,63}$/u.test(c)?c:'column_'+(i+1);while(used.has(name.toLowerCase()))name='_'+name.slice(0,62);used.add(name.toLowerCase());return {name,type:'LONGTEXT',nullable:true,primary:false};})};}
document.addEventListener('change',async event=>{try{
 if(event.target.id==='data-sql-kind'){$('#sql-key-label').hidden=$('#sql-key-help').hidden=event.target.value!=='update';$('#data-sql-preview').textContent='点击“获取 SQL”查看当前类型。';$('#sql-size').textContent='';}
 if(event.target.id==='dataset-choice'&&event.target.value){stage=1;await selectDataset(event.target.value);}
 if(event.target.id.startsWith('query-')||event.target.closest('.sql-field')||['data-table','data-sql-kind','data-sql-key'].includes(event.target.id)){dataState.sql=null;$('#save-data-sql').disabled=true;}
}catch(e){toast(e.message);}});
document.addEventListener('click',async event=>{
 const b=event.target.closest('button');if(!b)return;
 if(b.hasAttribute('data-ask')&&b.closest('#work')){event.stopImmediatePropagation();await requestAdvice(b);return;}
 const id=b.id,d=dataState.dataset;
 const actions=['reload-datasets','upload-data','data-prev','data-next','confirm-import','rename-dataset','delete-dataset','preview-clean','apply-clean','generate-data-sql','save-data-sql','download-csv'];
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
  if(id==='rename-dataset'){
   const name=prompt('输入新的数据集名称',d.name)?.trim();
   if(name===undefined||name===d.name)return;
   if(!name||Array.from(name).length>255)throw Error('名称须为 1 至 255 个字符');
   await dataRequest('/'+d.id+'/rename',{name});
   dataState.list=await dataRequest();await selectDataset(d.id,d.page);toast('数据集已重命名');
  }
  if(id==='delete-dataset'&&confirm('确定永久删除当前数据集？其他已保存版本不受影响。')){await dataRequest('/'+d.id,undefined,'DELETE');dataState.dataset=null;await refreshDatasets();}
  if(id==='preview-clean'){
   dataState.autoClean=null;$('#apply-clean').disabled=true;$('#clean-status').textContent='DeepSeek 正在识别并对全量数据预览，请稍候…';
   const result=await dataRequest('/'+d.id+'/ai',{purpose:'clean_auto',question:'识别并处理异常值、缺失值、日期和金额格式不统一，生成覆盖全部字段的清洗方案；不能确定的内容保留并说明。',consent:true,includeSamples:false,useSearch:false});
   if(!b.isConnected||dataState.dataset?.id!==d.id)return;
   $('#clean-status').textContent=result.needsClarification||result.explanation||'全量预览完成，请核对后保存。';
   if(result.plan){dataState.autoClean={datasetId:d.id,plan:result.plan};$('#clean-plan').innerHTML=result.plan.fields.map(f=>`<p><strong>${escapeHtml(f.column)}</strong> · ${escapeHtml({keep:'保留原值',date:'统一日期',money:'统一金额',number:'数值检查'}[f.kind])} · ${f.missing==='drop'?'缺失值所在行将移除':'缺失值保留'}${f.minimum!=null||f.maximum!=null?' · 保留范围 '+escapeHtml(f.minimum??'不限')+' 至 '+escapeHtml(f.maximum??'不限'):''}<br>${escapeHtml(f.reason)}</p>`).join('');showCleanPreview(result.preview);$('#apply-clean').disabled=false;}
  }
  if(id==='apply-clean'){
   const ready=dataState.autoClean;if(!ready||ready.datasetId!==d.id)throw new Error('请先识别并预览');
   const result=await dataRequest('/'+d.id+'/clean/auto/apply',ready.plan);dataState.autoClean=null;dataState.list=await dataRequest();stage=1;await selectDataset(result.id);toast('已保存清洗版本，原始数据保留');
  }
  if(id==='generate-data-sql'){dataState.sql=await dataRequest('/'+d.id+'/sql',sqlOptions(),undefined,true);$('#data-sql-preview').textContent=await dataState.sql.slice(0,100000).text();$('#sql-size').textContent='完整脚本 '+dataState.sql.size+' 字节；预览最多 100 KB；未执行 SQL。';$('#save-data-sql').disabled=false;}
  if(id==='save-data-sql'&&dataState.sql)saveFile(dataState.sql,'dataset-'+d.id+'.sql');
  if(id==='download-csv')saveFile(await dataRequest('/'+d.id+'/csv',undefined,undefined,true),'dataset-'+d.id+'.csv');
 }catch(e){toast(e.message);if(id==='preview-clean'&&$('#clean-status'))$('#clean-status').textContent=e.message;const message=$('#data-message');if(message)message.textContent=e.message;}finally{if(b.isConnected)b.disabled=false;}
},true);
const oldShowWorkspace=showWorkspace;
showWorkspace=function(i=0){oldShowWorkspace(i);queueMicrotask(firstWorkflow);if(i===-1||!dataState.list.length)refreshDatasets().catch(e=>toast(e.message));};
const originalLogout=$('#user-logout').onclick;
$('#user-logout').onclick=async()=>{await originalLogout();if(!demoLoggedIn){dataState.dataset=null;dataState.list=[];dataState.teams=[];dataState.sql=null;dataState.autoClean=null;}};
function teamOptions(owned=false){return dataState.teams.filter(t=>!owned||Number(t.owner_id)===Number(currentUser?.id)).map(t=>`<option value="${t.id}">${escapeHtml(t.name)}</option>`).join('');}
function teamPanel(){const managed=dataState.teams.filter(t=>Number(t.owner_id)===Number(currentUser?.id));return `<section class="panel"><h3>团队与成员</h3><p>你是自己创建团队的管理员；加入其他团队时为成员，各团队身份互不影响。</p><p>${dataState.teams.map(t=>escapeHtml(t.name)+' · '+(Number(t.owner_id)===Number(currentUser?.id)?'管理员':'成员')).join('；')||'尚未创建或加入团队，创建后你将成为该团队管理员。'}</p>${dataState.teams.map(t=>`<button data-view-team="${t.id}">查看 ${escapeHtml(t.name)}</button>`).join('')}<div id="team-details" aria-live="polite"></div>${input('新团队名称','new-team-name')}<button id="create-team">创建团队</button>${managed.length?`<label>管理我的团队<select id="manage-team">${teamOptions(true)}</select></label>${input('成员已注册邮箱／手机号','member-account')}<button id="add-member">添加成员</button><button id="list-members">查看成员</button><div id="members" role="status"></div>`:''}</section>`;}
function teamSharePanel(){return `<section class="panel"><label>共享到团队<select id="share-team">${teamOptions()}</select></label><button id="share-dataset">只读共享此版本</button><button id="unshare-dataset">撤销此团队的共享</button></section>`;}
const pendingCrawlJobs=new Set();
function notifyCrawlCompletion(jobs){
 for(const job of jobs){
  const id=String(job.id);
  if(['QUEUED','RUNNING'].includes(job.status)){pendingCrawlJobs.add(id);continue;}
  if(!pendingCrawlJobs.delete(id))continue;
  if(job.status==='COMPLETED')toast(`数据已爬取完成，共 ${job.actual_count} 条，可点击“查看已保存结果”。`);
  else if(job.status==='PARTIAL')toast(`数据采集已结束，共 ${job.actual_count} 条，未达到目标数量，可查看已保存结果。`);
 }
}
async function loadJobs(){const target=$('#crawl-jobs');if(!target)return;const jobs=await dataRequest('/crawl');notifyCrawlCompletion(jobs);if(!target.isConnected)return;const selected=new Set([...target.querySelectorAll('[data-job-select]:checked')].map(x=>x.value));target.innerHTML=jobs.slice(0,3).map(j=>`<article><label><input type="checkbox" data-job-select value="${j.id}" ${selected.has(String(j.id))?'checked':''} ${['QUEUED','RUNNING'].includes(j.status)?'disabled':''}>选择此记录</label><b>任务 #${j.id} · ${escapeHtml(j.status)}</b><p>目标 ${j.target_count} 条，实际 ${j.actual_count} 条</p><p>${escapeHtml(j.reason||'正在等待或读取来源，没有可靠总量，不显示虚假进度百分比。')}</p>${j.dataset_id?`<button data-open-dataset="${j.dataset_id}">查看已保存结果</button>`:''}${['QUEUED','RUNNING'].includes(j.status)?`<button data-cancel-job="${j.id}">取消</button>`:''}</article>`).join('')||'暂无采集任务';}
document.addEventListener('click',async event=>{
 const b=event.target.closest('button');if(!b)return;const id=b.id;
 const actions=['create-team','add-member','list-members','share-dataset','unshare-dataset','inspect-crawl','start-crawl','refresh-jobs','delete-crawl-jobs'];
 if(!actions.includes(id)&&!b.dataset.viewTeam&&!b.dataset.removeMember&&!b.dataset.cancelJob&&!b.dataset.openDataset)return;
 b.disabled=true;
 try{
  if(b.dataset.viewTeam){
   const panel=$('#team-details'),team=b.dataset.viewTeam;panel.dataset.team=team;
   panel.textContent='正在加载团队信息…';
   try{
    const info=await dataRequest('/teams/'+team);
    if(panel.isConnected&&panel.dataset.team===team)panel.innerHTML=`<h4>${escapeHtml(info.name)}</h4><p>管理员：${escapeHtml(info.administrator)}</p><p>成员：${info.members.map(escapeHtml).join('、')}</p><h4>团队共享数据集</h4>${info.datasets.map(d=>`<p><button data-open-dataset="${d.id}">${escapeHtml(d.name)}</button> · ${d.row_count} 行</p>`).join('')||'<p>暂无已共享的数据集。</p>'}`;
   }catch(e){if(panel.isConnected&&panel.dataset.team===team)panel.textContent=e.message;}
  }
  if(id==='create-team'){await dataRequest('/teams',{name:$('#new-team-name').value});dataState.teams=await dataRequest('/teams');render();toast('团队已创建');}
  if(id==='add-member'){if(!$('#manage-team').value)throw new Error('请选择团队');await dataRequest('/teams/'+$('#manage-team').value+'/members',{account:$('#member-account').value});toast('成员已添加');}
  if(id==='list-members'){const team=$('#manage-team').value;if(!team)throw new Error('请选择团队');const members=await dataRequest('/teams/'+team+'/members');$('#members').innerHTML=members.map(m=>`<p>${escapeHtml(m.username)} · ${escapeHtml(m.account)} <button data-team="${team}" data-remove-member="${m.id}">移除成员</button></p>`).join('')||'<p>暂无成员</p>';}
  if(b.dataset.removeMember&&confirm('撤销此成员对团队共享数据的访问权限？')){await dataRequest('/teams/'+b.dataset.team+'/members/'+b.dataset.removeMember,undefined,'DELETE');b.closest('p').remove();}
  if(id==='share-dataset'||id==='unshare-dataset'){const team=$('#share-team').value;if(!team)throw new Error('请先创建或加入团队');const result=await dataRequest('/teams/'+team+'/datasets/'+dataState.dataset.id,id==='share-dataset'?{}:undefined,id==='share-dataset'?'POST':'DELETE');toast(result.message);}
  if(id==='inspect-crawl'){
   const url=$('#crawl-url').value.trim();if(!url)throw new Error('请输入网址');
   dataState.crawlUrl=null;$('#start-crawl').disabled=true;$('#crawl-preview').textContent='正在识别页面标题和数据…';
   const page=await dataRequest('/crawl/inspect',{url});
   if(!$('#crawl-url')||$('#crawl-url').value.trim()!==url)return;
   dataState.crawlUrl=url;$('#crawl-title').textContent=page.title;$('#crawl-preview').innerHTML='<p>'+escapeHtml(page.kind)+' · 识别到 '+page.count+' 条，预览前 5 条</p>'+'<fieldset id="crawl-fields"><legend>选择需要采集的字段（可多选）</legend>'+page.columns.map(c=>'<label><input type="checkbox" name="crawl-field" value="'+escapeHtml(c)+'" checked>'+escapeHtml(c)+'</label>').join('')+'</fieldset>'+realTable(page);$('#start-crawl').disabled=false;
  }
  if(id==='start-crawl'){if(dataState.crawlUrl!==$('#crawl-url').value.trim())throw new Error('网址已变化，请先重新识别');const job=await dataRequest('/crawl',{url:$('#crawl-url').value,target:Number($('#crawl-target').value),fields:[...document.querySelectorAll('[name="crawl-field"]:checked')].map(x=>x.value)});pendingCrawlJobs.add(String(job.id));await loadJobs();}
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
const adviceResults=new WeakMap();
document.addEventListener('keydown',event=>{
 if(!event.target.matches('#work .ai textarea')||event.key!=='Enter'||event.shiftKey||event.isComposing||event.keyCode===229)return;
 event.preventDefault();
 if(!event.repeat)event.target.closest('.ai').querySelector('[data-ask]').click();
});
async function requestAdvice(button){
 const input=button.closest('.ai').querySelector('textarea'),question=input.value.trim();
 if(!question||button.disabled){input.focus();return;}
 button.disabled=true;input.value='';input.focus();
 const chat=$('#work #answer'),dataset=dataState.dataset;
 if(!chat.children.length)chat.replaceChildren();
 const message=document.createElement('p'),answer=document.createElement('div');
 message.textContent='我：'+question;message.style.whiteSpace='pre-wrap';
 chat.append(message,answer);chat.setAttribute('aria-live','polite');
 try{
  const purpose=dataState.aiMode==='search'?'search':inferAiPurpose(question,stage);
  if(purpose==='search'){answer.textContent='正在检索并读取网页，整理回答…';const result=await dataRequest('/search/answer',{query:question});if(answer.isConnected)answer.innerHTML='<p style="white-space:pre-wrap">'+escapeHtml(result.answer||result.message)+'</p>'+sourceLinks(result.pages);return;}
  if(!dataset||dataset.status!=='READY')throw new Error('请先选择已确认的数据集');
  if(dataset.readOnly)throw new Error('共享数据只读；只有所有者可以将其发送给外部模型');
  const request={purpose,question,useSearch:false,consent:true,includeSamples:true};

  answer.textContent='正在请求服务，数据尚未修改…';
  const result=await dataRequest('/'+dataset.id+'/ai',request);
  if(!answer.isConnected||dataState.dataset?.id!==dataset.id)return;
  adviceResults.set(answer,{...result,datasetId:dataset.id});
  answer.innerHTML='<p>'+escapeHtml(result.explanation)+'</p>'+(result.needsClarification?'<p>需要补充：'+escapeHtml(result.needsClarification)+'</p>':'')+(result.sqlPreview?'<pre>'+escapeHtml(result.sqlPreview)+'</pre><button data-ai-action="ai-download-sql">下载已校验的完整 SQL</button>':'')+(result.rule?'<pre>'+escapeHtml(JSON.stringify(result.preview,null,2))+'</pre><button data-ai-action="ai-apply-clean">确认生成清洗版本</button>':'')+(result.reference?'<pre>'+escapeHtml(result.reference)+'</pre><button data-ai-action="ai-download-reference">下载参考内容</button>':'')+'<p>'+escapeHtml(result.validation||'参考建议，未执行')+'</p>'+sourceLinks(result.sources);
 }catch(e){toast(e.message);if(answer.isConnected)answer.textContent=e.message;}finally{if(button.isConnected)button.disabled=false;}
}
document.addEventListener('click',async event=>{
 const b=event.target.closest('button');if(!b||!['ai-download-sql','ai-apply-clean','ai-download-reference'].includes(b.dataset.aiAction))return;
 const ai=adviceResults.get(b.parentElement);if(!ai||dataState.dataset?.id!==ai.datasetId)return;b.disabled=true;
 try{
  if(b.dataset.aiAction==='ai-download-sql')saveFile(await dataRequest('/'+ai.datasetId+'/sql',ai.options,undefined,true),'dataset-'+ai.datasetId+'-ai.sql');
  if(b.dataset.aiAction==='ai-apply-clean'&&confirm('按刚才预览的规则生成新版本？原始数据将保留。')){const result=await dataRequest('/'+ai.datasetId+'/clean/apply',ai.rule);dataState.list=await dataRequest();stage=1;await selectDataset(result.id);}
  if(b.dataset.aiAction==='ai-download-reference')saveFile(new Blob([ai.reference],{type:'text/plain;charset=utf-8'}),'ai-visualization-reference.txt');
 }catch(e){toast(e.message);}finally{if(b.isConnected)b.disabled=false;}
},true);

// Restore only after all workspace overrides and handlers are installed.
restoreSession();

document.addEventListener('input',event=>{if(event.target.id==='crawl-url'){dataState.crawlUrl=null;$('#start-crawl').disabled=true;$('#crawl-title').textContent='网页数据采集';$('#crawl-preview').textContent='网址已更新，请先识别标题与数据。';}});

document.addEventListener('change',event=>{if(event.target.name==='crawl-field')$('#start-crawl').disabled=!document.querySelector('[name="crawl-field"]:checked');});
