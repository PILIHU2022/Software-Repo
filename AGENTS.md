# Software-Repo 代理指南

本文件适用于整个仓库。子目录中的 `AGENTS.md` 若存在，则其规则优先于本文件；
用户的直接指令优先于所有 `AGENTS.md`。

## 当前阶段硬性限制

这些限制适用于主代理和所有子代理，除非用户在后续消息中明确解除：

- **不得构建任何软件**：不要运行 Gradle、`make`、`go build`、`cargo build`、
  `fdroid update`、APK 签名/对齐、容器构建或任何等价的编译/打包命令。
- **不得安装或下载开发工具**：不要使用包管理器、SDKMAN、`sdkmanager`、
  `apt`、`brew`、`pip install`、`npm install`、`go install` 等，也不要下载
  SDK、NDK、JDK、签名工具或上游源码。
- **不得运行代码检查或验证**：不要运行测试、lint、格式检查、类型检查、
  静态分析、`git am --check`、补丁试应用或其他构建前检查。
- **不得推送远端**：不要执行 `git push`、创建/更新远端分支、发布 Release、
  触发工作流或修改远端状态。
- **只编辑代码和文档**：本阶段只允许读取、搜索、编辑本仓库内的文件。
  不要提交 commit，除非用户明确要求。
- **允许使用子智能体**：可以委派读取、整理和编辑任务；子智能体必须遵守
  本节全部限制，且不得借委派绕过限制。

如果用户解除限制，只执行与当前任务直接相关的最小验证，不要顺带修复无关问题。

## 项目定位

这是一个 *patchwork overlay*（补丁叠加）单仓库：除 `dataark/` 外，不保存应用
源码，只保存按顺序应用到上游代码的补丁队列、构建/发布脚本和 CI 配置。

- Android 补丁队列：`apps/<app>/patches/*.patch`
- 桌面与 Android 共用的 BaiduNetGo 队列：`desktop/baidunetgo/patches/*.patch`
- 补丁锚点：`apps/<app>/upstream.txt`、`desktop/.build-state.json`
- 内置完整源码：`dataark/`（不经过补丁队列）
- 签名后的 F-Droid 仓库：`fdroid/`（APK 与索引由 CI 提交）
- 构建与发布逻辑：`scripts/`
- 工作流：`.github/workflows/`
- 设计资料：`docs/design/`

当前应用和队列规模以目录实际内容为准，不要相信 README 中已过时的计数。

## 目录与职责

| 路径 | 职责 |
| --- | --- |
| `apps/expenses/` | RecurringExpenseTracker 补丁队列与 `app.conf` |
| `apps/screentime/` | ScreenTime 补丁队列与 `app.conf` |
| `apps/fcitx5/` | Fcitx5 for Android 补丁队列与 `app.conf` |
| `apps/basicsync/` | BasicSync 补丁队列与 `app.conf` |
| `apps/gadgetbridge/` | Gadgetbridge 补丁队列与 `app.conf` |
| `desktop/baidunetgo/` | BaiduNetGo 桌面版和 Android 客户端共用的补丁队列、feed 元数据 |
| `dataark/` | 独立 Gradle 工程的完整应用源码 |
| `fdroid/` | 仓库配置、元数据、签名索引与已发布 APK；不要手工伪造索引 |
| `scripts/` | 拉取上游、应用队列、构建、发布和回归测试脚本 |
| `signing/patchwork-test.jks` | 故意公开的测试签名密钥，不是秘密 |
| `.cache/`、`dist/`、`.toolchain/` | 本地生成物，已在 `.gitignore` 中 |

`apps/<app>/app.conf` 是被 shell `source` 的配置文件，不是可执行脚本。修改时保持
现有键名和语义，尤其是 `APP_ID`、`UPSTREAM_URL`、`UPSTREAM_BRANCH`、
`VERSION_STYLE`、`SIGNING_STYLE`、`KEEP_ABIS`、`BUILD_ABI`、
`SUBMODULES`、`STATE_FILE`。

## 编辑约定

- 先读相关 README、`app.conf`、现有补丁和脚本，再做最小、局部的修改。
- 保持现有文件风格、命名、注释密度和补丁提交信息风格。
- 不要引入与当前任务无关的重构、重命名、格式化或依赖变更。
- 不要提交上游源码、作者工作副本、构建产物或工具链目录。
- 修改补丁队列时保持 `NNNN-description.patch` 的排序和现有文件名；
  不要无故重新编号。
- 修改补丁内容时遵循现有导出格式（通常由 `git format-patch` 生成）：
  mail header、`From`/`Subject`/`Date`、diffstat 和 `-- ` 签名尾部都要完整。
- Android 补丁最终必须能从上游分支用 `git am -3` 应用；BaiduNetGo 队列因包含
  CRLF 的 `android/gradlew.bat`，由脚本使用 `git am -3 --keep-cr` 重放。
  本阶段不要实际试应用来验证，只做静态编辑。
- 文档改动应同步考虑 `README.md` 与 `README.zh-CN.md` 是否需要一致更新；
  不要只改一边造成明显漂移。设计文档是历史快照，不要把它当作当前代码事实。

## 版本与发布不变量

不要在没有明确任务时改动这些约定：

- 常规 Android 版本规则：上游 `versionCode * 100 + PATCHWORK_REVISION`，
  发布名通常带 `-patchwork` 后缀。
- `apps/basicsync/app.conf` 使用 `VERSION_STYLE="pinned"`，因为上游从
  `git describe` 推导版本会溢出其 5 位提交计数字段。
- `apps/fcitx5/app.conf` 使用 `VERSION_STYLE="build-logic"`，由
  `PATCHWORK_REVISION`/`BUILD_VERSION_NAME` 驱动。
- BaiduNetGo 桌面版只构建 x86（`linux/amd64`、`windows/amd64`），产物是 GitHub
  Release、workflow artifact 和 `desktop/baidunetgo/update.json`，不是 F-Droid 包。
- BaiduNetGo Android 客户端与桌面版重放同一条补丁队列。
- `dataark.yml` 被刻意休眠；除非用户明确要求，不要“修复”触发条件或启用 CI。
- 发布用的 APK、签名索引和 build state 由 CI 生成并提交；不要手工伪造。

## 安全边界

- `signing/patchwork-test.jks` 及默认口令是公开测试凭据，可用于个人构建，
  但不构成安全边界。
- 不要写入新的真实密钥、token、证书口令或个人凭据。
- 不要声称签名后的应用与上游官方构建可互相覆盖安装；包名相同但签名不同。
- 不要把 `fdroid/` 中的发布物误认为可随意重新生成的普通文件。

## 子智能体规则

- 可以将大范围阅读、文档盘点、独立文件修改交给子智能体。
- 委派消息中必须重申“当前阶段不得构建、安装工具、运行检查或推送远端”。
- 子智能体仍以本仓库为唯一工作区；不要让其创建嵌套仓库或修改仓库外文件。
- 汇总子智能体结果时，主代理负责最终一致性和范围控制。
