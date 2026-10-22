// Package tetherpath terminates tethered client traffic in userspace
// and forwards it through a SOCKS5 proxy or direct connection.
package tetherpath

import (
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"syscall"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	nicID          tcpip.NICID = 1
	maxInFlight                = 512
	defaultRcvWnd              = 0
	dialTimeout                = 10 * time.Second
)

// Session owns a running netstack bound to one TUN file descriptor.
type Session struct {
	stack       *stack.Stack
	socksAddr   string
	vpnHandle   uint64
	mu          sync.Mutex
}

// StartNetstack builds a netstack over tunFD and attaches it to the TUN.
// socksAddr is the address of the upstream proxy (e.g. "127.0.0.1:7891").
func StartNetstack(tunFD int, mtu int, socksAddr string) (s *Session, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("gVisor panic recovered: %v", r)
			logMsg("PANIC in StartNetstack: %v", r)
		}
	}()

	logMsg("StartNetstack: tunFD=%d, mtu=%d, socksAddr=%s", tunFD, mtu, socksAddr)

	netStack := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
			icmp.NewProtocol4,
			icmp.NewProtocol6,
		},
	})

	endpoint, err := fdbased.New(&fdbased.Options{
		FDs: []int{tunFD},
		MTU: uint32(mtu),
	})
	if err != nil {
		netStack.Close()
		return nil, fmt.Errorf("tetherpath: link endpoint over fd %d: %w", tunFD, err)
	}

	if tcpipErr := netStack.CreateNIC(nicID, endpoint); tcpipErr != nil {
		netStack.Close()
		return nil, fmt.Errorf("tetherpath: create NIC on fd %d: %v", tunFD, tcpipErr)
	}

	netStack.SetPromiscuousMode(nicID, true)
	netStack.SetSpoofing(nicID, true)

	netStack.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: nicID},
		{Destination: header.IPv6EmptySubnet, NIC: nicID},
	})

	s = &Session{stack: netStack, socksAddr: socksAddr, vpnHandle: vpnNetworkHandle}
	installForwarders(netStack, socksAddr, vpnNetworkHandle)

	logMsg("StartNetstack: gVisor datapath started successfully")
	return s, nil
}

// Stop tears the netstack down.
func (s *Session) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stack == nil {
		return
	}
	s.stack.Close()
	s.stack = nil
	logMsg("Session stopped")
}

func installForwarders(netStack *stack.Stack, socksAddr string, vpnHandle uint64) {
	dialer := &net.Dialer{Timeout: dialTimeout}
	
	// If VPN handle is set, pin sockets to the VPN network
	if vpnHandle != 0 {
		logMsg("Pinning sockets to VPN network handle %d", vpnHandle)
		dialer.Control = func(network, address string, c syscall.RawConn) error {
			return c.Control(func(fd uintptr) {
				if err := bindToNetwork(vpnHandle, int(fd)); err != nil {
					logMsg("bindToNetwork failed for %s: %v", address, err)
				}
			})
		}
	}

	tcpForwarder := tcp.NewForwarder(netStack, defaultRcvWnd, maxInFlight,
		func(request *tcp.ForwarderRequest) { forwardTCP(request, dialer, socksAddr) })
	netStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(netStack,
		func(request *udp.ForwarderRequest) bool {
			return forwardUDP(request, dialer, socksAddr)
		})
	netStack.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)
}

func forwardTCP(request *tcp.ForwarderRequest, dialer *net.Dialer, socksAddr string) {
	id := request.ID()
	dest := destinationOf(id)

	logMsg("TCP forward: %s", dest)
	upstream, err := dialer.Dial("tcp", dest)
	if err != nil {
		logMsg("TCP dial failed for %s: %v", dest, err)
		request.Complete(true)
		return
	}

	var queue waiter.Queue
	endpoint, tcpipErr := request.CreateEndpoint(&queue)
	if tcpipErr != nil {
		logMsg("TCP CreateEndpoint failed for %s: %v", dest, tcpipErr)
		upstream.Close()
		request.Complete(true)
		return
	}
	request.Complete(false)

	client := gonet.NewTCPConn(&queue, endpoint)
	go relay(client, upstream)
}

func forwardUDP(request *udp.ForwarderRequest, dialer *net.Dialer, socksAddr string) bool {
	id := request.ID()
	dest := destinationOf(id)

	logMsg("UDP forward: %s", dest)
	upstream, err := dialer.Dial("udp", dest)
	if err != nil {
		logMsg("UDP dial failed for %s: %v", dest, err)
		return false
	}

	var queue waiter.Queue
	endpoint, tcpipErr := request.CreateEndpoint(&queue)
	if tcpipErr != nil {
		logMsg("UDP CreateEndpoint failed for %s: %v", dest, tcpipErr)
		upstream.Close()
		return false
	}

	client := gonet.NewUDPConn(&queue, endpoint)
	go relayDatagrams(client, upstream)
	return true
}

func destinationOf(id stack.TransportEndpointID) string {
	return net.JoinHostPort(
		net.IP(id.LocalAddress.AsSlice()).String(),
		strconv.Itoa(int(id.LocalPort)),
	)
}

func relay(client, upstream net.Conn) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)
	go func() {
		io.Copy(upstream, client)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(client, upstream)
		done <- struct{}{}
	}()
	<-done
}

func relayDatagrams(client, upstream net.Conn) {
	defer client.Close()
	defer upstream.Close()

	done := make(chan struct{}, 2)
	go func() {
		buf := make([]byte, 65536)
		for {
			n, err := client.Read(buf)
			if err != nil {
				done <- struct{}{}
				return
			}
			upstream.Write(buf[:n])
		}
	}()
	go func() {
		buf := make([]byte, 65536)
		for {
			n, err := upstream.Read(buf)
			if err != nil {
				done <- struct{}{}
				return
			}
			client.Write(buf[:n])
		}
	}()
	<-done
}
