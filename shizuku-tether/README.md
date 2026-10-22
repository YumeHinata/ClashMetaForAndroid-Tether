# Shizuku Tether

Shizuku Tether is a module for Clash Meta for Android that enables sharing the proxy connection
to downstream devices via Android's test network (TUN) API, using Shizuku for privileged access.

## Architecture

```
+------------------+       Shizuku        +---------------------+
|  Main App (UI)   | <==================> | TetherShellService   |
|                  |    (UserService)      | (shell UID 2000)     |
+------------------+                      +---------------------+
                                                  |
                                                  v
                                          +---------------+
                                          | TetherSession  |
                                          +---------------+
                                            |     |     |
                                     createTUN  setup  keepAlive
                                     Interface  TestNet  callback
                                            |     |     |
                                            v     v     v
                                    +---------------------------+
                                    |  gVisor netstack           |
                                    |  (tetherpath Go library)   |
                                    +---------------------------+
                                            |
                                            v
                                    +-------------------+
                                    | SOCKS5 / Direct   |
                                    | upstream proxy    |
                                    +-------------------+
```

### Data Flow

1. **App process** calls `TetherClient.bind()` to start a Shizuku UserService
2. **TetherShellService** runs as shell UID (2000) with `MANAGE_TEST_NETWORKS` permission
3. Creates a TUN interface via `TestNetworkManager.createTunInterface()`
4. Registers it as a test network via `setupTestNetwork()`
5. Sets `setPreferTestNetworks(true)` so the system routes tethered traffic through our TUN
6. Starts WiFi hotspot via `TetheringManager.startTethering()`
7. **gVisor netstack** (Go library built via gomobile) intercepts all traffic on the TUN
8. Traffic is forwarded upstream via direct connection or SOCKS5 proxy

### Module Structure

| Package | Description |
|---------|-------------|
| `tether.shizuku` | Shizuku integration (gate, client, shell service) |
| `tether.control` | Tethering lifecycle (session, downstream control, preferences) |
| `tether.forwarder` | Data path bridge (TetherPathBridge to Go, legacy TunForwarder) |
| `tether.testnetwork` | Android TestNetworkManager API wrappers, hidden API catalog |
| `tetherpath` (Go) | gVisor-based userspace networking stack |

## Build Requirements

- **Go 1.21+** with `gomobile` installed (`go install golang.org/x/mobile/cmd/gomobile@latest`)
- **JDK 21** (configured in root `build.gradle.kts`)
- **Android SDK** with CMake and NDK
- **GOPATH** set (defaults to `~/go`)

### Building the Go library

The gomobile bind task runs automatically as part of `preBuild`:

```bash
./gradlew :shizuku-tether:assembleAlphaDebug
```

To build the Go AAR manually:

```bash
cd shizuku-tether/src/main/golang/tetherpath
export ANDROID_HOME=/path/to/android-sdk
export CGO_ENABLED=1
gomobile bind -v -target=android -androidapi=21 -o=../../../libs/tetherpath.aar .
```

## Known Issues and Limitations

- **IPv6 UDP**: gVisor's UDP forwarding over IPv6 is not fully tested and may not work correctly
- **MIUI/HyperOS**: `setupTestNetwork()` may return null on Xiaomi devices; the code polls
  `ConnectivityManager.allNetworks` as a workaround
- **SOCKS5 proxy integration**: Currently uses direct connection; SOCKS5 forwarding through
  Mihomo is defined in `socks5.go` but not yet wired into the forwarder path
- **Android 16+**: Requires `android_setsocknetwork` for network binding; implemented via
  CGo in `bind_android.go`
- **Shizuku dependency**: Requires Shizuku to be installed, running, and granted permission

## Hidden API Usage

This module uses Android hidden APIs via reflection and `HiddenApiBypass`:

- `TestNetworkManager` (API 29+): Create TUN interfaces and register test networks
- `TetheringManager.setPreferTestNetworks` (API 32+): Route tethered traffic through test network
- `TetheringManager.startTethering/stopTethering` (API 30+): Control WiFi hotspot
- `NetworkCapabilities.TRANSPORT_TEST` (API 29+): Keep-alive for test networks

See `HiddenApiCatalog.kt` for the complete list of hidden API paths used.
