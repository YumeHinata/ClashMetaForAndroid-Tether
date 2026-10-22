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
    
    void* handle = dlopen("libc.so", RTLD_NOW);
    if (!handle) {
        __android_log_print(ANDROID_LOG_ERROR, "TetherPath", "dlopen libc.so failed: %s", dlerror());
        return;
    }
    _setsocknetwork = (setsocknetwork_func)dlsym(handle, "android_setsocknetwork");
    if (!_setsocknetwork) {
        __android_log_print(ANDROID_LOG_WARN, "TetherPath", "android_setsocknetwork not available (expected on Android 16+)");
    }
    dlclose(handle);
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
