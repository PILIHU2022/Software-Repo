# Patchwork F-Droid repository

Personal builds of open-source Android apps, automatically rebuilt from upstream
plus a small patch queue by GitHub Actions.

* Repository URL (F-Droid → Settings → Repositories → Add): <https://software.sparkzh.top/repo>
* Source, patches and build pipeline: <https://github.com/PILIHU2022/Software-Repo>
* Index signing certificate SHA-256:
  `D3:34:88:79:40:65:C9:2E:37:0D:24:A1:0E:61:4E:1E:64:E3:19:B7:5E:6F:8B:B7:C4:AA:D6:7F:3A:5B:5E:01`

The APKs use the same package names as the official releases but a different signing
key, so uninstall the official app before installing these builds.

## Icons

Each published package has a 512x512 source icon at
`fdroid/icons-src/<package>.png`. `scripts/publish-fdroid.sh` copies it to
`repo/<package>/en-US/icon.png` (index-v2) and
`repo/icons/<package>.<versionCode>.png` (legacy index-v1) before running
`fdroid update`, then adds the `icon` field to index-v1 and re-signs both
`index-v1.jar` and `entry.jar`. Add an icon source when adding an app: without
one the pipeline warns, and `scripts/fdroid-icons.py --check` fails.
