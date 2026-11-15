# Upstream research: Android-DataBackup (GPL-3.0) — archive layout, privilege work, restore repair

Source analysed: `/home/Spark/Projects/GitHub_APPS/data-backup-ref` (shallow clone, HEAD `1ba03fa [Next] refactor: Organize Archive records by user`).
Licence: **GPL-3.0** (`data-backup-ref/LICENSE`, `data-backup-ref/README.md:89-90`).
Method: read-only inspection of `source/` (legacy, "v2") and `source-next/` (rewrite, "Next"); toybox capability facts were verified against AOSP `external/toybox` tags `android-12.0.0_r1` … `android-16.0.0_r1` (see §7.1).

Everything below is behaviour of the *upstream* app. This document contains no upstream code; §6 covers what may be adapted.

---

## 1. Map of the two code paths

| Concern | v2 (`source/`) | Next (`source-next/`) |
|---|---|---|
| Tar engine | bundled **GNU tar** static binary in `assets/bin.zip`, unpacked to `files/bin`, put on `PATH` | GNU tar compiled into **`libtar-wrapper.so`**, CLI entry called via JNI |
| Archive format | one `.tar` / `.tar.zst` / `.tar.lz4` file per data part | single `.tar.zst` per part, streamed through a FIFO (no intermediate tar) |
| Privilege | root shell (`com.topjohnwu.superuser`) with `nsenter` into the global mount namespace | root service process + hidden-API/hiddenapi wrapper + native helpers |
| SELinux repair | `ls -Zd` + `chcon -hR` (or a destination-parent fallback) | native `selinux_android_restorecon_pkgdir()` via `dlopen`/xDL + `restorecon` |
| Ownership repair | `chown -hR uid:gid` after extraction | native `chown_app_dir()` (FTS walk, cache-GID policy) + `Os.chown/Os.chmod` |

Key files:

- v2 shell layer: `source/core/util/src/main/kotlin/com/xayah/core/util/command/{TarUtil.kt,SELinuxUtil.kt,BaseUtil.kt,PmUtil.kt,PreparationUtil.kt}`
- v2 orchestration: `source/core/service/src/main/kotlin/com/xayah/core/service/util/{PackagesBackupUtil.kt,PackagesRestoreUtil.kt,CommonBackupUtil.kt}`, `source/core/service/src/main/kotlin/com/xayah/core/service/packages/{backup,restore}/Abstract*Service.kt`
- v2 paths: `source/core/util/src/main/kotlin/com/xayah/core/util/PathUtil.kt`, `source/core/data/src/main/kotlin/com/xayah/core/data/util/DataTypeUtil.kt`, `source/core/data/src/main/kotlin/com/xayah/core/data/repository/PackageRepository.kt`
- Next streaming: `source-next/app/src/main/java/com/xayah/databackup/service/archive/ArchiveStreamHelper.kt`, `.../service/restore/archive/ArchiveTarHelper.kt`
- Next repair: `source-next/app/src/main/java/com/xayah/databackup/service/restore/{RestoreInternalDataHelper.kt,RestoreExternalDataHelper.kt,InstallApkHelper.kt}`
- Next native: `source-next/native/src/main/jni/nativelib/{owner.cpp,selinux.cpp,inode.cpp}`, `source-next/native/src/main/jni/external/tar/{tar-wrapper.cpp,CMakeLists.txt}`

---

## 2. Backup: exact command sequence and archive layout

### 2.1 v2 — per-app serial backup

Order is fixed (`source/core/service/.../packages/backup/AbstractBackupService.kt:141-203`):

1. create target dirs (`.ota`-safe `mkdirs`), log task
2. for each selected package:
   - optional kill/force-stop (`AbstractPackagesService.kt:39-56`)
   - per-app dir `${mAppsDir}/${p.archivesRelativeDir}` where
     `archivesRelativeDir = "${packageName}/user_${userId}"` (+`@<preserveId>` for preserved copies)
     (`source/core/model/.../database/PackageEntity.kt:264-265`; `AbstractBackupService.kt:160-162`)
   - `backup(PACKAGE_APK)`, `(PACKAGE_USER)`, `(PACKAGE_USER_DE)`, `(PACKAGE_DATA)`, `(PACKAGE_OBB)`, `(PACKAGE_MEDIA)` — never in parallel
   - `backupPermissions()`, `backupSsaid()`
   - on success: write `package_restore_config.json` (a serialised `PackageEntity`) next to the archives
     (`AbstractBackupService.kt:173-188`, `PathUtil.getPackageRestoreConfigDst`, `PathUtil.kt:73-74`)

Per-part tar invocations (`TarUtil.kt`; every command is a single string joined with spaces and executed through `su`, so shell globs/quotes are deliberate):

```bash
# data parts (TarUtil.kt:44-80)
tar --totals ${excludeArgs} ${h} -cpf - -C "$srcDir" -- "$packageName" > "$dst"
# when compression != tar:
tar --totals ... -cpf - -C "$srcDir" -- "$packageName" | zstd -r -T0 --ultra -q --priority=rt -$level > "$dst"

# apk part (TarUtil.kt:11-42 + PackagesBackupUtil.kt:221-267)
cd "$srcDir"            # ApplicationInfo.sourceDir's parent, e.g. /data/app/~~<k>/com.foo-<k>
tar --totals -cpf - "./*.apk" > "$dst"      # shell glob => base.apk + split_*.apk
cd /
```

- `h = "-h"` only if the "follow symlinks" setting is on (`PackagesBackupUtil.kt:335`, default false: `core/datastore/.../Boolean.kt:27`).
- owner/mode are always recorded in the header (uid/gid both numerically and as names; mode bits incl. special bits). v2 runs as root, so its extractor (`-p`) re-applies them before the post-extract `chown`/`chcon` repair fixes them for the destination user.
- Exclusions are per data type (`PackagesBackupUtil.kt:305-323`):
  - `user`, `user_de`: `pkg/.ota`, `pkg/cache`, `pkg/lib`, `pkg/code_cache`, `pkg/no_backup`
  - `data`, `obb`, `media`: `pkg/cache`, `Backup_*`
  - Exclusion lists are built as `--exclude='pkg/cache'` style args against member names that already include the package dir.
- Data dirs (`DataTypeUtil.kt:6-12`, `PathUtil.kt:57-63`):

| part | source path | archive member |
|---|---|---|
| `apk` | `/data/app/~~*/<pkg>-*/` (`sourceDir` + `splitSourceDirs`) | `./*.apk` (no package dir) |
| `user` (CE) | `/data/user/<userId>` | `<pkg>/…` |
| `user_de` (DE) | `/data/user_de/<userId>` | `<pkg>/…` |
| `data` | `/data/media/<userId>/Android/data` | `<pkg>/…` |
| `obb` | `/data/media/<userId>/Android/obb` | `<pkg>/…` |
| `media` | `/data/media/<userId>/Android/media` | `<pkg>/…` |

- Compression (`source/core/model/.../Enum.kt:5-12`, `ModelUtil.kt:248-251`):
  `tar` → `.tar`; `zstd` → `.tar.zst` via `zstd -r -T0 --ultra -q --priority=rt -$level`; `lz4` → `.tar.lz4` via the same zstd command with `--format=lz4`.
  Default compression level is user-selectable; `--ultra` implies levels up to 22.
- Archive verification after every part (`CommonBackupUtil.kt:88-112` → `TarUtil.kt:82-106`):
  `tar -tf "$dst" >/dev/null 2>&1` (or `zstd -d -c "$dst" | tar -tf -`); on failure the archive is **deleted**.
  There is **no per-archive hash**; MD5 is only used for icon de-duplication (`core/data/.../AppsRepo.kt:426-442`, `HashUtil.kt`).
- Incremental skip: if the archive exists and the recomputed source byte size equals the recorded size, the part is skipped (`PackagesBackupUtil.kt:240-244, 327-331`).

Resulting layout (v2, local mode; root = user-chosen backup dir):

```
<backupRoot>/
  apps/
    com.foo/user_0/
      apk.tar.zst
      user.tar.zst          # CE
      user_de.tar.zst       # DE
      data.tar.zst
      obb.tar.zst
      media.tar.zst
      package_restore_config.json   # PackageEntity incl. permissions, ssaid, uid, timestamps, compression type
    com.foo/user_0@1726280000000/   # preserved (dated) copy
  files/<name>/media.tar.* + media_restore_config.json
  configs/icons.tar
  configs/configurations.json     # blacklists, labels, cloud config
  DataBackup.apk                  # self-backup ("Backup itself")
```

(`PathUtil.kt:12-33, 90-102`, `CommonBackupUtil.kt:47-86, 116-155`.)

### 2.2 Next — streamed tar+zstd, validate-then-extract

Backup (`source-next/.../service/backup/archive/ArchiveBackupAppsHelper.kt:56-88`):

- inputs = APK source dir(s) from `IPackageManager` (`sourceDir` + `splitSourceDirs`) or the single data path; for the APK part a synthetic `install-source.json` is appended (`ApkInstallSource.kt`, members named `install-source.json`).
- argument construction: `inputs.flatMap { listOf("-C", parentOf(it), childOf(it)) }` — archive member names are the last path segment only; ACL/owner/timestamps come from the file itself.
- tar runs as `tar -cpf - …` writing into a **FIFO**; the reader is a pollable `FifoInputStream` fed to `ZstdOutputStream` at a fixed level (`ArchiveStreamHelper.kt:38-98`). No temp `.tar` is written; failures delete the partial `.zst`.
- `tar` is not the system binary: it is GNU tar's `main()` called in a forked child through `TarWrapper.callCli(stdoutPath, stderrPath, argv)` (`ArchiveStreamHelper.kt:145-167`, `tar-wrapper.cpp:28-…`).
- Restore-side decompression: `zstd -d` in-process (`ZstdInputStream`) → FIFO → `tar --file=<fifo> --read-full-records …` (`ArchiveStreamHelper.kt:100-143`). Non-empty tar stderr **or** non-zero exit is a hard failure.
- Two passes on restore (`ArchiveTarHelper.kt:25-48, 60-75`): first `tar --list --verbose --numeric-owner --full-time --quoting-style=c` builds an entry table and validates it (unique paths, every parent present, hardlink targets exist, no absolute/`.`/`..` members, members must start with `<packageName>/`); then extraction:

```bash
tar --extract --directory=<dst> --numeric-owner --no-overwrite-dir --strip-components=1
  # internal data adds: --same-owner --same-permissions
  # external/APK parts add: --no-same-owner --no-same-permissions
```

- The archive inode/size/mtime/ctime is re-stat'ed before extraction ("Archive changed during restore") and paths are re-validated (`ArchiveTarHelper.kt:50-58`).
- Layout (Next): `<backupRoot>/backups/<uuid or name>/` containing `.config` (BackupConfig JSON: uuid, created_at, name, backend) and `apps/<pkg>@u<userId>/{apk,apk.tar.zst | int_data/{user.tar.zst,user_de.tar.zst} | ext_data/data.tar.zst | addl_data/{obb.tar.zst,media.tar.zst}}` plus `records/…json` (`PathHelper.kt:30-59, 75-130`; `ArchiveRecordPaths.kt`; `BackupConfigRepository.kt:150-190`).
  `BACKUP_ROOT/backups/<name>/.config`; per app dir name uses `@u<id>` (`PathHelper.APP_USER_SEPARATOR='@'`, `APP_USER_PREFIX="u"`). Note: this is **incompatible** with the v2 layout (`apps/<pkg>/user_<id>/<part>.<ext>`).

---

## 3. How app data is captured (paths, users, splits, external)

- **CE vs DE vs `/data/data`**: both trees use `/data/user/<userId>` and `/data/user_de/<userId>`, never `/data/data` (a symlink to `/data/user/0`; use the real path so member names and symlink handling stay unambiguous). See `PathUtil.kt:57-63` and `source-next/.../util/PathHelper.kt:75-79`.
- **Multi-user**: `userId` is the Android user (0, 10, 11…), taken from the package record; the Linux uid is `userId * 100000 + appId` (e.g. `/data/user/10/com.foo` ⇒ uid `1010xxx`). Restore can remap user (`AbstractRestoreService.kt:87, 132`: `restoreUser` setting overrides `p.userId`).
- **APKs and splits**: `ApplicationInfo.sourceDir` (base) + `splitSourceDirs` (splits) are collected via `getPackageSourceDir()` (`source/core/rootservice/.../RemoteRootServiceImpl.kt:263-272`); v2 tars all `./*.apk` in the parent dir; Next tars the directory's children plus `install-source.json`. OAT/`lib/` are not part of the APK archive.
- **External**: back up the **backing store** `/data/media/<userId>/Android/{data,obb,media}/<pkg>`, not the FUSE view `/storage/emulated/<userId>/…` (`PathUtil.kt:60-63`). Next additionally:
  - decides between writing to the FUSE/sdcardfs view and the backing store by parsing `/proc/mounts`/`/proc/filesystems` and `external_storage.sdcardfs.enabled` (`RestoreExternalDataHelper.kt:81-108`);
  - handles legacy *shared* OBB (`/data/media/obb/<pkg>`) unless the mount has `unshared_obb` (`RestoreExternalDataHelper.kt:103-113`);
  - on Android 11+ calls `IVold.setupAppDir()` / `fixupAppDir()` so ownership, mode and **project ID** are correct, and recreates POSIX default ACLs (`RestoreExternalDataHelper.kt:135-159, 178-195`).
- **What is deliberately not captured**: `cache`, `code_cache`, `lib`, `no_backup`, `.ota` (v2 exclusions, §2.1); Next keeps cache *directory roots* only and requires them to be directory entries (`ArchiveTarHelper.kt:97`). xattrs are **not** archived (no `--xattrs` anywhere); POSIX ACL support is compiled out of the bundled GNU tar (`USE_ACL 0`, `tar-config/config.h:2324`).

---

## 4. Ownership, permissions, timestamps, SELinux — preserve + repair

### Principle used by both trees

Transport tar metadata (uid/gid/mode/mtime inside the archive) **and then actively re-derive destination metadata** from the live system, because the destination uid, cache GIDs, SELinux categories and storage project IDs are device/app-install specific. This is the part that matters most for a lightweight clone.

### v2 (`source/core/service/.../util/PackagesRestoreUtil.kt:249-359`)

1. Read destination SELinux context **before** extraction: `ls -Zd "$dst" | awk 'NF>1{print $1}'` (`SELinuxUtil.kt:10-19`).
2. Decompress with GNU tar:
   `tar --totals <excludes> [--recursive-unlink] -xmpf "$src" -C "$dstDir"` (`TarUtil.kt:136-167`)
   - `-m` = `--touch`: **mtimes are not restored** for data parts;
   - `--recursive-unlink` is the "clean restoring" setting (`core/datastore/.../Boolean.kt:28`, default false).
3. Re-own: `chown -hR "$uid:$gid" "$dst/"` (`SELinuxUtil.kt:21-29`). `gid` is the app uid for `user`/`user_de`, but the **gid of the container dir** (`stat` of `/data/media/<u>/Android/data`) for `data`/`obb`/`media` (`PackagesRestoreUtil.kt:311-320`, native `getUidGid` = `stat()` in `source/native/src/main/jni/nativelib/nativelib.cpp:24-39`).
4. Re-label: `chcon -hR "$pathContext" "$dst/"` (`PackagesRestoreUtil.kt:321-340`). Fallback when no context was captured: take the parent container's context and text-substitute `system_data_file` → `app_data_file`.
5. APK install is done by decompressing the apk archive into `files/tmp/apks/<pkg>` then `pm install`/session install, then `queryInstalled` verification, then deleting the temp dir (`PackagesRestoreUtil.kt:176-235`, `PathUtil.getTmpApkPath` `PathUtil.kt:105`).

Notes:
- Ownership/mode come from the archive *only because the extractor runs as root*; GNU tar restores numeric owners by default when euid==0 and `-p` keeps full mode bits.
- SELinux contexts are **never** stored in v2 archives; they are re-derived from the destination system.
- The bundled GNU tar cannot even do `--selinux`: `HAVE_SELINUX_*` are undefined in `source/native/src/main/jni/external/tar/tar-config/config.h:1570-1579` while `HAVE_XATTRS` and `HAVE_LISTXATTR` are defined (`:1928, :1379`) and `xattrs.c` is compiled (`CMakeLists.txt` tar executable sources) — `--xattrs` would work, `--selinux` would not, and neither is used.

### Next (authoritative repair, best reference for us)

Internal CE/DE (`source-next/.../service/restore/RestoreInternalDataHelper.kt:36-114`):

- Require the **system-created** destination dir to already exist and be owned by the app uid (`stat` check, `:48-52`); never `mkdir -p` it (fscrypt policy + inode identity must survive).
- Record `st_dev`, refuse to cross mount points (`:58, :126`).
- Save the `lib` symlink target before wiping children; restore it afterwards pointing at the installed APK (`:59-61, 99-104`).
- Delete children but **preserve `cache` and `code_cache` directory roots** (only their contents are deleted) (`:62-73`).
- Extract with `--same-owner --same-permissions` (archive member names are stripped of `<pkg>` on restore).
- Repair metadata even if extraction failed (`:74-112`):
  - `NativeLib.chownAppDir(target, app.uid, sourceUid)` — FTS lchown walk with the AOSP `createAppDataDirs` policy (`owner.cpp:37-136`): root dir gid = uid; `cache`/`code_cache` children get the **cache GID** (`uid - 10000 + 20000` = `uid + 10000` for app ids 10000–19999); SDK-sandbox ids (13+) and PCC ids (17+) have their own rules; objects owned by other uids are skipped.
  - root dir `chown(uid,uid)` + `chmod 0700` (targetSdk ≥ N) else `0751`;
  - `cache`/`code_cache`: `chown(uid, cacheGid)`, `chmod 02771` (O+) else `0771`;
  - write `user.inode_cache` / `user.inode_code_cache` xattrs on the CE root (installd compatibility; `inode.cpp:12-52`);
  - `selinux_android_restorecon_pkgdir(path, seInfo, uid, RECURSE|FORCE)` where `seInfo = ApplicationInfo.seInfo + seInfoUser` (`native/src/main/jni/nativelib/selinux.cpp:70-96`; caller `rootservice/RemoteRootService.kt:478-489`).

External (`RestoreExternalDataHelper.kt:70-205`): stop app → `vold.setupAppDir()` → lstat/uid check → delete children (never cross devices, never follow links) → extract with `--no-same-owner --no-same-permissions` → `vold.fixupAppDir()` per directory + recreate `system.posix_acl_default/access` xattrs → `selinux_android_restorecon(RECURSE|FORCE)` on the **backing** dir (not the FUSE view; FUSE shows `genfscon` labels).

Timestamps: Next restores mtimes (no `-m`); v2 drops mtimes for data (`-m`). Neither preserves birthtime. Toybox tar restores mtime by default and `-m` disables it.

---

## 5. Stopping apps, clearing data, split-APK install, integrity

**Stop** (`AbstractPackagesService.kt:39-56`, options in `core/model/Enum.kt` `KillAppOption`):
- `OPTION_I`: loop on `dumpsys activity processes … | grep packageList …` + `killall -9 <pkg>` + `am force-stop --user <u> <pkg>` + `am kill <pkg>` (`BaseUtil.kt:143-157`).
- `OPTION_II`: `ActivityManager.forceStopPackageAsUser(pkg, userId)` through the root service (`RemoteRootServiceImpl.kt:473-475`).
- Next: callback `stopApp()` invoked once per restore operation (all destroy/write steps are serialized).

**Clear data**: no `pm clear` anywhere. v2 optionally passes `--recursive-unlink` to tar (unlink-as-you-extract, keeps dir inodes); Next deletes children explicitly, keeping the roots (§4). That preserves fscrypt policy, inode xattrs and the `lib` symlink, and avoids the permission/SSAID resets that `pm clear` causes.

**Split APK install** (`PmUtil.kt:9-101`):
```bash
# single APK
pm install [-i com.android.vending] --user <u> -r -t <apk>          # +--bypass-low-target-sdk-block on 14+
# 1 APK chosen when archive contains only one file; >1 → session:
sid=$(pm install-create [-i com.android.vending] --user <u> -t | grep -E -o '[0-9]+')
pm install-write "$sid" <name> <apk>      # once per split
pm install-commit "$sid"
```
Next installs through `PackageInstaller` sessions from the root process (`InstallApkHelper.kt:20-80`): `INSTALL_REPLACE_EXISTING|INSTALL_ALLOW_TEST`, `INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK` on 14+, `installerPackageName` override on 9+, plus the recorded `install-source.json` (initiating/installing/originating packages) so Play-Store-installed apps stay "owned" by Play (`ApkInstallSource.kt`).
Pre-flight: `PreparationUtil.setInstallEnv()` turns off package/dex verifiers (`settings put global verifier_verify_adb_installs 0`, `package_verifier_enable 0`, `package_verifier_user_consent -1`, `upload_apk_enable 0`) (`PreparationUtil.kt:119-…`).
Post-install verification: `queryInstalled(pkg, userId)` and install-result status check (`PackagesRestoreUtil.kt:229-235`, `ApkInstallResultReceiver`).

**Integrity**:
- v2: `tar -tf` after writing each archive, then delete on failure (`CommonBackupUtil.kt:88-112`). Optionally toggled off (`readCompressionTest`, default true).
- Next: full two-pass parse (entry table validation + zstd-to-EOF/checksum drain), archive identity re-stat, non-empty tar stderr treated as failure (`ArchiveTarHelper.kt:25-58`, `ArchiveStreamHelper.kt:139-143`).
- Neither stores archive checksums in metadata. `Checksum.kt` equivalent does not exist upstream.

---

## 6. Licence implications (GPL-3.0) and what we may adapt

- Upstream is **GPL-3.0** (`data-backup-ref/LICENSE`, `README.md:89-90`). **DataArk is already GPL-3.0 too** (`DataArk/LICENSE`, `DataArk/README.md:158`), so adapting upstream Kotlin/C++ (JNI repair helpers, shell command builders, path tables) is licence-compatible. Obligations that still apply: keep copyright/licence notices, add a clear "based on XayahSuSuSu/Android-DataBackup (GPL-3.0)" header to adapted files, state modifications, and keep complete source available. Note `DataArk/README.md:158` currently says “本项目未直接复制上游源码” — if we do port code, that sentence must be updated.
- Bundled binaries upstream: `assets/bin.zip` is built by `build/build_bin.sh` and contains **GNU tar (GPL-3.0-or-later)**, **busybox (GPL-2.0)** and **zstd (BSD-3-Clause / GPL-2.0 dual)** (`build_bin.sh:258-263`; GNU tar tree `source/native/src/main/jni/external/tar`). Shipping that zip in an APK means distributing GPL binaries ⇒ ship complete corresponding source + licence texts, and keep the whole distribution GPL-3.0-compatible.
- `source-next` statically links GNU tar into **`libtar-wrapper.so`** (`external/tar/CMakeLists.txt:353-361`, `tar-wrapper.cpp`). Under GPL-3.0 that is legal for DataArk, but it makes the shipped `.so` a GPL combined work (source obligation) and adds NDK/CMake/ABI maintenance — do not choose it unless toybox proves insufficient.
- **Preferred boundary for DataArk: keep calling the system `tar` (toybox) via the root shell.** toybox is permissive (0BSD-style grant in `landley/toybox` `LICENSE`), process invocation is not linking, and it keeps GPL binaries out of the APK. Reimplement the *behaviour* documented here (paths, exclusions, repair order, validation) rather than copying files.
- If toybox lacks something we need (e.g. `--strip-components` on Android 12, `--xattrs`), options are: (a) restructure the archive so the flag is unnecessary (recommended, §7.4); (b) do the operation in Kotlin with Apache Commons Compress (Apache-2.0) — but it cannot chown or set SELinux labels either; (c) bundle/compile GNU tar and accept the extra GPL/NDK obligations. Not recommended: `pm`/`restorecon`-free shortcuts that skip metadata repair.

---

## 7. Recommended shell recipe for DataArk (Android 12–16, toybox tar)

### 7.1 Verified toybox capability matrix

Facts below are from AOSP `external/toybox` at tags `android-12.0.0_r1`, `android-13.0.0_r1`, `android-14.0.0_r1`, `android-15.0.0_r1`, `android-16.0.0_r1` (`toys/posix/tar.c`, `android/device/generated/help.h`); `restorecon`/`chcon` are toybox applets in the same builds.

| flag | A12 (31) | A13 (33) | A14 (34) | A15 (35) | A16 (36) |
|---|---|---|---|---|---|
| `-c -x -t -f -C -v -z -j -J -I` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `-o/--no-same-owner`, `-p/--same-permissions` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `--numeric-owner`, `--exclude`, `--no-recursion`, `--full-time` | ✅ | ✅ | ✅ | ✅ | ✅ |
| `--one-file-system` | ❌ | ❌ | ❌ | ❌ | ✅ |
| `--strip-components=N` | ❌ | ✅ | ✅ | ✅ | ✅ |
| `--selinux` (save via pax `security.selinux=`, restore via `/proc/self/attr/fscreate`) | ❌ | ✅ | ✅ | ✅ | ✅ |
| `-Z` (zstd) | ❌ | ❌ | ❌ | ❌ | ✅ (needs an external `zstd` filter program; zstd is not a toybox applet, so do not rely on it) |
| `-S` sparse, hardlink entries, GNU long-name headers | ✅ | ✅ | ✅ | ✅ | ✅ |
| GNU-only flags we might miss: `--recursive-unlink`, `--xattrs`, `--same-owner`, `--no-overwrite-dir`, `--exclude-from=FILE` (toybox has `-X FILE`) | ❌ | ❌ | ❌ | ❌ | ❌ |
| toybox extras: `--to-command`, `--owner/--group/--mode/--mtime`, `--restrict`, `--overwrite`, `-k`, `--sort`(14+), `--null`(14+), `--xform`(14+) | ✅ | ✅ | ✅ | ✅ | ✅ |

Extraction semantics as root (A12 `tar.c`): owner restored via `lchown` unless `-o`; mode `chmod(header.mode)` with `-p`, otherwise masked to `0777`; existing files are `unlink()`ed before creation unless `-k`; existing dirs are merged (`mkdir` + `EEXIST` tolerated); **mtimes are restored unless `-m`**; a leading `/` in a member name is stripped unless `-P/--absolute-names`.

Probe at runtime (DataArk already does a `--help` probe in `DataArk/app/src/main/java/dev/dataark/backup/core/ShellScripts.kt:17-27` — keep it, but extend it):

```bash
tar --help 2>&1              # look for "--selinux", "--strip-components", "-Z"
restorecon -n /data/user/0   # exit 0 => toybox restorecon present
chcon --help 2>&1            # exit 0 => toybox chcon present
stat -c '%u %g %a %C' /data/user/0  # owner/mode/context readout
```

### 7.2 Privilege assumptions

- Anything under `/data/user*`, `/data/user_de*`, `/data/media*`, `/data/app*` needs **root**. `shell` (uid 2000, i.e. Shizuku in ADB mode) cannot read other apps' CE data except via `run-as` on `debuggable` apps; Dhizuku (device owner) cannot either. So: data backup/restore requires Root or Shizuku-started-as-root; keep the ADB/Dhizuku paths for APK-only work and degrade explicitly, exactly as `DataArk/README.md` already documents.
- If the shell's mount namespace does not show global mounts (FUSE, `/mnt/user`), re-enter it like v2 does: `nsenter --mount=/proc/1/ns/mnt sh` (`source/core/util/.../BaseUtil.kt:37-55`). For data paths prefer `/data/media` over `/storage` (see pitfall 6).
- Always `set -o pipefail` and check exit codes of **both** sides of a pipe (`tar | zstd`), otherwise a failed tar hides behind a successful compressor.

### 7.3 Backup recipe (per app, per part)

Run the tar side in the root shell, streaming the archive on stdout and writing/compressing it from
Kotlin (`ProcessBuilder("su","-c",script)` + binary `InputStream` → `FileOutputStream` + `DigestOutputStream`).
That keeps FUSE permissions out of the root process, gives byte progress and a SHA-256 for free, and works
on every Android 12–16. `tar -czf -` does gzip inside the same process (no pipe, so no status masking).

```bash
#!/system/bin/sh
# One invocation per part; the app captures stdout and writes <UBACKUP>/<pkg>/<part>.tar.gz.
set -e
U=0; PKG=com.example

case "$PART" in
user)   # CE (internal) data: exclude regenerable dirs, as upstream v2 does
  tar -czf - -C "/data/user/$U" \
      --exclude="$PKG/cache" --exclude="$PKG/code_cache" --exclude="$PKG/lib" --exclude="$PKG/no_backup" \
      -- "$PKG" ;;
user_de)
  tar -czf - -C "/data/user_de/$U" -- "$PKG" ;;
data|obb|media)   # backing store, NOT /storage/emulated (see note below)
  tar -czf - -C "/data/media/$U/Android/$PART" -- "$PKG" ;;
apk)   # parent dir of ApplicationInfo.sourceDir: base.apk + split_*.apk
  APKDIR=/data/app/~~xxxxxxxx/com.example-yyyyyyyy
  ( cd "$APKDIR" && tar -czf - -- ./*.apk ) ;;   # subshell: glob must expand inside APKDIR
esac
```

If the backup directory is writable by root (plain `/data` mount, or a directory root can `touch`), the same
commands may redirect straight to it (`... > /path/part.tar.gz`); test with `touch` first — root writes into
`/sdcard` FUSE volumes are frequently denied by the storage daemon.

Alternative: use `-cpf -` (plain tar) and compress in Kotlin with zstd-jni (better ratio/speed, tunable level);
keep `tar -tf`/`tar -tzf` validation after upload as upstream does (`CommonBackupUtil.kt:88-112`).

Per-app sidecar JSON (written by the app, next to the archives): `pkg`, `userId`, `uid/gid/mode at backup`,
`SELinux context at backup` (`stat -c %C`), `compression`, `size`, `sha256`, `versionCode`, `install-source`,
`apk file list`, `backup time`. Never trust archive headers for these values on restore — re-derive from the
destination device, except the install-source which is only known at backup time.

### 7.4 Restore recipe (order matters)

```bash
#!/system/bin/sh
set -e
U=0; PKG=com.example

# 0) pre-checks: SHA-256 matches sidecar, tar -tzf parses, df has free space, user $U exists

# 1) stop the app hard (both calls, ignore failures)
am force-stop --user "$U" "$PKG"
am kill "$PKG" 2>/dev/null || true

IN=/path/to/backup/com.example        # caller-provided

# 2) install APKs first: PM/installd create the data dirs owned by the *destination* uid
mkdir -p /data/local/tmp/apk.$$
tar -xzpf "$IN/apk.tar.gz" -C /data/local/tmp/apk.$$
if [ "$(ls /data/local/tmp/apk.$$/*.apk 2>/dev/null | wc -l)" -gt 1 ]; then
  sid=$(pm install-create -r -t --user "$U" | grep -E -o '[0-9]+')
  for f in /data/local/tmp/apk.$$/*.apk; do pm install-write "$sid" "$(basename "$f")" "$f"; done
  pm install-commit "$sid"
else
  pm install -r -t --user "$U" /data/local/tmp/apk.$$/base.apk
fi
pm list packages --user "$U" "$PKG"        # must succeed before touching data
rm -rf /data/local/tmp/apk.$$

# 3) CE data: keep the platform-created root dir and cache roots, wipe the rest
DST=/data/user/$U/$PKG
CTX=$(stat -c %C "$DST" 2>/dev/null || true)
UID_=$(stat -c %u "$DST") || { echo "no data dir for $PKG after install" >&2; exit 1; }
find "$DST" -mindepth 1 -maxdepth 1 ! -name cache ! -name code_cache -exec rm -rf {} +
tar -xzpf "$IN/user.tar.gz" -C "/data/user/$U"   # member names are $PKG/...
chown -R "$UID_:$UID_" "$DST"
chmod 0700 "$DST"          # 0751 instead when targetSdkVersion < N
for d in cache code_cache; do
  # cache GID is uid+10000 only for app ids (uid % 100000) in 10000..19999
  [ -d "$DST/$d" ] && { chown "$UID_:$((UID_ + 10000))" "$DST/$d"; chmod 02771 "$DST/$d"; }
done
# lib symlink: never trust the archived one; point it at the installed APK's lib dir
rm -rf "$DST/lib"
APKDIR=$(dirname "$(pm path --user "$U" "$PKG" | head -1 | sed 's/^package://')")
for abi in arm64 arm x86_64 x86; do
  [ -d "$APKDIR/lib/$abi" ] && { ln -s "$APKDIR/lib/$abi" "$DST/lib"; break; }
done
# SELinux: prefer the destination's own context captured above (keeps per-app categories)
if [ -n "$CTX" ]; then chcon -hR "$CTX" "$DST"; else restorecon -RF "$DST"; fi

# 4) DE data: same pattern, no cache-GID rule, no lib symlink
DST_DE=/data/user_de/$U/$PKG
mkdir -p "$DST_DE"
find "$DST_DE" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
tar -xzpf "$IN/user_de.tar.gz" -C "/data/user_de/$U"
chown -R "$UID_:$UID_" "$DST_DE"
restorecon -RF "$DST_DE"

# 5) external data on the backing store (gid: AID_EXT_DATA_RW=1078, AID_EXT_OBB_RW=1079)
for kind in data obb media; do
  DST=/data/media/$U/Android/$kind/$PKG
  mkdir -p "$DST"
  find "$DST" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  tar -xzpf "$IN/$kind.tar.gz" -C "/data/media/$U/Android/$kind"
  gid=1078; [ "$kind" = obb ] && gid=1079
  chown -R "$UID_:$gid" "$DST"
  chmod 0771 "$DST"
  restorecon -RF "$DST"          # labels live on the backing fs, not on the FUSE view
done
```

Order rationale (matches upstream): stop → install (APK first, so installd/PM create the destination data
dirs with the *destination* uid) → CE → DE → external. Verify `pm path` and the exit code of every step; on
failure leave a sidecar marker so a retry starts from a clean wipe instead of a half-merged tree.

### 7.5 Known pitfalls

1. **Toybox version skew** (§7.1): no `--strip-components` and no `--selinux` on Android 12; no zstd anywhere reliable. Design the archive so no stripping is needed: store `<pkg>/…` members and extract with `-C <container>`; the existing `<pkg>` dir is merged, not recreated.
2. **`/data/data` is a symlink** to `/data/user/0`. Use `/data/user/<userId>`; otherwise tar can duplicate or follow it (`-h` would follow all symlinks, including app-internal ones).
3. **Never `rmdir`/recreate the app data roots.** installd/vold created them with fscrypt policy, project IDs and inode xattrs. Wipe *children* only, and treat `cache`/`code_cache` as roots to keep (upstream `RestoreInternalDataHelper.kt:62-95`).
4. **Owner in archive ≠ owner on device.** Toybox tar as root happily applies the source uid/gid; always `chown -hR <dest uid>` afterwards. Cache dirs need the cache GID `uid + 10000` (only when `uid % 100000` ∈ 10000–19999) and mode `02771`; SDK-sandbox (13+) dirs use gid 9999/uid rules (`source-next/native/.../owner.cpp:37-69`).
5. **SELinux**: bare `restorecon -R` recomputes labels from the *destination* `file_contexts` but cannot reproduce per-app MLS categories (`seinfo`-derived); upstream uses `selinux_android_restorecon_pkgdir(pkgpath, seInfo, uid, RECURSE|FORCE)`. Practical tiers for us: (a) capture destination `stat -c %C` before extraction and `chcon -hR` it back (v2 approach, good for CE/DE roots); (b) `restorecon -RF` as fallback/safety net; (c) `tar --selinux` only on 13+ and only for same-ROM backups (source labels may be wrong for another device). Note file creation inside the existing app dir normally inherits the parent label, so (a) is usually enough.
6. **FUSE / scoped storage**: `/storage/emulated/<u>/Android/{data,obb,media}` is a FUSE view; as root, writes can be denied or get wrong ownership. Back up and restore `/data/media/<u>/Android/...`; if you must write via FUSE, expect `EPERM` on other apps' dirs. On Android 11+ exact project IDs/ACLs need `vold.setupAppDir`/`fixupAppDir`; without it, media apps may lose quota accounting but generally still work.
7. **Pipe failure masking**: `tar | zstd` returns the compressor's status. Use `set -o pipefail` and/or `PIPESTATUS`.
8. **Space**: app data is often larger than free space on `/data`. `stat -fs`-equivalent check before start; write archives straight to the external backup volume, not to `/data/local/tmp`.
9. **Long names / odd filenames**: toybox writes GNU long-name headers (`L`) and reads pax; keep paths relative, avoid absolute member names, and never build file lists with unquoted shell expansion. `--null -T list` is only available on 14+; on 12 use `-T list` with newline-free names or positional args after `--`.
10. **Hardlinks / sparse files**: both supported by toybox (`st_nlink` hardlink table, `-S`). Hardlink relationships are only preserved *within one part archive* (CE and DE are separate tar files, so a link shared across them becomes two files).
11. **Multi-user**: restore user may differ from source user; recompute uid from the live dir (`stat -c %u`) or `PackageManager` after install, never from the archive.
12. **DE data can only be touched while unlocked** (CE especially); DE dir exists per user and is *not* recreated by `pm install` until the app is installed for that user — install first, restore second.
13. **`pm install` on 14+** needs `--bypass-low-target-sdk-block` for old apps and may need verifier settings off (`setInstallEnv` in `PreparationUtil.kt:119+`); Play-Protect state otherwise blocks restores silently.
14. **Symlinks in app data**: tar stores symlinks as links (good), but absolute links into the old `/data/app/~~old-hash/pkg-hash/...` path (notably `lib`) are stale after reinstall. Never use `-h` for whole-tree backups (it inlines `/data/app` contents and can explode); instead repair known links after restore (upstream: `RestoreInternalDataHelper.kt:98-104`).
15. **Atomic-swap vs in-place restore**: upstream restores **in place** because installd/vold own the CE/DE root dirs (fscrypt policy, inode xattrs, project IDs). If DataArk implements an atomic rollback by renaming a fresh dir over the old one, verify the new root still carries the system-assigned encryption policy and `user.inode_cache*` xattrs; otherwise the app will create a second dir and look empty. A safer rollback: copy/rename the *children* to a side location, keep the root inode, restore, and delete the side copy only after success.
16. **Split APK ordering**: base + config splits must go into one session; writing splits as separate `pm install` calls leaves a broken app. Verify with `pm path` after commit.
17. **Partial archives**: unlink the `.tar.gz`/`.zst` on any failure before writing metadata (upstream `ArchiveBackupAppsHelper.kt:108-146`), and never trust an archive whose length differs from the recorded size.

---

## 8. Reference index (file:line)

v2:
- `source/core/util/.../command/TarUtil.kt:11-42` apk compress-in-cwd, `:44-80` data compress + exclusions + `-h`, `:82-106` `tar -tf` integrity, `:108-134` `-xmpf` decompress, `:136-167` decompress with exclusions/`--recursive-unlink`.
- `source/core/util/.../command/SELinuxUtil.kt:10-19` context read (`ls -Zd|awk`), `:21-29` `chown -hR`, `:31-39` `chcon -hR`.
- `source/core/util/.../command/BaseUtil.kt:37-55` shell init (`nsenter`, `PATH=files/bin`, `pipefail`), `:125-157` kill/kill-package.
- `source/core/util/.../command/PmUtil.kt:9-44` `pm install` per API, `:46-101` install-create/write/commit.
- `source/core/util/.../command/PreparationUtil.kt:119-…` verifier-off install env.
- `source/core/util/.../PathUtil.kt:12-33` dir constants, `:57-63` CE/DE/external paths, `:73-74` restore-config name, `:105` tmp apk path.
- `source/core/service/.../util/PackagesBackupUtil.kt:221-267` apk part, `:272-358` data parts (exclusions `:305-323`, `-h` `:335`), `:360-387` permissions+ssaid, `:190-215` icons tar.
- `source/core/service/.../util/PackagesRestoreUtil.kt:159-244` apk restore + session install + verify, `:249-359` data restore (context `:290-296`, decompress `:299-309`, chown `:311-320`, chcon/fallback `:321-340`), `:361-417` permissions/ssaid.
- `source/core/service/.../util/CommonBackupUtil.kt:88-112` archive test+delete, `:47-86` self APK backup.
- `source/core/service/.../packages/backup/AbstractBackupService.kt:141-203` per-app pipeline, `:173-188` config JSON write.
- `source/core/service/.../packages/restore/AbstractRestoreService.kt:108-156` restore pipeline + user remap `:132`.
- `source/core/service/.../packages/AbstractPackagesService.kt:39-56` kill options.
- `source/core/model/.../database/PackageEntity.kt:264-265` `archivesRelativeDir`.
- `source/core/model/.../Enum.kt:5-12` compression table; `ModelUtil.kt:248-251` level args.
- `source/core/data/.../repository/PackageRepository.kt:69` `getArchiveDst`, `:145-147` data src dir/name.
- `source/build/build_bin.sh:258-263` `bin.zip` = zstd+busybox+gnu tar; `:210-232` GNU tar build.
- `source/native/src/main/jni/external/tar/CMakeLists.txt:267-311` GNU tar executable; `.../tar-config/config.h:1579,1928,2324` SELinux off/xattrs on/ACL off.

Next:
- `source-next/app/.../service/archive/ArchiveStreamHelper.kt:38-98` FIFO+`tar -cpf -`+zstd, `:100-143` decompress+`--read-full-records`, `:145-167` JNI tar.
- `source-next/app/.../service/restore/archive/ArchiveTarHelper.kt:25-48` validate listing, `:60-75` extract flags, `:78-176` parser/validation.
- `source-next/app/.../service/backup/archive/ArchiveBackupAppsHelper.kt:56-88` inputs/args/stream, `:159-180` output paths; `.../service/backup/BackupAppSourceHelper.kt:15-30` candidate paths.
- `source-next/app/.../service/restore/RestoreInternalDataHelper.kt:36-114` full CE/DE repair sequence.
- `source-next/app/.../service/restore/RestoreExternalDataHelper.kt:70-162` mount detection/vold/clear/extract/repair, `:178-205` ACL+restorecon.
- `source-next/app/.../service/restore/InstallApkHelper.kt:20-80` PackageInstaller session; `entity/backup/ApkInstallSource.kt` metadata.
- `source-next/app/.../util/PathHelper.kt:30-59,75-130` layout; `service/archive/ArchiveRecordPaths.kt`; `data/BackupConfigRepository.kt:150-190`.
- `source-next/native/src/main/jni/nativelib/owner.cpp:37-136` ownership/cache-GID policy; `selinux.cpp:6-96` dlopen libselinux + restorecon(pkgdir); `inode.cpp:12-52` `user.inode_cache*` xattrs.
- `source-next/native/src/main/jni/external/tar/CMakeLists.txt:353-361` + `tar-wrapper.cpp` static GPL tar in `.so`.