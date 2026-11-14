**English** | [简体中文](README.zh-CN.md)

[![Build](https://github.com/PILIHU2022/Software-Repo/actions/workflows/build.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/build.yml)
[![Deploy Pages](https://github.com/PILIHU2022/Software-Repo/actions/workflows/deploy-pages.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/deploy-pages.yml)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)

# Software-Repo — Patchwork build overlay

A personal, patch-based build overlay for three Android apps — **ScreenTime**,
**RecurringExpenseTracker** and **Fcitx5 for Android** — plus **BaiduNetGo**, a
desktop Netdisk client that is built here but is *not* an Android/F-Droid app.
No application source is vendored here. The repository keeps ordered patch
queues, plus the CI that applies them to current upstream code: signed APKs are
published through a signed F-Droid repository, and the desktop app's x86
installers are published as workflow artifacts.

> Personal project, not affiliated with or endorsed by the upstream authors
> (Dennis Bauer, Atharok, the Fcitx5 for Android contributors, pengstem).

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
   desktop app for x86 only and uploads the installers as artifacts.

Re-applying the queue to the latest upstream commit on every build lets upstream
fixes and features flow in without maintaining a diverged fork. A patch that no
longer applies fails the pipeline and opens an issue instead of publishing stale
code.

## Apps

| App | Upstream (branch) | Upstream revision | Patches | Built version |
| --- | --- | --- | --- | --- |
| RecurringExpenseTracker | [DennisBauer/RecurringExpenseTracker](https://github.com/DennisBauer/RecurringExpenseTracker) (`main`) | `effbf0d` | 19 | `0.21.4-patchwork` (6811) |
| ScreenTime | [Atharok/ScreenTime](https://gitlab.com/Atharok/ScreenTime) (`main`) | `9ac2520` | 11 | `1.4.1-patchwork` (1304, arm64-v8a) |
| Fcitx5 for Android | [fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) (`master`) | `eb5b5021` | 4 | `0.1.3-patchwork` (11202, arm64-v8a) |
| BaiduNetGo (desktop) | [pengstem/baidunetgo](https://github.com/pengstem/baidunetgo) (`main`) | `60ca12c` | 19 | `0.8.3` (linux/windows x86) |

The three Android apps are published in F-Droid. **BaiduNetGo is not**: it is a
Go/MyGo desktop program, built on its own 10-day schedule and uploaded as a
workflow artifact (`baidunetgo-x86-<sha>`), never as an APK.

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
Android, built from upstream `master` plus four patches: an
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
are unaffected. Only `arm64-v8a` is published (upstream produces per-ABI APKs
and no universal APK), and the APK is
about 52 MB.

**BaiduNetGo (desktop, not F-Droid)** — the community-fixed build of pengstem's
pure-Go Baidu Netdisk client: a duplicate finder, an in-app file clipboard with
undo, Finder-style marquee selection and drag-to-move, window/font adaptation,
and a pluggable update-check feed, on top of MyGo's native GPU UI. Built from
`desktop/baidunetgo/patches` for **x86 only** (`linux/amd64`, `windows/amd64`)
by `scripts/build-baidunetgo.sh`, which reapplies the queue with `git am -3`
(the exported-commit form of rebasing) to every new upstream head. Its installers
are workflow artifacts, never F-Droid packages.

Both Android apps with prebuilt native dependencies (RecurringExpenseTracker and
ScreenTime) are compiled **ARM only**: `KEEP_ABIS="arm64-v8a armeabi-v7a"`
in their `app.conf` makes `build-app.sh` strip `lib/x86/` and `lib/x86_64/` after
Gradle, then realign to 16 KB pages and re-sign the APK (v2/v3). Fcitx5's
`arm64-v8a` build is unchanged.

## F-Droid repository

Repository (custom domain, served and TLS-terminated in front of GitHub Pages;
verified):

```
https://software.sparkzh.top/repo
```

The index returns HTTP 200, and `index-v2.json` lists
`de.dbauer.expensetracker` **6806**, `com.atharok.screentime` **1303** and
`org.fcitx.fcitx5.android` **11202**.
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

`build.yml` checks the upstream repositories once every 10 days and builds only
the app(s) whose upstream SHA differs from `fdroid/.build-state.json`. GitHub
crons cannot express "every 10 days" (a `*/10` day-of-month rule would fire on
the 1st, 11th, 21st and 31st), so the workflow runs daily (`17 4 * * *`) and the
check job exits early while `.watch-state.json`'s `android` timestamp is younger
than 10 days. It also runs on pushes to `main` touching `apps/**`, `scripts/**`
or the workflow itself, and on manual dispatch (optional `app` / `force`
inputs). A push that changes a patch queue or an `app.conf` triggers a build
immediately and that commit bumps `versionCode` as described below.

The old 15-minute poller (`upstream-watch.yml`), its hourly watchdog and their
one-hour polling windows were removed with the 10-day cadence: a daily gated run
is simpler, a delayed or missed day only shifts the window by a day, and the
gate only advances after a scheduled run that finished, so a hard failure is
retried the next day instead of waiting another 10. When the queue no longer
applies to the head, the build falls back to the last successful upstream commit
and the next window retries the new head. The Windows/Linux x86 desktop build is
the separate `baidunetgo.yml` (see below).

Builds are per app: a push rebuilds only the apps whose `apps/<app>/**` paths
changed. A shared `scripts/**` or workflow change alone rebuilds nothing
(dispatch with `force` when a shared change must be rolled out) and a force push
whose before-SHA is unknown rebuilds nothing, so a docs/CI push cannot start the
expensive fcitx5 leg by accident. Scheduled runs rebuild only the apps whose
upstream SHA changed. Manual dispatch can target one app with the `app` input,
or rebuild everything with `app=all` + `force`. The fcitx5 leg additionally
frees ~10 GB of runner disk, installs `extra-cmake-modules`/`gettext` plus the
NDK 28.0.13004108, CMake 3.31.6 and build-tools 36.1.0 SDK packages, and has a
120-minute timeout. Each run:

1. **check** — `scripts/check-upstream.sh` compares the upstream branch SHAs
   (`main` for expenses/screentime, `master` for fcitx5) with
   `fdroid/.build-state.json` and emits the build matrix, after the 10-day gate;
2. **build** — one matrix leg per changed app (`fail-fast: false`): mirror clone
   in `.cache/mirror/<app>.git`, fresh checkout, optional recursive submodule
   update (fcitx5), `git am -3` over the queue, version handling, Gradle release
   build, keystore signing, an ARM ABI strip plus 16 KB realign and re-sign when
   `KEEP_ABIS` is set, then `apksigner` / `aapt` verification;
3. **publish** — `publish-fdroid.sh` keeps at most 3 APKs per package, dedupes by
   `(package, versionCode)`, signs the index with fdroidserver (`fdroid update`),
   commits `fdroid/repo` and `.build-state.json` with `[skip ci]`, and deploys
   Pages. Failed legs keep their old state so the next run retries them, and
   `record-check` advances the 10-day window only when the scheduled run
   finished (a fallback build counts: its app keeps the old recorded head, so
   the next window retries the new upstream);
4. **failure** — opens or comments on a **“Patchwork build failed”** issue with
   the run link and a local reproduction command.

`baidunetgo.yml` is the desktop counterpart: it runs daily (`43 4 * * *`), gates
on `.watch-state.json`'s `baidunetgo` timestamp, resolves `pengstem/baidunetgo`
`main` with `git ls-remote`, and when the SHA differs from
`desktop/.build-state.json` replays `desktop/baidunetgo/patches` onto it
(`git am -3`, with one retry on the last built commit when the queue no longer
applies), builds `linux/amd64,windows/amd64` with `go tool mygo build`, and
uploads the installers as the `baidunetgo-x86-<sha>` artifact. A fallback build
keeps the recorded head, so the next 10-day window retries the new upstream.
Nothing in it touches `fdroid/`.

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
| RecurringExpenseTracker | `68` / `"0.21.4"` | 9 | `6809` / `"0.21.4-patchwork"` |
| Fcitx5 for Android | `112` (arm64-v8a) / `"0.1.3"` | 2 | `11202` / `"0.1.3-patchwork"` |

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

## Repository layout

```
.
├── README.md                  # English (this file)
├── README.zh-CN.md            # Simplified Chinese
├── LICENSE                    # GPL-3.0-only
├── apps/
│   ├── expenses/{app.conf,patches/}     # 0001..0018, KEEP_ABIS=ARM
│   ├── screentime/{app.conf,patches/}   # 0001..0011, KEEP_ABIS=ARM
│   └── fcitx5/{app.conf,patches/}       # 0001..0003
├── desktop/
│   ├── baidunetgo/{app.conf,patches/}   # 0001..0019, x86 desktop build
│   └── .build-state.json                # last upstream commit built
├── .watch-state.json          # last android/baidunetgo 10-day check
├── scripts/
│   ├── build-app.sh           # fetch, patch, version, build, verify
│   ├── build-baidunetgo.sh    # fetch, rebase queue, x86 desktop build
│   ├── check-upstream.sh      # upstream SHA diff -> CI matrix
│   ├── publish-fdroid.sh      # APKs + signed index
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
to `../.toolchain/`:

```bash
export ANDROID_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-sdk
export ANDROID_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-home
export GRADLE_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/gradle-home

./scripts/build-app.sh expenses --no-build    # fetch + patch + rewrite version
./scripts/build-app.sh expenses              # full build into dist/
./scripts/build-app.sh screentime --out dist
./scripts/build-app.sh fcitx5 --no-build     # fetch + patches, no submodules (11202)
./scripts/build-app.sh fcitx5 --out ../.toolchain/tmp/fcitx5-dist
./scripts/check-upstream.sh                  # JSON status; --all/--apps select apps
./scripts/publish-fdroid.sh --apk-dir dist   # rebuild fdroid/repo + index
./scripts/sync-patches.sh --check            # compare queues with the clones
./scripts/test-publish-fdroid.sh dist/RecurringExpenseTracker-0.21.4-patchwork.apk

# Desktop app (x86 only): --prepare fetches and rebases the queue, --build then
# compiles. --out defaults to dist/baidunetgo.
./scripts/build-baidunetgo.sh --prepare
./scripts/build-baidunetgo.sh --build
PATCHWORK_PLATFORMS=linux/amd64 ./scripts/build-baidunetgo.sh   # skip NSIS locally
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

Patches are authored in four separate app clones; this repository only consumes
the exported files. Expenses and ScreenTime keep their queue in
`.patchwork/patches/` (or on a `patchwork` branch); fcitx5-android keeps it on
the `feat/rime-sync-manager` branch, exported with
`git format-patch master..feat/rime-sync-manager`; BaiduNetGo keeps its queue
folded into `main` (upstream plus the community commits), exported relative to
`origin/main`.

```bash
# 1. Develop on a feature branch in the app clone
cd ../ScreenTime
git switch -c feat/my-change main
# ... edit, build, commit ...

# 2. Export the queue in the same format as the existing patches
rm -f .patchwork/patches/*.patch
git format-patch main..feat/my-change \
  -o .patchwork/patches --no-signature --zero-commit --no-numbered

# 3. Sync into this repository, commit and push; CI rebuilds
cd ../Software-Repo
./scripts/sync-patches.sh
git add apps/screentime/patches
git commit -m "Update ScreenTime patch queue"
git push origin main
```

BaiduNetGo's queue is exported from its authoring clone's `main`, which is the
upstream branch plus the community commits (no `.patchwork/` directory):

```bash
cd ../baidunetgo
git fetch origin
rm -f ../Software-Repo/desktop/baidunetgo/patches/*.patch
git format-patch --no-signature --zero-commit --no-numbered \
  origin/main..main -o ../Software-Repo/desktop/baidunetgo/patches

cd ../Software-Repo
git add desktop/baidunetgo/patches
git commit -m "Update the BaiduNetGo patch queue"
git push origin main
```

`sync-patches.sh` only knows the three Android clones; the desktop queue is
exported by hand as above (the workflow rebuilds it within 10 days, or run
`baidunetgo.yml` manually with `force`).

`./scripts/sync-patches.sh --check` reports differences only (exit code 1 when
out of sync) and is suitable for CI or a local pre-commit check. For expenses
and screentime, if the clone is not on the `patchwork` branch, the script reads
the queue from that branch via `git archive` and never modifies the clone. For
fcitx5 it exports from `feat/rime-sync-manager`, and if the clone is unavailable
it falls back to the staged queue in `../.toolchain/tmp/fcitx5-patches`
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
