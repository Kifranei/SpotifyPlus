# SpotifyPlus

LSPosed / Xposed 模块：增强 Spotify（`com.spotify.music`）。

**包名:** `dev.kifranei.spotifyplus`  
**设置 UI:** [Miuix](https://github.com/compose-miuix-ui/miuix) Compose（HyperOS / MIUI 风格）

## 功能（开关见设置页）

1. **Canvas 下载** — 播放页更多菜单保存 Canvas 循环视频  
2. **词幕 / LyricProvider 桥接** — 逐字歌词 + 翻译（有则）  
3. **艺人封面下载** — 高清艺人图（Halcyon 等）  
4. **分享卡片增强** — 歌曲/歌词/MV 色卡导出分享与本地保存  
5. **媒体通知「补回」** — HyperOS 恢复收藏／随机／循环（需同时勾选 SystemUI）  

详见 [DESIGN.md](./DESIGN.md)。

## 不做

不帮助抓取 DRM 音频。Canvas / 封面图 / 分享位图 / 歌词文本除外。

## 构建

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # arm64 release (signing via local.properties)
./gradlew :app:testDebugUnitTest
```

工具链：AGP 9.2.1 / Gradle 9.7.1 / Kotlin 2.4.10 / compileSdk 37 / JDK 21（Miuix 0.9.4 需要）；`local.properties` 写 `sdk.dir`。

安装后在 LSPosed 勾选作用域 `com.spotify.music` **与** `系统界面`（`com.android.systemui`），强制停止 Spotify 并重启 SystemUI（或重启手机）。

**HyperOS 媒体通知「补回」：** Spotify 进程内规范化 PlaybackState CustomAction；SystemUI 侧清空 `NotificationSettingsManager.mHiddenCustomActionsList` 解黑名单。仅勾选 Spotify 时，HyperOS 仍可能只显示默认 prev/play/next 三键。

## English

SpotifyPlus is an LSPosed module for Spotify Android. Settings use **Miuix** Compose to match HyperOS/MIUI. Features: Canvas save, LyricProvider/词幕 bridge (word-level + translation when available), artist image download, share-card export/save. See DESIGN.md. No DRM audio ripping.

Release: **v1.0.0** — hooks from static analysis of Spotify **9.1.90.2270** (see DESIGN.md). Install the release APK in LSPosed, scope `com.spotify.music` and SystemUI.
