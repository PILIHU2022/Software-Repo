# DataArk 测试记录（2026-11-15）

环境：
- 主机：Arch Linux / JDK 21 / Android SDK 37 / Gradle 9.7.1
- 模拟器：`sdk_gslim_x86_64`，Android 11（API 30），userdebug，`adb root` 可用，TCG 无 KVM
- 真机：OnePlus PJF110，Android 15（production），Shizuku 13.6.0（ADB/shell 模式）、Dhizuku 2.13.0（Device Owner）
- 构建：`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`

## 1. 单元测试

```
ChecksumTest: 2 tests, 0 failures
ShellScriptsTest: 5 tests, 0 failures
```

覆盖 SHA-256 已知向量、空文件、shell 单引号转义、tar 参数生成、压缩/解压参数、进度计算、归档大小计算。

## 2. Shizuku Root 模式全链路（模拟器）

Shizuku 13.6.0 服务由 `/data/local/tmp/libshizuku.so` 以 UID 0 启动；应用授权后状态显示：

```
Shizuku · Root 模式 (UID 0) access=FULL
```

备份 `org.fcitx.fcitx5.android.debug`（debuggable 测试应用）：
- `/sdcard/DataArk/.../data.tar`：143 MB（CE + DE，已排除 cache/code_cache/lib/no_backup/.ota）
- `external.tar`：2.5 KB（Android/data；无 obb）
- `apk/base.apk`
- `meta.json`：模式 SHIZUKU、UID、版本、组件、SHA-256、大小

应用内备份结果：`已备份：私有数据 + 外部数据 + 1 个 APK`。

## 3. 恢复与完整性（模拟器）

备份 `de.dbauer.expensetracker`（非 debuggable，Shizuku root 全权限）：

1. 在 `/data/user/0/de.dbauer.expensetracker/files/CTX_TEST` 写入 `chcon-test-456`。
2. 备份，确认 `data.tar` 包含该文件。
3. 删除该文件后执行恢复。
4. 恢复结果：
   - 文件内容 `chcon-test-456` 字节级一致；
   - owner/group `10076:10076`；
   - mode `600`；
   - SELinux `u:object_r:app_data_file:s0:c76,c256,c512,c768`（使用恢复前捕获的当前应用 context `chcon -hR`，fallback `restorecon -RF`）。

这验证了替换数据目录、失败回滚、UID/GID 修复、权限和 SELinux 修复。

## 4. Shizuku ADB 模式（真机 Android 15）

- 状态：`Shizuku · ADB 模式 (UID 2000)`，access=DEBUGGABLE_ONLY。
- `de.dbauer.expensetracker`（非 debuggable）仅 APK 备份成功，签名与大小正常。
- 普通应用私有数据和 `Android/data` 在 ADB 模式下受 UID 2000 / FUSE 限制；应用在 UI/引擎中显式禁用/跳过，不虚假宣称成功。

## 5. Dhizuku（真机 Android 15，Device Owner）

- `Dhizuku.init()` 成功，权限已授予，状态：`Dhizuku · Device Owner`，UID 10366，access=APK_ONLY。
- 非 debuggable 应用 APK-only 备份成功（`apk/base.apk` + meta）。
- 说明：Dhizuku 的 `newProcess` 在 Dhizuku 应用自身 UID 下运行，不提供跨应用私有目录读取；UI 及 README 已明确标注。

## 6. Root（libsu）说明

测试模拟器没有 Magisk/KernelSU，AOSP `/system/xbin/su` 仅允许 shell/root 组执行，普通应用无法通过 libsu 获取 su，因此未在该环境完成真机 Root 端到端。
Root 路径与 Shizuku Root 模式共用同一套 tar/restore 脚本；差别仅在 `RootShellRunner` 通过 libsu `Shell.getShell()` 执行。Shell 脚本已在 UID 0 下通过 Shizuku 服务完整验证。

## 7. Release 构建

- 安装 `dist/DataArk-1.0.0.apk`（使用 debug keystore 签名，`android:debuggable=false`）到 OnePlus Android 15。
- 启动、Shizuku 状态检测、应用列表、存储卡片正常；无 FATAL/ANR。
- Release 不包含 `src/debug` 的 ADB 自动化 Activity/Receiver。
- APK SHA-256：`ff3c05db4adb8202d77a62320fd85e9c302e9395970dd79b0d851d40c49f0bcf`。

## 8. 未覆盖/已知限制

- 未在真实 Magisk/KernelSU/APatch 设备上跑 Root 端到端（原因见 §6）。
- 未实现 APK 自动重装（split install session）；恢复前要求应用已安装。
- 多用户、云备份、加密归档未实现。
- Android 11+ 的 `Android/data` 受 FUSE 限制；仅 UID 0 / MANAGE_EXTERNAL_STORAGE 路径可可靠备份外部数据。
- TCG 模拟器极慢，服务启动可能超过 Android 的 5 秒 FGS 窗口；真实设备不受此影响。服务已在 `onCreate()` 提前 `startForeground` 以缩小窗口。

## 9. 专项：Dhizuku 能否备份 `/sdcard/Android/data`？（2026-11-15 实测）

结论：**不能**。三条独立证据（真机 OnePlus Android 15，Dhizuku 2.13.0 Device Owner）：

### 9.1 Dhizuku 服务进程的身份

```text
$ adb shell 'grep -E "^Groups:|^Uid:" /proc/$(pidof com.rosan.dhizuku)/status'
Uid:    10366 10366 10366 10366
Groups: 9997 20366 50366
```

- `10366` = Dhizuku 自己的普通应用 UID（`u0_a366`，untrusted_app）。
- 属组里**没有** `1078 (ext_data_rw)` 与 `1079 (ext_obb_rw)`；而 `/sdcard/Android/data/<pkg>` 的权限是
  `2770 <app-uid>:ext_data_rw`（实测 `10448 1078 2770`），others 无任何权限。

### 9.2 Dhizuku 无法被授予“所有文件访问”

```text
$ adb shell dumpsys package com.rosan.dhizuku | sed -n '/requested permissions:/,/install permissions:/p'
      android.permission.READ_EXTERNAL_STORAGE
      android.permission.WRITE_EXTERNAL_STORAGE
      ...（没有 MANAGE_EXTERNAL_STORAGE）
```

Dhizuku 未声明 `MANAGE_EXTERNAL_STORAGE`，因此系统设置里根本不会出现它的“所有文件访问”开关；
Device Owner 也没有可用的 DPM/AppOps API 去设置 appop（`AppOpsManager.setMode` 需要 `MANAGE_APP_OPS_MODES`，属 signature|privileged）。
`DhizukuBinderWrapper` 只是把 binder 调用换成 Device Owner 身份，无法绕过内核/FUSE 的文件访问检查。

### 9.3 即使拿到“所有文件访问”也读不到（关键）

用本应用自己（`dev.dataark.backup`，已授 `MANAGE_EXTERNAL_STORAGE`，属组含 `1078 ext_data_rw` 与 `50450 all_a450`）实测：

```text
$ adb shell 'run-as dev.dataark.backup id'
uid=10450(u0_a450) groups=...,1078(ext_data_rw),1079(ext_obb_rw),50450(all_a450)

$ adb shell 'stat -c "%u %g %a %n" /storage/emulated/0/Android/data/org.fcitx.fcitx5.android'
10448 1078 2770 /storage/emulated/0/Android/data/org.fcitx.fcitx5.android

$ adb shell 'run-as dev.dataark.backup ls /storage/emulated/0/Android/data/org.fcitx.fcitx5.android'
ls: ...: Permission denied
$ adb shell 'run-as dev.dataark.backup head -c 40 /storage/emulated/0/Android/data/org.fcitx.fcitx5.android/files/config/config'
head: ...: Permission denied
```

属组匹配、mode 也允许 group 访问，仍然 `Permission denied` —— 说明拦截来自 **MediaProvider 的 FUSE 门禁**，
按“调用方 UID 对应的包名”放行，与权限位/All files access 无关。即 Android 11+ 上
`/sdcard/Android/data/<pkg>` 只有**包主、root、以及被 FUSE 识别为所有者的身份**能读。

同一现象也解释了之前 Shizuku ADB 模式（uid 2000，属组含 1078）tar 该目录时对文件报 `Permission denied`：
目录元数据可列，文件内容被拒。

### 9.4 能力对照（实测汇总）

| 后端 | 进程 UID | 读他人 `/sdcard/Android/data` |
|---|---|---|
| Root（libsu，su→0） | 0 | ✅ 绕过 FUSE 门禁 |
| Shizuku Root 模式 | 0 | ✅ |
| Shizuku ADB 模式 | 2000 | ❌ 文件内容被 FUSE 拒 |
| 普通应用 + MANAGE_EXTERNAL_STORAGE | 应用 UID | ❌ 实测被拒 |
| Dhizuku Device Owner | Dhizuku 应用 UID | ❌（且无法获得 All files access） |

因此 DataArk 的策略正确：`Android/data`、`obb` 只在 `DataAccess.FULL`（Root / Root 模式 Shizuku）下备份；
Dhizuku 模式仅 APK；UI 与 README 均如实标注。

补充建议（未实现）：Root 模式下备份外部数据建议改走真实路径 `/data/media/0/Android/data/<pkg>`
（FUSE 挂载点背后就是它），可进一步避开 FUSE 门禁与配额元数据的干扰。
