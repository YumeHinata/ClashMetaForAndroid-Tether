package tetherpath

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strconv"
)

// dialSOCKS5 connects to the destination through a SOCKS5 proxy.
func dialSOCKS5(dialer *net.Dialer, proxyAddr, network, dest string) (net.Conn, error) {
	conn, err := dialer.Dial(network, proxyAddr)
	if err != nil {
		return nil, fmt.Errorf("tetherpath: dial proxy %s: %w", proxyAddr, err)
	}

	host, portStr, err := net.SplitHostPort(dest)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("tetherpath: parse dest %s: %w", dest, err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("tetherpath: parse port %s: %w", portStr, err)
	}

	if err := socks5Handshake(conn); err != nil {
		conn.Close()
		return nil, fmt.Errorf("tetherpath: SOCKS5 handshake: %w", err)
	}

	if err := socks5Connect(conn, host, uint16(port)); err != nil {
		conn.Close()
		return nil, fmt.Errorf("tetherpath: SOCKS5 connect: %w", err)
	}

	return conn, nil
}

func socks5Handshake(conn net.Conn) error {
	// Greeting: no auth
	_, err := conn.Write([]byte{0x05, 0x01, 0x00})
	if err != nil {
		return err
	}

	resp := make([]byte, 2)
	_, err = io.ReadFull(conn, resp)
	if err != nil {
		return err
	}
	if resp[0] != 0x05 || resp[1] != 0x00 {
		return fmt.Errorf("unexpected greeting response: %x", resp)
	}
	return nil
}

func socks5Connect(conn net.Conn, host string, port uint16) error {
	// CONNECT command
	var addrType byte
	var addr []byte

	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			addrType = 0x01
			addr = ip4
		} else {
			addrType = 0x04
			addr = ip.To16()
		}
	} else {
		addrType = 0x03
		addr = []byte(host)
	}

	req := []byte{0x05, 0x01, 0x00, addrType}
	if addrType == 0x03 {
		req = append(req, byte(len(addr)))
	}
	req = append(req, addr...)

	portBytes := make([]byte, 2)
	binary.BigEndian.PutUint16(portBytes, port)
	req = append(req, portBytes...)

	_, err := conn.Write(req)
	if err != nil {
		return err
	}

	resp := make([]byte, 4)
	_, err = io.ReadFull(conn, resp)
	if err != nil {
		return err
	}
	if resp[0] != 0x05 || resp[1] != 0x00 {
		return fmt.Errorf("connect failed: reply=%x", resp[:2])
	}

	// Read remaining bind address
	switch resp[3] {
	case 0x01:
		io.ReadFull(conn, make([]byte, 4+2))
	case 0x04:
		io.ReadFull(conn, make([]byte, 16+2))
	case 0x03:
		lenBuf := make([]byte, 1)
		io.ReadFull(conn, lenBuf)
		io.ReadFull(conn, make([]byte, int(lenBuf[0])+2))
	}

	return nil
}
