const {contextBridge,ipcRenderer}=require('electron');contextBridge.exposeInMainWorld('phone',{call:(action,payload)=>ipcRenderer.invoke('phone',action,payload)});
