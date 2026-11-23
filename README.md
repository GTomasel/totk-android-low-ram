# TotK Android Low RAM

[![Support this fork on Ko-fi](https://img.shields.io/badge/Support_this_fork_on_Ko--fi-FF5E5B?style=for-the-badge&logo=ko-fi&logoColor=white)](https://ko-fi.com/gabrielmdq)

TotK Android Low RAM is a fork of [TotK Native for Android](https://github.com/AurelioB/totk-android), the original Android port by Aurelio Bernal.

**TotK Fork** (`com.gtomasel.totk`) runs **The Legend of Zelda: Tears of the Kingdom 1.4.3** natively on Android using the bundled Turnip Vulkan driver. This fork focuses on reducing renderer memory pressure during gameplay and scene transitions. The APK contains no game files. Setup prepares the game on the device from your own copy.

## Requirements

- **Android 13 or newer (64-bit ARM)** and a **Qualcomm Adreno GPU compatible with the bundled Mesa Turnip driver**. Mali GPUs are currently unsupported.
- **Your own copy of Tears of the Kingdom with the 1.4.3 update.** Allow about **40 GB of free storage during setup** and about **20 GB for the prepared game**.
- This fork was tested on a **Snapdragon 8 Gen 2** with **8 GB RAM** and **Android 16**. Performance and stability vary by device. A full playthrough is still pending.

For a first test, we recommend a **Snapdragon 8 Gen 2 (Adreno 740)** and prefer **12 GB RAM** for extra memory headroom. The original port reached about 30 FPS in short tests on the AYN Thor. The **Dragonwing Q-7790 (Adreno 722)** is also a candidate for testing. The original port measured about 28–30 FPS in short tests on the Retroid Pocket Duo, which still needs testing with this fork. [Original port benchmarks](https://github.com/AurelioB/totk-android#performance)

[Download the latest APK and SHA-256 checksum](https://github.com/GTomasel/totk-android-low-ram/releases/latest)

## What changed in this fork

- **Low memory mode** reclaims empty Vulkan image memory blocks when the GPU has finished using them, reduces how much upload and command-recording memory is retained, uses smaller descriptor pools where layouts allow, and limits shader warming. Version 1.1.2 adds a 12 MiB staging budget for deferrable GPU-to-game copies. Immediate reads retain their existing path. The mode leaves texture quality, draw distance, render resolution, shadows and the game's heap settings at their selected values.
- **Renderer and cache fixes** correct resource retirement, GPU copy synchronization and shader-cache reads. The GPU index conversion fix restored the missing Hero's Path trail at both map zoom levels inspected on the S23+.
- **Display pacing** keeps a refresh-rate request active during controller-only gameplay and fixes the 30 FPS cap on high-refresh displays. On the S23+, Android reported 120 Hz throughout the sampled sky and surface sessions in Adaptive mode and selected 60 Hz in Standard mode. The 30 FPS cap measured about 29.9 FPS in the high-refresh timing check. Gameplay FPS varies by scene.
- **In-game menu and restart** let you use Android Back to pause and open the app menu. Graphics changes made there apply at the next game start. **Resume game** continues the current session. **Restart game** asks for confirmation about unsaved progress and waits for an active save to finish before restarting. Cancelling keeps the current session available to resume.
- **Display and controller settings** offer 4:3, 21:9 and 32:9 camera aspect ratios, a separate **Stretch to fill** option and rumble gains up to 400%.
- **Mod and save handling** adds folder and ZIP import under **Game data → Mods**, including supported RomFS overlays and ExeFS IPS patches. Local regression tests cover interrupted mod imports and ZIP expansion limits. The save file-count limit was raised to 4,096 to handle larger save sets. [Mod import details](docs/mods.md#android)

[Changelog](CHANGELOG.md) · [Android device validation](docs/s23-device-validation.md) · [Local source audit](docs/source-audit.md)

## Low memory mode

Open **Graphics → Performance → Low memory mode**. It is off by default and can be used with any graphics preset. If the game is already running, save your progress and restart the game from the app menu to apply the setting.

The staging budgets govern renderer allocations. Total game and system RAM usage still varies with what the game is loading. Shader and pipeline work can still occur on first use.

## What we verified on the Galaxy S23+

Our device results come from an **8 GB Galaxy S23+ running Android 16** with game version 1.4.3 and the bundled Turnip driver. We loaded checkpoints directly to test the prologue, Great Sky Island, Akkala, Lookout Landing, shrines and a round trip through the Depths. We also exercised save and reload, Android Back, and the confirmed graphics restart. [Device validation and checkpoint coverage](docs/s23-device-validation.md)

- **Memory pressure during the return from the Depths.** Two earlier battery-powered runs ended with Android reporting `LOW_MEMORY` after 31 and 67 seconds from the return warp request. A later run with the 12 MiB budget went through the Sky, the Depths and back to the surface in the same process. It ended normally at the 40% battery cutoff after 41 minutes, 151 seconds after the return request. The runs differed in process age, weather and route timing. Peak process memory in the later run fell between the two failing runs. In a separate short comparison, peak write-back staging measured 12.03 MiB with the budget and 26.81 MiB in the control. Both short runs completed, with differences in starting temperature, charging and process age.
- **Frame rate depends on the scene.** In that later run the field averaged 29.92 FPS over 23 measured windows, while the Depths averaged 24.89 FPS over 27 windows. The run used handheld mode, a 30 FPS cap, 960 × 540 with dynamic resolution and a 256-pixel shadow setting. Full playthrough coverage, repeatable 90–120 minute sessions, screen-off recovery and sustained 30 FPS in demanding scenes are still pending.
- **Cache and visual quality experiments.** In one short Lookout Landing comparison, using the pipeline cache from a previous visit reduced logged pipeline build time from 352.2 to 83.5 ms. The draw-distance, texture mip and resource-preload experiments did not establish a consistent reduction in process RAM. Those experimental mods are outside the release profile.

[S23+ campaign results for 1.1.2](docs/s23-campaign-results.md) · [Detailed memory and cache trials](docs/s23-device-validation.md#lookout-landing-cache-comparison)

## Install or update

1. Download `TotK-Fork-<version>.apk` from [Releases](https://github.com/GTomasel/totk-android-low-ram/releases/latest) and install it on a device that meets the [requirements](#requirements).
2. Open **TotK Fork** and use **Begin setup** to select files from your own copy of the game.
   - **Decrypted dump** uses the updated 1.4.3 RomFS image and the corresponding ExeFS files `main`, `sdk` and `subsdk0`. This is the route verified on the S23+.
   - **Container setup** is also available in the app. It accepts the 1.0.0 base game as XCI or NSP, the 1.4.3 update NSP and the keys from your own console.
3. Let setup finish and select **Start game**.

Install updates over the existing signed **TotK Fork** installation to keep its saves and settings. To switch from the original app or a development build, use **Saves** to export a backup from that installation and import it into the fork. Export before uninstalling because Android removes that installation's private saves.

Use this repository's Releases page for updates. The built-in update checker still queries the original project's release feed. You can turn it off in **About → Check for updates when the game starts**.

## Report a bug and attach logs

- If you see **Setup stopped**, select **Share details** before selecting **Resume**. Save the shared text as a `.txt` file or paste it into the issue.
- For a **crash at launch or during play**, copy `totk.log` after the crash and before selecting **Start game** again. Each launch replaces the log. Check **Game data → Storage** for the game data location. If you chose a folder, look inside that folder. For the app folder on an SD card or an older install using shared internal storage, look under `Android/data/com.gtomasel.totk/files/` on that volume.

If the game data is in the app's private internal storage or you cannot access the folder, capture the failure with ADB. Install [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools), enable **USB debugging** in Android's **Developer options**, connect the device by USB and accept the debugging prompt. Run `adb devices` on your computer and check that your device appears with the status `device`. Then start the capture.

```sh
adb logcat -b main -b system -b crash -b events -v threadtime -T 1 > totk-logcat.txt
```

With `logcat` running, reproduce the problem and press **Ctrl+C** to stop the capture. The file is saved in the terminal's current directory. [Android's ADB setup guide](https://developer.android.com/tools/adb#Enabling)

Review the log for personal information, then drag the `.log` or `.txt` file into the description of your [GitHub issue](https://github.com/GTomasel/totk-android-low-ram/issues/new). Include your device model, SoC, RAM, Android and app versions, driver name and version if customized, any enabled mods, steps to reproduce, and the exact error or a screenshot.

## Build and test locally

Source regressions run locally with `python -m unittest discover -s tests` after installing the build dependencies. The fork also provides separately signed development APK builds and a tool that tracks the gameplay process by PID during memory captures. [Build guide](docs/building.md) · [Fork APK packaging](docs/android-test-builds.md)

## Credits and license

The original Android port builds on [MacNativeGames](https://github.com/ly05010419/MacNativeGames). This project uses components including [D2R Mobile](https://github.com/jhaizhou-ops/d2r-mobile), Ryujinx's shader translator and [Mesa Turnip](https://mesa3d.org). [Third-party credits and licenses](THIRD_PARTY_NOTICES.md)

The project is released under the [MIT license](LICENSE). Use game files from your own copy.
