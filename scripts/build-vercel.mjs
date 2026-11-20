import {mkdir,copyFile,writeFile,rm} from 'node:fs/promises';
const origin=new URL(process.env.DATALENS_API_ORIGIN||'');
if(!['https:','http:'].includes(origin.protocol)||origin.username||origin.password||origin.search||origin.hash||origin.pathname!=='/'||/^(localhost|127\.|\[::1\])/.test(origin.hostname))throw Error('DATALENS_API_ORIGIN must be the public HTTP or HTTPS backend origin');
const out='.vercel/output';
await rm(out,{recursive:true,force:true});
await mkdir(out+'/static/vendor',{recursive:true});
for(const file of ['DataLens-UI.html','DataLens-workflow.html','Workbench.js','Workbench-enhancements.js','Workbench.css','vendor/echarts.min.js','vendor/ECHARTS-LICENSE'])await copyFile(file,out+'/static/'+file);
await writeFile(out+'/config.json',JSON.stringify({version:3,routes:[{src:'/api/(.*)',dest:origin.origin+'/api/$1'},{src:'/',dest:'/DataLens-UI.html'},{handle:'filesystem'},{src:'/.*',status:404}]},null,2));
console.log('Built static assets and same-origin API proxy.');
