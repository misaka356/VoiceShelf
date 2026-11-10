import {packager} from '@electron/packager';
import path from 'node:path';
const root=import.meta.dirname;
try{const result=await packager({dir:root,name:'Yinqi',appVersion:'0.1.0',electronVersion:'45.2.2',platform:'win32',arch:'x64',out:path.join(root,'../releases'),overwrite:true,electronZipDir:process.argv[2],icon:path.join(root,'assets/yinqi.ico'),ignore:[/README\.md/,/build-desktop/],win32metadata:{CompanyName:'Yinqi',ProductName:'音栖',FileDescription:'音栖局域网播放器'}});console.log(result);}catch(e){console.error(e.stack,e.cause);process.exitCode=1;}
