const {app,BrowserWindow,protocol,net,ipcMain,safeStorage}=require('electron');
const fs=require('node:fs');const path=require('node:path');const {pathToFileURL}=require('node:url');
protocol.registerSchemesAsPrivileged([{scheme:'app',privileges:{standard:true,secure:true,supportFetchAPI:true}},{scheme:'phone',privileges:{standard:true,secure:true,supportFetchAPI:true,stream:true,corsEnabled:true}}]);
let win,connection=null;const mediaCache=new Map();let cacheBytes=0,cacheRevision=null;const configFile=()=>path.join(app.getPath('userData'),'connection.json');
function readConfig(){try{return JSON.parse(fs.readFileSync(configFile(),'utf8'));}catch{return {};}}
function saveConnection(){const data={address:connection.base};if(safeStorage.isEncryptionAvailable())data.token=safeStorage.encryptString(connection.token).toString('base64');fs.writeFileSync(configFile(),JSON.stringify(data));}
function parseAddress(input){const u=new URL(input.includes('://')?input:`http://${input}`);const p=u.hostname.split('.').map(Number);if(u.protocol!=='http:'||u.username||u.password||u.pathname!=='/'||u.search||u.hash||p.length!==4||p.some(x=>!Number.isInteger(x)||x<0||x>255)||!(p[0]===10||p[0]===192&&p[1]===168||p[0]===172&&p[1]>=16&&p[1]<=31||p[0]===127))throw Error('请输入手机显示的局域网 IPv4 地址');return `http://${u.hostname}:${u.port||17856}`;}
async function request(endpoint,body,unauth=false){if(!connection)throw Error('请先连接手机');let response;try{response=await net.fetch(connection.base+endpoint,{method:body===undefined?'GET':'POST',headers:{...(unauth?{}:{Authorization:`Bearer ${connection.token}`}),...(body===undefined?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000),redirect:'error'});}catch{throw Error('无法连接手机，请检查 Wi-Fi 与手机共享开关');}const text=await response.text();let data;try{data=JSON.parse(text);}catch{throw Error('手机返回了无效数据');}if(!response.ok)throw Error(data.error||`请求失败 ${response.status}`);return data;}
function sender(event){if(!win||event.sender!==win.webContents||!event.senderFrame?.url.startsWith('app://yinqi/'))throw Error('Invalid sender');}
const smoke=!app.isPackaged&&process.env.YINQI_SMOKE_DIR;if(smoke)app.setPath('userData',path.join(smoke,'profile'));
app.whenReady().then(async()=>{
 protocol.handle('app',req=>{const u=new URL(req.url);const file=u.pathname==='/'?'index.html':u.pathname.slice(1);if(u.hostname!=='yinqi'||!['index.html','style.css','renderer.js','assets/yinqi.png'].includes(file))return new Response('Not found',{status:404});return net.fetch(pathToFileURL(path.join(__dirname,file)).toString());});
 protocol.handle('phone',async req=>{
  const u=new URL(req.url);if(!connection||u.hostname!=='media'||!/^\/(audio|cover|art)\/[A-Za-z0-9_-]+$/.test(u.pathname))return new Response('',{status:403});
  const image=!u.pathname.startsWith('/audio/');const key=connection.base+u.pathname;
  if(image&&mediaCache.has(key)){const entry=mediaCache.get(key);return new Response(entry.bytes,{status:entry.status,headers:{'Content-Type':entry.type,'Access-Control-Allow-Origin':'app://yinqi'}});}
  try{const response=await net.fetch(connection.base+u.pathname,{method:req.method==='HEAD'?'HEAD':'GET',headers:{Authorization:`Bearer ${connection.token}`,...(req.headers.get('range')?{Range:req.headers.get('range')}:{})},redirect:'error',signal:req.signal});
   const headers=new Headers(response.headers);headers.set('Access-Control-Allow-Origin','app://yinqi');
   if(image&&req.method!=='HEAD'&&(response.ok||response.status===404)){const bytes=await response.arrayBuffer();if(bytes.byteLength<4000000){while(cacheBytes+bytes.byteLength>16000000||mediaCache.size>=80){const first=mediaCache.keys().next().value;if(!first)break;cacheBytes-=mediaCache.get(first).bytes.byteLength;mediaCache.delete(first);}mediaCache.set(key,{bytes,status:response.status,type:headers.get('content-type')||'image/jpeg'});cacheBytes+=bytes.byteLength;}return new Response(bytes,{status:response.status,headers});}
   return new Response(response.body,{status:response.status,headers});
  }catch{return new Response('',{status:503});}
 });
 const saved=readConfig();if(saved.address&&saved.token&&safeStorage.isEncryptionAvailable())try{connection={base:parseAddress(saved.address),token:safeStorage.decryptString(Buffer.from(saved.token,'base64'))};}catch{}
 ipcMain.handle('phone',async(event,action,payload={})=>{try{sender(event);let data;
  if(action==='connection')data={address:readConfig().address||'',paired:!!connection};
  else if(action==='pair'){const base=parseAddress(String(payload.address||'').trim());const code=String(payload.code||'').trim();if(!/^\d{6}$/.test(code))throw Error('请输入手机显示的六位配对码');const old=connection;connection={base,token:''};try{data=await request('/pair',{code},true);connection.token=data.token;mediaCache.clear();cacheBytes=0;saveConnection();data={connected:true};}catch(e){connection=old;throw e;}}
  else if(action==='disconnect'){connection=null;fs.writeFileSync(configFile(),JSON.stringify({address:readConfig().address||''}));data={ok:true};}
  else if(action==='library'){data=await request('/api/library');if(cacheRevision!==data.revision){mediaCache.clear();cacheBytes=0;cacheRevision=data.revision;}}
  else if(action==='session')data=await request('/api/session',payload.queue?payload:undefined);
  else if(action==='state')data=await request('/api/state');
  else if(action==='lyrics'&&/^[a-f0-9]{32}$/.test(payload.id))data=await request('/lyrics/'+payload.id);
  else if(action==='progress'&&/^[a-f0-9]{32}$/.test(payload.id)){const body=payload.position===undefined?undefined:{position:Math.max(0,Math.min(2147483647,Math.round(Number(payload.position)||0)))};data=await request('/progress/'+payload.id,body);}
  else if(action==='favorite'&&/^(RJ[0-9]+|unidentified)$/.test(payload.id))data=await request('/api/favorite',{id:payload.id,favorite:!!payload.favorite});
  else if(action==='metadata'&&/^RJ[0-9]+$/.test(payload.id)){const body={id:payload.id};for(const key of ['title','circle','actors'])if(typeof payload[key]==='string')body[key]=payload[key].slice(0,2000);data=await request('/api/metadata',body);}
  else if(action==='settings'){const body={};for(const key of ['coverBackground','reduceMotion'])if(typeof payload[key]==='boolean')body[key]=payload[key];for(const key of ['coverOpacity','coverBrightness','lyricTransparency','workSort'])if(Number.isFinite(payload[key]))body[key]=payload[key];data=await request('/api/settings',body);}
  else throw Error('无效操作');return {ok:true,data};
 }catch(e){return {ok:false,error:e.message};}});
 win=new BrowserWindow({show:!smoke,width:1280,height:860,minWidth:960,minHeight:680,title:'音栖',icon:path.join(__dirname,'assets','yinqi.png'),backgroundColor:'#10131c',autoHideMenuBar:true,webPreferences:{preload:path.join(__dirname,'preload.cjs'),contextIsolation:true,nodeIntegration:false,sandbox:true}});
 win.webContents.setWindowOpenHandler(()=>({action:'deny'}));win.webContents.on('will-navigate',(e,url)=>{if(!url.startsWith('app://yinqi/'))e.preventDefault();});win.webContents.session.setPermissionRequestHandler((_web,_permission,done)=>done(false));await win.loadURL('app://yinqi/');if(smoke){setTimeout(async()=>{try{const report=await win.webContents.executeJavaScript("({title:document.title,bridge:typeof window.phone?.call,search:!!document.getElementById('search'),errors:document.body.innerText.includes('undefined')})");fs.writeFileSync(path.join(smoke,'report.json'),JSON.stringify(report));const picture=await win.webContents.capturePage();fs.writeFileSync(path.join(smoke,'preview.png'),picture.toPNG());}finally{app.quit();}},900);}
});
app.on('window-all-closed',()=>app.quit());
