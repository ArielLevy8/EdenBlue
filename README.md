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

<h4 align="center">A fork of the <a href="https://git.eden-emu.dev/eden-emu/eden">Eden</a> Nintendo Switch emulator for Android, with presets from the Snapdragon 865 to the newest Snapdragon chips.</h4>

EdenBlue is based on Eden `master` and adds device tuning, built-in frame generation, a game
import tool, a mod browser and a darker blue look. It installs next to regular Eden builds
(package `dev.edenblue.emulator`) and keeps its own data.

## Which download to use

| Your chip | Download |
|---|---|
| Snapdragon 865, 870, 888 | `EdenBlue-<version>-legacy.apk` |
| Snapdragon 7+ Gen 2, 8 Gen 1 and newer | `EdenBlue-<version>-modern.apk` |

The legacy build carries Eden's patches for older Adreno GPUs, which cost some performance on
newer chips. Both APKs need Android 10 or newer on arm64. None of these builds have been tested on
real hardware yet, so reports are welcome.

Windows and Mac builds are not released yet.

## What EdenBlue adds

| Feature | Where |
|---|---|
| **Boot to home menu** (off by default). Optionally open straight into the Switch home menu (QLaunch). | Settings → EdenBlue |
| **Return to home menu** after a game exits (off by default). | Settings → EdenBlue |
| **Device tuning**: pick your chip (the detected one is preselected) to apply a settings preset for your GPU generation and install the driver Eden recommends for that GPU. Covers Snapdragon 865 through 8 Elite Gen 5. These are rule-based presets, not machine learning. | Settings home, Settings → EdenBlue, first-run setup |
| **Built-in frame generation**: motion-compensated interpolation that needs no Lossless.dll. It only adds frames when your screen has room for them, and the FPS counter shows both numbers, for example `FPS: 60 (30 real)`. It is classical block matching, not a neural network, so HUDs and fast motion can ghost. Lossless Scaling stays selectable. | Settings → Frame generation, in-game quick settings |
| **Import games**: moves `.nsp` and `.xci` files you already have, or ZIPs containing them, into your ROMs folder. Originals are only removed after a full copy. | Settings home |
| **Install mods from ZIP**: pick any mod ZIP (nested folders are handled) and it lands in the game's `load/` folder. | game → Add-ons → Install → Mods (ZIP file) |
| QLaunch button shown by default, dark blue theme, blue launcher and splash icons. | |

## Keys and firmware

EdenBlue **does not ship** `prod.keys`, `title.keys` or Switch firmware. They are Nintendo's
property and cannot be redistributed. Dump them from your own console and import them in
the first-run setup (or later under *Manage EdenBlue data*):

1. Install the APK and open EdenBlue.
2. On the *Emulator data* page, tap **Keys** and pick your `prod.keys` (or a ZIP that contains it).
3. Tap **Firmware** and pick your firmware ZIP.
4. Add your games folder, apply device tuning and finish setup.

The home menu needs both keys and firmware.

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

The APK is written to `artifacts/`. Set `EDENBLUE_VARIANT=modern` to build without the legacy
patches.

CI: pushing an `edenblue-*` tag runs `.github/workflows/edenblue-android.yml`, which attaches both
APKs to a GitHub release. The Windows and Mac workflows are started by hand.

## Upstream

EdenBlue is based on Eden, which is derived from yuzu and Sudachi, licensed under the GPLv3
or any later version. See [LICENSE.txt](LICENSE.txt). Upstream README, build docs and
contribution policies live in [docs/](docs/). Issues with the core emulator belong upstream;
issues with EdenBlue-specific features belong here.
