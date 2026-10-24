import protobuf from 'protobufjs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const exec = promisify(execFile);
const project = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const schema = await protobuf.load(resolve(project, 'tools/official-site.proto'));
const offline = process.argv.includes('--offline');
const fixture = resolve(project, 'app/src/test/resources/official');
const output = resolve(project, 'docs/evidence');
await mkdir(output, { recursive: true });
const deviceEvidence = JSON.parse(await readFile(resolve(output, 'device-playback.json'), 'utf8').catch(() => '{"channels":{}}'));

async function get(url, name, type) {
  let bytes;
  if (offline) bytes = await readFile(resolve(fixture, `${name}.pb`));
  else {
    // PowerShell uses the configured Windows system proxy, without hardcoding it.
    const script = `$ProgressPreference='SilentlyContinue'; $r=Invoke-WebRequest -UseBasicParsing -Uri '${url}' -TimeoutSec 25; if($r.Content -is [byte[]]){[Convert]::ToBase64String($r.Content)}else{[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($r.Content))}`;
    const encoded = Buffer.from(script, 'utf16le').toString('base64');
    const { stdout } = await exec('powershell.exe', ['-NoProfile', '-EncodedCommand', encoded], { maxBuffer: 4 * 1024 * 1024 });
    bytes = Buffer.from(stdout.trim(), 'base64');
  }
  const message = schema.lookupType(`official.${type}`).decode(bytes);
  const data = schema.lookupType(`official.${type}`).toObject(message, { longs: String, defaults: true });
  if (data.code !== 200) throw new Error(`${name}: official API returned ${data.code}`);
  return data;
}

const nav = await get('https://capi.yangshipin.cn/api/oms/pc/navigation/home_top_nav', 'navigation', 'NavigationResponse');
const tv = nav.data.tabList.find(tab => tab.channelTag === 2);
if (!tv || !/^[A-Za-z0-9_-]+$/.test(tv.feedId)) throw new Error('Television feed not found');
const page = await get(`https://capi.yangshipin.cn/api/oms/pc/page/${tv.feedId}`, 'page', 'PageResponse');
const channels = page.data.modules.flatMap(module => module.channels);
if (!channels.length) throw new Error('No channels returned');
const date = offline ? '20261022' : new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()).replaceAll('-', '');
const report = { checkedAt: new Date().toISOString(), mode: offline ? 'recorded-fixtures' : 'live-http', feed: tv.feedId, channelCount: channels.length, representativeChannels: [] };
for (const name of ['CCTV1', 'CCTV13', 'CCTV5']) {
  const channel = channels.find(item => item.name === name);
  if (!channel || !/^\d+$/.test(channel.pid)) throw new Error(`${name}: channel not found`);
  const epg = offline && name !== 'CCTV1' ? null : await get(`https://capi.yangshipin.cn/api/yspepg/program/${channel.pid}/${date}`, 'epg', 'EpgResponse');
  const device = deviceEvidence.channels?.[name] ?? {};
  report.representativeChannels.push({ name, pid: channel.pid, date, programCount: epg?.programs.length ?? null, epgEvidence: epg ? (offline ? 'CCTV1_RECORDED_FIXTURE' : 'LIVE_HTTP') : 'NO_CHANNEL_FIXTURE', nativePlayback: device.nativePlayback ?? 'NOT_TESTED_ON_DEVICE', officialWebPlayback: device.officialWebPlayback ?? 'NOT_TESTED_ON_DEVICE' });
}
await writeFile(resolve(output, offline ? 'fixture-probe.json' : 'source-probe.json'), JSON.stringify(report, null, 2) + '\n');
const lines = ['channel,pid,isVip,group,nativePlayback,officialWebPlayback', ...channels.map(c => {
  const device = deviceEvidence.channels?.[c.name] ?? {};
  return [c.name, c.pid, c.isVip, c.group, device.nativePlayback ?? 'NOT_TESTED_ON_DEVICE', device.officialWebPlayback ?? 'NOT_TESTED_ON_DEVICE']
    .map(v => `"${String(v).replaceAll('"', '""')}"`).join(',');
})];
await writeFile(resolve(output, offline ? 'fixture-channel-matrix.csv' : 'channel-matrix.csv'), '\uFEFF' + lines.join('\r\n') + '\r\n');
console.log(JSON.stringify(report, null, 2));
