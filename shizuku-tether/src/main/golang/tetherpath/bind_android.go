//go:build android

package tetherpath

/*
#include <stdint.h>
#include <dlfcn.h>
#include <android/log.h>

typedef uint64_t net_handle_t;
typedef int (*setsocknetwork_func)(net_handle_t, int);

static setsocknetwork_func _setsocknetwork = NULL;
static int _dlsym_attempted = 0;

static void ensure_setsocknetwork_loaded() {
    if (_dlsym_attempted) return;
    _dlsym_attempted = 1;

    // android_setsocknetwork() is an NDK symbol exported by libandroid.so
    // (declared in <android/multinetwork.h>), NOT by libc.so. Resolve it from
    // libandroid.so first, then fall back to the global symbol table, which
    // finds it when libnetd_client/libandroid are already loaded into the
    // (Shizuku shell) process. This is stable across Android 13-16.
    void* handle = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
    if (!handle) {
        handle = dlopen("libandroid.so", RTLD_NOW);
    }
    if (handle) {
        _setsocknetwork = (setsocknetwork_func)dlsym(handle, "android_setsocknetwork");
    }
    if (!_setsocknetwork) {
        _setsocknetwork = (setsocknetwork_func)dlsym(RTLD_DEFAULT, "android_setsocknetwork");
    }
    if (!_setsocknetwork) {
        __android_log_print(ANDROID_LOG_WARN, "TetherPath",
            "android_setsocknetwork unavailable; falling back to process-level network binding");
    } else {
        __android_log_print(ANDROID_LOG_INFO, "TetherPath", "android_setsocknetwork resolved");
    }
    // Intentionally no dlclose(): the symbol must stay valid for the process lifetime.
}

static int call_setsocknetwork(net_handle_t net, int fd) {
    ensure_setsocknetwork_loaded();
    if (!_setsocknetwork) return -1;
    return _setsocknetwork(net, fd);
}
*/
import "C"

import (
	"fmt"
)

// bindToNetwork pins a socket fd to a specific Android network handle.
func bindToNetwork(handle uint64, fd int) error {
	if handle == 0 {
		return nil
	}
	rc := C.call_setsocknetwork(C.net_handle_t(handle), C.int(fd))
	if rc != 0 {
		return fmt.Errorf("android_setsocknetwork: rc=%d", rc)
	}
	return nil
}
