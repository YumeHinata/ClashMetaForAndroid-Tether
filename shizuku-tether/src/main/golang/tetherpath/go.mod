module dev.clashmeta/tetherpath

go 1.26.3

// Pin gvisor version to avoid breaking changes from upstream updates.
// The waiter package moved from pkg/tcpip/waiter to pkg/waiter in newer versions.
// Keep this version synchronized with core module when possible.
require gvisor.dev/gvisor v0.0.0-20260804180831-0f34e3556d62

require (
	github.com/google/btree v1.1.2 // indirect
	golang.org/x/exp v0.0.0-20250711185948-6ae5c78190dc // indirect
	golang.org/x/mobile v0.0.0-20261011034528-197770444867 // indirect
	golang.org/x/mod v0.42.0 // indirect
	golang.org/x/sync v0.24.0 // indirect
	golang.org/x/sys v0.49.0 // indirect
	golang.org/x/time v0.15.0 // indirect
	golang.org/x/tools v0.52.0 // indirect
)

tool golang.org/x/mobile/cmd/gobind
