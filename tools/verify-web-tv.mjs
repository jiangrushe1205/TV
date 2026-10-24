// Hardware checks for this app's debug WebView. No stream URLs, cookies or account data are exported.
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, appendFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const option = (key, fallback) => { const i = process.argv.indexOf(key); return i < 0 ? fallback : process.argv[i + 1]; };
const device = option('--device', '192.168.1.76:5555');
const mode = option('--mode', 'channels');
const output = resolve(root, option('--output', `docs/evidence/v050/${mode}`));
const minutes = Number(option('--minutes', '30'));
const switchMethod = option('--switch-method', 'remote');
if (!['channels', 'switches', 'soak'].includes(mode) || !Number.isFinite(minutes) || minutes <= 0) throw Error('Invalid mode/minutes');
mkdirSync(output, { recursive: true });
const adbPath = resolve(root, '.local/android-sdk/platform-tools/adb.exe');
const adb = (...args) => {
  const r = spawnSync(adbPath, ['-s', device, ...args], { encoding: 'utf8', timeout: 15000, windowsHide: true });
  if (r.status !== 0) throw Error(`ADB ${args[0]} failed: ${r.stderr || r.error || r.stdout}`);
  return r.stdout.trim();
};
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const logFile = resolve(output, 'samples.ndjson');
const log = entry => appendFileSync(logFile, JSON.stringify({ at: new Date().toISOString(), ...entry }) + '\n');
const report = { mode, device, started: new Date().toISOString(), completed: false, checks: [] };
let socket, nextId = 0;
const pending = new Map();
async function connect() {
  const pid = adb('shell', 'pidof', 'cn.v65.live');
  if (!/^\d+$/.test(pid)) throw Error('App is not running');
  adb('forward', 'tcp:9223', `localabstract:webview_devtools_remote_${pid}`);
  const targets = await fetch('http://127.0.0.1:9223/json/list', { signal: AbortSignal.timeout(5000) }).then(r => r.json());
  const target = targets.find(t => t.url?.includes('yangshipin.cn/tv/'));
  if (!target) throw Error('Official TV WebView is unavailable');
  report.processId = pid;
  report.webViewTargets = targets.length;
  socket = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject; });
  socket.onmessage = e => {
    const message = JSON.parse(e.data), item = pending.get(message.id);
    if (item) { clearTimeout(item.timer); pending.delete(message.id); item.resolve(message); }
  };
}
async function evaluate(expression) {
  const id = ++nextId;
  const result = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(Error('WebView diagnostic timed out')); }, 6000);
    pending.set(id, { resolve, timer });
    socket.send(JSON.stringify({ id, method: 'Runtime.evaluate', params: { expression, returnByValue: true } }));
  });
  if (result.error || result.result?.exceptionDetails) throw Error('WebView evaluation failed');
  return result.result.result.value;
}
const expression = `(() => {
  const v=document.querySelector('video'),p=document.querySelector('.tv-main-con-l-vid'),r=p?.getBoundingClientRect();
  const pending=window.__v65PendingSwitch,pid=new URL(location.href).searchParams.get('pid');
  return {pid:new URL(location.href).searchParams.get('pid'),generation:window.__v65Tv?.generation,
    freshMedia:!pending||(pending.pid===pid&&(!v||v!==pending.video||v.currentSrc!==pending.src)),
    foreground:window.__v65Tv?.foreground,ready:document.readyState,viewport:[innerWidth,innerHeight],
    fitted:!!r&&Math.abs(r.x)<2&&Math.abs(r.y)<2&&Math.abs(r.width-innerWidth)<2&&Math.abs(r.height-innerHeight)<2,
    layout:document.documentElement.classList.contains('v65-tv-video'),
    officialMessage:p?.innerText?.slice(0,400),videoCount:document.querySelectorAll('video').length,
    media:v?{time:v.currentTime,ready:v.readyState,paused:v.paused,muted:v.muted,volume:v.volume,
      size:[v.videoWidth,v.videoHeight],frames:v.webkitDecodedFrameCount,dropped:v.webkitDroppedFrameCount,
      audioBytes:v.webkitAudioDecodedByteCount,error:v.error?.code||0}:null};
})()`;
const sample = async () => { const v = await evaluate(expression); log({ type: 'sample', ...v }); return v; };
const screenshot = name => {
  adb('shell', 'screencap', '-p', '/sdcard/v050-verify.png');
  adb('pull', '/sdcard/v050-verify.png', resolve(output, `${name}.png`));
};
async function waitForMedia(pid, timeout = 35000) {
  const start = Date.now(); let previous, latest, progressed = false, transientError;
  while (Date.now() - start < timeout) {
    try { latest = await sample(); } catch (e) { transientError = e.message; await sleep(500); continue; }
    if (latest.foreground === false) throw Error('App left foreground or television entered standby');
    if (latest.pid === pid && latest.freshMedia && latest.media?.ready >= 2 && !latest.media.paused && latest.media.size[0] > 0 &&
        previous?.pid === pid && latest.media.time > (previous.media?.time ?? Infinity)) { progressed = true; break; }
    previous = latest;
    await sleep(1000);
  }
  return { pid, progressed, elapsedMs: Date.now() - start, latest, transientError };
}
try {
  await connect();
  if (mode === 'channels') {
    const channels = [['CCTV1','600001859'],['CCTV3','600001801'],['CCTV5','600001818'],['CCTV13','600001811'],['Hunan','600002475'],['Dongfang','600002483']];
    for (const [name, pid] of channels) {
      adb('shell', 'am', 'start', '-n', 'cn.v65.live/.ui.OfficialWebActivity', '--es', 'channel_id', pid);
      const result = { name, ...await waitForMedia(pid) };
      screenshot(name); report.checks.push(result);
      console.log(JSON.stringify({ name, progressed: result.progressed, elapsedMs: result.elapsedMs, message: result.latest?.officialMessage }));
    }
  } else if (mode === 'switches') {
    if (!['remote', 'intent'].includes(switchMethod)) throw Error('Invalid switch method');
    report.switchMethod = switchMethod;
    const initialPid = switchMethod === 'remote' ? '600001801' : '600001859';
    adb('shell', 'am', 'start', '-n', 'cn.v65.live/.ui.OfficialWebActivity', '--es', 'channel_id', initialPid);
    report.initial = await waitForMedia(initialPid);
    for (let i = 0; i < 20; i++) {
      const before = report.checks.at(-1)?.latest || report.initial.latest;
      const expected = switchMethod === 'remote' ? (i % 2 === 0 ? '600001814' : '600001801') :
        ['600001801','600001818','600001811','600002475','600002483','600001859'][i % 6];
      if (switchMethod === 'remote') adb('shell', 'input', 'keyevent', i % 2 === 0 ? 'KEYCODE_DPAD_DOWN' : 'KEYCODE_DPAD_UP');
      else adb('shell', 'am', 'start', '-n', 'cn.v65.live/.ui.OfficialWebActivity', '--es', 'channel_id', expected);
      const playback = await waitForMedia(expected, 30000);
      const result = { sequence: i + 1, from: before.pid, changed: playback.latest?.pid === expected && before.pid !== expected, ...playback };
      report.checks.push(result);
      console.log(JSON.stringify({ sequence: i + 1, changed: result.changed, progressed: result.progressed, elapsedMs: result.elapsedMs }));
    }
    screenshot('after-20-switches');
  } else {
    const first = await sample();
    report.initial = await waitForMedia(first.pid);
    if (!report.initial.progressed) throw Error('Playback did not start; continuous-playback test cannot begin');
    const start = Date.now(), duration = minutes * 60000;
    report.playbackStarted = new Date(start).toISOString();
    screenshot('start');
    let previous = report.initial.latest, lastAdvance = start, longestStallMs = 0, samples = 0;
    while (Date.now() - start < duration) {
      await sleep(Math.min(10000, duration - (Date.now() - start)));
      const current = await sample(); samples++;
      if (current.foreground === false || current.pid !== first.pid) throw Error('Playback interrupted by background, standby or channel change');
      if (current.media?.time > (previous.media?.time ?? Infinity) || current.media?.frames > (previous.media?.frames ?? Infinity)) lastAdvance = Date.now();
      longestStallMs = Math.max(longestStallMs, Date.now() - lastAdvance);
      if (current.media?.error || current.videoCount !== 1) report.checks.push({ elapsedMs: Date.now()-start, anomaly: current });
      previous = current;
      report.elapsedMs = Date.now() - start; report.longestStallMs = longestStallMs; report.samples = samples;
      writeFileSync(resolve(output, 'report.json'), JSON.stringify(report, null, 2));
      if (samples % 6 === 0) console.log(JSON.stringify({ elapsedMinutes: +(report.elapsedMs/60000).toFixed(1), longestStallMs, pid: current.pid, time: current.media?.time }));
    }
    screenshot('end');
    report.final = previous;
  }
  report.completed = true;
} catch (e) { report.error = e.message; console.error(e.message); process.exitCode = 1; }
finally {
  report.finished = new Date().toISOString();
  writeFileSync(resolve(output, 'report.json'), JSON.stringify(report, null, 2));
  try { writeFileSync(resolve(output, 'app.log'), adb('logcat', '-d', '-v', 'time', '-s', 'V65WebTV:I', 'AndroidRuntime:E')); } catch {}
  socket?.close();
}
