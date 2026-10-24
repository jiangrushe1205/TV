# 客厅直播 · 央视频网页电视版

Kotlin / Android TV APK，**0.5.0 / versionCode 6**，包名 `cn.v65.live`。Android 6 / API 23 及以上；当前实机为华为 HEGE-560B、Android 10。应用不依赖 Google 服务，无须电脑或服务器长期运行。这是 APK，不是 HarmonyOS NEXT HAP。

## 当前播放方式

默认入口是 `OfficialWebActivity`，在一个 WebView 中打开央视频官方电视页面。首次启动 CCTV1，以后恢复上次频道；频道不存在时回退 CCTV1。目录使用原有缓存与内置快照，不阻塞播放。目录缓存 24 小时、节目单缓存 5 分钟（空节目单 1 分钟），有效缓存直接使用；过期或损坏时获取并校验新数据后再替换。更新失败保留旧数据，并标明过期。详见 [缓存更新与构建记录](docs/CACHE-UPDATE.md)。

官网负责直播、播放验证、地址刷新、登录与版权提示。**本版实际使用官方网页播放，不将它标记为原生直播成功。** 旧原生播放器源码保留供历史参考，不作为默认入口，也不会在启动时取源或验证第三方地址。

针对电视反馈，切台优先点击官网自己的频道项，复用页面和播放器资源；只有首次打开、主动刷新或站内切台不可用才加载整页。原生黑色加载层遮住网页排版过程，播放器按比例铺满屏幕后显示。切台仍受官网播放验证与网络影响，不承诺秒开。官网要求操作时，进入“网页操作”处理。

## 遥控器

- 全屏：上 / 下切换上一台 / 下一台，首尾循环；确定打开频道菜单。
- 频道菜单：左 / 右切换央视、卫视、收藏；上 / 下浏览，确定播放。浏览焦点不切台。
- 菜单键：播放 / 暂停、刷新、收藏、网页操作、退出；无操作 5 秒隐藏。
- 网页操作：方向键移动指针，确定点击官网按钮，返回恢复全屏。仅此模式显示指针。
- 返回：先关频道菜单、工具栏或指针，再显示退出确认；默认焦点“继续观看”。

保留原有收藏、最近观看和上次频道。加载超 20 秒显示提示，不反复自动刷新；频道列表和需要处理的提示不自动关闭。退到后台暂停媒体，退出销毁 WebView。

## 构建与覆盖安装

Windows PowerShell，JDK 17 或 21：

```powershell
cd D:\work\tv
# 第一次安装 SDK（本机 SDK 已有时不需要 -InstallSdk）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -InstallSdk
# 编译、Lint、单元测试
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1
# 覆盖安装并启动，保留数据
powershell -NoProfile -ExecutionPolicy Bypass -File tools\install.ps1 -DeviceAddress 192.168.1.76:5555 -ApkPath dist\living-room-live-v0.5.0-cache-debug.apk
Get-FileHash dist\living-room-live-v0.5.0-cache-debug.apk -Algorithm SHA256
# 打包可复现源码与实机报告摘要（不含 SDK、缓存和签名私钥）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\package-source.ps1
```

电视需开启 ADB 并授权电脑，华为安装提示需确认“继续安装”。同一台电视地址由 `192.168.1.71` 变为 `192.168.1.76`；路由器 DHCP 变化后请使用电视当前地址。安装使用 `install -r`，请勿卸载或清除应用数据。

脚本使用 Gradle 8.11.1、Android SDK 35；SDK、构建缓存和签名私钥不随源码交付。交付包使用现有本机调试签名，可覆盖现有安装；另一台电脑重新生成调试密钥不能直接覆盖此安装，应继续使用本机原有密钥。

## 测试与诊断

缓存更新的构建、Lint 和单元测试结果见 [构建记录](docs/CACHE-UPDATE.md)。用户已确认此前官网版电视画面、声音正常；本次缓存修改未重新执行实机播放、20 次切台或 30 分钟稳定性验收。

Node.js 22 可运行实机检查，不导出媒体地址或 Cookie：

```powershell
node tools/verify-web-tv.mjs --device 192.168.1.76:5555 --mode channels
node tools/verify-web-tv.mjs --device 192.168.1.76:5555 --mode switches
node tools/verify-web-tv.mjs --device 192.168.1.76:5555 --mode soak --minutes 30
```

这些命令需要应用在前台且电视保持开机；会切台或持续采样。输出位于 `docs/evidence/v050/`，媒体计时推进不能替代对实际画面、声音的核对。

没有录制、下载、回看、投屏或市场发布功能。使用正常 HTTPS 验证，网页不允许混合 HTTP 内容；历史原生模块的明文网络配置仍保留。许可证见 [THIRD-PARTY.md](THIRD-PARTY.md) 与 [LICENSE](LICENSE)。

旧取源 / 导出工具保留在 `tools/`，不参与 0.5.0 默认播放。
