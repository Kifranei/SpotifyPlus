# SpotifyPlus 设计文档 / Design

**模块名 / Module ID:** `dev.kifranei.spotifyplus`  
**展示名:** SpotifyPlus  
**宿主:** `com.spotify.music`（目标分析版本 **9.1.90.2270**）  
**作者:** Kifranei (`xinckidn@outlook.com`)  
**UI:** [Miuix](https://github.com/compose-miuix-ui/miuix) Compose（`miuix-ui` + `miuix-preference`）— HyperOS/MIUI 风格设置页，中文文案优先。

> **不做：** 不帮助抓取 DRM 音频流。Canvas 视频循环、封面/艺人图、分享卡片位图、歌词文本 OK。

---

## 1. 功能总览

| ID | 功能 | 入口 | 产物 |
|----|------|------|------|
| A | Canvas 下载 | 播放页「更多」菜单项 | 保存 `.mp4` 到相册/Downloads |
| B | LyricProvider / 词幕桥接 | 后台 MediaSession + color-lyrics | 逐字 + 翻译（有则）推给词幕 |
| C | 艺人封面下载 | 艺人页 / 更多菜单 | 高清艺人图（Halcyon 可用） |
| D | 分享卡片增强 | 原生分享轮播（歌曲/歌词/MV） | 分享到其他 App + 本地保存位图 |

设置页四个 `SuperSwitch` 一一对应 A–D。

---

## 2. Feature A — Canvas 下载

### 已知 CDN / API（开源参考）

- API: `POST https://spclient.wg.spotify.com/canvaz-cache/v0/canvases`（protobuf，需 Bearer + Client-Token）
- 媒体: `https://canvaz.scdn.co/.../*.mp4`（ArchiveTune / Convx / BitChord 等验证）
- 备选域名历史: `canvas-media.spotifycdn.com`（较少见；以抓包为准）

### 实现思路（模块内，优先宿主会话）

1. **URL 捕获（推荐）:** Hook OkHttp / Cronet 响应或图片/视频加载器，匹配 `canvaz.scdn.co` / `*.mp4` + 当前 track URI，缓存 `CanvasUrlStore`。
2. **菜单注入:** 仿 AM-plus-plus-media-plugin 的 more-menu 模式，在 Spotify 播放页 overflow 加「下载 Canvas」。
3. **保存:** OkHttp 直下 mp4 → MediaStore / Downloads；无 DRM（Canvas 为公开短循环）。

### 开源参考

- `rukamori/ArchiveTune` `SpotifyCanvasProvider.kt` — protobuf encode/decode、clienttoken
- `amarinne/spicy-ex` — 歌词为主，可能有媒体加载钩子可借鉴（非目标）
- 未发现成熟的「LeNerd46/SpotifyPlus Canvas」专用 LSPosed 模块；本模块自行实现

### Open questions

- [ ] 9.1.90 播放器更多菜单的 Compose/View 类名（jadx 后填）
- [ ] Canvas 是否仅在主进程加载，需不需要 hook 媒体进程
- [ ] 无 Canvas 曲目的 UX（Toast / 灰显）

---

## 3. Feature B — LyricProvider / 词幕桥接

### LyricProvider Spotify 现状（`tomakino/LyricProvider` `spotify-music`）

- **逐字:** 已支持。`color-lyrics` protobuf → `ProtoSyllable` → `LyricWord`（`Converter.kt`）。JSON 降级时仅行级。
- **翻译:** **未真正支持中文翻译。**
  - JSON 路径把 `transliteratedWords` 填进 `RichLyricLine.translation`（音译/罗马音，不是翻译）。
  - Protobuf 路径 **不设置** `translation`。
  - README: Spotify「标准歌词、逐字歌词」——对比 Apple Music「逐字、翻译」。
- **协议:** 通过 `LyriconFactory.createProvider` → `LyriconProvider.player.setSong(Song)`；`Song.lyrics: List<RichLyricLine>` 含 `text` / `words` / `translation`。词幕（Lyricon）消费该协议。

### PR #121（Higanoneko，open）— `fix(spotify-music): fix hook in new version`

- 背景: Spotify **9.1.78+** R8 混淆后 `okhttp3.Headers` 明文类消失，旧 `hookOkHttp()` 失效 → color-lyrics 缺登录头。
- 改动:
  - 新增 `HeaderCapture.kt`：明文路径 + **DexKit** 结构扫描 `Headers(<init>(String[]))`，结果按版本号+安装时间缓存。
  - `Spotify.kt` 改在 Application `onCreate` 后台线程启动捕获；失败时歌词接口退化。
  - `SpotifyApi` 线程安全 `recordHeader` / `snapshotHeaders`。
  - `build.gradle.kts` 加 `dexkit` 依赖。
- **与本模块关系:** 若做独立歌词桥，需同样处理 R8；或 **依赖/协作** 官方 Spotify LyricProvider + 本模块只做翻译增强/菜单。推荐：**复用 LyricProvider 的逐字管线，本模块补翻译与菜单类功能**，避免双 Hook 冲突。

### Apple Music 对比（已支持逐字+翻译）

- `AppleSongMapper`: `translation = appleLine.htmlTranslationLineText`，`words` / `secondaryWords` 完整。
- Spotify 台湾店面部分曲目有中文翻译 → 需确认 color-lyrics protobuf 是否另有 translation 字段，或需 `market=TW` / `clientLanguage=zh-TW` 请求。

### Open questions

- [ ] protobuf schema 是否含独立 translation 字段（当前 `ProtoModels` 未建模）
- [ ] `clientLanguage` / Accept-Language / 账号区是否影响翻译返回
- [ ] 与 LyricProvider 共存策略：本模块当「增强」还是「替代」Spotify provider

---

## 4. Feature C — 艺人封面下载

- 目标: 高清艺人图供 **Halcyon**（`Kifranei/Halcyon`）使用。
- 参考: AM-plus-plus-media-plugin `ContentDownloadsIntegration` / artist anchor — 在艺人页锚定 UI 注入下载按钮。
- CDN: Spotify 图床多为 `i.scdn.co` / `mosaic.scdn.co`；艺人 header 常有更大尺寸 URL（抓包确认 `images` 数组最大 width）。
- Open: [ ] 艺人页 Compose 树定位；[ ] 是否与「更多」菜单共用注入框架。

---

## 5. Feature D — 分享卡片增强

用户截图（HyperOS 机）展示原生分享轮播三态：

| Tab | 内容 |
|-----|------|
| 歌曲 | 封面 + 曲名/艺人色卡 |
| 歌词 | 歌词大字 + 小封面色卡 |
| MV | Music video 剧照 + 时长色卡 |

诉求:

1. **把卡片位图分享到其他应用**（类似 Apple Music / AM++ `NativeShareImage`）
2. **本地保存**卡片 PNG/JPG

实现思路:

1. Hook 分享 sheet 渲染出的 `Bitmap` / Compose `ImageBitmap` / PixelCopy 目标 View。
2. 在系统分享行旁或卡片长按增加「保存图片」「分享图片」。
3. 不替代 Spotify 社交分享；增强导出路径。

Open: [ ] 分享页 Activity/Compose 类名；[ ] 卡片是否已是 Bitmap 或需 PixelCopy。

---

## 6. UI / 工程

- **Settings:** Miuix `MiuixTheme` + `SuperSwitch`；中文 `strings.xml`。
- **Hooks:** `HookEntry` → 按开关安装 stub：`MoreMenuHook` / `CanvasHook` / `LyricsBridgeHook` / `ArtistCoverHook` / `ShareCardHook`。
- **Prefs:** `XSharedPreferences` + 设置页 `SharedPreferences`（LSPosed 推荐可读）。
- **不推 GitHub**，待用户确认模块名/选项（同 Monet 插件习惯）。

---

## 7. APK 分析计划

- 文件: `/home/box/Downloads/spotify-9.1.90.2270*.apk`（universal anydpi，~135MB）
- 工具: `/opt/jadx/bin/jadx`；输出 `/workspace/apk/spotify-9.1.90/`
- 优先字符串: `canvaz`, `Canvas`, `ShareCard`, `color-lyrics`, `i.scdn.co`, 分享「歌词」「MV」

---

## 8. 风险与约束

- Spotify 频繁 R8 → DexKit / 字符串特征定位，避免硬编码短命类名。
- 与 LyricProvider / spicy-ex 同进程注入时注意 OkHttp Headers Hook 冲突（PR #121 已用 DexKitCacheBridge 并提示多模块）。
- 仅保存用户可见的 Canvas/图/卡片/歌词；不碰 Widevine 音轨。


---

## 9. APK 与分析方法

| 项 | 值 |
|----|-----|
| 分析对象 | `/home/box/Downloads/spotify-9.1.90.2270.apk`（com.spotify.music 9.1.90.2270，sha256 `5c05366d…b3b4` 已校验） |
| 方法 | 解出 `classes*.dex`，用 `grep -a` 定位字符串所在 dex，再对**单个 dex** 跑 jadx（全量 6.8 万类过慢）。输出 `/workspace/apk/spx/dN`、dex `/workspace/apk/sp-x` |
| 混淆 | 业务类被 R8 重命名为 `p.xxxx`；**`com.spotify.*` 下 protobuf/player/share 包名大多未混淆**，`androidx.*`、`android.*` 未混淆 |

> **验证级别图例** — 🟢 静态分析确认（反编译代码里读到了该字符串/调用）；🟡 静态推断，设备上未跑；🔴 假设，未证实。
> **本阶段没有任何一项在真机上运行过。** 下表“已验证”仅指静态层面。

## 10. 锚点（9.1.90.2270，静态分析）

### 10.1 更多菜单注入（a）

| 项 | 锚点 | 级别 |
|----|------|------|
| 菜单 ViewModel 构造器 | 字符串 `"ContextMenuViewModel cannot contain items with duplicate itemResId"`，在 `p.yal.<init>(p.v7l header, List<p.v8l> items, boolean)` | 🟢 |
| 菜单项类 | `p.v8l(itemResId:String, icon:ydw?, icon2:s8l?, titleRes:Integer?, titleText:String?, a11yRes:Integer?, enabled:boolean, badge:l251?, action:u8l)`；`u8l(ah40, int, t8l, e520 onClick)`；`e520 = interface { Object invoke(Object) }` | 🟢 |
| 轨道菜单识别 | 稳定 itemResId 字符串 `artist_browse` / `album_browse` / `add_to_playlist` / `list_add_to_queue`（工厂 `p.ey1` 等 `new v8l("…")`） | 🟢 |
| 注入方式 | Hook 构造器、克隆已有 item（换 itemResId/标题/onClick 代理）追加 | 🟡（反射克隆依赖字段声明顺序；`e520` 调用约定未验证） |
| 菜单对应曲目 | 构造器不带曲目；模块使用“正在播放曲目”（MediaSession media-id），**对列表里非当前曲目的菜单会下错对象** | 🔴 已知限制 |
| 其它入口 | `CONTEXTMENU_TRACK/ARTIST/SHARE…`、`context-menu/view-artists` 等 URI 路由（`p.qj71`），未用于注入 | 🟢（仅记录） |

### 10.2 Canvas（b）

| 项 | 锚点 | 级别 |
|----|------|------|
| 数据来源 | 宿主把 Canvas 放在 `ContextTrack.metadata()`：键 `canvas.type`、`canvas.url`、`canvas.fileId`、`canvas.id`、`canvas.explicit`、`canvas.artist.name/uri/avatar`（`p.az0.b(ContextTrack)` → `p.mlc`） | 🟢 |
| 播放使用 | `p.cmc.O()`：优先 `canvas.fileId` → `spotify-video://<fileId>`（内部 scheme，不可下载），否则 `canvas.url`；失败日志 `"Canvas video URL is null or blank"`、`"CANVAS_VIDEO_PLAYBACK_ERROR"` | 🟢 |
| 非混淆 Hook 点 | `com.spotify.player.model.AutoValue_ContextTrack`（构造后读 `uri()` / `metadata()`） | 🟢 类存在；🟡 构造后取值 |
| 元数据来源 | 扩展元数据 `com.spotify.extendedmetadata.extensions.canvasimpl.proto.EntityCanvazResponse$Canvaz`（`CANVAZ`、`canvas-v1`）；`canvaz-cache/v0/canvases` 在本包 **未** 出现 | 🟢 |
| CDN | 字符串里**没有** `canvaz.scdn.co` 常量，URL 由服务端下发到 `canvas.url` | 🟢（没有硬编码）；`canvas.url` 是否总为 https mp4 直链 🔴 |
| 处理 | `canvas.url` 为 http(s) 才下载（OkHttp 无鉴权，MediaStore Movies/Spotify）；只有 fileId 时提示无直链 | 🟡 |

### 10.3 歌词（c）

端点（Retrofit，`@y820`/`@zc40({"Accept: application/protobuf"})`）：

* v2 `p.scd0`：`GET color-lyrics/v2/track/{trackId}?vocalRemoval&clientLanguage&preview` → `ColorLyricsResponse`；`…/v2/download/track/{trackId}?clientLanguage`
* v3 `p.wod0`（实现 `p.zod0`）：`GET color-lyrics/v3/track/{trackId}?vocalRemoval&clientLanguage&preview` → `LyricsWrapperResponse`；`…/v3/download/track/{trackId}?clientLanguage`

Protobuf（由 lite `newMessageInfo` 解码，🟢）：

```
v3  LyricsWrapperResponse{1:LyricsV3Response lyrics, 2:bool hasVocalRemoval}
    LyricsV3Response{1:syncType(enum) 2:lines* 3:previewLines* 4:Provider{1:name 2:lyricsId 3:displayName}
                     5:Translation{1:Language{1:code 2:isRtl} 2:string lines*} 6:Language 7:capStatus(enum)}
    LyricsLine{1:startMs 2:endMs 3:words 4:SyncData*{1:startMs 2:endMs 3:numChars}}
v2  ColorLyricsResponse{1:LyricsResponse 2:ColorData{background,text,highlightText} 3:hasVocalRemoval}
    LyricsResponse{1:syncType 2:lines* 3:provider 4:providerLyricsId 5:providerDisplayName 7:syncLyricsUri
                   9:Alternative*{1:language 2:string lines* 3:isRtl} 10:language 11:isRtl 14:capStatus
                   17:previewLines*(LyricProvider) 19:translationLanguages*}
    LyricsLine{1:startMs 2:words 3:Syllable*{1:startMs 2:numChars (3:endMs per LyricProvider)}}
```

* **翻译字段：有。** v3 `translation`（单语言，行数与 `lines` 一一对应）；v2 `alternatives` + `translationLanguages`。宿主 `p.gpd0.a` 把 v3 translation 映射成单元素“翻译列表”(语言代码, 行, isRtl)。🟢
* UI 侧：偏好键 `key_selected_lyrics_translation_language`（`p.m5q0`），Room 表 `lyrics_entities(... translations ...)`，UI tag `LyricsTranslationLineTag`、`LyricsCardTranslationHeaderButtonTag`。🟢
* **台湾区/语言如何影响：** 请求只有 `clientLanguage`（`p.lf60.j()`，推测为设备/应用语言）；没有 `market` 参数。是否返回翻译取决于服务端（曲目有翻译 + clientLanguage/账号市场）。模块提供 `clientLanguage` 覆盖（zh-Hans / zh-TW）与可选 `market` 查询参数。**“台湾账号一定返回中文翻译”与“`market` 被服务端接受”均未验证** 🔴。
* v3 `SyncData.startTimeMs` 是行内相对还是绝对未确认；解析器用启发式（首个起点 < 行起点 ⇒ 相对）🔴/🟡。
* 鉴权头：宿主 OkHttp 为 R8 重命名（`p.ad40` = `okhttp3.Headers`，构造 `(String[])`；`p.jlz0` = Request）。锚点字符串 `"Expected alternating header names and values"` 位于静态方法 `p.fh5.Y(String...)`，其返回类型即 Headers。🟢 定位；🟡 抓取。

### 10.4 艺人图（d）

| 项 | 锚点 | 级别 |
|----|------|------|
| ContextTrack 元数据 | `artist_uri`、`artist_name`、`canvas.artist.uri`、`canvas.artist.avatar`、`image_url`、`image_large_url`、`image_xlarge_url` | 🟢 |
| 图床 | `spotify:image:<hex>` → `https://i.scdn.co/image/<hex>`（`p.kej1`）；艺人头像 id 前缀 `ab6761610000{f178,5174,e5eb}`=160/320/640 | 🟢 前缀常量来自字符串样例，🟡 “放大前缀仍有效” |
| 高清图 | spclient `GET /metadata/4/artist/{gidHex}`（protobuf，librespot schema：portrait=11 / portrait_group=17）；gid=base62→hex。该端点**不在**宿主字符串里，来自社区已知接口 | 🔴 未验证 |
| 艺人页“header”大图 | Spotify 艺人页头图来自 GraphQL/Home 视图，未定位；本模块不处理 | — |

### 10.5 分享卡片（e）

| 项 | 锚点 | 级别 |
|----|------|------|
| 分享页包 | `com.spotify.share.sheet.sections.{preview.previewrow,offplatform,preview.disclosure}`（Compose，`ShareSheetPreviewSectionImpl` → `p.ip31`），`com.spotify.share.shareables.impl.ShareablesBitmapParseException`（“Can't convert shareables response to bitmap”，`p.le11`） | 🟢 |
| 卡片类型 | `share-menu.swatches.background-color-<hex|gradient>`、`…background-canvas-image`、`…background-canvas-video`（`p.kgj`）；歌词卡 `mobile-lyrics-card`（`p.s1j0`）；卡片配置 `com.spotify.sharecard.config.impl.proto.*` (`CardData/Quote/Line/Background/Video/Image`) | 🟢 |
| 位图→文件 | `Bitmap.compress(PNG,100,OutputStream)`（`p.rr20` case 13，异常 `com.spotify.share.file.api.bitmapconverter.CompressFileException` / `CreateFileException: Could not make shareable directory`） | 🟢 |
| 暴露方式 | `androidx.core.content.FileProvider.getUriForFile`（未混淆）；各目的地为 `ShareDestination(pkg, ACTION_SEND, mime, …)` 子类（`p.fa61` Snapchat video/\*、`p.dna0` 等） | 🟢 |
| 保存/分享路径 | **Spotify 自带分享面板内**加两个目的地「保存图片」「分享图片」（不再用系统 chooser）：`p.uas(int,List<xn31>)` 目的地 id 列表（`p.lg31.B`←`p.km31.a`）在 `copy-link`(`p.vm31`) 之后追加备用 id `p.jn31`/`p.tn31`；`p.tch0.o(xn31)`→`p.om31` 的懒加载 Map 注入 `p.aft`（官方“Download”目的地，锚点串 "Video file path is null"）的克隆实例；`aft.a/c/e/f` 按实例覆盖，`f(p.ao31,…)` 的 `ao31.a`(`p.yl31`)=当前选中卡片数据；标签用 `Resources.getText` 响应假 res id。日志前缀 `share-menu:`。`FileProvider.getUriForFile` 仅用于登记卡片与“自动保存” | 🟡 |
| 原生分享“更多”是否带图 | 宿主的通用分享 `Intent.createChooser(text/plain)`（`p.j970`）只含链接；卡片图是否经 FileProvider 暴露给系统 chooser 未证实，故另提供菜单项“保存最近的分享卡片” | 🔴 |
| 歌词/MV 卡片的 View/Compose 节点 | 未定位到可直接 `draw()` 的 View；未做 PixelCopy 方案 | — |

## 11. 实现状态与验证矩阵

| 功能 | 代码 | 单测 | 静态确认 | 真机 |
|------|------|------|----------|------|
| A Canvas 下载（菜单+TrackStore+MediaStore） | `CanvasHook` `TrackStore` `Actions.downloadCanvas` `MediaSaver` | URL/扩展名解析 | 🟢 键名、🟡 取值时机 | ❌ 未测 |
| B 词幕桥（逐字+翻译） | `LyricsBridgeHook` `SpotifyLyricsClient` `SpotifyLyricsParser` `LyriconMapper` `HeaderCapture` | v3/v2 解析、翻译对齐、词切片、映射、URL 构造 | 🟢 schema / 🟡 头抓取 | ❌ 未测 |
| C 艺人封面 | `ArtistCoverHook` `Actions.downloadArtistCover` `ArtistMetadata` | base62→gid、尺寸升级、portrait 解析 | 🟢 键名 / 🔴 metadata 端点 | ❌ 未测 |
| D 分享卡片导出 | `ShareCardHook` `ShareRegistry` | 文件判定谓词 | 🟢 调用点 / 🔴 chooser 是否带图 | ❌ 未测 |
| 菜单注入 | `MoreMenuHook` `DexResolver` | — | 🟢 锚点 / 🟡 反射克隆 | ❌ 未测 |
| 设置页 | `SettingsActivity`（Miuix 0.9.4 `SwitchPreference`/`ArrowPreference`） | — | — | ❌ 未测（可编译） |

## 12. 风险

* R8：混淆名按版本放在 `hook/HostSymbols.kt`（9.1.90.2270、9.1.98.521）；已知版本直接用表，运行时逐项结构校验，校验失败则用 DexKit（字符串锚点 + 结构）重新解析；未知版本直接走 DexKit。全部失败时该功能降级为“不可用”并写日志，不会崩溃（所有注入在 try/catch 内）。离线校验：`python3 scripts/verify_host_symbols.py`。
* DexKit 原生库在宿主进程加载：先 `System.loadLibrary("dexkit")`，失败则 `System.load("<module.apk>!/lib/<abi>/libdexkit.so")`（APK 内 .so 已 4K/16K 对齐且 Stored）。真机未验。
* 与官方 LyricProvider Spotify 模块并存会注册两个 Provider。
* 设置读取依赖 LSPosed 的 `xposedsharedprefs`；改设置后需强停 Spotify。
* 不触碰 Widevine/音频流。


## 11. 9.1.98.521 适配（2026-10）

框架层 Hook（MediaSession、Resources/TextView、FileProvider、Instrumentation）与非混淆锚点（`AutoValue_ContextTrack.uri()/metadata()`、
`explicit_badge_text_*`、canvas.* 元数据键、菜单 itemResId）在 9.1.98 未变。变化的只有 R8 名：

| 作用 | 9.1.90.2270 | 9.1.98.521 | 备注 |
|---|---|---|---|
| ContextMenuViewModel / item / action | `p.yal` / `p.v8l` / `p.u8l` | `p.ajm` / `p.ygm` / `p.xgm` | 结构不变（action 第二参 int→`p.wgm`，克隆逻辑按字段类型无影响） |
| okhttp3.Headers | `p.ad40` | `p.h360` | `(String[])` 构造器不变 |
| 分享 Download 目的地 | `p.aft` | `p.vqu` | 字段 a..f、方法 a/c/e/f 不变 |
| More 目的地（图标来源） | `p.k6k0` | `p.djm0` | |
| 目的地 id 类型 / 基类 / a() 声明类 | `p.xn31` / `p.om31` / `p.rm31` | `p.k471` / `p.c371` / `p.f371` | |
| id→目的地 解析 | `p.tch0.o` | `p.fmj0.k` | 懒加载 Map 在字段 `c` |
| 懒 Map 构建 | `p.cwm0.invoke` | `p.tcp0.invoke` | 字段 `b` |
| 目的地配置 | `p.uas(int, List)` | `p.nlt(List, int)` | **参数顺序互换** |
| 隐藏目的地集合 | `p.a041(tq10, Set, boolean, String)` | `p.vh71(Set, boolean, String)` | **Set 位置变化** |
| 最终列表 / 过滤 | `p.fpx.i` / `p.lle0.h` | `p.sr00.e` / `p.uwo1.o` | |
| kotlin.Unit / 集合工厂 | `p.aad1` / `p.s2j1.o` | `p.bhh1` / `p.hoo1.w` | |
| 备用 id（qr-code / users-nearby） | `p.jn31` / `p.tn31` | `p.w371` / `p.g471` | |
