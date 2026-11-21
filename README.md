**English** | [简体中文](README.zh-CN.md)

[![Gadgetbridge](https://github.com/PILIHU2022/Software-Repo/actions/workflows/gadgetbridge.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/gadgetbridge.yml)
[![Fcitx5 for Android](https://github.com/PILIHU2022/Software-Repo/actions/workflows/fcitx5.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/fcitx5.yml)
[![Deploy Pages](https://github.com/PILIHU2022/Software-Repo/actions/workflows/deploy-pages.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/deploy-pages.yml)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)

# Software-Repo — Patchwork build overlay

A personal, patch-based build overlay for six Android apps — **ScreenTime**,
**RecurringExpenseTracker**, **Fcitx5 for Android**, **BasicSync**,
**Gadgetbridge** and the **BaiduNetGo Android client** — plus the
**BaiduNetGo desktop** client, which is built here but is *not* an
Android/F-Droid app.
No application source is vendored here. The repository keeps ordered patch
queues, plus the CI that applies them to current upstream code: signed APKs are
published through a signed F-Droid repository, and the desktop app's x86
installers are published as GitHub Releases plus an update feed.

> Personal project, not affiliated with or endorsed by the upstream authors
> (Dennis Bauer, Atharok, the Fcitx5 for Android contributors, Andrew Gunnerson,
> the Gadgetbridge contributors, pengstem).

## What this is

A *patchwork overlay* monorepo that stores two things and no app source:

1. **Patch queues** — `apps/<app>/patches/*.patch` for the Android apps and
   `desktop/baidunetgo/patches/*.patch` for the desktop one: ordered changes
   that apply on top of the upstream branch with `git am -3`. The monitored
   branches are expenses `main`, screentime `main`, fcitx5 `master`
   (fcitx5-android's real default branch) and baidunetgo `main`.
2. **Build and publish automation** — CI fetches the current upstream head,
   applies the queue, rewrites the version, builds signed APKs and updates the
   signed F-Droid repository; separately, `baidunetgo.yml` builds the patched
   desktop app for x86 only, publishes the installers to a tagged GitHub
   Release and commits the update feed `desktop/baidunetgo/update.json`.

Re-applying the queue to the latest upstream commit on every build lets upstream
fixes and features flow in without maintaining a diverged fork. A patch that no
longer applies fails the pipeline and opens an issue instead of publishing stale
code.

## Apps

| App | Upstream (branch) | Upstream revision | Patches | Built version |
| --- | --- | --- | --- | --- |
| RecurringExpenseTracker | [DennisBauer/RecurringExpenseTracker](https://github.com/DennisBauer/RecurringExpenseTracker) (`main`) | `e8ac17f` | 20 | `0.21.4-patchwork` (6812) |
| ScreenTime | [Atharok/ScreenTime](https://gitlab.com/Atharok/ScreenTime) (`main`) | `9ac2520` | 11 | `1.4.1-patchwork` (1304, arm64-v8a) |
| Fcitx5 for Android | [fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) (`master`) | `eb5b5021` | 17 | `0.1.3-patchwork` (11207, arm64-v8a) |
| BasicSync | [chenxiaolong/BasicSync](https://github.com/chenxiaolong/BasicSync) (`master`) | `74b1dea` | 4 | `3.29-patchwork` (25604802, arm64-v8a) |
| Gadgetbridge | [Freeyourgadget/Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (`master`) | `93d4d79` | 5 | `2026.11.1-patchwork` (25700) |
| BaiduNetGo (desktop) | [pengstem/baidunetgo](https://github.com/pengstem/baidunetgo) (`main`) | `60ca12c` | 48 | `0.8.3.48` — upstream `0.8.3` + 48 patches (linux/windows x86, Arch package; Release + feed) |
| BaiduNetGo (Android) | [pengstem/baidunetgo](https://github.com/pengstem/baidunetgo) (`main`) — same queue as the desktop build | `60ca12c` | 48 | `0.8.3-patchwork` (80348 — upstream 803 × 100 + 48 patches) |
| DataArk | *in-tree* — source at [`dataark/`](dataark/) (no upstream; GPL-3.0, icon from [Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup)) | — | — | `1.0.0` — full source committed, not a patch queue; CI is dormant (see below) |

**DataArk** — the one app whose full source lives in this tree instead of a
patch queue. It is an original Kotlin/Compose backup tool (package
`dev.dataark.backup`, GPL-3.0-or-later) that archives other apps' private
`/data` via Root, Shizuku (ADB/root modes) or Dhizuku, with tar+SHA-256
snapshots, atomic restore with SELinux/ownership repair, and honest capability
gating — Dhizuku is APK-only, and external `Android/data` requires root. See
[`dataark/README.md`](dataark/README.md) and the test notes in
[`dataark/research/`](dataark/research/). Its build workflow
[`.github/workflows/dataark.yml`](.github/workflows/dataark.yml) is
**intentionally dormant** (Feb-30 cron, unreachable branch filter, and an
`if: false` job guard): nothing runs it automatically or manually until the
triggers are consciously re-enabled.

The five Android apps are published in F-Droid. **The BaiduNetGo desktop
program is not**: it is a
Go/MyGo desktop program, built on its own 10-day schedule. Its installers —
`.deb`, `.tar.gz`, an Arch Linux `.pkg.tar.zst` (`sudo pacman -U`) and the
Windows setup/portable `.zip` — go to a GitHub Release tagged
`baidunetgo-desktop-<version>-<short_sha>` and to the `baidunetgo-x86-<sha>`
workflow artifact; on Linux a downloaded update can be installed in place and
the app restarted, while a pacman-installed copy defers to `pacman -U`. Each
build also commits the update feed `desktop/baidunetgo/update.json` — never an
APK.

**ScreenTime** — daily usage history persisted in a local SQLite database, a new
analysis screen backed by that history (durations count up from zero while the
weekday bars and charts grow, with a dedicated **Analysis** settings category and
an in-app explanation of the local history), a grow-from-zero reveal animation
on the main and application-detail Canvas charts, cached rasterized app icons,
and no main-thread work at startup.

**RecurringExpenseTracker (Expenses)** — Tilla-style UI (coloured service tiles,
built-in catalog of 92 service icons, editor icon picker, name-based
suggestions), a plain `LazyColumn` list, a backup/restore fix that restarts the
database process and infers icons, and a new Tilla-style **Calendar** tab: month
card with 7-column 1:1 day cells and a locale-aware week start; day cells with
the date, an 18dp tinted service icon, a `+N` badge for multiple payments and a
paid check; day-detail cards below the month; arrow and swipe month paging; month
projection off the main thread; English, Simplified and Traditional Chinese
strings. Later patches add a Tilla-style home bar (sort/filter/menu), an
analytics screen, an archive screen, pause/resume (database v13), a redesigned
settings hub with seven preset themes (including Catppuccin) and a configurable
card layout. See [`docs/design/tilla-calendar.md`](docs/design/tilla-calendar.md).

**Fcitx5 for Android** — the Fcitx5 input method framework and engines on
Android, built from upstream `master` plus ten patches: an
`MANAGE_EXTERNAL_STORAGE` permission so librime can write a shared-storage
`sync_dir` (for example `Download/Rime-Sync`), a built-in Rime/Fcitx5
configuration file manager and UTF-8 editor with stale `.temp.userdb` cleanup at
startup, a horizontal back arrow in the file manager, and a Syncthing bridge.
Android forbids other apps from writing the app-private Rime directory, so the
bridge copies `Download/Rime-Sync/Rime-Arch` into it on demand, removes
`build/` and restarts Fcitx so Rime redeploys; trigger it from Advanced →
“Apply Syncthing Rime profile” or with
`adb shell am broadcast -a org.fcitx.fcitx5.android.action.RIME_IMPORT
-n org.fcitx.fcitx5.android/.receiver.RimeImportReceiver` (no-op when nothing
changed). One patch lets `build-logic` honour a downstream
`PATCHWORK_REVISION`; it is inert when the variable is unset, so upstream builds
are unaffected.

The 11xx patch wave adds a GitHub-style typing heatmap and statistics to the
app: the main settings page now opens with a 53-week contribution card
(customisable palette, tap-a-cell tooltip with the day's character count), the
IME records committed characters and corrections into a small Room database
(counts only, never text, password fields skipped), and a compact
“Statistics & heatmap” page exposes the palette, the tracking switch and reset.
Settings sub-pages render as Material 3 surface cards with a 64 dp top app bar
(dynamic colour on Android 12+, baseline M3 palette below), the whole settings
home page is regrouped into Input / Appearance & interaction / Tools & data /
System, and auto backups zip `shared_prefs` + `databases` once per version bump
(three kept) with a restore entry under Advanced, so settings, clipboard history
and typing statistics survive future updates. Only `arm64-v8a` is published (upstream produces per-ABI APKs
and no universal APK), and the APK is
about 52 MB.

**Gadgetbridge** — Bluetooth wearables without the vendor's app. The queue
starts from upstream and adds five patches:

* the **user interface runs on Jetpack Compose + Material 3 Expressive**: the
  settings tree (home, every sub-screen, the search), the first-run guide, the
  About page and the sleep score sheet are Compose, and the remaing XML screens
  inherit the larger Expressive shapes, so a settings change or a theme switch
  looks the same everywhere;
* an **age-based sleep score**: when the device reports no score of its own, the
  recorded sleep stages are scored against the sleep duration recommended for
  the user's age (deep sleep share, REM share and efficiency included), shown in
  the day and week charts and in the dashboard widget, with a **breakdown sheet
  behind the score ring** and a chart setting to turn it off;
* a **backup that runs on every app open** — the one moment the process is
  guaranteed to be alive — with its own settings page, a retention period, a
  changeable folder and a reset action, instead of relying on WorkManager, which
  vendor ROMs routinely never run;
* the **database export writes a temporary file first**, validates the SQLite
  header and only then replaces the target, so a failing run (a revoked SAF
  grant, a moved document) can no longer truncate the previous backup, and a
  missing location is reported rather than silently skipped;
* the **Xiaomi music and time paths are made consistent**: one complete music
  snapshot per change (state, title, artist, position, duration) instead of a
  metadata packet and a state packet that overwrite each other, a finished
  release build signed from the environment, and the clock re-sent once per
  connection after the watch answers a device state request.

The release APK is signed here rather than upstream: `PATCHWORK_SIGN_*` feeds a
small build patch, so the F-Droid repository serves a build whose package name
matches the official app but whose key does not.

**BasicSync** — Syncthing on Android, wrapped in a small Kotlin/Compose app
that only decides *when* Syncthing runs. The queue adds four patches on top of
the 3.29 release:

* the app now **opens on the sync page** — Syncthing's web UI is what the app is
  for, so it is no longer two taps deep in a settings list — and a **navigation
  drawer** (modal on phones, permanent on tablets and TVs) holds the pages that
  are actually used: sync, sync conflicts (with a badge), settings, network
  conditions and the sync schedule, plus the app version and the live Syncthing
  run state. The drawer's `WebView` is owned by the activity, so the web UI keeps
  its DOM state instead of reloading when switching pages;
* a **conflict management page**: every sync conflict shows its folder, its
  creation time and three actions — *Use conflict version* (replace the original
  with the conflict copy), *Keep original* (delete the conflict copy) and *Open
  folder* — behind confirmations, with per-item progress and readable errors.
  This works for direct-storage folders and for SAF folders, both resolved
  through the same document URIs Syncthing already reports;
* the Gradle build honours `PATCHWORK_VERSION_CODE`, `PATCHWORK_VERSION_NAME` and
  `PATCHWORK_ABI`, which the overlay needs because upstream derives its
  versionCode from `git describe` (a 5-bit commit-count field a long-lived queue
  would overflow) and would otherwise build the Go/Syncthing runtime for all
  four ABIs. All three variables are unset in upstream builds.

**BaiduNetGo (desktop, not F-Droid)** — the community-fixed build of pengstem's
pure-Go Baidu Netdisk client: a duplicate finder, an in-app file clipboard with
undo, Finder-style marquee selection and drag-to-move, window/font adaptation,
and a pluggable update-check feed, on top of MyGo's native GPU UI. Built from
`desktop/baidunetgo/patches` for **x86 only** (`linux/amd64`, `windows/amd64`)
by `scripts/build-baidunetgo.sh`, which reapplies the queue with `git am -3`
(the exported-commit form of rebasing) to every new upstream head, plus
`scripts/build-baidunetgo-arch.sh`, which runs makepkg in an
`archlinux:base-devel` container to add the Arch Linux `.pkg.tar.zst`
(`sudo pacman -U <file>`). Its installers are published to a tagged GitHub
Release and the `baidunetgo-x86-<sha>` workflow artifact; on Linux a downloaded
update can be installed in place and the app restarted, while a copy installed
by pacman refuses and points at `pacman -U`. Each build also commits
`desktop/baidunetgo/update.json`, the feed the app reads from Settings → Updates
at
<https://raw.githubusercontent.com/PILIHU2022/Software-Repo/main/desktop/baidunetgo/update.json>
(compiled into the binary). It is never an F-Droid package.

**BaiduNetGo (Android)** — the Android half of the same queue: a Material 3
Expressive Compose client (package `com.pengstem.baidunetgo`) that signs in by
scanning a QR code, browses the netdisk, and backs up chosen folders and photo
albums to it on a schedule — each uploaded file OpenPGP-encrypted into a
`.gpg` that `gpg -d` opens on any computer, the same format the desktop
backup writes. The desktop and Android builds replay the identical
`desktop/baidunetgo/patches` queue onto the same upstream head
(`scripts/build-baidunetgo.sh --prepare` prepares the tree; the Android half
is built from its `android/` Gradle project by
`scripts/build-baidunetgo-android.sh` with the same SDK/JDK steps as the other
apps). The signed APK is published three ways: a tagged GitHub Release
(`baidunetgo-android-<version>-<short_sha>`, never marked `latest`), the
F-Droid repository, and the in-app update feed `fdroid/baidunetgo/update.json`
at <https://software.sparkzh.top/baidunetgo/update.json>, which
`scripts/baidunetgo-android-feed.py` regenerates each build and
`deploy-pages.yml` deploys with the rest of `fdroid/`. It is signed with the
same committed patchwork test keystore as the other apps; the `dev.jks`
inside the upstream tree is a development-only key and is never used.

Both Android apps with prebuilt native dependencies (RecurringExpenseTracker and
ScreenTime) are compiled **ARM only**: `KEEP_ABIS="arm64-v8a armeabi-v7a"`
in their `app.conf` makes `build-app.sh` strip `lib/x86/` and `lib/x86_64/` after
Gradle, then realign to 16 KB pages and re-sign the APK (v2/v3). Fcitx5's
`arm64-v8a` build is unchanged. BasicSync also ships `arm64-v8a`
only, but there the filter is applied *before* the build: the queue's version
patch narrows the ABI splits to `PATCHWORK_ABI`, so the embedded Go/Syncthing
runtime is compiled once instead of four times.

## F-Droid repository

Repository (custom domain, served and TLS-terminated in front of GitHub Pages;
verified):

```
https://software.sparkzh.top/repo
```

The index returns HTTP 200, and `index-v2.json` lists
`de.dbauer.expensetracker` **6806**, `com.atharok.screentime` **1303** and
`org.fcitx.fcitx5.android` **11203**.
Landing page: <https://software.sparkzh.top/>. The GitHub Pages URL
<https://pilihu2022.github.io/Software-Repo/repo> redirects to this domain.

Add it in F-Droid: **Settings** → **Repositories** → **Add repository** → paste
the URL (it must end in `/repo`) → check the fingerprint in the repository
details. Signing certificate:

```
SHA-256: D3:34:88:79:40:65:C9:2E:37:0D:24:A1:0E:61:4E:1E:64:E3:19:B7:5E:6F:8B:B7:C4:AA:D6:7F:3A:5B:5E:01
```

> Package names match the official apps, but the signing key differs. Android
> rejects updates across keys, so uninstall the official app first — which
> deletes its data, so back it up. This applies to all three apps, including
> Fcitx5 for Android: switching from the official or F-Droid build requires
> uninstalling it, and the Rime user data under `Download/Rime-Sync` is only
> preserved if you back it up yourself.

Raw fallback for when Pages is unavailable:

```
https://raw.githubusercontent.com/PILIHU2022/Software-Repo/main/fdroid/repo
```

(raw.githubusercontent.com has no directory listing; use this as the repository
address — F-Droid requests `index-v2.json` under it.)

Both addresses serve the same signed index. The index advertises the canonical
repository address (the custom domain), so some clients warn about an address
mismatch when the raw URL is used; the content is identical.

## How automation works

Every app has its own pipeline workflow — `expenses.yml`, `screentime.yml`,
`fcitx5.yml`, `basicsync.yml` and `gadgetbridge.yml` — and each of them only
declares that app's triggers, delegating the work to the reusable
`app-build.yml`. A pipeline only ever starts for its own app: its `push` trigger
is limited to `apps/<app>/**` (plus its workflow file), so a commit for one app
never starts a build for another, and nothing at all runs for a docs-only or
`scripts/**` commit. Each pipeline runs daily at its own minute; GitHub crons
cannot express "every 10 days" (a `*/10` day-of-month rule would fire on the
1st, 11th, 21st and 31st), so the run gates itself on that app's timestamp in
`.watch-state.json` and builds only once the 10 days have passed **and** the
upstream SHA differs from `fdroid/.build-state.json`. A delayed or missed day
only shifts that app's window.

When the upstream moved, the queue is re-anchored before building:
`scripts/rebase-queue.sh` fetches the new head, applies the queue with `git am
-3`, exports the applied commits back into `apps/<app>/patches` (keeping the
existing file names) and records the new anchor in `apps/<app>/upstream.txt`;
the watch job commits that with `[skip ci]`. A queue that no longer applies
fails the run and opens an issue instead of publishing stale code — it is never
anchored backwards onto the fallback commit, and the 10-day window is only
advanced by a run that really built and published.

`app-build.yml` holds the shared jobs, one app per run:

1. **watch** — `git ls-remote` the app's upstream branch, compare it with
   `fdroid/.build-state.json`, apply the 10-day gate, re-anchor the queue;
2. **build** — mirror clone in `.cache/mirror/<app>.git`, fresh checkout,
   optional recursive submodule update (fcitx5), `git am -3` over the queue,
   version handling, Gradle release build, keystore signing, an ARM ABI strip
   plus 16 KB realign and re-sign when `KEEP_ABIS` is set, then `apksigner` /
   `aapt` verification. The fcitx5 leg additionally frees ~10 GB of runner
   disk and installs `extra-cmake-modules`/`gettext`, NDK 28.0.13004108, CMake
   3.31.6 and build-tools 36.1.0;
3. **publish** — `publish-fdroid.sh` keeps at most 3 APKs per package, dedupes
   by `(package, versionCode)`, signs the index with fdroidserver (`fdroid
   update`), commits `fdroid/repo` and `.build-state.json` with `[skip ci]`,
   updates the README table and deploys Pages. A failed build keeps its old
   state, so that app's next run retries it;
4. **record** — advance that app's 10-day window, only after build and publish
   succeeded;
5. **notify** — open or comment on a **“Patchwork build failed”** issue with the
   run link and a local reproduction command, and close it once a run recovers.

There is no workflow that builds every app anymore: `app-build.yml` is reusable
and takes the app id as its only input, so a manual run means dispatching that
app's workflow (or `scripts/build-app.sh <app>` locally).

`baidunetgo.yml` is the desktop counterpart: it runs daily (`43 4 * * *`), gates
on `.watch-state.json`'s `baidunetgo` timestamp, resolves `pengstem/baidunetgo`
`main` with `git ls-remote`, and when the SHA differs from
`desktop/.build-state.json` replays `desktop/baidunetgo/patches` onto it
(`git am -3 --keep-cr`, with one retry on the last built commit when the queue
no longer applies), builds `linux/amd64,windows/amd64` with `go tool mygo build`, and
uploads the installers as the `baidunetgo-x86-<sha>` artifact. It also publishes
them to a Release tagged `baidunetgo-desktop-<version>-<short_sha>` (assets:
`.deb`, `.tar.gz`, the Arch `.pkg.tar.zst` that a makepkg step builds in an
`archlinux:base-devel` container, Windows setup `.exe` and portable `.zip`; never
marked `latest`) and commits `desktop/baidunetgo/update.json`, the feed the
patched binary checks from Settings → Updates at
<https://raw.githubusercontent.com/PILIHU2022/Software-Repo/main/desktop/baidunetgo/update.json>
(the URL is baked in at build time). A fallback build keeps the recorded head, so
the next 10-day window retries the new upstream. A second job in the same
workflow builds the queue's Android client with the same SDK/JDK/keystore steps
app-build.yml uses, signs it with the patchwork keystore, publishes it to a
`baidunetgo-android-<version>-<short_sha>` Release and the F-Droid repository,
and commits the Android update feed `fdroid/baidunetgo/update.json`
(<https://software.sparkzh.top/baidunetgo/update.json> — deployed by
`deploy-pages.yml`). The 10-day window advances only when both builds finished.

The last full build
([run 40076793167](https://github.com/PILIHU2022/Software-Repo/actions/runs/40076793167))
is green. Pages was enabled once manually (Settings → Pages → Build and
deployment → Source: “GitHub Actions”); `GITHUB_TOKEN` cannot create the site.
The site is published on the custom domain `software.sparkzh.top` (a `CNAME`
file in `fdroid/` keeps it attached), and GitHub Pages redirects the
`pilihu2022.github.io` URL there.

`deploy-pages.yml` is the hourly safety net (`17 * * * *`, plus `fdroid/**`
pushes): it publishes the committed `fdroid/` without rebuilding and stays green
while Pages is unavailable.

## Version scheme

```
versionCode = upstream versionCode × 100 + PATCHWORK_REVISION
versionName = "<upstream versionName>-patchwork"
```

`PATCHWORK_REVISION` defaults to the number of commits on `HEAD` touching
`apps/<app>` (max 99, overridable via the `PATCHWORK_REVISION` environment
variable). Counting the whole app directory — not only its patch queue — is
what lets a non-patch change such as `KEEP_ABIS` ship a new installable build:
every downstream commit bumps `versionCode` even when upstream did not change.

| App | Upstream versionCode / versionName | App-dir commits | Result |
| --- | --- | --- | --- |
| ScreenTime | `13` / `"1.4.1"` | 4 | `1304` / `"1.4.1-patchwork"` |
| RecurringExpenseTracker | `68` / `"0.21.4"` | 11 | `6811` / `"0.21.4-patchwork"` |
| Fcitx5 for Android | `112` (arm64-v8a) / `"0.1.3"` | 7 | `11207` / `"0.1.3-patchwork"` |
| BasicSync | `256048` / `"3.29"` | 2 | `25604802` / `"3.29-patchwork"` |
| Gadgetbridge | `257` / `"2026.11.1"` | 5 | `25700` / `"2026.11.1-patchwork"` |

For fcitx5 the upstream code is itself ABI-dependent
(`baseVersionCode × 10 + ABI id`, where arm64-v8a is 2), so the full formula is:

```
versionCode = (baseVersionCode × 10 + abiId) × 100 + PATCHWORK_REVISION
```

`app/build.gradle.kts` has no `versionCode` line in fcitx5-android: build-logic
computes it in `Versions.calculateVersionCode()`. Patch `0003` therefore reads
the optional `PATCHWORK_REVISION` environment variable there, and
`build-app.sh` exports it together with `BUILD_ABI` and `BUILD_VERSION_NAME`
(`VERSION_STYLE=build-logic`) instead of rewriting a file. Unset, the upstream
code is returned unchanged. Because the per-ABI order is preserved
(`111xx < 112xx < 113xx`), publishing `armeabi-v7a` later would not conflict
with the arm64 build.

**BasicSync** is different again: upstream computes versionCode from
`git describe` as `major << 16 | minor << 11 | commit-count << 3 | abi`, so a
downstream queue would eat into a 5-bit counter and, at 32 commits past the last
tag, wrap into the next release's number. `build-app.sh`'s
`VERSION_STYLE=pinned` branch therefore reads `v<major>.<minor>-<count>-g<sha>`
from the applied worktree, rebuilds the upstream part of the code, and pins the
result through the environment:

```
versionCode = (major << 16 | minor << 11 | count << 3) × 100 + PATCHWORK_REVISION
versionName = "<major>.<minor>-patchwork"
```

A single ABI is built per invocation (`BUILD_ABI=arm64-v8a`), so no ABI
digit is added and each published APK carries the architecture it was built for.
The patch also narrows the ABI splits, which keeps the embedded Go/Syncthing
runtime to one architecture.

**BaiduNetGo (Android)** follows the same formula, with the queue's patch count
as the revision (there is no `apps/<app>/` directory to count commits in):

```
versionCode = 803 × 100 + patch count        (48 patches -> 80348)
versionName = "<upstream versionName>-patchwork"   (0.8.3 -> "0.8.3-patchwork")
```

`scripts/build-baidunetgo-android.sh` rewrites `android/app/build.gradle.kts`
in the prepared tree before the Gradle build, the same file-rewrite approach
`build-app.sh` uses, and rejects patch counts above 99 like every other app
here. The desktop build's own scheme (`0.8.3` + `.<patch count>` = `0.8.3.48`)
is unchanged.

## Repository layout

```
.
├── README.md                  # English (this file)
├── README.zh-CN.md            # Simplified Chinese
├── LICENSE                    # GPL-3.0-only
├── apps/
│   ├── expenses/{app.conf,patches/}     # 0001..0018, KEEP_ABIS=ARM
│   ├── screentime/{app.conf,patches/}   # 0001..0011, KEEP_ABIS=ARM
│   ├── fcitx5/{app.conf,patches/}       # 0001..0017
│   └── basicsync/{app.conf,patches/}    # 0001..0004, VERSION_STYLE=pinned
├── desktop/
│   ├── baidunetgo/{app.conf,patches/}   # 0001..0041, desktop + Android client
│   └── .build-state.json                # last upstream commit built
├── .watch-state.json          # last android/baidunetgo 10-day check
├── scripts/
│   ├── baidunetgo-feed.py       # write the update feed (update.json)
│   ├── baidunetgo-android-feed.py # write the Android update feed
│   ├── build-app.sh             # fetch, patch, version, build, verify
│   ├── build-baidunetgo.sh      # fetch, rebase queue, x86 desktop build
│   ├── build-baidunetgo-android.sh # build + sign the queue's Android client
│   ├── build-baidunetgo-arch.sh # makepkg in a container -> Arch package
│   ├── check-upstream.sh        # upstream SHA diff -> CI matrix
│   ├── publish-fdroid.sh        # APKs + signed index
│   ├── test-baidunetgo-feed.sh  # its regression test
│   ├── test-baidunetgo-android-feed.sh # the Android feed's regression test
│   ├── test-publish-fdroid.sh # publisher regression test
│   └── sync-patches.sh        # copy queues from authoring clones
├── fdroid/
│   ├── config.yml
│   ├── metadata/*.yml
│   ├── repo/                  # APKs + index, committed by CI
│   └── .build-state.json
├── signing/patchwork-test.jks # deliberately public test key
├── docs/design/               # calendar spec, Expenses architecture
└── .github/workflows/{build,baidunetgo,deploy-pages}.yml
```

## Build locally

Prerequisites for the Android apps: bash, Git, JDK 21, Android SDK 37 (preview
platform and build-tools); expenses/screentime also need `zip` to strip the
non-ARM ABIs. They need no NDK.

The desktop app needs Go (the version in the upstream `go.mod`), `zip` and, for
the Windows installers, NSIS (`makensis`). MyGo needs no cgo, so `linux/amd64`
and `windows/amd64` are both built on any machine:

BasicSync needs the most unusual toolchain of the Gradle-only apps: Android SDK
packages `build-tools;37.0.0`, `platforms;android-37.0` and
`ndk;30.0.16248370` (the NDK version `app/build.gradle.kts` pins), a Go
toolchain on `PATH`, and the two pinned submodules (`external/go` and
`external/syncthing`, ~200 MB) because the app builds Syncthing as a Go library
with `gomobile`. The first build compiles the Go toolchain and gomobile itself and
takes 8–12 minutes; later builds reuse them. `apps/basicsync/app.conf` sets
`SUBMODULES=recursive`, so `build-app.sh` fetches the submodules right before
Gradle, and `--no-build` still stops before that. `GOCACHE` must point inside the
repository (or any writable directory) when `$HOME` is not writable.

Fcitx5 for Android needs more: Android SDK packages `platforms;android-36`,
`build-tools;36.1.0`, `cmake;3.31.6`, `ndk;28.0.13004108`, the host packages
`extra-cmake-modules` and `gettext` (`msgfmt`), 19 git submodules (~450 MB, one
of them the 364 MB shallow `prebuilt`), and roughly 13 GB of free disk for the
SDK, Gradle cache, checkout and build outputs. Expect 15–30 minutes on a 4-vCPU
machine (about 3 minutes on a warm 16-core cache). `build-app.sh` runs
`git submodule update --init --recursive` just before Gradle when
`SUBMODULES=recursive`, which `apps/fcitx5/app.conf` sets; `--no-build` stops
before that and does not download them.

When unset, `ANDROID_HOME`, `ANDROID_USER_HOME` and `GRADLE_USER_HOME` default
to `<repo>/.toolchain/`:

```bash
export ANDROID_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-sdk
export ANDROID_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-home
export GRADLE_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/gradle-home

./scripts/build-app.sh expenses --no-build    # fetch + patch + rewrite version
./scripts/build-app.sh expenses              # full build into dist/
./scripts/build-app.sh screentime --out dist
./scripts/build-app.sh fcitx5 --no-build     # fetch + patches, no submodules (11203)
./scripts/build-app.sh fcitx5 --out .toolchain/tmp/fcitx5-dist
./scripts/check-upstream.sh                  # JSON status; --all/--apps select apps
./scripts/publish-fdroid.sh --apk-dir dist   # rebuild fdroid/repo + index
./scripts/sync-patches.sh --check            # compare queues with the clones
./scripts/test-publish-fdroid.sh dist/RecurringExpenseTracker-0.21.4-patchwork.apk

# Desktop app (x86 only): --prepare fetches and rebases the queue, --build then
# compiles. --out defaults to dist/baidunetgo.
./scripts/build-baidunetgo.sh --prepare
./scripts/build-baidunetgo.sh --build
PATCHWORK_PLATFORMS=linux/amd64 ./scripts/build-baidunetgo.sh   # skip NSIS locally

# The queue's Android client: --prepare once, then the Gradle build + sign
# (JDK 21 and ANDROID_HOME as above). Defaults to signing with the committed
# patchwork test keystore.
./scripts/build-baidunetgo-android.sh --out dist/baidunetgo-android
./scripts/test-baidunetgo-android-feed.sh
```

`dist/build-info.txt` records `upstream_commit`, `patch_count`, `version_code`,
`version_name`, `variant`, `built_at`, `apk`, `apk_size` and `sha256`, plus
`abi` for per-ABI builds such as fcitx5 and `abis` for the ARM-only apps. The
desktop build writes the same kind of file under `dist/baidunetgo/` (or
`--out`): `upstream_head`, `upstream_commit`, `upstream_fallback`,
`patch_count`, `version`, `platforms`, `built_at` and one `file=` line per
installer with its size and SHA-256. Build caches (`.cache/`) and outputs
(`dist/`) are gitignored.

If the GitHub HTTPS clone stalls (a broken local proxy, for example), rewrite
only that run to SSH instead of changing the scripts:

```bash
GIT_CONFIG_COUNT=1 \
GIT_CONFIG_KEY_0='url.ssh://git@github.com/.insteadOf' \
GIT_CONFIG_VALUE_0='https://github.com/' \
GIT_SSH_COMMAND='ssh -F /dev/null -o BatchMode=yes' \
./scripts/build-app.sh fcitx5
```

## Updating the patches

Patches are authored in five separate app clones; this repository only consumes
the exported files. Expenses and ScreenTime keep their queue in
`.patchwork/patches/` (or on a `patchwork` branch); fcitx5-android keeps it on
the `feat/rime-sync-manager` branch, exported with
`git format-patch master..feat/rime-sync-manager`; BaiduNetGo keeps its queue
folded into `main` (upstream plus the community commits), exported relative to
`origin/main`.

```bash
# 1. Develop on a feature branch in the app clone
cd apps/ScreenTime
git switch -c feat/my-change main
# ... edit, build, commit ...

# 2. Export the queue in the same format as the existing patches
rm -f .patchwork/patches/*.patch
git format-patch main..feat/my-change \
  -o .patchwork/patches --no-signature --zero-commit --no-numbered

# 3. Sync into this repository, commit and push; CI rebuilds
cd ../..
./scripts/sync-patches.sh
git add apps/screentime/patches
git commit -m "Update ScreenTime patch queue"
git push origin main
```

BaiduNetGo's queue is exported from its authoring clone's `main`, which is the
upstream branch plus the community commits (no `.patchwork/` directory):

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

The queue carries both halves of the app — the desktop client and, since the
Android client landed, its `android/` Gradle project — and one of the patches
adds a CRLF `android/gradlew.bat`. Plain `git am` strips every trailing CR
(mailinfo treats the patch as CRLF-formatted mail), so `build-baidunetgo.sh`
replays the queue with `git am -3 --keep-cr`: the replayed tree then matches
the authoring clone's `main` tree byte for byte.

`sync-patches.sh` only knows the three Android clones; the desktop queue is
exported by hand as above (the workflow rebuilds it within 10 days, or run
`baidunetgo.yml` manually with `force`).

`./scripts/sync-patches.sh --check` reports differences only (exit code 1 when
out of sync) and is suitable for CI or a local pre-commit check. For expenses
and screentime, if the clone is not on the `patchwork` branch, the script reads
the queue from that branch via `git archive` and never modifies the clone. For
fcitx5 it exports from `feat/typing-heatmap`, and if the clone is unavailable
it falls back to the staged queue in `.toolchain/tmp/fcitx5-patches`
(override with `FCITX5_PATCHES_DIR`; `FCITX5_BRANCH` and `FCITX5_BASE` select
the branch and base).

Patches must stay exportable from upstream `main` and still apply with
`git am -3`; CI checks out with `fetch-depth: 0` so the three-way merge has full
history.

## Signing / security notes

**`signing/patchwork-test.jks` is a test keystore committed on purpose**, with
the public alias `patchwork` and password `patchwork123`.

- CI can build signed APKs with zero secrets.
- Anyone who can read this repository can also sign updates with the same key.
- It is not a security boundary; use it for personal builds only.

To replace it, create a new keystore, store it base64-encoded in the
`PATCHWORK_KEYSTORE_BASE64` secret, and set `PATCHWORK_STORE_PASSWORD`,
`PATCHWORK_KEY_ALIAS` and `PATCHWORK_KEY_PASSWORD`. Also update
`fdroid/config.yml` so the index is signed with the new key. A new key makes
F-Droid treat this as a different repository, and installed apps must be
uninstalled before they can be updated.

## Docs

- [`docs/design/tilla-calendar.md`](docs/design/tilla-calendar.md) — the
  reverse-engineered Tilla 2.8 calendar spec (layout, sizes, day-cell state
  priority, gesture thresholds, data model) behind patches `0011`–`0015`.
- [`docs/design/expenses-architecture.md`](docs/design/expenses-architecture.md)
  — architecture map of RecurringExpenseTracker (`feat/tilla-ui`): modules, Room
  layer, recurrence semantics, UI insertion points, date projection.

Both are snapshots from the time of writing; treat them as design rationale,
not as line-by-line truth.

## License

Copyright (C) 2026 PILIHU2022 (Spark).

This repository — build scripts, CI, documentation and the patch queues — is
licensed under **GPL-3.0-only**. See [`LICENSE`](LICENSE) for the full text.

**Why GPL-3.0-only.** The patches are derivative works of free-software
upstreams: ScreenTime is GPL-3.0-or-later, RecurringExpenseTracker is
GPL-3.0-only and Fcitx5 for Android is LGPL-2.1-only (whose section 3 allows
relicensing under GPL v2 or later). GPL-3.0-only is compatible with all three,
and it avoids a mixed “only / or-later” grant across the repository.

The published APKs are built from those upstream projects and are distributed
under the upstream terms: GPL-3.0 for expenses and screentime, **LGPL-2.1-only**
for Fcitx5 for Android. Their corresponding source is the upstream repositories,
at the revisions listed above, plus the patch queue in this repository. The
per-app F-Droid metadata keeps each upstream project's own license declaration
(`License: LGPL-2.1-only` for fcitx5, `GPL-3.0-only` /
`GPL-3.0-or-later` for the other two).

`signing/patchwork-test.jks` and its passwords are a **public test signing
key**, not a secret, and carry no trust expectation: anyone can sign APKs with
it. Rotate it for anything beyond personal use. The rest of the repository —
scripts, CI, docs, patches — is GPL-3.0-only.

This is a personal fork/patch collection, not affiliated with or endorsed by the
upstream authors (Dennis Bauer / Atharok).
