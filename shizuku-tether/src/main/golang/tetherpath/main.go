// Package tetherpath provides gVisor-based userspace networking for tethering.
package tetherpath

import (
	"fmt"
	"log"
	"os"
	"sync"
)

var (
	activeSession      *Session
	sessionMu          sync.Mutex
	logFile            *os.File
	vpnNetworkHandle   uint64
)

func init() {
	// Open log file for debugging
	var err error
	logFile, err = os.OpenFile("/data/local/tmp/tetherpath.log", os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
	if err != nil {
		// Fallback to stderr if we can't open the file
		log.SetOutput(os.Stderr)
	} else {
		log.SetOutput(logFile)
	}
	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
}

func logMsg(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	log.Println(msg)
	if logFile != nil {
		logFile.Sync()
	}
}

// Start initializes gVisor netstack and begins forwarding traffic.
func Start(tunFD int, mtu int, socksAddr string) error {
	sessionMu.Lock()
	defer sessionMu.Unlock()

	if activeSession != nil {
		activeSession.Stop()
		activeSession = nil
	}

	logMsg("TetherPath: Starting with fd=%d mtu=%d addr=%s", tunFD, mtu, socksAddr)
	
	s, err := StartNetstack(tunFD, mtu, socksAddr)
	if err != nil {
		logMsg("TetherPath: start failed: %v", err)
		return err
	}
	activeSession = s
	logMsg("TetherPath: gVisor datapath started successfully")
	return nil
}

// Stop tears down the running netstack.
func Stop() {
	sessionMu.Lock()
	defer sessionMu.Unlock()

	if activeSession != nil {
		activeSession.Stop()
		activeSession = nil
		logMsg("TetherPath: stopped")
	}
}

// SetVpnNetworkHandle sets the Android network handle for the VPN.
// When set, gVisor will pin outbound sockets to this network.
func SetVpnNetworkHandle(handle int) {
	vpnNetworkHandle = uint64(handle)
	logMsg("TetherPath: VPN network handle set to %d", handle)
}
