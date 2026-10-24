[English](README.md) | **简体中文**

[![Build](https://github.com/PILIHU2022/Software-Repo/actions/workflows/build.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/build.yml)
[![Deploy Pages](https://github.com/PILIHU2022/Software-Repo/actions/workflows/deploy-pages.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/deploy-pages.yml)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)

# Software-Repo —— Patchwork 构建叠加仓库

这是一个面向两个 Android 应用的个人补丁式构建仓库：**ScreenTime** 与
**RecurringExpenseTracker**。仓库里不保存任何应用源码，只保存一条有序的补丁队列，
以及把补丁应用到上游当前代码、构建签名 APK、发布签名 F-Droid 仓库的 CI。

> 个人项目，与上游作者（Dennis Bauer / Atharok）无关，也未获其背书。

## 这是什么

一个 *patchwork overlay*（补丁叠加）单仓库，只存两样东西，不存应用源码：

1. **补丁队列** —— `apps/<app>/patches/*.patch`，能按顺序用 `git am -3` 应用到
   上游 `main` 之上的改动；
2. **构建与发布自动化** —— CI 拉取上游当前 `main`，应用补丁队列，改写版本号，
   构建签名 APK，并更新签名后的 F-Droid 仓库。

每次构建都把补丁重新应用到最新的上游提交上，因此上游的修复和新功能会自动进入
构建，而不需要长期维护一个已经分叉的 fork。补丁一旦无法应用，流水线会失败并开
issue，而不是继续发布过期代码。

## 应用

| 应用 | 上游 | 上游提交 | 补丁数 | 已发布 |
| --- | --- | --- | --- | --- |
| RecurringExpenseTracker | [DennisBauer/RecurringExpenseTracker](https://github.com/DennisBauer/RecurringExpenseTracker) | `82ef9c9` | 17 | `0.21.4-patchwork` (6804) |
| ScreenTime | [Atharok/ScreenTime](https://gitlab.com/Atharok/ScreenTime) | `9ac2520` | 8 | `1.4.1-patchwork` (1301) |

**ScreenTime** —— 每日使用记录持久化到本地 SQLite 数据库，新增基于该历史的分析页；
用 Compose `Canvas` 图表替换 Vico；缓存已栅格化的应用图标；启动时不再有主线程阻塞
工作。

**RecurringExpenseTracker（Expenses）** —— Tilla 风格界面（服务品牌色磁贴、内置
92 个服务图标目录、编辑页图标选择器、按名称推荐图标）、普通 `LazyColumn` 列表、
备份/恢复修复（重启数据库进程并按名称推断图标），以及全新的 Tilla 风格**日历**
tab：月历卡片使用 7 列 1:1 日格和按 locale 推断的每周首日；日格显示日期、18dp
着色服务图标、同一天多笔付款的 `+N` 徽标和已付款对勾；月卡下方是当日详情卡片；
支持箭头与横向滑动换月；整月投影在主线程之外计算；提供英文、简体中文、繁体中文
文案。后续补丁还加入了 Tilla 风格主界面顶部栏（排序/筛选/菜单）、分析页、
存档页、暂停/恢复（数据库 v13）、拆分后的设置中心（7 套预设主题、含 Catppuccin）
以及可自定义的卡片布局。规格见 [`docs/design/tilla-calendar.md`](docs/design/tilla-calendar.md)。

## F-Droid 仓库

仓库地址（自定义域名，由 GitHub Pages 托管、CDN + TLS；**已上线并验证**）：

```
https://software.sparkzh.top/repo
```

索引返回 HTTP 200，`index-v2.json` 中列出 `de.dbauer.expensetracker` **6804** 与
`com.atharok.screentime` **1301**。站点首页：<https://software.sparkzh.top/>。
GitHub Pages 地址 <https://pilihu2022.github.io/Software-Repo/repo> 会 301 跳转到
这个域名。

在 F-Droid 客户端中添加：**设置** → **存储库** → **添加存储库** → 粘贴上面的地址
（必须以 `/repo` 结尾）→ 在仓库详情中核对指纹。签名证书：

```
SHA-256: D3:34:88:79:40:65:C9:2E:37:0D:24:A1:0E:61:4E:1E:64:E3:19:B7:5E:6F:8B:B7:C4:AA:D6:7F:3A:5B:5E:01
```

> 包名与官方应用完全相同，但签名密钥不同。Android 会拒绝跨密钥覆盖安装，请先卸载
> 官方版本 —— 卸载会删除应用数据，请先备份。

Pages 不可用时的 raw 备用地址：

```
https://raw.githubusercontent.com/PILIHU2022/Software-Repo/main/fdroid/repo
```

（raw.githubusercontent.com 不提供目录列表；把它当作仓库地址使用，F-Droid 会在其下
请求 `index-v2.json`。）

两个地址提供的是同一份签名索引。索引里始终写正式 Pages 地址，因此用 raw 地址添加
仓库时，部分客户端会提示地址不一致；内容完全相同。

## 自动化流程

`build.yml` 每 6 小时运行一次（`23 */6 * * *`），在推送到 `main` 且改动涉及
`apps/**`、`scripts/**` 或工作流本身时运行，也支持手动触发（可选 `force` 输入）。
改动 `apps/*/patches/` 的补丁提交本身就是一次 `apps/**` 推送：**会立即触发构建**
（无需等定时运行），并按下文的版本号规则自动 +1。

构建按 app 分开：推送只重建 `apps/<app>/**` 有改动的那个 app（`scripts/**` 或
工作流改动会重建两个；无法确定 before 的强制推送回退为两个）。定时运行只重建上游
SHA 变化的 app。手动触发可用 `app` 输入只构建一个 app，或用 `app=all` + `force`
全量重建。每次运行：

1. **check** —— `scripts/check-upstream.sh` 对比上游 `main` 的 SHA 与
   `fdroid/.build-state.json`，生成构建矩阵；
2. **build** —— 每个有变化的应用一个矩阵任务（`fail-fast: false`）：在
   `.cache/mirror/<app>.git` 维护 mirror、创建全新检出、用 `git am -3` 应用补丁
   队列、改写版本号、Gradle release 构建、keystore 签名，再用 `apksigner` /
   `aapt` 校验；
3. **publish** —— `publish-fdroid.sh` 每个包最多保留 3 个 APK，按
   `(package, versionCode)` 去重，用 fdroidserver（`fdroid update`）签名索引，把
   `fdroid/repo` 与 `.build-state.json` 以 `[skip ci]` 提交回 `main`，最后部署
   Pages。失败的矩阵任务保留旧状态，下次运行会重试；
4. **failure** —— 打开或评论 **“Patchwork build failed”** issue，附运行链接和本地
   复现命令。

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

`PATCHWORK_REVISION` 默认取 `HEAD` 上触及 `apps/<app>/patches` 的提交数（上限 99，
可用同名环境变量覆盖）。这正是“只改补丁”的版本也能在 F-Droid 里升级的原因：
补丁队列提交会让 `versionCode` 增加，即使上游版本号没变。

| 应用 | 上游 versionCode / versionName | 补丁队列提交数 | 构建结果 |
| --- | --- | --- | --- |
| ScreenTime | `13` / `"1.4.1"` | 1 | `1301` / `"1.4.1-patchwork"` |
| RecurringExpenseTracker | `68` / `"0.21.4"` | 4 | `6804` / `"0.21.4-patchwork"` |

## 仓库结构

```
.
├── README.md                  # 英文说明（本仓库默认首页）
├── README.zh-CN.md            # 简体中文说明（本文件）
├── LICENSE                    # GPL-3.0-only
├── apps/
│   ├── expenses/{app.conf,patches/}     # 0001..0017
│   └── screentime/{app.conf,patches/}   # 0001..0008
├── scripts/
│   ├── build-app.sh           # 拉上游、打补丁、改版本、构建、校验
│   ├── check-upstream.sh      # 上游 SHA 差异 -> CI 矩阵
│   ├── publish-fdroid.sh      # 发布 APK 并签名索引
│   ├── test-publish-fdroid.sh # 发布脚本的回归测试
│   └── sync-patches.sh        # 从作者仓库同步补丁队列
├── fdroid/
│   ├── config.yml
│   ├── metadata/*.yml
│   ├── repo/                  # APK + 索引，由 CI 提交
│   └── .build-state.json
├── signing/patchwork-test.jks # 故意公开的测试签名密钥
├── docs/design/               # 日历规格、Expenses 架构图谱
└── .github/workflows/{build,deploy-pages}.yml
```

## 本地构建

前置条件：bash、Git、JDK 21、Android SDK 37（预览版 platform 与 build-tools），
发布还需要 `fdroidserver`。不需要 NDK。未设置的 `ANDROID_HOME`、
`ANDROID_USER_HOME`、`GRADLE_USER_HOME` 默认指向 `../.toolchain/`：

```bash
export ANDROID_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-sdk
export ANDROID_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-home
export GRADLE_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/gradle-home

./scripts/build-app.sh expenses --no-build    # 只拉上游 + 打补丁 + 改版本
./scripts/build-app.sh expenses              # 完整构建，输出到 dist/
./scripts/build-app.sh screentime --out dist
./scripts/check-upstream.sh                  # 输出 JSON；--all/--apps 选择 app
./scripts/publish-fdroid.sh --apk-dir dist   # 重建 fdroid/repo 与索引
./scripts/sync-patches.sh --check            # 对比作者仓库与补丁队列
./scripts/test-publish-fdroid.sh dist/RecurringExpenseTracker-0.21.4-patchwork.apk
```

`dist/build-info.txt` 记录 `upstream_commit`、`patch_count`、`version_code`、
`version_name`、`variant`、`built_at`、`apk`、`apk_size`、`sha256`。构建缓存
（`.cache/`）和产物（`dist/`）都已 gitignore。

## 更新补丁

补丁仍在两个独立的 app clone 中开发；本仓库只消费导出的补丁文件。

```bash
# 1. 在 app clone 中从上游 main 建 feature 分支并开发
cd ../ScreenTime
git switch -c feat/my-change main
# ... 修改、构建、提交 ...

# 2. 按现有补丁的格式导出队列
rm -f .patchwork/patches/*.patch
git format-patch main..feat/my-change \
  -o .patchwork/patches --no-signature --zero-commit --no-numbered

# 3. 同步进本仓库，提交并推送；CI 会自动重建
cd ../Software-Repo
./scripts/sync-patches.sh
git add apps/screentime/patches
git commit -m "Update ScreenTime patch queue"
git push origin main
```

`./scripts/sync-patches.sh --check` 只报告差异（不同步时退出码为 1），适合 CI 或
本地提交前检查。如果 app clone 不在 `patchwork` 分支，脚本会通过 `git archive`
从该分支读取队列，不会修改 app clone。

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

**为什么是 GPL-3.0-only**：补丁是 GPL-3.0 上游的衍生作品 —— ScreenTime 为
GPL-3.0-or-later，RecurringExpenseTracker 为 GPL-3.0-only。GPL-3.0-only 与两者都
兼容（“or later”作品可以按 v3 分发），也避免了整个仓库出现 “only / or-later”
混合授权。

发布的 APK 由上述上游项目构建，并按相同的 GPL-3.0 条款分发。其对应源码是上面列出
的上游仓库（对应提交），加上本仓库中的补丁队列。`fdroid/metadata/` 中每个应用的
元数据保留各自上游项目的许可证声明。

`signing/patchwork-test.jks` 及其密码是**公开的测试签名密钥**，不是秘密，也不附带
任何信任预期：任何人都可以用它给 APK 签名。超出个人用途请更换密钥。仓库其余部分
——脚本、CI、文档、补丁——仍然以 GPL-3.0-only 授权。

这是个人 fork / 补丁合集，与上游作者（Dennis Bauer / Atharok）无关，也未获其背书。
