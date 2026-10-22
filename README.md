## Clash Meta for Android

A rule-based tunnel for Android, with Shizuku Tether for root-free proxy sharing via WiFi hotspot.

### Feature

Feature of [Clash.Meta](https://github.com/MetaCubeX/Clash.Meta)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/com.github.metacubex.clash.meta/)

### Requirement

- Android 5.0+ (minimum)
- Android 7.0+ (recommend)
- `armeabi-v7a` , `arm64-v8a`, `x86` or `x86_64` Architecture

### Build

1. Update submodules

   ```bash
   git submodule update --init --recursive
   ```

2. Install **OpenJDK 11**, **Android SDK**, **CMake** and **Golang**

3. Create `local.properties` in project root with

   ```properties
   sdk.dir=/path/to/android-sdk
   ```

4. (Optional) Custom app package name. Add the following configuration to `local.properties`.

   ```properties
   # config your ownn applicationId, or it will be 'com.github.metacubex.clash'
   custom.application.id=com.my.compile.clash
   # remove application id suffix, or the applicaion id will be 'com.github.metacubex.clash.alpha'
   remove.suffix=true

5. Create `signing.properties` in project root with

   ```properties
   keystore.path=/path/to/keystore/file
   keystore.password=<key store password>
   key.alias=<key alias>
   key.password=<key password>
   ```

6. Build

   ```bash
   ./gradlew app:assembleAlphaRelease
   ```

### Automation

APP package name is `com.github.metacubex.clash.meta`

- Toggle Clash.Meta service status
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `com.github.metacubex.clash.meta.action.TOGGLE_CLASH`
- Start Clash.Meta service
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `com.github.metacubex.clash.meta.action.START_CLASH`
- Stop Clash.Meta service
  - Send intent to activity `com.github.kr328.clash.ExternalControlActivity` with action `com.github.metacubex.clash.meta.action.STOP_CLASH`
- Import a profile
  - URL Scheme `clash://install-config?url=<encoded URI>` or `clashmeta://install-config?url=<encoded URI>`

### Shizuku Tether (Tether Proxy)

CMFA includes a Shizuku Tether module that shares the proxy connection to downstream devices via WiFi hotspot, enabling other devices to access the internet through Clash's proxy without requiring root access.

This implementation is inspired by and references the [Shizzi project](https://github.com/carlelieser/shizzi), which demonstrated the feasibility of using Android's Test Network API with Shizuku for proxy sharing. We extend our gratitude to the Shizzi developers for their pioneering work and open-source contributions.

#### Key Features

- **No Root Required**: Uses Shizuku's shell permissions to access system-level network APIs
- **Automatic Lifecycle Management**: Intelligent monitoring and recovery system that automatically handles various scenarios:
  - Detects and recovers from Shizuku service interruptions
  - Automatically stops tether when the proxy service stops
  - Maintains monitoring capability when hotspot is temporarily disabled
  - Clean resource cleanup when the app is closed
- **Hotspot Acceleration**: Seamlessly integrates with Android's WiFi hotspot to share proxy access with connected devices
- **Stability Enhancements**: Includes fixes for native crashes and buffer management issues in high-traffic scenarios

#### Requirements

- **Shizuku** installed and running on the device
- **Android 10+** (API 29) for TestNetworkManager API
- **Android 12+** (API 32) recommended for `setPreferTestNetworks`

See [shizuku-tether/README.md](shizuku-tether/README.md) for architecture details and build instructions.

#### Build Extra Requirements

In addition to the standard build requirements, building the Shizuku Tether module requires:

- **gomobile** installed: `go install golang.org/x/mobile/cmd/gomobile@latest`
- The gomobile bind task runs automatically during `preBuild`

### Contribution and Project Maintenance

#### Meta Kernel

- CMFA uses the kernel from `android-real` branch under `MetaCubeX/Clash.Meta`, which is a merge of the main `Alpha` branch and `android-open`.
  - If you want to contribute to the kernel, make PRs to `Alpha` branch of the Meta kernel repository.
  - If you want to contribute Android-specific patches to the kernel, make PRs to  `android-open` branch of the Meta kernel repository.

#### Maintenance

- When `MetaCubeX/Clash.Meta` kernel is updated to a new version, the `Update Dependencies` actions in this repo will be triggered automatically.
  - It will pull the new version of the meta kernel, update all the golang dependencies, and create a PR without manual intervention.
  - If there is any compile error in PR, you need to fix it before merging. Alternatively, you may merge the PR directly.
- Manually triggering `Build Pre-Release` actions will compile and publish a `PreRelease` version.
- Manually triggering `Build Release` actions will compile, tag and publish a `Release` version.
  - You must fill the blank `Release Tag` with the tag you want to release in the format of `v1.2.3`.
  - `versionName` and `versionCode` in `build.gradle.kts` will be automatically bumped to the tag you filled above.
