**English** | [简体中文](README.zh-CN.md)

[![Build](https://github.com/PILIHU2022/Software-Repo/actions/workflows/build.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/build.yml)
[![Deploy Pages](https://github.com/PILIHU2022/Software-Repo/actions/workflows/deploy-pages.yml/badge.svg)](https://github.com/PILIHU2022/Software-Repo/actions/workflows/deploy-pages.yml)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)

# Software-Repo — Patchwork build overlay

A personal, patch-based build overlay for two Android apps: **ScreenTime** and
**RecurringExpenseTracker**. No application source is vendored here. The
repository keeps an ordered patch queue, plus the CI that applies it to current
upstream code and publishes signed APKs through a signed F-Droid repository.

> Personal project, not affiliated with or endorsed by the upstream authors
> (Dennis Bauer / Atharok).

## What this is

A *patchwork overlay* monorepo that stores two things and no app source:

1. **Patch queues** — `apps/<app>/patches/*.patch`, ordered changes that apply
   on top of upstream `main` with `git am -3`.
2. **Build and publish automation** — CI fetches the current upstream `main`,
   applies the queue, rewrites the version, builds signed APKs, and updates the
   signed F-Droid repository.

Re-applying the queue to the latest upstream commit on every build lets upstream
fixes and features flow in without maintaining a diverged fork. A patch that no
longer applies fails the pipeline and opens an issue instead of publishing stale
code.

## Apps

| App | Upstream | Upstream revision | Patches | Published |
| --- | --- | --- | --- | --- |
| RecurringExpenseTracker | [DennisBauer/RecurringExpenseTracker](https://github.com/DennisBauer/RecurringExpenseTracker) | `82ef9c9` | 17 | `0.21.4-patchwork` (6804) |
| ScreenTime | [Atharok/ScreenTime](https://gitlab.com/Atharok/ScreenTime) | `9ac2520` | 10 | `1.4.1-patchwork` (1301 published; 1302 local, pending) |

**ScreenTime** — daily usage history persisted in a local SQLite database, a new
analysis screen backed by that history (durations count up from zero while the
weekday bars and charts grow, with a dedicated **Analysis** settings category and
an in-app explanation of the local history), Compose `Canvas` charts instead of
Vico, cached rasterized app icons, and no main-thread work at startup.

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

## F-Droid repository

Repository (custom domain, served and TLS-terminated in front of GitHub Pages;
verified):

```
https://software.sparkzh.top/repo
```

The index returns HTTP 200, and `index-v2.json` lists
`de.dbauer.expensetracker` **6804** and `com.atharok.screentime` **1301**.
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
> deletes its data, so back it up.

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

`build.yml` runs every 6 hours (`23 */6 * * *`), on pushes to `main` touching
`apps/**`, `scripts/**` or the workflow itself, and on manual dispatch (optional
`app` / `force` inputs). A push that changes the patch queues under
`apps/*/patches/` is an ordinary `apps/**` push: it triggers a build immediately
(no need to wait for the schedule) and the patch-queue commit bumps
`versionCode` as described below.

Builds are per app: a push rebuilds only the apps whose `apps/<app>/**` paths
changed (a `scripts/**` or workflow change rebuilds both; an unknown/forced push
falls back to both). Scheduled runs rebuild only the apps whose upstream SHA
changed. Manual dispatch can target one app with the `app` input, or rebuild
everything with `app=all` + `force`. Each run:

1. **check** — `scripts/check-upstream.sh` compares upstream `main` SHAs with
   `fdroid/.build-state.json` and emits the build matrix;
2. **build** — one matrix leg per changed app (`fail-fast: false`): mirror clone
   in `.cache/mirror/<app>.git`, fresh checkout, `git am -3` over the queue,
   version rewrite, Gradle release build, keystore signing, then `apksigner` /
   `aapt` verification;
3. **publish** — `publish-fdroid.sh` keeps at most 3 APKs per package, dedupes by
   `(package, versionCode)`, signs the index with fdroidserver (`fdroid update`),
   commits `fdroid/repo` and `.build-state.json` with `[skip ci]`, and deploys
   Pages. Failed legs keep their old state so the next run retries them;
4. **failure** — opens or comments on a **“Patchwork build failed”** issue with
   the run link and a local reproduction command.

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
`apps/<app>/patches` (max 99, overridable via the `PATCHWORK_REVISION`
environment variable). This is what makes patch-only releases upgradeable in
F-Droid: a patch-queue commit bumps `versionCode` even when upstream did not
change.

| App | Upstream versionCode / versionName | Patch-queue commits | Result |
| --- | --- | --- | --- |
| ScreenTime | `13` / `"1.4.1"` | 2 | `1302` / `"1.4.1-patchwork"` |
| RecurringExpenseTracker | `68` / `"0.21.4"` | 4 | `6804` / `"0.21.4-patchwork"` |

## Repository layout

```
.
├── README.md                  # English (this file)
├── README.zh-CN.md            # Simplified Chinese
├── LICENSE                    # GPL-3.0-only
├── apps/
│   ├── expenses/{app.conf,patches/}     # 0001..0017
│   └── screentime/{app.conf,patches/}   # 0001..0008
├── scripts/
│   ├── build-app.sh           # fetch, patch, version, build, verify
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
└── .github/workflows/{build,deploy-pages}.yml
```

## Build locally

Prerequisites: bash, Git, JDK 21, Android SDK 37 (preview platform and
build-tools) and `fdroidserver` for publishing. No NDK is needed. When unset,
`ANDROID_HOME`, `ANDROID_USER_HOME` and `GRADLE_USER_HOME` default to
`../.toolchain/`:

```bash
export ANDROID_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-sdk
export ANDROID_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/android-home
export GRADLE_USER_HOME=/home/Spark/Projects/GitHub_APPS/.toolchain/gradle-home

./scripts/build-app.sh expenses --no-build    # fetch + patch + rewrite version
./scripts/build-app.sh expenses              # full build into dist/
./scripts/build-app.sh screentime --out dist
./scripts/check-upstream.sh                  # JSON status; --all/--apps select apps
./scripts/publish-fdroid.sh --apk-dir dist   # rebuild fdroid/repo + index
./scripts/sync-patches.sh --check            # compare queues with the clones
./scripts/test-publish-fdroid.sh dist/RecurringExpenseTracker-0.21.4-patchwork.apk
```

`dist/build-info.txt` records `upstream_commit`, `patch_count`, `version_code`,
`version_name`, `variant`, `built_at`, `apk`, `apk_size` and `sha256`. Build
caches (`.cache/`) and outputs (`dist/`) are gitignored.

## Updating the patches

Patches are authored in two separate app clones; this repository only consumes
the exported files.

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

`./scripts/sync-patches.sh --check` reports differences only (exit code 1 when
out of sync) and is suitable for CI or a local pre-commit check. If the clone is
not on the `patchwork` branch, the script reads the queue from that branch via
`git archive` and never modifies the clone.

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

**Why GPL-3.0-only.** The patches are derivative works of GPL-3.0 upstreams:
ScreenTime is GPL-3.0-or-later and RecurringExpenseTracker is GPL-3.0-only.
GPL-3.0-only is compatible with both (an “or later” work may be distributed
under v3), and it avoids a mixed “only / or-later” grant across the repository.

The published APKs are built from those upstream projects and are distributed
under the same GPL-3.0 terms. Their corresponding source is the upstream
repositories, at the revisions listed above, plus the patch queue in this
repository. The per-app F-Droid metadata keeps each upstream project's own
license declaration.

`signing/patchwork-test.jks` and its passwords are a **public test signing
key**, not a secret, and carry no trust expectation: anyone can sign APKs with
it. Rotate it for anything beyond personal use. The rest of the repository —
scripts, CI, docs, patches — is GPL-3.0-only.

This is a personal fork/patch collection, not affiliated with or endorsed by the
upstream authors (Dennis Bauer / Atharok).
