# desktop/ — non-Android builds

This tree holds apps that are **not** Android apps and therefore never appear in
the F-Droid repository. `apps/` stays Android-only; `build.yml` and
`check-upstream.sh` glob `apps/*/app.conf` and would otherwise try to run a
Gradle build on a desktop program. Bitwarden is here too because the same
upstream tree produces both its browser extensions and its Electron clients.

## BaiduNetGo

| | |
| --- | --- |
| Upstream | [pengstem/baidunetgo](https://github.com/pengstem/baidunetgo), `main` |
| Queue | `baidunetgo/patches/*.patch`, exported from the authoring clone's `main` |
| Platforms | `linux/amd64`, `windows/amd64` — x86 only |
| Builder | `scripts/build-baidunetgo.sh` (plus `scripts/build-baidunetgo-arch.sh` for the Arch package) |
| Workflow | `.github/workflows/baidunetgo.yml`, one window every 10 days |

BaiduNetGo is a pure-Go/MyGo desktop client; it has no APK, no package name and
no F-Droid metadata. Its installers (Linux `.deb` + `.tar.gz`, Arch Linux
`.pkg.tar.zst`, Windows setup `.exe` + portable `.zip`) are uploaded as the
workflow artifact `baidunetgo-x86-<sha>` and published to a GitHub Release — see
[Update check / releases](#update-check--releases).

The workflow compares the upstream `main` SHA with `.build-state.json`, replays
the queue onto the new head with `git am -3` (a rebase of the exported commits),
and builds both x86 targets. If the queue no longer applies, the script retries
once on the last successfully built commit recorded in `.build-state.json`; that
fallback build is uploaded but the recorded head is kept, so the next 10-day
window retries the real upstream change.

## Update check / releases

Every successful build does two things on top of the workflow artifact:

- it publishes the installers to a GitHub Release tagged
  `baidunetgo-desktop-<version>-<short_sha>`, where `<short_sha>` is the first
  seven characters of the built upstream commit. The assets are the same x86
  files as the artifact (Linux `.deb` + `.tar.gz`, Arch Linux
  `baidunetgo-<version>-1-x86_64.pkg.tar.zst`, Windows setup `.exe` + portable
  `.zip`; `build-info.txt` stays artifact-only). The Arch package is built in an
  `archlinux:base-devel` container by `scripts/build-baidunetgo-arch.sh`;
  `sudo pacman -U <file>` installs it into `/opt/baidunetgo` with a
  `/usr/bin/baidunetgo` link. The tag is explicit and the release is never
  marked `latest`, so `latest` can point at something else later; the feed
  carries the tagged URLs itself;
- it regenerates and commits `baidunetgo/update.json`, the update feed that the
  app reads from **Settings → Updates**, and prints the feed address and the
  release link in the run summary.

The feed is public at:

```
https://raw.githubusercontent.com/PILIHU2022/Software-Repo/main/desktop/baidunetgo/update.json
```

Raw GitHub content is used because Pages only publishes `fdroid/`, which cannot
host this file. `app.conf`'s `UPDATE_FEED_URL` holds that address, the workflow
passes it to the build as `PATCHWORK_FEED_URL`, and `build-baidunetgo.sh` bakes
it into the binary with `MYGO_LDFLAGS -X …/internal/updates.defaultFeed=<url>`
(patch `0019` adds `internal/updates` and the settings page). A build from CI
therefore needs no configuration on the user's side: it checks the feed out of
the box, and when the feed lists a newer version for the running platform it
offers the matching installer for download.

Only x86 is built, so the feed only carries `linux-amd64` and `windows-amd64`.
Any other platform reading the feed (an ARM or macOS build of the same app) ends
up on the "更新信息没有 … 的下载地址" path, which reports that the feed has no
download URL for this platform.

The community build carries a version of its own: `build-baidunetgo.sh` rewrites
`mygo.json`'s version to `<upstream version>.<patch count>` before building, so
0.8.3 with 21 patches is `0.8.3.21`. The app compares versions segment by
segment, which makes a queue change an update (`0.8.3.21 < 0.8.3.22`), lets an
upstream bump win (`0.8.3.22 < 0.8.4.21`), and keeps a rebuild of the same tree
from prompting again — same version, nothing newer. The version lands in the
binary, the package names, the release tag and the feed, and Settings → About
shows it as the running build.

To publish a queue change before upstream moves (a broken installer, a fix that
should be downloadable now), dispatch the workflow with `force`; that builds the
new patch count's version, and since it is higher the installed apps are offered
the update on their next check.

The count only goes down when patches are dropped (upstream merged a fix, or a
patch was retired). If that ever lands below a version already published, set a
higher version by hand in `mygo.json` for that build: the script keeps whatever
the tree names as the upstream part and only appends the count.

The workflow artifact (`baidunetgo-x86-<sha>`, kept 90 days) is still uploaded
as the quickest way to grab a build without a Release round trip.

### Updating in place

On Linux the download can be installed without leaving the app: after the
update check has downloaded the new `.tar.gz`, **install and restart** replaces
the real binary behind `os.Executable()` (symlinks resolved) with the unpacked
one and starts the app again, keeping the previous binary as `<exe>.old`.
Windows and macOS still only download their installer and leave installing it
to the user.

A copy installed by pacman — the Arch package puts the app in
`/opt/baidunetgo`, owned by root — refuses the in-place update and says to run
`sudo pacman -U` on the new `.pkg.tar.zst` instead. A copy installed by the
archive's `install.sh` under the user's home updates itself.

## Refreshing the queue

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

`scripts/sync-patches.sh` only knows the three Android clones; this queue is
exported by hand. Pushing the queue does not trigger the desktop build (its
workflow only runs on its schedule or manual dispatch) — run `BaiduNetGo desktop
build` manually with `force` if you do not want to wait for the window.

## Bitwarden

| | |
| --- | --- |
| Upstream | [bitwarden/clients](https://github.com/bitwarden/clients), `main` |
| Queue | `bitwarden/patches/*.patch`, replayed with `git am -3` |
| Browser targets | Chrome, Firefox, Edge |
| Desktop targets | Linux packages, Windows (installer/zip), macOS (dmg/zip) |
| Builder | `scripts/build-bitwarden.sh` |
| Workflow | `.github/workflows/bitwarden.yml`, one window every 10 days |

The queue adds an offline security check to both the browser popup and the
desktop window. It flags weak, reused and breached passwords, lists logins
without a saved TOTP, and uses HIBP's SHA-1 k-anonymity range endpoint (only the
first five hash characters leave the device). There is no NodeWarden server
dependency and no password is persisted.

The Linux desktop build starts Electron with `ozone-platform-hint=auto` and
enables native Wayland decorations when `WAYLAND_DISPLAY` is present, while
falling back to X11 when needed. CI launches the packaged app under headless
Weston and fails if the process exits during the smoke test.

The workflow builds browser packages on Ubuntu and each desktop installer on
its native runner. Artifacts are attached to the run as
`bitwarden-browser-<sha>` and `bitwarden-<platform>-<sha>`; they are not
published to F-Droid. See [`bitwarden/README.md`](bitwarden/README.md) for the
security model, Wayland escape hatch and queue maintenance.
