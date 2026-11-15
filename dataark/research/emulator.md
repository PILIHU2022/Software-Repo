# Android target for DataArk testing (adb + root)

**Status: WORKING.** A headless, root-capable emulator is booted and usable right now, despite
the host having no `/dev/kvm`.

| Item | Value |
|---|---|
| adb serial | `emulator-5556` |
| AVD | `dataark_api30_atd` |
| Image | `system-images;android-30;google_atd;x86_64` (Android 11, API 30) |
| Build type | `userdebug` (`ro.debuggable=1`, dev-keys) |
| `adb root` | **works** — `adbd is already running as root`; `adb shell id` → `uid=0(root) ... context=u:r:su:s0` |
| `pm list packages` | works (107 packages on the stock ATD image) |
| `su` | `/system/xbin/su` setuid root; `adb shell 'su 0 id'` → `uid=0(root)` |
| `tar` / `gzip` / `sqlite3` | all present (`toybox 0.8.3-android`); tar.gz round-trip verified |
| Installed test app | `dev.dataark.backup` (DataArk `app-debug.apk`, from `DataArk/app/build/outputs/apk/debug/`) |
| Boot time | **~4 min** from `emulator` launch to `sys.boot_completed=1` (software emulation) |
| Accelerator | none — pure TCG (`-accel off`) |
| Logs | `.scratch/emulator/` (`emulator-dataark_api30_atd.log`, `boot-progress.log`, `RESULTS.md`) |

Two things keep it alive, and **both matter** if it ever looks "dead" (see §6):

| Background job | Holds | If it dies |
|---|---|---|
| `bash-a435427a51e74b8b` | the emulator (QEMU) process | the VM is gone; relaunch `run-session.sh` |
| `bash-925ac60c90ca40ed` | the adb **server** daemon | every new shell starts its own server and you see transient `device offline` |

---

## 1. Environment facts that shaped the setup

```
$ uname -m                     -> x86_64
$ nproc / RAM                  -> 16 / 15 GB
$ ls /dev/kvm                  -> No such file or directory
$ lsmod | grep kvm             -> kvm_intel, kvm (loaded)
$ ls /sys/class/misc/kvm       -> exists
$ grep -c vmx /proc/cpuinfo    -> 32
$ grep CapEff /proc/self/status-> 0000000000000000      # zero capabilities
$ mknod /dev/kvm c 10 232      -> Operation not permitted
$ sudo -n true                 -> "no new privileges" flag set, sudo refuses
```

So the host CPU supports VMX and the KVM modules are loaded, but the harness runs commands in a
`bwrap --unshare-pid --die-with-parent` sandbox **with an empty capability set**, so the missing
`/dev/kvm` device node cannot be created. **Hardware acceleration is not obtainable from inside
this session** — this is a privilege limitation, not a misconfiguration, and it needs the host
side (create `/dev/kvm`, or run the container with `--device /dev/kvm --cap-add MKNOD`).

## 2. `sdkmanager` "Failed to download any source lists" — root cause and fix

This was **not** a network/proxy/Java-trust problem. It reproduces exactly when
`ANDROID_USER_HOME` is unset and sdkmanager falls back to `~/.android`, which is mounted
**read-only** in the sandbox. Its repository cache/manifest write fails and surfaces as the
misleading "IO exception while downloading manifest / Failed to download any source lists".

Reproduced side by side (same shell, same network):

```
# A: ANDROID_USER_HOME points at a writable dir in the workspace
ANDROID_USER_HOME=/home/Spark/Projects/GitHub_APPS/.android-home sdkmanager --list
  -> exit 0, 620 package rows listed

# B: unset -> falls back to ~/.android (read-only)
env -u ANDROID_USER_HOME sdkmanager --list
  -> exit 0 but: Warning: Failed to download any source lists!
                 Warning: IO exception while downloading manifest
                 14 rows (installed packages only, no remote catalog)
```

`dl.google.com` / `repo1.maven.org` were reachable the whole time (`HTTP 200`), and the env's
`http(s)_proxy=http://127.0.0.1:41729` is harmless (Java ignores it; curl works through it).

**Working invocation:**

```bash
export ANDROID_SDK_ROOT=/home/Spark/Projects/GitHub_APPS/.toolchain/android-sdk
export ANDROID_HOME=$ANDROID_SDK_ROOT
export ANDROID_USER_HOME=/home/Spark/Projects/GitHub_APPS/.android-home   # MUST be writable
export ANDROID_AVD_HOME=$ANDROID_USER_HOME/avd
export JAVA_HOME=/usr/lib/jvm/default
```

Note `ANDROID_AVD_HOME` is also required in practice: `emulator` searches
`$ANDROID_AVD_HOME`, `$ANDROID_SDK_HOME/avd`, then `$HOME/.android/avd` — it does **not** honour
`ANDROID_USER_HOME` when locating AVDs, so without it you get
`ERROR | Unknown AVD name ...`.

## 3. Install commands (what actually ran)

```bash
yes | sdkmanager --install \
    "emulator" \
    "system-images;android-30;google_atd;x86_64" \
    "system-images;android-30;google_apis;x86_64"

no | avdmanager create avd -n dataark_api30_atd \
    -k "system-images;android-30;google_atd;x86_64" -d pixel_5 --force
```

`sdkmanager`'s direct-manifest fallback was never needed — no hand-downloading from
`repository2-3.xml` or the sys-img XML was required once `ANDROID_USER_HOME` was writable.

Emulator: **37.2.13**. Two API 30 x86_64 images installed; the **ATD** one is used because it is
the smallest/fastest to boot (`google_atd`, "Automated Test Device" — Google APIs, userdebug,
stripped of user-facing apps). `google_apis;x86_64` is also installed as a fallback.

`google_atd` boots to `userdebug` with `dev-keys`, so `adb root` is permitted. The AVD config was
patched to a 720x1280 / 320 dpi framebuffer to cut SwiftShader work under TCG.

### Does the emulator refuse x86_64 without KVM?

`emulator -accel-check` exits `8` ("`/dev/kvm is not found`"), and the launcher emits

```
WARNING | x86_64 emulation may not work without hardware acceleration!
```

That is a **warning, not a fatal error** on emulator 37.2.13 — TCG proceeds and the guest boots
correctly. (`-accel off` is what forces TCG; TCG logs harmless
`TCG doesn't support requested feature: CPUID.01H:ECX.avx/f16c` lines.)

## 4. Boot command

```bash
emulator -avd dataark_api30_atd \
  -port 5556 \
  -no-window -no-audio -no-boot-anim -no-metrics \
  -gpu swiftshader_indirect \
  -accel off \
  -no-snapshot -no-snapshot-save -wipe-data \
  -cores 4 -memory 2048 \
  -camera-back none -camera-front none
```

Ready-made scripts in `.scratch/emulator/`:

| Script | Purpose |
|---|---|
| `run-session.sh` | **Use this.** Boots, waits for `sys.boot_completed`, runs the root checks into `RESULTS.md`, then stays alive |
| `boot-emulator.sh` | Just boot the emulator |
| `wait-boot.sh` | Poll `sys.boot_completed` on `emulator-5556` |
| `sdkmanager-install.log`, `emulator-*.log`, `boot-progress.log`, `RESULTS.md` | Logs/evidence |

## 5. Verified evidence

```
$ adb devices -l
List of devices attached
emulator-5556  device product:sdk_gslim_x86_64 model:Google_ATD_built_for_x86_64 device:generic_x86_64

$ adb -s emulator-5556 shell getprop sys.boot_completed   -> 1     (4 min after launch)
$ adb -s emulator-5556 shell getprop ro.build.type        -> userdebug
$ adb -s emulator-5556 shell getprop ro.debuggable        -> 1
$ adb -s emulator-5556 shell getprop ro.build.version.sdk -> 30
$ adb -s emulator-5556 shell getprop ro.build.fingerprint -> google/sdk_gslim_x86_64/generic_x86_64:11/RSR1.210722.030/10147527:userdebug/dev-keys

$ adb -s emulator-5556 root            -> adbd is already running as root
$ adb -s emulator-5556 shell id        -> uid=0(root) gid=0(root) ... context=u:r:su:s0
$ adb -s emulator-5556 shell whoami    -> root

$ adb -s emulator-5556 shell pm list packages | wc -l   -> 107
$ adb -s emulator-5556 shell 'ls /data/data | head'    -> android, android.ext.services, ...
$ adb -s emulator-5556 shell 'ls /data/media/0'        -> Alarms Android DCIM ... (root)
$ adb -s emulator-5556 shell 'ls -l /system/xbin/su'   -> -rwsr-x--- root shell  (setuid su)
$ adb -s emulator-5556 push/pull                       -> both OK
$ adb -s emulator-5556 shell 'echo x > /sdcard/roottest.txt' -> OK
$ adb -s emulator-5556 shell service check activity    -> Service activity: found
```

### App install + launch, end-to-end (this is the real proof)

```
$ adb -s emulator-5556 install -r DataArk/app/build/outputs/apk/debug/app-debug.apk
Performing Streamed Install
Success

$ adb shell am start -W -n dev.dataark.backup/dev.dataark.backup.MainActivity
$ adb shell pidof dev.dataark.backup                      -> 3157
$ adb shell dumpsys activity activities | grep mResumed   -> dev.dataark.backup/.MainActivity
$ adb shell dumpsys window | grep mCurrentFocus
    -> Window{... dev.dataark.backup/dev.dataark.backup.MainActivity}
$ adb shell appops set dev.dataark.backup MANAGE_EXTERNAL_STORAGE allow -> ok,
  appops get -> MANAGE_EXTERNAL_STORAGE: allow
$ adb shell uiautomator dump ... -> text node "DataArk", package dev.dataark.backup
```

So the emulator runs DataArk's Compose UI, is root, and can grant `MANAGE_EXTERNAL_STORAGE`
(needed by a backup app) via `appops`. DataArk is `minSdk 26 / targetSdk 36 / compileSdk 37`, and
it installs and runs on API 30.

`pm list packages`/`appops` also work from a plain shell; `/data/data` and `/data/media/0` are
readable because adbd runs as root.

### Backup tooling present on the device (verified round-trip)

```
$ adb shell 'ls -l /system/xbin/su'   -> -rwsr-x--- root shell  (setuid, usable)
$ adb shell 'su 0 id'                 -> uid=0(root) ...            # NOTE: toybox su takes a uid, not -c
$ adb shell 'which tar gzip sqlite3'  -> /system/bin/tar /system/bin/gzip /system/bin/sqlite3
$ adb shell 'tar --version'           -> toybox 0.8.3-android
```

Real round-trip executed in `/data/local/tmp/bk`:

```
$ tar -cf t.tar f1.txt f2.txt ; gzip -c t.tar > t.tar.gz   -> rc=0
$ tar -tvf t.tar          -> -rw-rw-rw- root/root 11 f1.txt / 6 f2.txt
$ mkdir x && tar -xzf t.tar.gz -C x && cat x/f1.txt        -> extract rc=0, "payload123"
```

So `tar`/`gzip`/`sqlite3` and root are all available for backup/restore round-trip testing.

## 6. Lifecycle warning — the emulator lives inside a background job

Each `bash` call from this session runs in its **own PID namespace**
(`bwrap --ro-bind / / --dev /dev --unshare-pid --proc /proc --die-with-parent ...`). Consequence:
a process started with `nohup`/`setsid` from a normal call **is killed as soon as that call
returns**, and `ps` in a later call cannot see it. This bit me once (a `setsid`-detached emulator
died immediately; the next boot then failed with
`FATAL | Running multiple emulators with the same AVD` because of stale `hardware-qemu.ini.lock`/
`multiinstance.lock` files).

Therefore the emulator is launched **as a managed background job** (`bash-a435427a51e74b8b`,
running `run-session.sh`), which keeps the namespace alive while the job runs. If that job is
killed, the emulator dies with it. Relaunch with:

```bash
# clear stale locks first
rm -f /home/Spark/Projects/GitHub_APPS/.android-home/avd/dataark_api30_atd.avd/*.lock
cd /home/Spark/Projects/GitHub_APPS/.scratch/emulator && ./run-session.sh   # as a background job
```

Then poll `.scratch/emulator/boot-progress.log` for `=== BOOT COMPLETED ===`, and use
`emulator-5556` as the serial.

### If it looks dead, it probably isn't: `device offline` and "no qemu process visible"

Two misleading symptoms, both caused by the PID-namespace sandbox:

1. **`ps`/`pgrep` shows no qemu-system-x86.** That is expected — the VM lives in the
   `bash-a435427a51e74b8b` job's namespace and is invisible from every other shell on the box.
   "No qemu process" does **not** mean the emulator died. Trust `adb get-state` instead.
2. **`adb devices` briefly shows `offline`, or `get-state` says `error: device offline`.** The adb
   *server* is a daemon started from whichever shell first needed it; because each shell has its
   own PID namespace, that daemon dies with its shell. Every new shell therefore re-spawns a
   server ("`daemon not running; starting now`") and the first command or two race the device
   re-attach and report `offline`/`not found`.

Fixes, cheapest first:

```bash
adb kill-server; adb start-server; sleep 3; adb devices -l   # most reliable one-liner
adb reconnect offline
```

A persistent server daemon is held open by background job **`bash-925ac60c90ca40ed`**
(`adb start-server` + `exec sleep`), so commands normally do **not** churn the server — as long as
it is alive, `adb devices` is instant and stable. If a call ever prints
`* daemon not running; starting now`, that job has died; restart it, or just re-run the one-liner
above. Retry once after ~3 s before concluding anything is broken.

## 7. Limitations / notes

- **Screenshots come out black.** `adb exec-out screencap -p` returns a valid 720x1280 PNG that is
  entirely black: a known artifact of the headless gfxstream/SwiftShader readback path. Use
  `uiautomator dump` for assertions (it works — it reported DataArk's UI text). If real pixels are
  required, boot a second instance with `-gpu guest` (pure in-guest software GL) or run with a
  window.
- **`google_atd` is a stripped test image**: 107 packages, no Play Store/GMS UI apps, no launcher
  extras. Fine for app testing; `google_apis;x86_64` (also installed) is the fallback if a more
  complete system is needed.
- **No hardware acceleration.** Everything is TCG: app install of a 26 MB APK is quick, but heavy
  CPU-bound work and first-run JIT/warm-up will be slow. `am start -W` timed out at its default
  while the activity still reached `mResumedActivity`, so don't treat `Status: timeout` as failure.
- **Network device found earlier is gone.** Before the emulator existed there was a physical
  phone over network adb (`192.168.1.15:34311`, `PJF110`, OnePlus). It was a **production build**:
  `adb root` → `adbd cannot run as root in production builds`, so it was never root-capable. Its
  wireless-debugging port later closed (`Connection refused`; mDNS still advertises it), so it is
  currently unavailable. It was not usable for the root-requiring parts anyway.
- Stale mDNS ghost entries may appear in `adb devices -l` as `10.0.2.15:34311 offline` (the
  emulator's own NAT address). Harmless; always target `emulator-5556` explicitly with `-s`.
- `~/.emulator_console_auth_token` is unwritable → `emulator console` (telnet 5554) is
  unavailable. Not needed; use adb.

## 8. Suggested next steps

- Run instrumentation tests with `adb -s emulator-5556`; grant storage permissions with
  `appops`/`pm grant` as shown above.
- For Shizuku/Dhizuku-based paths (DataArk requests `moe.shizuku.manager.permission.API_V23` and
  `com.rosan.dhizuku.permission.API`): `adb root` replaces the need for Shizuku for
  shell-level work, and Shizuku can be started over adb (`sh /sdcard/Android/data/<pkg>/start.sh`)
  by pushing its APK if a Shizuku-flavoured test is required.
- If API-36 fidelity is needed (`targetSdk=36`), `system-images;android-36;google_apis;x86_64` is
  downloadable with the same command; expect a longer TCG boot than the 4 min measured here.