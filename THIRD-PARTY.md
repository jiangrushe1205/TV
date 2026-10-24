# Third-party attribution

The anonymous Yangshipin request protocol in `YspTicket.kt`, the request parameters in
`PlaybackResolver.kt`, and the channel identifier snapshot in
`app/src/main/assets/native-channels-20261023.json` are adapted from
[akiralereal/iptv](https://github.com/akiralereal/iptv), snapshot inspected on 2026-10-23
(main: `8e51a0bf0baaaf12ab984057dbc65a553dc4966f`).

Upstream files: `extractors/yangshipin/ckey.js`, `api.js`, `channels.js`.
`tools/vendor/yangshipin/ckey.js` and `api.js` reuse the same GPL-3.0 source;
the latter keeps only the anonymous API request helpers for the export tool.
Copyright belongs to the upstream contributors. License: GNU GPL version 3;
the full license is included in `LICENSE`. This combined application is supplied
under GPL-3.0 terms with its corresponding source in this project.

Changes: ported anonymous request tickets from JavaScript to Kotlin; Android
coroutine cancellation, request bounds, official error display, in-memory URL
cache, and Media3 playback on the television. No upstream server, media proxy,
VIP bridge, account session, media descrambler, or playlist rewriting is included.

Other dependencies retain their licenses, including AndroidX/Media3, Kotlin,
OkHttp/Okio and Protobuf (Apache-2.0 / BSD as applicable).

Public channel endpoint lists (`public-cn_cctv.m3u8`, `public-cn_province.m3u8`)
were obtained from [best-fan/iptv-sources](https://github.com/best-fan/iptv-sources),
main branch, 2026-10-23 snapshot. Its README advertises MIT, but the inspected
repository root supplies no LICENSE file; no permission to redistribute video
content is implied. These are endpoint metadata, including third-party relays,
not bundled broadcasts. The retained 0.4.0 native module can refresh the two lists
and filter expired URLs, restricted channel flags and a device-confirmed
wrong-channel row. The default 0.5.0 OfficialWebActivity does not call this module
or refresh those lists: it loads the official Yangshipin website and uses its
normal channel selection and playback flow.
No upstream scripts or pay-channel playlist are included or executed.
