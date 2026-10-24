# Software-Repo — Patchwork 构建仓库

## 项目目的

本仓库不保存 ScreenTime 和 RecurringExpenseTracker 的完整源码，而是保存两样东西：

1. **补丁队列**（`apps/*/patches/*.patch`）：对上游源码的小幅定制；
2. **构建 / 发布流水线**：每次 CI 运行时拉取上游最新 `main`，用 `git am -3`
   按顺序应用补丁，重新编号版本，构建签名 APK，并更新 F-Droid 仓库。

这样做的好处是上游的修复和新功能会自动进入构建，而不需要长期维护一个已经
分叉的 fork。补丁一旦与上游冲突，流水线会失败并开出 issue，提醒我们更新补丁。

## 目录结构

```
.
├── README.md                     # 本文件
├── .gitignore
├── apps/
│   ├── expenses/
│   │   ├── app.conf              # RecurringExpenseTracker 构建参数
│   │   └── patches/              # 0001..0015
│   └── screentime/
│       ├── app.conf              # ScreenTime 构建参数
│       └── patches/              # 0001..0008
├── scripts/
│   ├── build-app.sh              # 拉上游 + 打补丁 + 改版本 + 构建 + 校验
│   ├── check-upstream.sh         # 比较上游 SHA，输出 GitHub Actions matrix
│   ├── publish-fdroid.sh         # 发布 APK 到 fdroid/repo 并生成索引
│   ├── test-publish-fdroid.sh    # publish-fdroid.sh 的回归测试（去重/索引）
│   └── sync-patches.sh           # 从本地作者仓库同步补丁队列
├── fdroid/
│   ├── config.yml                # fdroidserver 仓库配置
│   ├── metadata/*.yml            # 两个应用的 F-Droid 元数据
│   ├── repo/                     # APK + index（由 CI 提交）
│   └── .build-state.json         # 上次成功构建的上游 SHA
├── signing/
│   ├── patchwork-test.jks        # 故意提交的测试签名密钥（见下方警告）
│   └── README.md
├── docs/design/                  # 设计文档占位目录
└── .github/workflows/build.yml   # GitHub Actions 工作流
```

## F-Droid 仓库

仓库地址：

```
https://pilihu2022.github.io/Software-Repo/repo
```

在 F-Droid 客户端中添加：

1. 打开 F-Droid → **设置**（Settings）；
2. 进入 **存储库**（Repositories）；
3. 点击 **添加存储库**（Add repository）；
4. 输入上面的地址（注意必须以 `/repo` 结尾）；
5. 添加后可在仓库详情中核对签名指纹。

签名证书指纹：

```
SHA-256: D3:34:88:79:40:65:C9:2E:37:0D:24:A1:0E:61:4E:1E:64:E3:19:B7:5E:6F:8B:B7:C4:AA:D6:7F:3A:5B:5E:01
SHA-1:   C7:CB:2C:10:F7:97:3E:80:C8:64:93:C5:31:69:B5:99:5D:6E:E2:F6
```

> 不要把这个仓库和 F-Droid 官方源混在一起：两个应用的包名与官方版本相同，
> 但签名密钥不同，Android 会拒绝覆盖安装。安装前请先卸载官方版本。

### 启用 GitHub Pages（一次性）

正式地址通过 GitHub Pages 提供。首次使用前需要仓库管理员在
**Settings → Pages → Build and deployment → Source** 中选择
**“GitHub Actions”**。`GITHUB_TOKEN` 无权自动创建 Pages 站点
（`configure-pages` 的 `enablement: true` 需要 PAT / GitHub App token），
因此工作流在 Pages 未启用时只打印 warning，不会让构建失败。

### 备用地址（Pages 尚未启用时）

Pages 未启用或返回 404 时，可以直接使用 `main` 分支上的原始文件地址：

```
https://raw.githubusercontent.com/PILIHU2022/Software-Repo/main/fdroid/repo
```

它提供与 Pages 完全相同的 `index-v2.json`、`entry.json` 和 APK
（同样以 `/repo` 结尾）；Pages 启用后建议改回正式地址。
两种地址的索引都由同一把仓库密钥签名，指纹见上。

## 版本号策略

构建时会把上游 `app/build.gradle.kts` 中的版本改写为：

```
versionCode = 上游 versionCode × 100 + PATCHWORK_REVISION
versionName = "上游 versionName-patchwork"
```

`PATCHWORK_REVISION` **默认自动计算**：它是仓库 `HEAD` 上触及该应用补丁目录
（`apps/<app>/patches`）的提交数，上限 99。也就是说，每提交一次补丁队列变更，
下一次构建的 `versionCode` 就自动 +1，F-Droid 客户端才能把“上游版本号没变、
只改了补丁”的构建识别为升级。超过 99 时脚本会直接报错退出（因为
`上游 versionCode × 100 + 100` 会与下一个上游版本号撞车），此时需要压缩补丁
提交或显式指定 revision。

例如（当前仓库状态）：

| 应用 | 上游 versionCode / versionName | 补丁目录提交数 | 本仓库构建结果 |
| --- | --- | --- | --- |
| ScreenTime | `13` / `"1.4.1"` | 1 | `1301` / `"1.4.1-patchwork"` |
| RecurringExpenseTracker | `68` / `"0.21.4"` | 2 | `6802` / `"0.21.4-patchwork"` |

需要临时固定某个 revision 时仍可覆盖：

```bash
PATCHWORK_REVISION=1 ./scripts/build-app.sh screentime
# versionCode 变为 1301，versionName 仍为 1.4.1-patchwork
```

## CI 工作方式

`.github/workflows/build.yml` 每次运行会：

1. **check**：`scripts/check-upstream.sh` 对每个应用执行 `git ls-remote`，与
   `fdroid/.build-state.json` 中记录的上游 SHA 比较，生成构建矩阵；
2. **build**：仅构建有变化的 app（矩阵并行、`fail-fast: false`）：
   - 用 `git clone --mirror` 维护 `.cache/mirror/<app>.git`；
   - 每次创建全新的 `.cache/work/<app>` 检出；
   - `git am -3` 依次应用 `apps/<app>/patches/*.patch`；
   - 改写 `versionCode` / `versionName`；
   - 用统一 keystore 签名的 release APK，并用 `apksigner` / `aapt` 校验；
3. **publish**：把 APK 放进 `fdroid/repo/`，每个包最多保留 3 个版本，
   运行 `fdroid update --verbose` 生成并签名索引，提交
   `fdroid/repo` 与 `fdroid/.build-state.json`（提交信息带 `[skip ci]`），
   最后把 `fdroid/` 发布到 GitHub Pages。
   - 矩阵中某个 app 构建失败时，publish 仍会运行并把幸存 APK 发布出去；
   - 发布前按 `(package, versionCode)` 去重：同一版本重新构建产生的
     新 APK 会替换 `fdroid/repo/` 里的旧文件，索引不会指向旧二进制；
   - `.build-state.json` 只记录真正产出 APK 的 app，失败 app 的旧 SHA
     会保留，下次运行会重试；
   - 没有可发布的 APK 时 publish 直接跳过；
   - 提交回 `main` 若因仓库 Actions 默认权限只读而返回 403，会打印
     warning 但**不阻断** Pages 部署（见“已知限制”）。
4. 失败时由 `actions/github-script` 打开或评论 issue
   **“Patchwork build failed”**，附运行链接和本地复现命令。

触发条件：

- **定时**：每 6 小时（cron `23 */6 * * *`）；
- **手动**：Actions → *Patchwork build* → *Run workflow*，可勾选 `force`；
- **推送**：只监听 `apps/**`、`scripts/**`、`.github/workflows/build.yml`
  的变更，避免机器人提交 `fdroid/**` 后再次触发。

## 如何手动触发

1. 打开 GitHub 仓库的 **Actions** 页面；
2. 左侧选择 **Patchwork build**；
3. 点击 **Run workflow**；
4. 需要忽略“上游无变化”时勾选 **force**（等价于 `check-upstream.sh --all`）；
5. 运行结束后在 `fdroid/repo/` 查看新提交，或在 Artifacts 下载 APK。

## 如何更新 / 新增补丁

补丁的“作者空间”仍是两个独立的 app clone，本仓库只消费导出的 patch 文件。

```bash
# 1. 在 app clone 中从上游 main 建 feature 分支并开发
cd ../ScreenTime
git switch -c feat/my-change main
# ... 修改、构建、git commit ...

# 2. 导出补丁队列（使用与现有补丁一致的格式）
rm -f .patchwork/patches/*.patch
git format-patch main..feat/my-change \
  -o .patchwork/patches --no-signature --zero-commit --no-numbered

# 3. 回到本仓库同步并提交
cd ../Software-Repo
./scripts/sync-patches.sh          # 会先删除 apps/*/patches 中的旧文件
git add apps/screentime/patches    # 或 apps/expenses/patches
git commit -m "Update ScreenTime patch queue"
```

`scripts/sync-patches.sh --check` 只比较差异，不写文件；有差异时退出码为 1，
可用于 CI 或本地检查。若 app clone 当前不在 `patchwork` 分支、工作区没有
`.patchwork/patches`，脚本会尝试从该 clone 的 `patchwork` 分支用
`git archive` 读取，不会修改 app clone。

> 补丁必须保持“从上游 `main` 的某个提交导出”的形式，并且基于当前上游仍能
> 3-way apply。`git am -3` 需要完整历史，CI 已设置 `fetch-depth: 0`，本地
> mirror 也保留了全部 refs。

## 签名密钥警告

**`signing/patchwork-test.jks` 是故意提交到仓库的测试密钥，密码为
`patchwork123`（alias 为 `patchwork`）。**

- 这样 CI 不需要任何 secret 就能构建签名 APK；
- 但也意味着**任何能读取本仓库的人都能签署更新包**；
- 测试密钥只能用于个人自用，不能视为安全边界。

生产环境请改为：

1. 新建 keystore，把内容 base64 后放入仓库 secret
   `PATCHWORK_KEYSTORE_BASE64`；
2. 配置 secrets `PATCHWORK_STORE_PASSWORD`、`PATCHWORK_KEY_ALIAS`、
   `PATCHWORK_KEY_PASSWORD`；
3. 修改 `fdroid/config.yml` 的 `keystorepass` / `keypass`，并确保 CI 使用
   新 keystore 生成 F-Droid 索引；
4. 从仓库删除测试密钥，并清除 Git 历史中的密码。

更换仓库签名密钥后，F-Droid 客户端会把仓库视为新仓库，用户需要删除并重新
添加仓库。

## 本地构建

本地只需要 bash、Git、JDK 21 和 Android SDK；脚本会自动把未设置的
`ANDROID_HOME` / `ANDROID_USER_HOME` / `GRADLE_USER_HOME` 指向
`../.toolchain/` 下的本地工具链。

```bash
export ANDROID_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-sdk
export ANDROID_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-home
export GRADLE_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/gradle-home

# 只拉上游、打补丁、改版本（快速验证补丁是否仍能应用）
./scripts/build-app.sh expenses --no-build
./scripts/build-app.sh screentime --no-build

# 完整构建（APK 与 build-info.txt 输出到 dist/）
./scripts/build-app.sh expenses
./scripts/build-app.sh screentime --out dist
```

输出文件：`dist/<APK_NAME_PREFIX>-<versionName>.apk` 和 `dist/build-info.txt`
（包含 upstream_commit、patch_count、version_code、version_name、variant、
built_at、apk、apk_size、sha256）。

构建缓存与产物在 `.cache/`、`dist/` 下，均已被 `.gitignore` 忽略。

检查上游状态：

```bash
./scripts/check-upstream.sh          # 只报告有变化的 app
./scripts/check-upstream.sh --all    # 强制视为全部变化
```

stdout 是一行可直接交给 `fromJSON` 的 JSON，stderr 是人类可读摘要。

## 测试发布脚本

`publish-fdroid.sh` 的 (package, versionCode) 去重、嵌套目录搜索和索引哈希
可以用一个已构建的 APK 做回归测试（在临时目录中运行，不会改动真实
`fdroid/repo`）：

```bash
./scripts/test-publish-fdroid.sh dist/RecurringExpenseTracker-0.21.4-patchwork.apk
```

需要 JDK、Android SDK（`aapt` / `apksigner`）、`jq`、`python3` 和
`fdroidserver`；CI 在 publish 前会自动运行它。

## 已知限制

- **与官方包名相同、签名不同**：首次安装必须卸载官方版本，否则会报
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；卸载会丢失应用数据，请先备份。
- **GitHub Pages 首次需要启用**：仓库 Settings → Pages → Build and
  deployment → Source 选择 “GitHub Actions”。`configure-pages` 的
  `enablement: true` 需要 PAT / GitHub App token，普通 `GITHUB_TOKEN` 无法
  自动创建 Pages 站点；工作流会把该失败降级为 warning，并使用上面的
  raw.githubusercontent.com 备用地址。首次启用前正式地址会 404。
- **Actions 写权限**：如果仓库 Settings → Actions → General → Workflow
  permissions 是 “Read-only”，publish 的“提交回 main”会返回 403。工作流会把
  该错误降级为 warning 并继续部署 Pages（本次运行的索引仍可访问），但
  `fdroid/repo` 不会写回仓库；要恢复提交，需要改成 “Read and write
  permissions”（或给 workflow 单独放行）。
- **只有上游 SHA 变化才会自动构建**：仅修改补丁时，推送会因 paths 触发一次
  `--all` 构建；如果只改了补丁但未推送，需要手动运行 workflow。
- **F-Droid 索引由测试密钥签名**，仅适合个人使用；不同设备之间请勿混用
  官方版和本仓库版。
- **上游预览工具链**：ScreenTime 的 `compileSdk 37` 需要较新的 build-tools /
  platform；CI 先尝试稳定通道，失败后用 `sdkmanager --channel=3` 安装预览包。
  构建不需要 NDK（上游 fdroiddata 为 RecurringExpenseTracker 声明了 NDK，
  但本项目没有 `ndkVersion` / `externalNativeBuild`，本地零 NDK 构建已通过）。

## 上游项目

| app | 上游 | 分支 | 包名 |
| --- | --- | --- | --- |
| ScreenTime | <https://gitlab.com/Atharok/ScreenTime> | `main` | `com.atharok.screentime` |
| RecurringExpenseTracker | <https://github.com/DennisBauer/RecurringExpenseTracker> | `main` | `de.dbauer.expensetracker` |

RecurringExpenseTracker 以 **GPL-3.0-only** 发布（上游 fdroiddata 元数据），
ScreenTime 以 **GPL-3.0-or-later** 发布；本仓库中的补丁遵循各自上游的许可证。
