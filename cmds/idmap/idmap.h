
#ifndef _IDMAP_H_
#define _IDMAP_H_

#define LOG_TAG "idmap"

#include <utils/Log.h>
#include <utils/Vector.h>

#include <errno.h>
#include <stdio.h>

#ifndef TEMP_FAILURE_RETRY
// Used to retry syscalls that can return EINTR.
#define TEMP_FAILURE_RETRY(exp) ({         \
    typeof (exp) _rc;                      \
    do {                                   \
        _rc = (exp);                       \
    } while (_rc == -1 && errno == EINTR); \
    _rc; })
#endif

int idmap_create_path(const char *target_apk_path, const char *overlay_apk_path,
        const char *idmap_path);

int idmap_create_fd(const char *target_apk_path, const char *overlay_apk_path, int fd);

int idmap_verify_fd(const char *target_apk_path, const char *overlay_apk_path, int fd);

// Regarding target_package_name: the idmap_scan implementation should
// be able to extract this from the manifest in target_apk_path,
// simplifying the external API.
int idmap_scan(const char *target_package_name, const char *target_apk_path,
        const char *idmap_dir, const android::Vector<const char *> *overlay_dirs);

int idmap_inspect(const char *idmap_path);

#endif // _IDMAP_H_
