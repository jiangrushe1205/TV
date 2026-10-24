package cn.v65.live.ui

import cn.v65.live.data.Channel
import org.json.JSONObject

/** Fits the official site's player and its controls; never replaces media or access checks. */
object OfficialPagePresentation {
    fun install(generation: Long) = """
        (() => {
            if (window.__v65Tv && window.__v65Tv.generation > $generation) return false;
            if (window.__v65Tv && window.__v65Tv.generation === $generation) {
                window.__v65Tv.apply();
                return !!document.querySelector('.tv-main-con-l-vid');
            }
            if (window.__v65Tv) window.__v65Tv.observer.disconnect();
            const state = {generation:$generation, enabled:true, foreground:true, userPaused:false};
            if (!window.__v65MediaGuard) {
                window.__v65MediaGuard = event => {
                    const current = window.__v65Tv;
                    if (current && (!current.foreground || current.userPaused) &&
                        (event.target.tagName === 'VIDEO' || event.target.tagName === 'AUDIO')) event.target.pause();
                };
                document.addEventListener('play', window.__v65MediaGuard, true);
            }
            state.apply = () => {
                const player = document.querySelector('.tv-main-con-l-vid');
                document.documentElement.classList.toggle('v65-tv-video', !!player && state.enabled);
                if (document.getElementById('v65-video-layout')) return;
                const style = document.createElement('style');
                style.id = 'v65-video-layout';
                style.textContent = `
                    html.v65-tv-video, html.v65-tv-video body { overflow:hidden!important; background:#000!important; }
                    html.v65-tv-video .tv-main-con-l-vid { position:fixed!important; left:0!important; top:0!important;
                        width:100vw!important; height:100vh!important; max-width:none!important;
                        margin:0!important; padding:0!important; background:#000!important; z-index:999!important; }
                    html.v65-tv-video .tv-main-con-l-vid > div, html.v65-tv-video .tv-main-con-l-vid .img {
                        width:100%!important; height:100%!important; margin:0!important; padding:0!important; }
                    html.v65-tv-video .tv-main-con-l-vid video {
                        width:100%!important; height:100%!important; object-fit:contain!important; }
                `;
                (document.head || document.documentElement).appendChild(style);
            };
            state.observer = new MutationObserver(() => state.apply());
            state.observer.observe(document.documentElement, {childList:true, subtree:true});
            window.addEventListener('pagehide', () => state.observer.disconnect(), {once:true});
            window.__v65Tv = state;
            state.apply();
            return !!document.querySelector('.tv-main-con-l-vid');
        })()
    """.trimIndent()

    fun enableVideo(enabled: Boolean) = "if(window.__v65Tv){window.__v65Tv.enabled=$enabled;window.__v65Tv.apply();}"

    fun mediaPolicy(foreground: Boolean, userPaused: Boolean) = """
        if(window.__v65Tv){window.__v65Tv.foreground=$foreground;window.__v65Tv.userPaused=$userPaused;}
        if(!$foreground || $userPaused){document.querySelectorAll('video,audio').forEach(v=>v.pause());}
    """.trimIndent()

    /** Use the site's own channel click handler, avoiding a full page and script reload. */
    fun switchChannel(channel: Channel) = """
        (() => {
            if (location.origin !== 'https://www.yangshipin.cn' || !location.pathname.startsWith('/tv/') ||
                document.readyState === 'loading' || !document.querySelector('.tv-main-con-l-vid')) return false;
            const name = ${JSONObject.quote(channel.name)};
            const rows = Array.from(document.querySelectorAll('.tv-main-con-r-list-left-imga,.tv-main-con-r-list-left-imgb'));
            const matches = rows.filter(row => {
                const span = row.querySelector('span');
                return span && Array.from(span.childNodes).filter(n => n.nodeType === 3).map(n => n.textContent).join('').trim() === name;
            });
            if (matches.length !== 1) return false;
            const tabs = document.querySelectorAll('.tv-main-con-r-tab-img');
            const index = matches[0].classList.contains('tv-main-con-r-list-left-imgb') ? 1 : 0;
            if (tabs[index]) tabs[index].click();
            matches[0].click();
            return true;
        })()
    """.trimIndent()

    fun prepareSwitch(channelId: String) = """
        (() => {
            const video = document.querySelector('video');
            window.__v65PendingSwitch = {pid:'$channelId',video,src:video ? video.currentSrc : ''};
            document.querySelectorAll('video,audio').forEach(v=>v.pause());
        })()
    """.trimIndent()

    val sample = """
        JSON.stringify((() => {
            const video = document.querySelector('video');
            const player = document.querySelector('.tv-main-con-l-vid');
            const rect = player && player.getBoundingClientRect();
            const pending = window.__v65PendingSwitch;
            const pid = new URL(location.href).searchParams.get('pid');
            const freshMedia = !pending || (pending.pid === pid && (!video || video !== pending.video || video.currentSrc !== pending.src));
            const actionRequired = !!player && /登录|会员|版权|地区|验证|播放失败|暂不支持/.test(player.innerText);
            return {
                pid,
                freshMedia,
                actionRequired,
                generation:window.__v65Tv ? window.__v65Tv.generation : -1,
                fullscreen:document.documentElement.classList.contains('v65-tv-video'),
                fitted:!!rect && Math.abs(rect.x)<2 && Math.abs(rect.y)<2 && Math.abs(rect.width-innerWidth)<2 && Math.abs(rect.height-innerHeight)<2,
                time:video ? video.currentTime : -1,
                ready:video ? video.readyState : 0,
                paused:video ? video.paused : true,
                width:video ? video.videoWidth : 0,
                error:video && video.error ? video.error.code : 0,
                online:navigator.onLine
            };
        })())
    """.trimIndent()

    val pauseMedia = "document.querySelectorAll('video,audio').forEach(v=>v.pause());"
    val toggleMedia = "(()=>{const v=document.querySelector('video');if(!v)return null;const paused=!v.paused;if(window.__v65Tv)window.__v65Tv.userPaused=paused;if(paused)v.pause();else v.play().catch(()=>{});return paused;})()"
    val resumeMedia = "document.querySelectorAll('video').forEach(v=>v.play().catch(()=>{}));"
}
