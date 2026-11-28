# Bitwarden clients

Bitwarden is built from [bitwarden/clients](https://github.com/bitwarden/clients)
plus the ordered queue in `patches/`. It is a browser-extension/Electron
project, so it lives under `desktop/` and never enters F-Droid.

| | |
| --- | --- |
| Upstream | [bitwarden/clients](https://github.com/bitwarden/clients), `main` |
| Queue | `patches/*.patch`, replayed with `git am -3` |
| Browser targets | Chrome, Firefox, Edge |
| Desktop targets | Linux packages, Windows (installer/zip), macOS (dmg/zip) |
| Builder | `scripts/build-bitwarden.sh` |
| Workflow | `.github/workflows/bitwarden.yml` |

## Security check

Patch `0001` ports the useful client-side part of
[NodeWarden](https://github.com/shuaiplus/NodeWarden)'s security dashboard into
the browser popup and the desktop window. Open **安全检测** from the floating
button, or navigate to `#/security-check` inside the app.

The check reads only the currently unlocked vault:

- weak passwords: short values, common passwords, repeated characters, common
  keyboard/name sequences, values containing the username, and low character
  diversity;
- reused passwords: logins sharing exactly the same password;
- breached passwords: Have I Been Pwned's range API with SHA-1 k-anonymity. The
  client sends only the first five hash characters and never sends the
  password or the full hash;
- missing TOTP: logins that have no TOTP secret in the vault. This is an
  advisory, not a claim that the website has no 2FA support.

The analysis runs in the renderer. There is no NodeWarden server, no new
network service, and no password is written to storage. If HIBP is unreachable,
the remaining checks still complete and the exposed-password tab shows a
warning.

The page deliberately does not copy NodeWarden's server-side account/login
policy. Bitwarden's own account 2FA state is not in the vault and is not
invented by this overlay.

## Wayland

Patch `0002` runs before Electron becomes ready and sets
`ozone-platform-hint=auto`. On a Wayland session with `WAYLAND_DISPLAY`, it also
enables `WaylandWindowDecorations`; on an X11-only desktop the same auto hint
falls back to X11. Users who need to force X11 can start the app with:

```bash
ELECTRON_OZONE_PLATFORM_HINT=x11 bitwarden
```

The Linux CI job installs Weston, starts the packaged app under a headless
Wayland compositor, and requires the process to stay alive. It also checks the
Electron log for the selected Ozone backend; a build that only compiles the
flag but never reaches the app is therefore not enough.

## Build locally

The commands below are intentionally not run by the overlay's documentation
stage. They assume Node 22, Rust, and the normal Electron Linux packaging
dependencies:

```bash
./scripts/build-bitwarden.sh --prepare
./scripts/build-bitwarden.sh --build --target browser --out dist/bitwarden
./scripts/build-bitwarden.sh --build --target desktop-linux --out dist/bitwarden
```

Use `desktop-windows` or `desktop-macos` on the matching host. The workflow runs
each desktop package on its native GitHub-hosted runner instead of cross
packaging.

The CI does not have Apple or Windows signing identities. The macOS artifact is
built on the arm64 runner and is unsigned, the Windows/Linux runners produce
x64 packages, and the Windows installer can raise SmartScreen. They are
workflow artifacts, not notarized store releases; macOS may require right-click
**Open** (or clearing the quarantine attribute) after downloading.

The artifact directory carries `build-info.txt` with the upstream head, the
possibly older fallback commit, the patch count and the upstream package
version. A fallback build is uploaded but is not recorded as the new upstream
state, so the next scheduled run retries the real head.

The browser zips are unsigned development builds. Chrome/Edge can load the
extracted directory through **Extensions → Developer mode → Load unpacked**;
Firefox can load the package through **about:debugging → This Firefox →
Load Temporary Add-on**.

## Maintaining the queue

The queue is exported from an authoring clone exactly like the other patchwork
apps:

```bash
git format-patch --no-signature --zero-commit origin/main..main \
  -o ../Software-Repo/desktop/bitwarden/patches
```

Keep the `NNNN-description.patch` ordering. When upstream moves an Angular
route file or the Electron entry point, re-anchor the imports/routes in the
queue rather than editing the generated checkout.
