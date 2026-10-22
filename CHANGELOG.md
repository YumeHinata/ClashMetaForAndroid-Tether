# Changelog

## [Unreleased]

### Added
- Shizuku Tether module: share proxy connection to downstream devices via Android test network API
- gVisor-based userspace networking (tetherpath Go library) for TCP and UDP forwarding
- TUN interface creation via `TestNetworkManager` hidden API
- WiFi hotspot control via `TetheringManager` with `setPreferTestNetworks` support
- Shizuku UserService integration for shell UID (2000) privileged operations
- Tether settings UI with auto-start toggle and status display
- Quick Settings tile for tether toggle
- Foreground service to maintain tether session
- File-based debug logging (`tether_debug.log`) for app process
- File-based Go logging (`/data/local/tmp/tetherpath.log`) for gVisor netstack
- Hidden API bypass via `HiddenApiBypass` for `TestNetworkManager` and `TetheringManager`
- MIUI/HyperOS workaround: poll `ConnectivityManager.allNetworks` when `setupTestNetwork` returns null
- Keep-alive NetworkCallback with `TRANSPORT_TEST` to prevent system from removing test network
- Android 16+ support via `android_setsocknetwork` CGo binding
- Compatibility check and reporting for device support verification
- AIDL interface (`ITetherService`) for IPC between app and shell service

### Changed
- Cleaned up debug logging: removed excessive step-by-step logs, kept essential info/error logs
- Removed hardcoded Go binary path from `build.gradle.kts` (now uses system PATH)
- Removed temporary test `.aar` files from Go source directory
- TunForwarder marked as legacy; primary data path now uses gVisor via TetherPathBridge

### Known Issues
- IPv6 UDP forwarding not fully tested
- SOCKS5 proxy integration defined but not wired into forwarder (uses direct connection)
- Shizuku must be installed, running, and granted permission before use
