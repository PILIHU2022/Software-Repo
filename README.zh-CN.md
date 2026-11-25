[English](README.md) | **简体中文**

[![Gadgetbridge](https://github.com/PILIHU2022/Software-Repo/actions/workflows/gadgetbridge.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/gadgetbridge.yml)
[![Fcitx5 for Android](https://github.com/PILIHU2022/Software-Repo/actions/workflows/fcitx5.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/fcitx5.yml)
[![Deploy Pages](https://github.com/PILIHU2022/Software-Repo/actions/workflows/deploy-pages.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/deploy-pages.yml)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)

# Software-Repo —— Patchwork 构建叠加仓库

这是一个面向六个 Android 应用——**ScreenTime**、**RecurringExpenseTracker**、
**Fcitx5 for Android**、**BasicSync**、**Gadgetbridge** 与
**BaiduNetGo Android 客户端**——一个桌面应用
**BaiduNetGo**，以及一个内置源码应用
**[DataArk](dataark/README.md)** 的个人构建仓库。前六个走补丁队列；DataArk 的
完整源码直接提交在 [`dataark/`](dataark/)（原创 Kotlin/Compose 应用数据备份工具，
支持 Root / Shizuku / Dhizuku 三种特权来源，GPL-3.0-or-later，图标来自
Android-DataBackup），其 CI（[.github/workflows/dataark.yml](.github/workflows/dataark.yml)）
默认**永久休眠**：2 月 30 日的 cron、不可达的分支过滤、再加 `if: false` 三重保险，
不会自动或手动触发。
BaiduNetGo 桌面版只在这里构建，它**不属于** Android / F-Droid 应用。仓库里不保存任何
应用源码，只保存按顺序应用的补丁队列，以及把补丁应用到上游当前代码、构建签名
APK、发布签名 F-Droid 仓库的 CI；桌面应用则构建 x86 安装包，发布成带 tag 的
GitHub Release 并附一份更新 feed。

> 个人项目，与上游作者（Dennis Bauer、Atharok、Fcitx5 for Android 贡献者、
> Andrew Gunnerson、
> pengstem）无关，也未获其背书。

## 这是什么

一个 *patchwork overlay*（补丁叠加）单仓库，只存两样东西，不存应用源码：

1. **补丁队列** —— Android 应用在 `apps/<app>/patches/*.patch`，桌面应用在
   `desktop/baidunetgo/patches/*.patch`，都能按顺序用 `git am -3` 应用到上游分支
   之上。监控的分支分别是：expenses `main`、screentime `main`、fcitx5 `master`
   （GitHub 上 fcitx5-android 的真实默认分支，不是 `main`；URL 不受影响）与
   baidunetgo `main`；
2. **构建与发布自动化** —— CI 拉取上游当前 `main`，应用补丁队列，改写版本号，
   构建签名 APK，并更新签名后的 F-Droid 仓库；另一条独立的 `baidunetgo.yml`
   只构建 x86 桌面安装包，把安装包发布成带 tag 的 Release，并提交更新 feed
   `desktop/baidunetgo/update.json`，完全不碰 F-Droid。

每次构建都把补丁重新应用到最新的上游提交上，因此上游的修复和新功能会自动进入
构建，而不需要长期维护一个已经分叉的 fork。补丁一旦无法应用，流水线会失败并开
issue，而不是继续发布过期代码。

## 应用

| 应用 | 上游（分支） | 上游提交 | 补丁数 | 构建版本 |
| --- | --- | --- | --- | --- |
| RecurringExpenseTracker | [DennisBauer/RecurringExpenseTracker](https://github.com/DennisBauer/RecurringExpenseTracker)（`main`） | `e8ac17f` | 20 | `0.21.4-patchwork` (6812) |
| ScreenTime | [Atharok/ScreenTime](https://gitlab.com/Atharok/ScreenTime)（`main`） | `ec95b22` | 11 | `1.4.2-patchwork`（1405，arm64-v8a） |
| Fcitx5 for Android | [fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)（`master`） | `eb5b5021` | 17 | `0.1.3-patchwork`（11207，arm64-v8a） |
| BasicSync | [chenxiaolong/BasicSync](https://github.com/chenxiaolong/BasicSync)（`master`） | `74b1dea` | 4 | `3.29-patchwork`（25604802，arm64-v8a） |
| Gadgetbridge | [Freeyourgadget/Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)（`master`） | `93d4d79` | 6 | `2026.11.1-patchwork`（25700） |
| BaiduNetGo（桌面） | [pengstem/baidunetgo](https://github.com/pengstem/baidunetgo)（`main`） | `60ca12c` | 48 | `0.8.3.48` —— 上游 `0.8.3` + 48 个补丁（linux/windows x86、Arch 包；Release + feed） |
| BaiduNetGo（Android） | [pengstem/baidunetgo](https://github.com/pengstem/baidunetgo)（`main`）—— 与桌面版共用同一条队列 | `60ca12c` | 48 | `0.8.3-patchwork`（80348 —— 上游 803 × 100 + 48 个补丁） |

五个 Android 应用发布在 F-Droid。**BaiduNetGo 桌面版不发布**：它是 Go/MyGo 桌面程序，
按自己的 10 天周期构建：安装包（`.deb`、`.tar.gz`、Arch Linux 的
`.pkg.tar.zst`（用 `sudo pacman -U` 安装）、Windows 安装版/便携版 `.zip`）发布成
`baidunetgo-desktop-<version>-<short_sha>` 的 Release，同时保留
`baidunetgo-x86-<sha>` artifact；Linux 上下载更新后可以就地安装并重启，pacman
装的副本则改为提示用 `pacman -U`。每次构建还会提交更新 feed
`desktop/baidunetgo/update.json`（地址
<https://raw.githubusercontent.com/PILIHU2022/Software-Repo/main/desktop/baidunetgo/update.json>，已内置进二进制），永远不会变成 APK。

**ScreenTime** —— 每日使用记录持久化到本地 SQLite 数据库，新增基于该历史的分析页
（时长表格从 0 递增、星期进度条与柱图从 0 生长；新增独立的「分析」设置分类和本地
历史说明）；主界面与应用详情的 Canvas 图表也改为从基线生长；用 Compose `Canvas`
图表替换 Vico；缓存已栅格化的应用图标；启动时不再有主线程阻塞工作。

**RecurringExpenseTracker（Expenses）** —— Tilla 风格界面（服务品牌色磁贴、内置
92 个服务图标目录、编辑页图标选择器、按名称推荐图标）、普通 `LazyColumn` 列表、
备份/恢复修复（重启数据库进程并按名称推断图标），以及全新的 Tilla 风格**日历**
tab：月历卡片使用 7 列 1:1 日格和按 locale 推断的每周首日；日格显示日期、18dp
着色服务图标、同一天多笔付款的 `+N` 徽标和已付款对勾；月卡下方是当日详情卡片；
支持箭头与横向滑动换月；整月投影在主线程之外计算；提供英文、简体中文、繁体中文
文案。后续补丁还加入了 Tilla 风格主界面顶部栏（排序/筛选/菜单）、分析页、
存档页、暂停/恢复（数据库 v13）、拆分后的设置中心（7 套预设主题、含 Catppuccin）
以及可自定义的卡片布局。规格见 [`docs/design/tilla-calendar.md`](docs/design/tilla-calendar.md)。

**Fcitx5 for Android** —— Fcitx5 输入法框架及其引擎的 Android 移植，从上游
`master` 加四个补丁构建：新增 `MANAGE_EXTERNAL_STORAGE` 权限，让 librime 能写入
共享存储里的 `sync_dir`（例如 `Download/Rime-Sync`）；内置 Rime/Fcitx5 配置文件
管理器与 UTF-8 编辑器，并在启动时清理残留的 `.temp.userdb`；文件管理器的返回
箭头改为水平方向；以及一个 Syncthing 桥接。Android 不允许其他应用写 Rime 的
应用私有目录，因此应用会在需要时把 `Download/Rime-Sync/Rime-Arch` 复制进去、
删除 `build/` 并重启 Fcitx 触发重新部署；入口是「高级」→「从 Syncthing 应用
Rime 配置」，也可用
`adb shell am broadcast -a org.fcitx.fcitx5.android.action.RIME_IMPORT
-n org.fcitx.fcitx5.android/.receiver.RimeImportReceiver`（没有变化时不重建）。
另一个补丁让 `build-logic` 读取下游的 `PATCHWORK_REVISION` 环境变量；该变量未
设置时行为与上游完全一致，官方/F-Droid 构建不受影响。只发布 `arm64-v8a`
（上游按 ABI 拆包、没有 universal APK），APK 约 52 MB。

**Gadgetbridge** —— 不用厂商应用也能用手环/手表。补丁队列在上游之上加六个补丁：

* **界面迁移到 Jetpack Compose + Material 3 Expressive**：设置树（首页、每个子页面、
  搜索）、首次运行引导、关于页与睡眠评分面板都是 Compose 渲染，其余仍走 XML 的
  页面则通过统一的 Expressive 圆角叠加获得同样的观感，改主题/强调色时两套界面一致；
* **首次运行权限页支持一次点击依次申请全部权限**：先批量申请运行时权限，再打开
  “不限制后台用电”，随后继续通知访问、自启动等特殊权限；每个系统权限仍需逐项确认，
  如果直接请求电池豁免不可用，会回退到系统的电池优化列表；
* **基于年龄的睡眠评分**：设备自己不上报评分时，用已记录的睡眠阶段对照该年龄建议的
  睡眠时长打分（含深睡占比、REM 占比与睡眠效率），显示在日/周图表与仪表板小组件里，
  **点圆环可展开评分构成**，并可在图表设置里关闭。睡眠按醒来日归属：21 日
  23:00 至 22 日 07:00 的一夜计入 22 日；
* **每次打开应用都备份**——那是进程一定还活着、一定允许写的时刻——有独立的设置
  分类、保留天数、可更改的备份文件夹与「恢复默认目录」，不再只依赖被国产 ROM
  经常跳过的 WorkManager；
* **数据库导出先写临时文件**、校验 SQLite 头，通过后才写入目标，因此授权失效、
  文档被移动等失败不会再截断上一份备份，导出位置为空时也会明确报错；
* **小米协议的音乐与校时不再自相矛盾**：播放状态或元数据变化时下发完整快照
  （播放状态、标题、歌手、进度、时长），未变化的重复包不再发送，只有状态的回调
  不会再吞掉先到的元数据更新，元数据更新也不会把手环重置成“正在播放、0:00”；
  Release 构建由环境变量提供的密钥签名；连接后手环回一次设备状态再补发一次时间；
* **下滑刷新不会永久卡住**：设备未连接、仍在初始化或上一次同步尚未结束时，刷新
  请求可能被跳过；主界面现在会结束这个等不到完成广播的转圈状态，避免
  `SwipeRefreshLayout` 吞掉之后的手势。正常完成的取数仍由原有广播收尾；30 秒是
  等待上限，较慢的同步也可能先隐藏转圈、继续在后台进行。设备就绪后可以直接再次
  下拉同步。

Release APK 由本仓库签名而不是上游：`PATCHWORK_SIGN_*` 交给队列里的一个小构建补丁，
所以 F-Droid 仓库里的包名与官方一致、签名不同。

**BasicSync** —— 在 Android 上运行 Syncthing 的小型 Kotlin/Compose 外壳，它本身
只决定 Syncthing *什么时候*运行。补丁队列在 3.29 之上加四个补丁：

* 应用现在**直接打开同步页面**——Syncthing 的 Web 界面正是这个应用存在的意义，
  不再藏在设置列表第二层；同时新增**侧边栏**（手机上是抽屉，平板/电视上是常驻
  侧栏），放常用页面：同步、同步冲突（带数量角标）、设置、网络条件、同步计划，
  顶部显示应用版本与 Syncthing 实时运行状态。侧栏的 `WebView` 由 Activity 持有，
  切换页面时 Web 界面保留 DOM 状态而不会重新加载；
* **冲突管理页面**：每个同步冲突都显示所在目录、创建时间和三个操作——*使用冲突版本*
  （用冲突副本替换原文件）、*保留原文件*（删除冲突副本）、*打开所在目录*——破坏性
  操作都有确认框，逐条显示进度，并给出可读的错误信息。直接存储与 SAF 目录都支持，
  两者都复用 Syncthing 已经上报的 document URI；
* Gradle 构建读取 `PATCHWORK_VERSION_CODE`、`PATCHWORK_VERSION_NAME` 与
  `PATCHWORK_ABI`：上游的 versionCode 由 `git describe` 推导（提交数字段只有 5 位，
  长期维护的队列会溢出），而且默认要为四种 ABI 各编一遍 Go/Syncthing 运行时。
  三个变量在上游构建里都不存在，因此不影响上游行为。

**BaiduNetGo（桌面，不进 F-Droid）** —— 基于 pengstem 的纯 Go 百度网盘客户端做的
社区修复版：查重、应用内文件剪贴板与撤销、Finder 风格的框选与拖动移动、窗口/字体
自适应，以及可配置的更新检查 feed；界面由 MyGo 原生 GPU 绘制。由
`scripts/build-baidunetgo.sh` 从 `desktop/baidunetgo/patches` 构建，**只出 x86**
（`linux/amd64`、`windows/amd64`），每次都把队列用 `git am -3`（导出成补丁的
rebase 形式）重放到新的上游头上；另外 `scripts/build-baidunetgo-arch.sh` 会在
`archlinux:base-devel` 容器里跑 makepkg，补上 Arch Linux 的 `.pkg.tar.zst`
（`sudo pacman -U <文件>` 安装）。安装包发布成带 tag 的 Release 并保留
`baidunetgo-x86-<sha>` artifact；Linux 上应用可以就地安装下载的更新并重启，
pacman 装的副本会拒绝并提示用 `pacman -U`。每次构建还提交
`desktop/baidunetgo/update.json`（应用在「设置 → 更新」里读的 feed，地址
<https://raw.githubusercontent.com/PILIHU2022/Software-Repo/main/desktop/baidunetgo/update.json>
已内置进二进制），不进入 F-Droid。

**BaiduNetGo（Android）** —— 同一条队列的另一半：Material 3 Expressive 的
Compose 客户端（包名 `com.pengstem.baidunetgo`），扫码登录、浏览网盘，并按计划
把选中的文件夹与相册备份到网盘 —— 每个上传文件都会用 OpenPGP 对称加密成
`.gpg`，任何电脑上的 `gpg -d` 都能解开，与桌面端备份写出的格式一致。桌面版和
Android 版重放同一条 `desktop/baidunetgo/patches` 队列、构建自同一个上游提交
（`scripts/build-baidunetgo.sh --prepare` 准备好树后，Android 的一半由
`scripts/build-baidunetgo-android.sh` 从其 `android/` Gradle 工程构建，SDK/JDK
步骤与其他应用相同）。签名后的 APK 有三个出口：带 tag 的 GitHub Release
（`baidunetgo-android-<version>-<short_sha>`，不标 `latest`）、F-Droid 仓库，
以及应用内更新 feed `fdroid/baidunetgo/update.json`
（<https://software.sparkzh.top/baidunetgo/update.json>，由
`scripts/baidunetgo-android-feed.py` 在每次构建后重新生成，
`deploy-pages.yml` 随 `fdroid/` 一起部署）。它与其它应用一样用仓库里的
patchwork 测试密钥签名；上游树里的 `dev.jks` 只是开发用密钥，从不参与发布。

两个带预编译原生依赖的 Android 应用（RecurringExpenseTracker、ScreenTime）都只编译
**ARM**：其 `app.conf` 里的 `KEEP_ABIS="arm64-v8a armeabi-v7a"` 会让
`build-app.sh` 在 Gradle 之后删掉 `lib/x86/`、`lib/x86_64/`，再按 16 KB 页对齐并用
v2/v3 重新签名。Fcitx5 的 `arm64-v8a` 构建保持不变。

## F-Droid 仓库

仓库地址（自定义域名，由 GitHub Pages 托管、CDN + TLS；**已上线并验证**）：

```
https://software.sparkzh.top/repo
```

索引返回 HTTP 200，`index-v2.json` 中列出 `de.dbauer.expensetracker` **6806**、
`com.atharok.screentime` **1303** 与 `org.fcitx.fcitx5.android` **11202**。
站点首页：<https://software.sparkzh.top/>。
GitHub Pages 地址 <https://pilihu2022.github.io/Software-Repo/repo> 会 301 跳转到
这个域名。

在 F-Droid 客户端中添加：**设置** → **存储库** → **添加存储库** → 粘贴上面的地址
（必须以 `/repo` 结尾）→ 在仓库详情中核对指纹。签名证书：

```
SHA-256: D3:34:88:79:40:65:C9:2E:37:0D:24:A1:0E:61:4E:1E:64:E3:19:B7:5E:6F:8B:B7:C4:AA:D6:7F:3A:5B:5E:01
```

> 包名与官方应用完全相同，但签名密钥不同。Android 会拒绝跨密钥覆盖安装，请先卸载
> 官方版本 —— 卸载会删除应用数据，请先备份。三个 Android 应用都是如此，Fcitx5 for Android
> 也一样：从官方版或官方 F-Droid 构建切换过来必须先卸载，`Download/Rime-Sync`
> 下的 Rime 用户数据请自行备份。

Pages 不可用时的 raw 备用地址：

```
https://raw.githubusercontent.com/PILIHU2022/Software-Repo/main/fdroid/repo
```

（raw.githubusercontent.com 不提供目录列表；把它当作仓库地址使用，F-Droid 会在其下
请求 `index-v2.json`。）

两个地址提供的是同一份签名索引。索引里始终写正式 Pages 地址，因此用 raw 地址添加
仓库时，部分客户端会提示地址不一致；内容完全相同。

每个已发布应用的 512×512 图标源在 `fdroid/icons-src/<package>.png`。发布脚本会把它
复制到 `fdroid/repo/<package>/en-US/icon.png`（index-v2）和
`fdroid/repo/icons/<package>.<versionCode>.png`（旧 index-v1），随后给 index-v1 补
`icon` 字段并重新签名。新增包时 `fdroid update` 可能顺带删掉其他包已有的
content-addressed index-v2 图标，图标脚本会从同一批源文件恢复它们。这些 APK 用的是
自适应 XML 图标，`fdroid update` 无法直接提取，所以图标由仓库提供。新增 app 时
记得添加对应的图标源文件。

## 自动化流程

每个 app 都有自己的流水线工作流——`expenses.yml`、`screentime.yml`、`fcitx5.yml`、
`basicsync.yml`、`gadgetbridge.yml`——每个文件只声明那个 app 的触发条件，具体工作交给
可复用的 `app-build.yml`。流水线只为自己那个 app 启动：`push` 只匹配
`apps/<app>/**`（加该工作流文件本身），因此改 A 应用不会启动 B 应用的构建，纯文档或
`scripts/**` 提交则完全不触发 Actions。

每条流水线每天在自己的分钟数跑一次；GitHub 的 cron 无法直接表达“每 10 天”
（`*/10` 的日规则会在 1、11、21、31 号触发），所以运行时会检查
`.watch-state.json` 里该 app 的时间戳，只有满 10 天**且**上游 SHA 与
`fdroid/.build-state.json` 不同才构建。延迟或漏掉一天只是把该 app 的窗口顺延。

上游有提交时，构建前会先重锚队列：`scripts/rebase-queue.sh` 取新 head、用
`git am -3` 应用队列、把应用后的提交导出回 `apps/<app>/patches`（沿用原文件名），
并把新锚点写进 `apps/<app>/upstream.txt`，由 watch 任务以 `[skip ci]` 提交。队列
已经无法应用时，运行失败并开 issue，而不是回退到旧提交发布过期代码；10 天窗口
只有真正构建并发布成功的那次运行才会推进。

`app-build.yml` 里的任务，一次只跑一个 app：

1. **watch** —— `git ls-remote` 取该 app 的上游分支，与 `fdroid/.build-state.json`
   比较，过 10 天闸门，重锚队列；
2. **build** —— 在 `.cache/mirror/<app>.git` 维护 mirror、创建全新检出、按需递归
   初始化子模块（fcitx5）、用 `git am -3` 应用补丁队列、处理版本号、Gradle
   release 构建、keystore 签名；设置了 `KEEP_ABIS` 的应用随后删除非 ARM ABI、
   按 16 KB 对齐并重新签名，再用 `apksigner` / `aapt` 校验。fcitx5 任务还会额外
   释放约 10 GB runner 磁盘并安装 `extra-cmake-modules`/`gettext`、NDK
   28.0.13004108、CMake 3.31.6、build-tools 36.1.0；
3. **publish** —— `publish-fdroid.sh` 每个包最多保留 3 个 APK，按
   `(package, versionCode)` 去重，用 fdroidserver（`fdroid update`）签名索引，把
   `fdroid/repo` 与 `.build-state.json` 以 `[skip ci]` 提交回 `main`，更新 README
   表格并部署 Pages。构建失败时保留旧状态，该 app 下次运行会重试；
4. **record** —— 只有在 build 与 publish 都成功后才推进该 app 的 10 天窗口；
5. **notify** —— 打开或评论 **“Patchwork build failed”** issue（附运行链接与本地
   复现命令），恢复后再关闭。

不再有“一次构建所有 app”的工作流：`app-build.yml` 可复用、只接收 app id，手动
构建就派发对应 app 的工作流（或本地跑 `scripts/build-app.sh <app>`）。

`baidunetgo.yml` 是桌面版对应的工作流，触发形状与其它 app 一致：`desktop/baidunetgo/**`
（或该工作流文件）的 push 立即构建，手动 dispatch 也总是构建；每天（`43 4 * * *`）运行，按
`.watch-state.json` 的 `baidunetgo` 时间戳过 10 天闸门，用 `git ls-remote` 解析
`pengstem/baidunetgo` 的 `main`；SHA 与 `desktop/.build-state.json` 不同时，把
`desktop/baidunetgo/patches` 用 `git am -3 --keep-cr` 重放到新 head（不适用时回退到上次成功
构建的提交重试一次），用 `go tool mygo build` 构建 `linux/amd64,windows/amd64`，
上传 `baidunetgo-x86-<sha>` artifact，同时把安装包发布到
`baidunetgo-desktop-<version>-<short_sha>` 的 Release（`.deb`、`.tar.gz`、在
`archlinux:base-devel` 容器里用 makepkg 打出的 Arch `.pkg.tar.zst`、Windows
安装版/便携版 `.zip`，不标 `latest`），并提交
`desktop/baidunetgo/update.json`——补丁版在「设置 → 更新」里检查的 feed，地址
<https://raw.githubusercontent.com/PILIHU2022/Software-Repo/main/desktop/baidunetgo/update.json>
在构建时内置进二进制。回退构建不会记录新 head，下一个 10 天窗口会继续重试。
同一条工作流里的第二个任务会构建队列中的 Android 客户端（与 app-build.yml 相同的
SDK/JDK/keystore 步骤），用 patchwork 密钥签名，发布到
`baidunetgo-android-<version>-<short_sha>` Release 与 F-Droid 仓库，并提交
Android 更新 feed `fdroid/baidunetgo/update.json`
（<https://software.sparkzh.top/baidunetgo/update.json>，由 `deploy-pages.yml`
部署）。10 天窗口在检查完成且无需构建、或两个构建都成功后推进；构建失败不推进，
下一天的定时运行会重试。

最近一次完整构建
（[run 40076793167](https://github.com/PILIHU2022/Software-Repo/actions/runs/40076793167)）
是绿色的。Pages 需要由仓库所有者手动启用一次（Settings → Pages → Build and
deployment → Source: “GitHub Actions”）；`GITHUB_TOKEN` 无权创建 Pages 站点。
站点发布在自定义域名 `software.sparkzh.top` 上（`fdroid/CNAME` 负责保持绑定），
`pilihu2022.github.io` 的地址会 301 跳转过去。

`deploy-pages.yml` 是每小时一次的安全网（`17 * * * *`，以及 `fdroid/**` 的推送）：
它只发布已提交的 `fdroid/`，不重新构建，Pages 不可用时也保持绿色。

## 版本号策略

```
versionCode = 上游 versionCode × 100 + PATCHWORK_REVISION
versionName = "<上游 versionName>-patchwork"
```

`PATCHWORK_REVISION` 默认取 `HEAD` 上触及 `apps/<app>` 整个目录的提交数（上限
99，可用同名环境变量覆盖）。统计整个应用目录而不只是补丁队列，是为了让
`KEEP_ABIS` 这类非补丁改动也能发布一个可升级的新包：任何下游提交都会让
`versionCode` 增加，即使上游版本号没变。

| 应用 | 上游 versionCode / versionName | 应用目录提交数 | 构建结果 |
| --- | --- | --- | --- |
| ScreenTime | `13` / `"1.4.1"` | 4 | `1304` / `"1.4.1-patchwork"` |
| RecurringExpenseTracker | `68` / `"0.21.4"` | 11 | `6811` / `"0.21.4-patchwork"` |
| Fcitx5 for Android | `112`（arm64-v8a）/ `"0.1.3"` | 7 | `11207` / `"0.1.3-patchwork"` |
| BasicSync | `256048` / `"3.29"` | 2 | `25604802` / `"3.29-patchwork"` |
| Gadgetbridge | `257` / `"2026.11.1"` | 5 | `25700` / `"2026.11.1-patchwork"` |

fcitx5 的上游 versionCode 本身就按 ABI 区分（`baseVersionCode × 10 + ABI id`，
arm64-v8a 的 id 为 2），所以完整公式是：

```
versionCode = (baseVersionCode × 10 + abiId) × 100 + PATCHWORK_REVISION
```

fcitx5-android 的 `app/build.gradle.kts` 里没有 `versionCode` 行，版本号由
build-logic 的 `Versions.calculateVersionCode()` 计算。因此补丁 `0003` 让该函数
读取可选的 `PATCHWORK_REVISION` 环境变量，`build-app.sh` 则以
`VERSION_STYLE=build-logic` 导出它和 `BUILD_ABI`、`BUILD_VERSION_NAME`，不再改写
文件。变量未设置时返回上游原值。由于保留了 per-ABI 顺序
（`111xx < 112xx < 113xx`），以后补发 `armeabi-v7a` 也不会与 arm64 版本冲突。

**BasicSync** 又是另一种情况：上游用 `git describe` 计算 versionCode，形式为
`major << 16 | minor << 11 | 提交数 << 3 | abi`，下游队列会吃掉只有 5 位的提交数
字段，超过 31 个提交就会撞进下一个版本的号段。因此 `build-app.sh` 的
`VERSION_STYLE=pinned` 分支从应用后的工作区里读出 `v<major>.<minor>-<count>-g<sha>`，
重建上游那部分编号，并通过环境变量把结果钉死：

```
versionCode = (major << 16 | minor << 11 | count << 3) × 100 + PATCHWORK_REVISION
versionName = "<major>.<minor>-patchwork"
```

每次构建只编一个 ABI（`BUILD_ABI=arm64-v8a`），所以不再叠加 ABI 位，
每个 APK 就是它自己架构的版本；补丁同时收窄 ABI 拆分，让内嵌的 Go/Syncthing
运行时只编一次。

**BaiduNetGo（Android）** 采用同一公式，revision 取补丁队列长度（没有
`apps/<app>/` 目录可数提交）：

```
versionCode = 803 × 100 + 补丁数        （48 个补丁 -> 80348）
versionName = "<上游 versionName>-patchwork"   （0.8.3 -> "0.8.3-patchwork"）
```

`scripts/build-baidunetgo-android.sh` 在构建前改写 prepared 树中的
`android/app/build.gradle.kts`（与 `build-app.sh` 相同的文件改写方式），补丁数
超过 99 时同样直接报错。桌面版自己的版本策略（`0.8.3` + `.<补丁数>` =
`0.8.3.48`）不变。

## 仓库结构

```
.
├── README.md                  # 英文说明（本仓库默认首页）
├── README.zh-CN.md            # 简体中文说明（本文件）
├── LICENSE                    # GPL-3.0-only
├── apps/
│   ├── expenses/{app.conf,patches/}     # 0001..0018，KEEP_ABIS=ARM
│   ├── screentime/{app.conf,patches/}   # 0001..0011，KEEP_ABIS=ARM
│   ├── fcitx5/{app.conf,patches/}       # 0001..0017
│   └── basicsync/{app.conf,patches/}    # 0001..0004，VERSION_STYLE=pinned
├── desktop/
│   ├── baidunetgo/{app.conf,patches/}   # 0001..0041，桌面版 + Android 客户端
│   └── .build-state.json                # 上次成功构建的上游提交
├── .watch-state.json          # 每个 app 的上次 10 天窗口
├── scripts/
│   ├── build-app.sh           # 拉上游、打补丁、改版本、构建、校验
│   ├── baidunetgo-feed.py     # 生成更新 feed（update.json）
│   ├── baidunetgo-android-feed.py # 生成 Android 更新 feed
│   ├── build-baidunetgo.sh    # 拉上游、rebase 补丁队列、构建 x86 桌面版
│   ├── build-baidunetgo-android.sh # 构建并签名队列中的 Android 客户端
│   ├── build-baidunetgo-arch.sh # 容器里跑 makepkg，打 Arch 包
│   ├── check-upstream.sh      # 上游 SHA 差异 -> CI 矩阵
│   ├── publish-fdroid.sh      # 发布 APK 并签名索引
│   ├── test-baidunetgo-feed.sh # 上面那个生成器的回归测试
│   ├── test-baidunetgo-android-feed.sh # Android feed 的回归测试
│   ├── test-publish-fdroid.sh # 发布脚本的回归测试
│   └── sync-patches.sh        # 从作者仓库同步补丁队列
├── fdroid/
│   ├── config.yml
│   ├── metadata/*.yml
│   ├── repo/                  # APK + 索引，由 CI 提交
│   └── .build-state.json
├── signing/patchwork-test.jks # 故意公开的测试签名密钥
├── docs/design/               # 日历规格、Expenses 架构图谱
└── .github/workflows/{build,baidunetgo,deploy-pages}.yml
```

## 本地构建

expenses / screentime 的前置条件：bash、Git、JDK 21、Android SDK 37（预览版
platform 与 build-tools），以及用于剥离非 ARM ABI 的 `zip`。它们不需要 NDK。

桌面版需要 Go（版本以拉下来的上游 `go.mod` 为准）、`zip`，以及 Windows 安装包所需的
NSIS（`makensis`）。MyGo 不需要 cgo，所以 `linux/amd64` 与 `windows/amd64` 都能在
任意机器上构建。

BasicSync 在纯 Gradle 应用里工具链最特殊：Android SDK 包
`build-tools;37.0.0`、`platforms;android-37.0`、`ndk;30.0.16248370`（
`app/build.gradle.kts` 钉住的 NDK 版本），`PATH` 中要有 Go 工具链，还要
`external/go` 与 `external/syncthing` 两个钉住的子模块（约 200 MB），因为应用用
`gomobile` 把 Syncthing 当成 Go 库编译进去。首次构建要现编 Go 工具链和 gomobile
本身，约 8–12 分钟，之后复用缓存。`apps/basicsync/app.conf` 设置了
`SUBMODULES=recursive`，因此 `build-app.sh` 在 Gradle 之前拉子模块；`--no-build`
会在此之前停下。`$HOME` 不可写时要把 `GOCACHE` 指到仓库内（或任何可写目录）。

Fcitx5 for Android 需要更多：Android SDK 包 `platforms;android-36`、
`build-tools;36.1.0`、`cmake;3.31.6`、`ndk;28.0.13004108`，宿主机包
`extra-cmake-modules` 与 `gettext`（`msgfmt`），19 个 git 子模块（约 450 MB，
其中 `prebuilt` 是 364 MB 的 shallow 仓库），以及约 13 GB 空闲磁盘用于 SDK、
Gradle 缓存、检出和构建产物。4 vCPU 机器预计 15–30 分钟（16 核且缓存已热时约
3 分钟）。`apps/fcitx5/app.conf` 设置了 `SUBMODULES=recursive`，脚本会在 Gradle
之前执行 `git submodule update --init --recursive`；`--no-build` 在此之前就结束，
不会下载子模块。

未设置的 `ANDROID_HOME`、`ANDROID_USER_HOME`、`GRADLE_USER_HOME` 默认指向
`<repo>/.toolchain/`：

```bash
export ANDROID_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-sdk
export ANDROID_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-home
export GRADLE_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/gradle-home

./scripts/build-app.sh expenses --no-build    # 只拉上游 + 打补丁 + 改版本
./scripts/build-app.sh expenses              # 完整构建，输出到 dist/
./scripts/build-app.sh screentime --out dist
./scripts/build-app.sh fcitx5 --no-build     # 拉上游 + 补丁，不下载子模块（11202）
./scripts/build-app.sh fcitx5 --out .toolchain/tmp/fcitx5-dist
./scripts/check-upstream.sh                  # 输出 JSON；--all/--apps 选择 app
./scripts/publish-fdroid.sh --apk-dir dist   # 重建 fdroid/repo 与索引
./scripts/sync-patches.sh --check            # 对比作者仓库与补丁队列
./scripts/test-publish-fdroid.sh dist/RecurringExpenseTracker-0.21.4-patchwork.apk

# 桌面版（只出 x86）：--prepare 拉上游并 rebase 补丁队列，--build 再编译。
# --out 默认是 dist/baidunetgo。
./scripts/build-baidunetgo.sh --prepare
./scripts/build-baidunetgo.sh --build
PATCHWORK_PLATFORMS=linux/amd64 ./scripts/build-baidunetgo.sh   # 本地没有 NSIS 时

# 同一条队列的 Android 客户端：先 --prepare，再跑 Gradle 构建并签名
# （需要上面的 JDK 21 与 ANDROID_HOME）。默认使用仓库里的 patchwork 测试密钥。
./scripts/build-baidunetgo-android.sh --out dist/baidunetgo-android
./scripts/test-baidunetgo-android-feed.sh
```

`dist/build-info.txt` 记录 `upstream_commit`、`patch_count`、`version_code`、
`version_name`、`variant`、`built_at`、`apk`、`apk_size`、`sha256`；按 ABI 构建的
应用（如 fcitx5）还会多一行 `abi`，只出 ARM 的应用多一行 `abis`。桌面构建把同类
信息写到 `dist/baidunetgo/`（或 `--out` 指定目录）下的 `build-info.txt`：
`upstream_head`、`upstream_commit`、`upstream_fallback`、`patch_count`、`version`、
`platforms`、`built_at`，以及每个安装包一行 `file=`（含大小与 SHA-256）。构建
缓存（`.cache/`）和产物（`dist/`）都已 gitignore。

如果本地 GitHub HTTPS 拉取卡住（例如代理损坏），只对那一次运行改用 SSH，不要改
脚本：

```bash
GIT_CONFIG_COUNT=1 \
GIT_CONFIG_KEY_0='url.ssh://git@github.com/.insteadOf' \
GIT_CONFIG_VALUE_0='https://github.com/' \
GIT_SSH_COMMAND='ssh -F /dev/null -o BatchMode=yes' \
./scripts/build-app.sh fcitx5
```

## 更新补丁

补丁仍在五个独立的 app clone 中开发；本仓库只消费导出的补丁文件。expenses 与
screentime 的队列放在 `.patchwork/patches/`（或 `patchwork` 分支）；fcitx5-android
的队列在 `feat/rime-sync-manager` 分支上，用
`git format-patch master..feat/rime-sync-manager` 导出；BaiduNetGo 的队列则直接
叠在 `main` 上（上游 + 社区提交），相对 `origin/main` 导出。

```bash
# 1. 在 app clone 中从上游 main 建 feature 分支并开发
cd apps/ScreenTime
git switch -c feat/my-change main
# ... 修改、构建、提交 ...

# 2. 按现有补丁的格式导出队列
rm -f .patchwork/patches/*.patch
git format-patch main..feat/my-change \
  -o .patchwork/patches --no-signature --zero-commit --no-numbered

# 3. 同步进本仓库，提交并推送；CI 会自动重建
cd ../..
./scripts/sync-patches.sh
git add apps/screentime/patches
git commit -m "Update ScreenTime patch queue"
git push origin main
```

BaiduNetGo 的队列从作者 clone 的 `main` 导出（没有 `.patchwork/` 目录）：

```bash
cd desktop/baidunetgo-clone
git fetch origin
rm -f ../baidunetgo/patches/*.patch
git format-patch --no-signature --zero-commit --no-numbered \
  origin/main..main -o ../baidunetgo/patches

cd ..
git add desktop/baidunetgo/patches
git commit -m "Update the BaiduNetGo patch queue"
git push origin main
```

队列同时承载应用的两半 —— 桌面客户端，以及 Android 客户端加入之后的
`android/` Gradle 工程 —— 其中一个补丁带入了 CRLF 行尾的
`android/gradlew.bat`。裸的 `git am` 会剥掉所有行尾 CR（mailinfo 把补丁当作
CRLF 格式的邮件处理），因此 `build-baidunetgo.sh` 用
`git am -3 --keep-cr` 重放队列：重放出的树与作者 clone 的 `main` 树逐字节一致。

`sync-patches.sh` 只认识三个 Android clone；桌面队列按上面的命令手动导出（工作流
会在 10 天内重建，也可以手动运行 `baidunetgo.yml`）。

`./scripts/sync-patches.sh --check` 只报告差异（不同步时退出码为 1），适合 CI 或
本地提交前检查。对 expenses / screentime，如果 clone 不在 `patchwork` 分支，脚本
会通过 `git archive` 从该分支读取队列，不会修改 clone；对 fcitx5，脚本从
`feat/rime-sync-manager` 导出，clone 不可用时回退到
`.toolchain/tmp/fcitx5-patches` 里的暂存队列（可用 `FCITX5_PATCHES_DIR` 覆盖；
`FCITX5_BRANCH`、`FCITX5_BASE` 选择分支与基线）。

补丁必须保持“从上游 `main` 导出”的形式，并且仍能用 `git am -3` 应用；CI 检出时
使用 `fetch-depth: 0`，保证三方合并有完整历史。

## 签名 / 安全说明

**`signing/patchwork-test.jks` 是故意提交到仓库的测试密钥**，alias 为
`patchwork`，密码为 `patchwork123`。

- CI 不需要任何 secret 就能构建签名 APK；
- 任何能读取本仓库的人也能用同一把密钥签署更新包；
- 它不是安全边界，只适合个人自用。

如需替换：新建 keystore，把 base64 内容放进 `PATCHWORK_KEYSTORE_BASE64` secret，
并设置 `PATCHWORK_STORE_PASSWORD`、`PATCHWORK_KEY_ALIAS`、
`PATCHWORK_KEY_PASSWORD`；同时修改 `fdroid/config.yml`，让索引用新密钥签名。
更换密钥后，F-Droid 会把它当作另一个仓库，已安装的应用必须先卸载才能更新。

## 文档

- [`docs/design/tilla-calendar.md`](docs/design/tilla-calendar.md) —— 对 Tilla 2.8
  日历的逆向规格（布局、尺寸、日格状态优先级、手势阈值、数据模型），是补丁
  `0011`–`0015` 的依据。
- [`docs/design/expenses-architecture.md`](docs/design/expenses-architecture.md) ——
  RecurringExpenseTracker（`feat/tilla-ui`）架构图谱：模块、Room 层、重复语义、
  UI 插入点、日期投影。

两份文档都是写作时刻的快照，请把它们当作设计取舍的参考，而不是逐行对应的现状。

## 许可证

Copyright (C) 2026 PILIHU2022 (Spark)。

本仓库 —— 构建脚本、CI、文档与补丁队列 —— 以 **GPL-3.0-only** 授权，完整文本见
[`LICENSE`](LICENSE)。

**为什么是 GPL-3.0-only**：补丁是自由软件上游的衍生作品 —— ScreenTime 为
GPL-3.0-or-later，RecurringExpenseTracker 为 GPL-3.0-only，Fcitx5 for Android
为 LGPL-2.1-only（其第 3 节允许改为 GPL v2 或更新版本分发）。GPL-3.0-only 与三者
都兼容，也避免了整个仓库出现 “only / or-later” 混合授权。

发布的 APK 由上述上游项目构建，并按各自上游条款分发：expenses 与 screentime 为
GPL-3.0，Fcitx5 for Android 为 **LGPL-2.1-only**。其对应源码是上面列出的上游仓库
（对应提交），加上本仓库中的补丁队列。`fdroid/metadata/` 中每个应用的元数据保留
各自上游项目的许可证声明（fcitx5 为 `License: LGPL-2.1-only`，另外两个分别为
`GPL-3.0-only` / `GPL-3.0-or-later`）。

`signing/patchwork-test.jks` 及其密码是**公开的测试签名密钥**，不是秘密，也不附带
任何信任预期：任何人都可以用它给 APK 签名。超出个人用途请更换密钥。仓库其余部分
——脚本、CI、文档、补丁——仍然以 GPL-3.0-only 授权。

这是个人 fork / 补丁合集，与上游作者（Dennis Bauer、Atharok、Fcitx5 for Android
贡献者）无关，也未获其背书。
