const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('tennisStudio', {
  getInfo:()=>ipcRenderer.invoke('studio:info'),
  checkUpdates:()=>ipcRenderer.invoke('studio:updates-check'),
  installUpdate:()=>ipcRenderer.invoke('studio:updates-install'),
  openData:()=>ipcRenderer.invoke('studio:open-data'),
  openLogs:()=>ipcRenderer.invoke('studio:open-logs'),
  selectProject:()=>ipcRenderer.invoke('studio:project'),
  prepareNetwork:()=>ipcRenderer.invoke('studio:network'),
  fullscreen:()=>ipcRenderer.invoke('studio:fullscreen'),
  retry:()=>ipcRenderer.invoke('studio:retry'),
  quit:()=>ipcRenderer.invoke('studio:quit'),
  onStatus:callback=>{const listener=(_event,value)=>callback(value);ipcRenderer.on('studio:status',listener);return()=>ipcRenderer.removeListener('studio:status',listener);},
});
