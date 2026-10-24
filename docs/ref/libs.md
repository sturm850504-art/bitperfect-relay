# Open-source library management (read when working on the settings screen / library swap)

## Current state (2026-10-09)
- Local-only; not pushed to GitHub. Not on the market; goal is community sharing.
- Swap feature is **kept**. GitHub integration (download · version list · CI) is **inactive because no repo is provided**.
- Currently loads only from local paths (`files/libs/<name>/<version>/<abi>/` .so). Unprovided versions show "unprovided".
- When the GitHub repo is provided later (if community demand is high), download/version list is enabled.

## Basics
- List: names, current versions, licenses of all open-source libraries in use
- Runtime swap target: the C-ABI native library libusb. (libsoxr and Oboe were removed with the Bluetooth output, 2026-10-11.) The rest (Compose, etc.) are list/version display only.
- Swap feature:
  - If a newer version exists, show an upgrade icon → tap to download and auto-apply
  - Version-select icon → tag list of the original GitHub repo → tap a version to install and apply

## Structure
- The engine (`libengine.so`) `dlopen`s libusb as a .so and calls it through a function-pointer table (`dl/dllib.h`, 24 symbols); it does **not** link it. Default versions ship in the APK jniLibs.
- Downloaded .so files are stored in app internal storage `files/libs/<name>/<version>/<abi>/`.
- Version list: query the original repo tags via GitHub API (OkHttp; cached for unauthenticated limits).
- Download source: a separate repo (e.g. `<user>/bitperfect-libs`) GitHub Releases. `manifest.json` records library · version · ABI · URL · SHA-256. Versions not in the manifest show "unprovided".
- Apply: SHA-256 verify → stop relay → unload engine → load new version → on failure auto-restore previous version → resume relay.

## CI
- GitHub Actions workflow (`ci/` or for the separate repo): build the specified tag with NDK, upload arm64-v8a .so to Releases, update manifest.json.