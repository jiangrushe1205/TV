// Adapted from akiralereal/iptv; GPL-3.0. See THIRD-PARTY.md and LICENSE.
import { createCKey, clientConstants } from './ckey.js'

export const API_URL = 'https://bkliveinfo.ysp.cctv.cn/'
export const UPSTREAM_HEADERS = Object.freeze({
  Accept: 'application/vnd.apple.mpegurl,application/json,*/*',
  Referer: 'https://live.cctv.cn/',
  'User-Agent': 'qqlive',
})

// 只声明 AVC/H.264 能力，不向接口申报 HEVC；用于电视盒子和内置播放器兼容模式。
const H264_CAPABILITY = Buffer.from('H(30:1080,60:1080|30:1080,60:1080)').toString('base64')

function withTimeout(timeoutMs) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  return { signal: controller.signal, done: () => clearTimeout(timer) }
}

export function isOfficialMediaUrl(value) {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && (
      url.hostname === 'cctv.cn'
      || url.hostname.endsWith('.cctv.cn')
      || url.hostname === 'ysp.cctv.cn'
      || url.hostname.endsWith('.ysp.cctv.cn')
      || url.hostname === 'cctv.com'
      || url.hostname.endsWith('.cctv.com')
    )
  } catch {
    return false
  }
}

function collectUrls(payload) {
  const values = [payload?.playurl]
  const backups = payload?.backurl_list ?? payload?.backurlList ?? payload?.backurl
  if (Array.isArray(backups)) {
    for (const item of backups) values.push(typeof item === 'string' ? item : item?.url || item?.playurl)
  } else if (typeof backups === 'string') {
    values.push(...backups.split(/[;,]/))
  }
  return [...new Set(values.map(value => String(value || '').trim()).filter(isOfficialMediaUrl))]
}

export async function requestPlayUrls(channel, options = {}) {
  const fetchImpl = options.fetchImpl || fetch
  const ticket = createCKey(channel.channelId, { now: options.now })
  const query = new URLSearchParams({
    atime: '120',
    livepid: channel.livePid,
    cnlid: channel.channelId,
    appVer: clientConstants.appVersion,
    app_version: '300090',
    caplv: '1',
    cmd: '2',
    defn: channel.defn || 'fhd',
    device: 'iPhone',
    encryptVer: '4.2',
    getpreviewinfo: '0',
    hevclv: '0',
    lang: 'zh-Hans_CN',
    livequeue: '0',
    logintype: '1',
    nettype: '1',
    newnettype: '1',
    newplatform: String(clientConstants.platform),
    platform: String(clientConstants.platform),
    sdtfrom: 'v3021',
    spacode: '23',
    spaudio: '1',
    spdemuxer: '6',
    spdrm: '2',
    spdynamicrange: '1',
    spflv: '1',
    spflvaudio: '1',
    sphdrfps: '60',
    sphttps: '1',
    spvcode: H264_CAPABILITY,
    spvideo: '4',
    stream: '1',
    system: '1',
    sysver: 'ios18.2.1',
    uhd_flag: '0',
    cKey: ticket.cKey,
    guid: ticket.guid,
    fntick: String(ticket.timestamp),
    flowid: ticket.flowId,
    playbacktime: '0',
  })
  const timeout = withTimeout(Number(options.timeoutMs || 12_000))
  try {
    const response = await fetchImpl(`${API_URL}?${query}`, {
      redirect: 'follow',
      signal: timeout.signal,
      headers: { 'User-Agent': 'qqlive', Accept: 'application/json' },
    })
    if (!response.ok) throw new Error(`官方接口 HTTP ${response.status}`)
    const payload = await response.json()
    if (Number(payload?.iretcode) !== 0) throw Object.assign(
      new Error(payload?.errinfo || `官方接口返回 ${payload?.iretcode ?? '未知错误'}`),
      { officialCode: Number(payload?.iretcode) })
    const urls = collectUrls(payload)
    if (!urls.length) throw new Error('官方接口没有返回可用的 HLS 地址')
    return { urls, payload }
  } finally {
    timeout.done()
  }
}

