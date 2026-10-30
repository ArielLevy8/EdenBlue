<!--
SPDX-FileCopyrightText: Copyright 2026 EdenBlue Project
SPDX-License-Identifier: GPL-3.0-or-later

SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
SPDX-License-Identifier: GPL-3.0-or-later
-->

<h1 align="center">
  <br>
  <img src="./src/android/app/src/edenBlue/res/drawable/ic_yuzu.png" alt="EdenBlue" width="160">
  <br>
  <b>EdenBlue</b>
  <br>
</h1>

<h4 align="center">A fork of the <a href="https://git.eden-emu.dev/eden-emu/eden">Eden</a> Nintendo Switch emulator for Android, tuned for Snapdragon 865 devices with the Adreno 650 GPU.</h4>

EdenBlue tracks Eden `master` and adds a Switch-like startup flow, a Snapdragon 865
optimizer, an in-app mod browser and a handful of quality-of-life changes. It installs
next to regular Eden builds (package `dev.edenblue.emulator`) and keeps its own data.

## Target devices

EdenBlue is built from Eden's **legacy** code path, which upstream recommends for the
Snapdragon 865 / Adreno 650, plus the `EDENBLUE` flavor. Tested targets:

- Retroid Pocket 5, Retroid Pocket Flip 2, Retroid Pocket Mini V2
- Snapdragon 865 phones (OnePlus 8 / 8 Pro / 8T, Galaxy S20 Exynos excluded, Xiaomi Mi 10, Poco F2 Pro, ...)
- Android 10 or newer, arm64 only

Other devices run EdenBlue too, but the tuned profile assumes an Adreno 650.

## What EdenBlue adds

| Feature | Where |
|---|---|
| **Boot to home menu** (on by default). With firmware and keys installed, EdenBlue opens straight into the Switch home menu (QLaunch) so the device feels like a Switch. | Settings → EdenBlue |
| **Return to home menu** after a game started from the games list exits. | Settings → EdenBlue |
| **Snapdragon 865 optimizer**: one tap applies a tuned profile (handheld 1x, low GPU accuracy, async shaders, EDS2, GPU ASTC, 3 pipeline workers, ...) and can download and select the recommended Mr. Purple Turnip driver. Also offered during first-run setup. | Settings home, Settings → EdenBlue, first-run setup |
| **Mod browser**: downloads **Eden Duo companions** (`igawa6/eden-duo-companions`) and the **Ultrawide & FPS mods** collection (`Fl4sh9174/Switch-Emulator-Ultrawide-FPS-Mods`) from GitHub and installs them into the right game's `load/` folder, matched by title ID. | Settings home, game → Mod browser, game → Add-ons → Install |
| **Install mods from ZIP**: pick any mod ZIP (nested folders are handled) and it lands in the game's `load/` folder. | game → Add-ons → Install → Mods (ZIP file) |
| QLaunch button shown by default, blue theme by default, blue launcher and splash icons. | |

### About Eden Duo companions

Eden Duo companions are second-screen packages (`.dsmod.zip`) made for the
[Eden Duo](https://github.com/igawa6/eden-duo) fork on dual-screen handhelds. EdenBlue
downloads and installs them into the game's mod folder, but it does not include Eden Duo's
dual-screen runtime, so the panels only render on Eden Duo builds. Regular mods (exefs,
romfs, cheats) work as usual.

## Keys and firmware

EdenBlue **does not ship** `prod.keys`, `title.keys` or Switch firmware. They are Nintendo's
property and cannot be redistributed. Dump them from your own console and import them in
the first-run setup (or later under *Manage EdenBlue data*):

1. Install the APK and open EdenBlue.
2. On the *Emulator data* page, tap **Keys** and pick your `prod.keys` (or a ZIP that contains it).
3. Tap **Firmware** and pick your firmware ZIP.
4. Add your games folder, apply the Snapdragon 865 profile and finish setup.

The home menu needs both keys and firmware. Without them EdenBlue falls back to the games list.

## Download

Prebuilt APKs are published on the
[Releases](https://github.com/ArielLevy8/EdenBlue/releases) page and as workflow artifacts
of the **EdenBlue Android** GitHub Action. Builds are signed with the repository debug key,
so uninstall older builds signed with another key before installing.

## Building

Requirements: JDK 17, Android SDK 36, NDK 28.2.13676358, CMake 3.31.5, Git.

```sh
git clone https://github.com/ArielLevy8/EdenBlue.git
cd EdenBlue
.ci/android/build.sh -t edenblue -b Release
```

The APK is written to `artifacts/`. `./gradlew assembleEdenBlueRelease` from `src/android`
works too. The flavor passes `-DEDENBLUE=ON -DYUZU_LEGACY=ON` to CMake.

CI: `.github/workflows/edenblue-android.yml` builds every push and attaches the APK to a
GitHub release when a `v*` tag is pushed.

## Upstream

EdenBlue is based on Eden, which is derived from yuzu and Sudachi, licensed under the GPLv3
or any later version. See [LICENSE.txt](LICENSE.txt). Upstream README, build docs and
contribution policies live in [docs/](docs/). Issues with the core emulator belong upstream;
issues with EdenBlue-specific features belong here.
