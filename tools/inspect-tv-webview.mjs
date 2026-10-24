// Local diagnostics for the app's own debug WebView; does not export media URLs or cookies.
const list = await fetch('http://127.0.0.1:9223/json/list').then(r => r.json());
const target = list.find(t => t.url?.includes('yangshipin.cn/tv/'));
if (!target) throw new Error('No official TV WebView target');
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let id = 0;
const pending = new Map();
ws.onmessage = e => { const v = JSON.parse(e.data); if (v.id) { pending.get(v.id)?.(v); pending.delete(v.id); } };
const timer = setTimeout(() => { ws.close(); process.exitCode = 1; }, 10000);
const send = (method, params = {}) => new Promise(resolve => { const n = ++id; pending.set(n, resolve); ws.send(JSON.stringify({id:n, method, params})); });
const expression = process.argv[2] || `JSON.stringify({
  pid:new URL(location.href).searchParams.get('pid'), ready:document.readyState,
  viewport:[innerWidth,innerHeight], fullscreen:document.documentElement.className,
  generation:window.__v65Tv?.generation,
  body:document.body?.innerText?.slice(0,1400),
  player:[...document.querySelectorAll('.tv-main-con-l-vid')].map(v=>({rect:v.getBoundingClientRect().toJSON(),children:[...v.children].map(n=>({tag:n.tagName,cls:n.className,rect:n.getBoundingClientRect().toJSON()}))})),
  videos:[...document.querySelectorAll('video')].map(v=>({time:v.currentTime,paused:v.paused,muted:v.muted,volume:v.volume,ready:v.readyState,size:[v.videoWidth,v.videoHeight],error:v.error?.code,rect:v.getBoundingClientRect().toJSON()}))
})`;
const result = await send('Runtime.evaluate', { expression, returnByValue:true });
console.log(result.result?.result?.value ?? JSON.stringify(result));
clearTimeout(timer); ws.close();
