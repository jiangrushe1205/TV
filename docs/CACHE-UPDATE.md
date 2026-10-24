# 0.5.0 缓存更新交付记录

构建日期：2026-10-24（北京时间）。包名 `cn.v65.live`，版本仍为 `0.5.0 / versionCode 6`。
此次文件名增加 `cache` 以区分之前的测试包，使用原有本机调试签名。

## 本次行为

- 频道目录：本机持久缓存有效期 24 小时，未过期直接使用，不发起目录网络请求。
- 节目单：按频道 ID 和北京时间日期隔离，正常数据缓存 5 分钟；官网返回有效空节目单时缓存 1 分钟，避免重复请求。
- 缓存缺失、过期、时间戳晚于当前时间或无法解析时重新获取；新响应通过解析校验后原子替换缓存。
- 请求失败或新响应无效时保留上次可解析数据，并明确提示正在显示过期缓存。失败不会延长旧缓存有效期；没有旧数据时频道使用内置快照、节目单显示暂不可用。
- 同一进程内多个仓库实例共享缓存锁，避免并发读取同一过期数据时重复请求；被取消的请求正常取消，不返回旧结果。
- 旧原生首页的目录刷新按钮支持强制更新。默认网页电视入口仍为 `OfficialWebActivity`，立即使用快照打开上次频道，后台仅在目录缓存失效时请求更新。

官网页面资源由 WebView 的 `LOAD_DEFAULT` 按 HTTP 缓存规则复用和重新验证。本次没有缓存直播视频、绕过网页校验、保存或复用已失效的播放票据；频道目录缓存不代表免除官网的取流和验证等待。

## 验证

执行 `powershell -NoProfile -ExecutionPolicy Bypass -File tools/build.ps1`：

- `assembleDebug` 成功。
- `lintDebug` 成功，0 错误、75 警告。
- `testDebugUnitTest` 成功，40 项测试全部通过；其中缓存测试 9 项，覆盖命中免联网、过期边界、缺失/损坏、强制更新、无效响应、网络失败、取消、时钟回退和并发请求合并。

本次未重新安装到电视、未重新验证现场画面和声音，也未完成本次版本的 20 次切台、30 分钟连续播放。现存 `docs/evidence/v050/channels-final` 属于此次缓存修改之前的实机记录，不能作为缓存版本的实机验收。

## 安装和文件

交付目录 `dist/` 包含 APK、源码 ZIP 和 `SHA256SUMS.txt`。

```powershell
# 覆盖安装，保留收藏和上次频道；使用电视当前实际 IP
powershell -NoProfile -ExecutionPolicy Bypass -File tools/install.ps1 -DeviceAddress 192.168.1.76:5555 -ApkPath dist/living-room-live-v0.5.0-cache-debug.apk
# 校验文件摘要
Get-FileHash dist/living-room-live-v0.5.0-cache-debug.apk -Algorithm SHA256
```

源码包包含工程、测试、构建安装工具及第三方许可证，不含 Android SDK、构建缓存、本地配置、账户信息和签名私钥。其他电脑需要 JDK 17/21；执行 `tools/build.ps1 -InstallSdk` 安装所需依赖。其他电脑生成的新调试签名不能直接覆盖电视上现有签名的安装。
