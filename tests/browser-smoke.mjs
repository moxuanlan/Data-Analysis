// Run against the disposable H2 test server described in README, never a user's database.
import {spawn} from 'node:child_process';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import assert from 'node:assert/strict';
const base='http://127.0.0.1:18088';
const directory=await mkdtemp(join(tmpdir(),'datalens-browser-'));
const browser=spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',['--headless=new','--no-first-run','--no-default-browser-check','--remote-debugging-port=0','--user-data-dir='+directory,'about:blank'],{stdio:'ignore'});
const pause=ms=>new Promise(r=>setTimeout(r,ms));
let socket;
try{
 let port;
 for(let i=0;i<100;i++){try{port=(await readFile(join(directory,'DevToolsActivePort'),'utf8')).split('\n')[0];break;}catch{await pause(100);}}
 assert(port,'Chrome failed to start');
 const targets=await (await fetch('http://127.0.0.1:'+port+'/json')).json();
 socket=new WebSocket(targets.find(t=>t.type==='page').webSocketDebuggerUrl);
 await new Promise((r,j)=>{socket.onopen=r;socket.onerror=j;});
 let sequence=0;const pending=new Map();const errors=[];
 socket.onmessage=e=>{const message=JSON.parse(e.data);if(message.id){const call=pending.get(message.id);pending.delete(message.id);message.error?call.reject(Error(JSON.stringify(message.error))):call.resolve(message.result);}else if(message.method==='Fetch.requestPaused'){setTimeout(()=>cdp('Fetch.continueRequest',{requestId:message.params.requestId}),800);}else if(message.method==='Runtime.exceptionThrown'){errors.push(message.params.exceptionDetails.text);}};
 const cdp=(method,params={})=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});socket.send(JSON.stringify({id,method,params}));});
 const evaluate=async expression=>{const result=await cdp('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});if(result.exceptionDetails)throw Error(result.exceptionDetails.exception?.description||result.exceptionDetails.text);return result.result.value;};
 const until=async expression=>{for(let i=0;i<100;i++){if(await evaluate(expression))return;await pause(100);}throw Error('Timed out: '+expression+' '+JSON.stringify(errors)+' '+await evaluate("JSON.stringify({msg:document.querySelector('#auth-message')?.textContent,entering:enteringWorkspace,user:!!currentUser,auth:document.querySelector('#auth').hidden,work:document.querySelector('#work').hidden})"));};
 await cdp('Runtime.enable');await cdp('Page.enable');await cdp('Emulation.setDeviceMetricsOverride',{width:1440,height:1000,deviceScaleFactor:1,mobile:false});
 await cdp('Page.navigate',{url:base+'/DataLens-UI.html'});
 await until("typeof dataRequest==='function' && !document.body.classList.contains('session-loading')");
 const account='browser-'+Date.now()+'@example.test',password='TestBrowser123!';
 await evaluate(`authRequest('register',{username:'Browser QA',account:${JSON.stringify(account)},password:${JSON.stringify(password)},confirmPassword:${JSON.stringify(password)}})`);
 await evaluate("document.querySelector('#start').click()");
 await until("!document.querySelector('#auth').hidden");
 await evaluate(`document.querySelector('#auth-account').value=${JSON.stringify(account)};document.querySelector('#auth-password').value=${JSON.stringify(password)};document.querySelector('#auth-submit').click()`);
 await until("!enteringWorkspace && !document.querySelector('#work').hidden && !!document.querySelector('#new-team-name')");
 await until("!!document.querySelector('#workflow-modal')?.open");
 await until("!!document.querySelector('#workflow-modal iframe').contentDocument?.querySelector('#flow-next')");
 await evaluate("document.querySelector('#workflow-modal iframe').contentDocument.querySelector('#flow-next').click()");
 await until("document.querySelector('#workflow-modal iframe').contentDocument.querySelector('#eyebrow').textContent.startsWith('02')");
 await evaluate("document.querySelector('#close-workflow').click();firstWorkflow()");
 assert.equal(await evaluate("document.querySelector('#workflow-modal').open"),false);
 // Force session restoration to win the race against the workspace script.
 await cdp('Network.enable');await cdp('Network.setCacheDisabled',{cacheDisabled:true});
 await cdp('Fetch.enable',{patterns:[{urlPattern:'*/Workbench.js',requestStage:'Request'}]});
 await cdp('Page.reload',{ignoreCache:true});
 await until("typeof dataState!=='undefined' && !document.body.classList.contains('session-loading')");
 assert(await evaluate("!!document.querySelector('#new-team-name')"),'Reload must render the real workspace, not the legacy overview');
 await cdp('Fetch.disable');
 await evaluate("document.querySelector('#work [data-stage=\"0\"]').click()");
 await until("!!document.querySelector('#data-file')");
 await pause(500);
 await evaluate("document.querySelector('#work [data-stage=\"-1\"]').click()");
 await until("!!document.querySelector('#new-team-name')");
 await pause(500);
 assert(await evaluate("!!document.querySelector('#new-team-name')"),'Home navigation must use the same workspace');

 await evaluate("document.querySelector('#work [data-stage=\"0\"]').click()");await until("!!document.querySelector('#data-file')");
 await evaluate("document.querySelector('#data-null').value='\\\\N'");
 const doc=await cdp('DOM.getDocument');const file=await cdp('DOM.querySelector',{nodeId:doc.root.nodeId,selector:'#data-file'});
 await cdp('DOM.setFileInputFiles',{nodeId:file.nodeId,files:[resolve('DataLens-backend/src/test/fixtures/browser.csv')]});
 await evaluate("document.querySelector('#upload-data').click()");
 await until("!!document.querySelector('#confirm-import')");
 assert.equal(await evaluate('dataState.dataset.row_count'),3);
 assert.equal(await evaluate("document.querySelectorAll('#work td details').length"),0);
 assert.equal(await evaluate('dataState.dataset.rows[0][0]'),'001');
 assert.equal(await evaluate('dataState.dataset.rows[2][2]'),null);
 await evaluate("document.querySelector('#confirm-import').click()");await until("dataState.dataset.status==='READY'");
 await evaluate("document.querySelector('#work [data-stage=\"3\"]').click()");await until("!!document.querySelector('#generate-data-sql')");
 await evaluate("document.querySelector('#generate-data-sql').click()");await until("!!dataState.sql");
 assert.match(await evaluate("document.querySelector('#data-sql-preview').textContent"),/CREATE TABLE/);
 await evaluate("document.querySelector('#work [data-stage=\"2\"]').click()");await until("!!document.querySelector('#preview-clean')");
 // Stub only the external model response; saving still uses the real test backend.
 await evaluate(`window.originalDataRequest=dataRequest;dataRequest=async(path,body,...args)=>{
  if(body?.purpose==='clean_auto')return {explanation:'Test model plan',plan:{fields:[{column:'id',kind:'keep',missing:'keep'},{column:'city',kind:'keep',missing:'keep'},{column:'amount',kind:'money',missing:'drop',reason:'Remove missing amount rows'}]},preview:{changed:1,rowCount:2,removed:1,columns:['id','city','amount'],samples:[]}};
  return originalDataRequest(path,body,...args);
 };document.querySelector('#preview-clean').click()`);
 await until("!!dataState.autoClean");
 assert.equal(await evaluate("document.querySelector('#clean-operation')"),null);
 await pause(400);await writeFile('/tmp/datalens-cleaning-desktop.png',Buffer.from((await cdp('Page.captureScreenshot',{format:'png'})).data,'base64'));
 await evaluate("document.querySelector('#apply-clean').click()");await until("stage===1 && dataState.dataset.parent_id!=null");
 assert.equal(await evaluate('dataState.dataset.row_count'),2);
 await evaluate('dataRequest=originalDataRequest');
 await evaluate("document.querySelector('#work [data-stage=\"4\"]').click()");await until("!!chartInstance && document.querySelector('#chart-status').textContent.includes('绘制前')");
 for(const kind of ['line','bar','rose','boxplot','scatter','heatmap']){
  await evaluate(`document.querySelector('#visual-kind').value='${kind}';document.querySelector('#visual-kind').dispatchEvent(new Event('change'))`);
  assert(await evaluate("document.querySelector('#chart-status').textContent.includes('绘制前')"),kind);
  assert(await evaluate("chartInstance.getOption().series.length>0"),kind);
 }
 await evaluate("document.querySelector('[data-ai-mode=deepseek]').click()");
 assert.equal(await evaluate("document.querySelector('#ai-consent')"),null);
 await evaluate("document.querySelector('[data-ai-mode=search]').click()");
 assert.equal(await evaluate("document.querySelector('#ai-purpose')"),null);
 await pause(1000);const shot=await cdp('Page.captureScreenshot',{format:'png'});await writeFile('/tmp/datalens-workspace-desktop.png',Buffer.from(shot.data,'base64'));
 await cdp('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
 assert(await evaluate("document.documentElement.scrollWidth<=innerWidth+1"));
 await pause(500);const mobile=await cdp('Page.captureScreenshot',{format:'png'});await writeFile('/tmp/datalens-workspace-mobile.png',Buffer.from(mobile.data,'base64'));
 await evaluate("document.querySelector('#change-password').click()");
 assert(await evaluate("document.querySelector('#password-dialog').open"));
 await evaluate("document.querySelector('#password-dialog .password-cancel').click()");
 assert.equal(await evaluate("document.querySelectorAll('#delete-dataset').length"),1);
 assert.deepEqual(errors,[],'Unexpected JavaScript exceptions');
 console.log('PASS: real Chrome login, file upload, confirmation, SQL, cleaning version, six ECharts charts, workflow modal and AI tabs and mobile render');
}finally{socket?.close();browser.kill('SIGTERM');await pause(400);await rm(directory,{recursive:true,force:true});}
