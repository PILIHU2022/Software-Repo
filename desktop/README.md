# desktop/ — non-Android builds

This tree holds apps that are **not** Android apps and therefore never appear in
the F-Droid repository. `apps/` stays Android-only; `build.yml` and
`check-upstream.sh` glob `apps/*/app.conf` and would otherwise try to run a
Gradle build on a desktop program.

## BaiduNetGo

| | |
| --- | --- |
| Upstream | [pengstem/baidunetgo](https://github.com/pengstem/baidunetgo), `main` |
| Queue | `baidunetgo/patches/*.patch`, exported from the authoring clone's `main` |
| Platforms | `linux/amd64`, `windows/amd64` — x86 only |
| Builder | `scripts/build-baidunetgo.sh` |
| Workflow | `.github/workflows/baidunetgo.yml`, one window every 10 days |

BaiduNetGo is a pure-Go/MyGo desktop client; it has no APK, no package name and
no F-Droid metadata. Its installers are uploaded as the workflow artifact
`baidunetgo-x86-<sha>` (Linux `.deb` + `.tar.gz`, Windows setup `.exe` + portable
`.zip`), together with `build-info.txt`.

The workflow compares the upstream `main` SHA with `.build-state.json`, replays
the queue onto the new head with `git am -3` (a rebase of the exported commits),
and builds both x86 targets. If the queue no longer applies, the script retries
once on the last successfully built commit recorded in `.build-state.json`; that
fallback build is uploaded but the recorded head is kept, so the next 10-day
window retries the real upstream change.

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