import protobuf from 'protobufjs';
import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { setTimeout as pause } from 'node:timers/promises';
import { requestPlayUrls, UPSTREAM_HEADERS } from './vendor/yangshipin/api.js';

const exec = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const outputArg = args.indexOf('--output');
const output = outputArg >= 0 ? resolve(args[outputArg + 1]) : resolve(root, 'dist/official-streams');
const verify = args.includes('--verify-manifests');
const resume = args.includes('--resume');
const formatOnly = args.includes('--format-only');
const only = args.find(a => a.startsWith('--only='))?.slice(7).split(',');
await mkdir(output, { recursive: true });

// Use the same configured Windows network path as the browser/PowerShell.
// Transfer values as base64 data, never interpolate a URL as PowerShell code.
async function windowsFetch(url, options = {}) {
  const data = Buffer.from(JSON.stringify({ url, headers: options.headers || {} })).toString('base64');
  const script = `
    $ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'
    $d=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('${data}')) | ConvertFrom-Json
    $headers=@{}; $d.headers.PSObject.Properties | ForEach-Object { $headers[$_.Name]=[string]$_.Value }
    try {
      $r=Invoke-WebRequest -UseBasicParsing -Uri $d.url -Headers $headers -TimeoutSec 12 -MaximumRedirection 4
      $bytes=if($r.Content -is [byte[]]){$r.Content}else{[Text.Encoding]::UTF8.GetBytes([string]$r.Content)}
      if($bytes.Length -gt 2097152){throw 'Response exceeds 2 MiB'}
      @{status=[int]$r.StatusCode;body=[Convert]::ToBase64String($bytes)} | ConvertTo-Json -Compress
    } catch {
      $status=0; if($_.Exception.Response){$status=[int]$_.Exception.Response.StatusCode}
      @{status=$status;body='';errorType=$_.Exception.GetType().Name} | ConvertTo-Json -Compress
    }
  `;
  const encoded = Buffer.from(script, 'utf16le').toString('base64');
  const { stdout } = await exec('powershell.exe', ['-NoProfile', '-EncodedCommand', encoded],
    { timeout: 20_000, maxBuffer: 4 * 1024 * 1024, windowsHide: true, signal: options.signal });
  const result = JSON.parse(stdout.trim());
  const bytes = Buffer.from(result.body, 'base64');
  return { ok: result.status >= 200 && result.status < 300, status: result.status, url, errorType: result.errorType,
    text: async () => bytes.toString('utf8'), json: async () => JSON.parse(bytes.toString('utf8')), bytes };
}

const schema = await protobuf.load(resolve(root, 'tools/official-site.proto'));
async function metadata(url, type, name) {
  const response = await windowsFetch(url);
  if (!response.ok) throw new Error(`Official metadata HTTP ${response.status}`);
  await writeFile(resolve(output, name), response.bytes);
  const messageType = schema.lookupType(`official.${type}`);
  const value = messageType.toObject(messageType.decode(response.bytes), { longs: String, defaults: true });
  if (value.code !== 200) throw new Error(`Official metadata code ${value.code}`);
  return value;
}

let catalog;
if (formatOnly) catalog = JSON.parse(await readFile(resolve(output, 'catalog.json'), 'utf8'));
else {
  const nav = await metadata('https://capi.yangshipin.cn/api/oms/pc/navigation/home_top_nav', 'NavigationResponse', 'navigation.pb');
  const tab = nav.data.tabList.find(t => t.channelTag === 2);
  if (!tab || !/^[A-Za-z0-9_-]+$/.test(tab.feedId)) throw new Error('TV directory not found');
  const page = await metadata(`https://capi.yangshipin.cn/api/oms/pc/page/${tab.feedId}`, 'PageResponse', 'page.pb');
  catalog = [...new Map(page.data.modules.flatMap(m => m.channels)
    .filter(c => ['yangshi', 'weishi'].includes(c.group)).map(c => [c.pid, c])).values()];
}
if (!catalog.length) throw new Error('Empty channel directory');
await writeFile(resolve(output, 'catalog.json'), JSON.stringify(catalog, null, 2) + '\n');
const mappings = JSON.parse(await readFile(resolve(root, 'app/src/main/assets/native-channels-20261023.json'), 'utf8'));
const byPid = new Map(mappings.map(m => [m.livePid, m]));
const channels = only ? catalog.filter(c => only.includes(c.name)) : catalog;
const previous = resume || formatOnly ? JSON.parse(await readFile(resolve(output, 'channels.json'), 'utf8')) : null;
const report = { startedAt: previous?.startedAt || new Date().toISOString(), sourcePage: 'https://www.yangshipin.cn/tv/home',
  catalogSource: 'capi.yangshipin.cn', playbackApi: 'https://bkliveinfo.ysp.cctv.cn/',
  playbackApiNote: 'Official anonymous client endpoint for the same channel IDs; not a captured PC-web signed request',
  warning: 'Temporary official URLs. API success and an HLS manifest do not prove native video/audio playback. No account, media decryption, or third-party relay is used.',
  requiredHeaders: UPSTREAM_HEADERS, catalogChannelCount: catalog.length, channels: previous?.channels || [] };
for (const row of report.channels) {
  row.streamId = catalog.find(c => c.pid === row.pid)?.streamId || '';
  if (row.manifest?.httpStatus === 0) {
    row.manifest.httpStatus = null;
    row.manifest.status = 'NETWORK_ERROR';
  }
}

const safeText = s => String(s).replace(/[\r\n"]/g, ' ');
async function checkpoint() {
  report.updatedAt = new Date().toISOString();
  report.summary = { total: report.channels.length, urlsReturned: report.channels.filter(c => c.urls?.length).length,
    hlsManifestConfirmed: report.channels.filter(c => c.manifest?.status === 'HLS_MANIFEST').length,
    manifestFailed: report.channels.filter(c => c.urls?.length && c.manifest && c.manifest.status !== 'HLS_MANIFEST').length,
    limitedOrFailed: report.channels.filter(c => !c.urls?.length).length };
  await writeFile(resolve(output, 'channels.json.tmp'), JSON.stringify(report, null, 2) + '\n');
  await rename(resolve(output, 'channels.json.tmp'), resolve(output, 'channels.json'));
  for (const [filename, filter, description] of [
    ['official-api-urls.m3u', c => c.urls?.length, 'API returned URLs; playback unverified'],
    ['official-hls-confirmed.m3u', c => c.manifest?.status === 'HLS_MANIFEST', 'HLS manifest confirmed; video/audio unverified'],
    ['cctv.m3u', c => c.group === 'yangshi' && c.manifest?.status === 'HLS_MANIFEST', 'CCTV: HLS manifest confirmed; video/audio unverified'],
    ['satellite.m3u', c => c.group === 'weishi' && c.manifest?.status === 'HLS_MANIFEST', 'Satellite: HLS manifest confirmed; video/audio unverified'],
    ['cctv-all.m3u', c => c.group === 'yangshi' && c.urls?.length, 'CCTV: all API returned URLs, including failed manifest checks; playback unverified'],
    ['satellite-all.m3u', c => c.group === 'weishi' && c.urls?.length, 'Satellite: all API returned URLs, including failed manifest checks; playback unverified'],
  ]) {
    const lines = ['#EXTM3U', `# ${description}`, `# Generated ${report.updatedAt}`];
    for (const c of report.channels.filter(filter)) {
      lines.push(`#EXTINF:-1 tvg-id="${c.pid}" group-title="${c.group === 'yangshi' ? '央视' : '卫视'}",${safeText(c.name)}`,
        '#EXTVLCOPT:http-user-agent=qqlive', '#EXTVLCOPT:http-referrer=https://live.cctv.cn/', c.confirmedUrl || c.urls[0]);
    }
    await writeFile(resolve(output, filename), lines.join('\n') + '\n');
  }
  const csv = ['name,group,pid,status,manifestStatus,manifestHttpStatus,requestedAt,primaryUrl,backupUrls,confirmedUrl,reason',
    ...report.channels.map(c => [c.name, c.group, c.pid, c.status, c.manifest?.status || '', c.manifest?.httpStatus ?? '',
      c.requestedAt || '', c.urls?.[0] || '', c.urls?.slice(1).join(' | ') || '', c.manifest?.status === 'HLS_MANIFEST' ? c.confirmedUrl || c.urls?.[0] || '' : '', c.reason || '']
      .map(v => `"${String(v).replaceAll('"', '""')}"`).join(','))];
  await writeFile(resolve(output, 'channels.csv'), '\uFEFF' + csv.join('\r\n') + '\r\n');
  const count = group => report.channels.filter(c => c.group === group && c.manifest?.status === 'HLS_MANIFEST').length;
  const restricted = report.channels.filter(c => c.status === 'OFFICIAL_ACCESS_REQUIRED').length;
  const failed = report.channels.filter(c => ['OFFICIAL_REQUEST_FAILED', 'OFFICIAL_ACCESS_REQUIRED', 'NO_ANONYMOUS_MAPPING'].includes(c.status));
  const readme = `# 央视频官方频道地址快照\n\n` +
    `采集来源：https://www.yangshipin.cn/tv/home\n\n` +
    `目录共 ${catalog.length} 个频道；已处理 ${report.summary.total} 个。官方接口返回地址 ${report.summary.urlsReturned} 个，HLS 清单 HTTP 200 ${report.summary.hlsManifestConfirmed} 个（央视 ${count('yangshi')}、卫视 ${count('weishi')}）。\n\n` +
    `官方明确返回访问受限 ${restricted} 个；取源失败、受限或无映射共 ${failed.length} 个。完整状态、主备地址与时间见 channels.json / channels.csv。\n\n` +
    `- cctv.m3u：央视，已确认 HLS 清单。\n- satellite.m3u：卫视，已确认 HLS 清单。\n` +
    `- official-hls-confirmed.m3u：上述两组合并。\n- official-api-urls.m3u：所有接口返回的地址，可能包括清单请求失败的条目。\n\n` +
    `- cctv-all.m3u / satellite-all.m3u：所有已取得地址，按央视 / 卫视分组，包含清单请求失败的条目。\n\n` +
    `通过官网公开目录取得频道标识，再使用同频道的央视频官方匿名客户端接口 bkliveinfo.ysp.cctv.cn 获取播放信息。此文件不是逐个复制 PC 网页的签名播放请求，也没有混入第三方转播源。\n\n` +
    `播放器请求头：User-Agent: qqlive；Referer: https://live.cctv.cn/ 。M3U 已附 VLC 请求头选项，其他播放器是否支持这些选项需验证；客户端可从 JSON 读取 requiredHeaders。\n\n` +
    `地址是本次请求取得的临时快照，可能随时间、出口 IP 或 CDN 策略失效，不能长期写死。接口续期提示只作参考，不保证有效期。主地址只检查一次；本次对主地址失败且存在备用地址的频道，另外各检查一次官方备用地址。M3U 优先采用确认成功的备用地址，JSON 保留主备检查结果。不下载分片、不处理媒体解密；HLS 清单通过不代表电视画面及声音已通过验证。\n\n` +
    `重新采集：在项目目录执行 node tools/export-official-streams.mjs --verify-manifests --output dist/official-streams-20261024 。每个频道至少间隔 3.5 秒，无失败重试；请勿频繁重复全量采集。\n\n` +
    `开始：${report.startedAt}\n\n最后更新：${report.updatedAt}\n` +
    (report.finishedAt ? `\n完成：${report.finishedAt}\n` : '\n当前仍在采集中。\n') +
    (failed.length ? '\n失败 / 受限原因：\n\n' + failed.map(c => `- ${c.name}：${c.reason || c.status}`).join('\n') + '\n' : '');
  await writeFile(resolve(output, 'README.md'), readme);
}

if (formatOnly) {
  report.finishedAt = previous.finishedAt;
  await checkpoint();
  console.log(JSON.stringify(report.summary));
  process.exit(0);
}
console.log(`Official directory: ${catalog.length}; selected: ${channels.length}; sequential API requests, minimum 3.5s apart.`);
let previousStart = 0;
for (const channel of channels) {
  const existing = report.channels.findIndex(c => c.pid === channel.pid);
  if (existing >= 0 && !['NO_ANONYMOUS_MAPPING', 'REQUIRES_OFFICIAL_ACCESS'].includes(report.channels[existing].status)) continue;
  const row = { name: channel.name, pid: channel.pid, streamId: channel.streamId, group: channel.group,
    isVip: channel.isVip, isLimitedFree: channel.isLimitedFree,
    officialPage: `https://www.yangshipin.cn/tv/home?pid=${channel.pid}` };
  const snapshot = byPid.get(channel.pid);
  const channelId = /^\d+$/.test(channel.streamId) ? channel.streamId : snapshot?.channelId;
  const mapping = channelId ? { livePid: channel.pid, channelId, defn: snapshot?.defn || 'fhd' } : null;
  if (!mapping) row.status = 'NO_ANONYMOUS_MAPPING';
  else {
    await pause(Math.max(0, 3500 - (Date.now() - previousStart)));
    previousStart = Date.now();
    row.requestedAt = new Date().toISOString();
    try {
      const { urls, payload } = await requestPlayUrls(mapping, { fetchImpl: windowsFetch, timeoutMs: 15_000 });
      row.status = 'OFFICIAL_URL_RETURNED_UNVERIFIED';
      row.urls = urls;
      row.renewIntervalSeconds = Number(payload.vkey_renew_interval) || null;
      row.nativePlayback = 'NOT_TESTED';
      if (verify) {
        // One manifest request only. No segment downloads, retry loop, proxy changes or DRM handling.
        const response = await windowsFetch(urls[0], { headers: UPSTREAM_HEADERS });
        const manifest = response.ok ? await response.text() : '';
        row.manifest = { checkedAt: new Date().toISOString(), httpStatus: response.status || null,
          status: response.ok && manifest.trimStart().startsWith('#EXTM3U') ? 'HLS_MANIFEST' : response.ok ? 'NOT_HLS' : response.status ? 'HTTP_ERROR' : 'NETWORK_ERROR',
          errorType: response.errorType,
          contentProtection: /#EXT-X-(?:SESSION-)?KEY:/.test(manifest),
          videoAudio: 'NOT_DECODED' };
      }
    } catch (error) {
      row.status = /登录|会员|login|vip/i.test(error.message || '') ? 'OFFICIAL_ACCESS_REQUIRED' : 'OFFICIAL_REQUEST_FAILED';
      row.officialCode = error.officialCode ?? null;
      // Never log the request URL or client ticket from an error stack.
      row.reason = String(error.message || 'Request failed').split('https://')[0].slice(0, 200);
    }
  }
  if (existing >= 0) report.channels[existing] = row;
  else report.channels.push(row);
  await checkpoint();
  console.log(`${report.channels.length}/${channels.length} ${row.name} ${row.status}${row.manifest ? ` ${row.manifest.status}:${row.manifest.httpStatus}` : ''}`);
}
report.finishedAt = new Date().toISOString();
await checkpoint();
console.log(JSON.stringify(report.summary));
