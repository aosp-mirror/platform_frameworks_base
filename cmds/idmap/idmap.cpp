#include "idmap.h"

#include <private/android_filesystem_config.h> // for AID_SYSTEM

#include <stdlib.h>
#include <string.h>

namespace {
    const char *usage = "NAME\n\
      idmap - create or display idmap files\n\
\n\
SYNOPSIS \n\
      idmap --help \n\
      idmap --fd target overlay fd \n\
      idmap --path target overlay idmap \n\
      idmap --scan dir-to-scan target-to-look-for target dir-to-hold-idmaps \n\
      idmap --inspect idmap \n\
\n\
DESCRIPTION \n\
      Idmap files play an integral part in the runtime resource overlay framework. An idmap \n\
      file contains a mapping of resource identifiers between overlay package and its target \n\
      package; this mapping is used during resource lookup. Idmap files also act as control \n\
      files by their existence: if not present, the corresponding overlay package is ignored \n\
      when the resource context is created. \n\
\n\
      Idmap files are stored in /data/resource-cache. For each pair (target package, overlay \n\
      package), there exists exactly one idmap file, or none if the overlay should not be used. \n\
\n\
NOMENCLATURE \n\
      target: the original, non-overlay, package. Each target package may be associated with \n\
              any number of overlay packages. \n\
\n\
      overlay: an overlay package. Each overlay package is associated with exactly one target \n\
               package, specified in the overlay's manifest using the <overlay target=\"...\"/> \n\
               tag. \n\
\n\
OPTIONS \n\
      --help: display this help \n\
\n\
      --fd: create idmap for target package 'target' (path to apk) and overlay package 'overlay' \n\
            (path to apk); write results to file descriptor 'fd' (integer). This invocation \n\
            version is intended to be used by a parent process with higher privileges to call \n\
            idmap in a controlled way: the parent will open a suitable file descriptor, fork, \n\
            drop its privileges and exec. This tool will continue execution without the extra \n\
            privileges, but still have write access to a file it could not have opened on its \n\
            own. \n\
\n\
      --path: create idmap for target package 'target' (path to apk) and overlay package \n\
              'overlay' (path to apk); write results to 'idmap' (path). \n\
\n\
      --scan: non-recursively search directory 'dir-to-scan' (path) for overlay packages with \n\
              target package 'target-to-look-for' (package name) present at 'target' (path to \n\
              apk). For each overlay package found, create an idmap file in 'dir-to-hold-idmaps' \n\
              (path). \n\
\n\
      --inspect: decode the binary format of 'idmap' (path) and display the contents in a \n\
                 debug-friendly format. \n\
\n\
EXAMPLES \n\
      Create an idmap file: \n\
\n\
      $ adb shell idmap --path /system/app/target.apk \\ \n\
                               /vendor/overlay/overlay.apk \\ \n\
                               /data/resource-cache/vendor@overlay@overlay.apk@idmap \n\
\n\
      Display an idmap file: \n\
\n\
      $ adb shell idmap --inspect /data/resource-cache/vendor@overlay@overlay.apk@idmap \n\
      SECTION      ENTRY        VALUE      COMMENT \n\
      IDMAP HEADER magic        0x706d6469 \n\
                   base crc     0xb65a383f \n\
                   overlay crc  0x7b9675e8 \n\
                   base path    .......... /path/to/target.apk \n\
                   overlay path .......... /path/to/overlay.apk \n\
      DATA HEADER  target pkg   0x0000007f \n\
                   types count  0x00000003 \n\
      DATA BLOCK   target type  0x00000002 \n\
                   overlay type 0x00000002 \n\
                   entry count  0x00000001 \n\
                   entry offset 0x00000000 \n\
                   entry        0x00000000 drawable/drawable \n\
      DATA BLOCK   target type  0x00000003 \n\
                   overlay type 0x00000003 \n\
                   entry count  0x00000001 \n\
                   entry offset 0x00000000 \n\
                   entry        0x00000000 xml/integer \n\
      DATA BLOCK   target type  0x00000004 \n\
                   overlay type 0x00000004 \n\
                   entry count  0x00000001 \n\
                   entry offset 0x00000000 \n\
                   entry        0x00000000 raw/lorem_ipsum \n\
\n\
      In this example, the overlay package provides three alternative resource values:\n\
      drawable/drawable, xml/integer, and raw/lorem_ipsum \n\
\n\
NOTES \n\
      This tool and its expected invocation from installd is modelled on dexopt.";

    bool verify_directory_readable(const char *path)
    {
        return access(path, R_OK | X_OK) == 0;
    }

    bool verify_directory_writable(const char *path)
    {
        return access(path, W_OK) == 0;
    }

    bool verify_file_readable(const char *path)
    {
        return access(path, R_OK) == 0;
    }

    bool verify_root_or_system()
    {
        uid_t uid = getuid();
        gid_t gid = getgid();

        return (uid == 0 && gid == 0) || (uid == AID_SYSTEM && gid == AID_SYSTEM);
    }

    int maybe_create_fd(const char *target_apk_path, const char *overlay_apk_path,
            const char *idmap_str)
    {
        // anyone (not just root or system) may do --fd -- the file has
        // already been opened by someone else on our behalf

        char *endptr;
        int idmap_fd = strtol(idmap_str, &endptr, 10);
        if (*endptr != '\0') {
            fprintf(stderr, "error: failed to parse file descriptor argument %s\n", idmap_str);
            return -1;
        }

        if (!verify_file_readable(target_apk_path)) {
            ALOGD("error: failed to read apk %s: %s\n", target_apk_path, strerror(errno));
            return -1;
        }

        if (!verify_file_readable(overlay_apk_path)) {
            ALOGD("error: failed to read apk %s: %s\n", overlay_apk_path, strerror(errno));
            return -1;
        }

        return idmap_create_fd(target_apk_path, overlay_apk_path, idmap_fd);
    }

    int maybe_create_path(const char *target_apk_path, const char *overlay_apk_path,
            const char *idmap_path)
    {
        if (!verify_root_or_system()) {
            fprintf(stderr, "error: permission denied: not user root or user system\n");
            return -1;
        }

        if (!verify_file_readable(target_apk_path)) {
            ALOGD("error: failed to read apk %s: %s\n", target_apk_path, strerror(errno));
            return -1;
        }

        if (!verify_file_readable(overlay_apk_path)) {
            ALOGD("error: failed to read apk %s: %s\n", overlay_apk_path, strerror(errno));
            return -1;
        }

        return idmap_create_path(target_apk_path, overlay_apk_path, idmap_path);
    }

    int maybe_scan(const char *overlay_dir, const char *target_package_name,
            const char *target_apk_path, const char *idmap_dir)
    {
        if (!verify_root_or_system()) {
            fprintf(stderr, "error: permission denied: not user root or user system\n");
            return -1;
        }

        if (!verify_directory_readable(overlay_dir)) {
            ALOGD("error: no read access to %s: %s\n", overlay_dir, strerror(errno));
            return -1;
        }

        if (!verify_file_readable(target_apk_path)) {
            ALOGD("error: failed to read apk %s: %s\n", target_apk_path, strerror(errno));
            return -1;
        }

        if (!verify_directory_writable(idmap_dir)) {
            ALOGD("error: no write access to %s: %s\n", idmap_dir, strerror(errno));
            return -1;
        }

        return idmap_scan(overlay_dir, target_package_name, target_apk_path, idmap_dir);
    }

    int maybe_inspect(const char *idmap_path)
    {
        // anyone (not just root or system) may do --inspect
        if (!verify_file_readable(idmap_path)) {
            ALOGD("error: failed to read idmap %s: %s\n", idmap_path, strerror(errno));
            return -1;
        }
        return idmap_inspect(idmap_path);
    }
}

int main(int argc, char **argv)
{
#if 0
    {
        char buf[1024];
        buf[0] = '\0';
        for (int i = 0; i < argc; ++i) {
            strncat(buf, argv[i], sizeof(buf) - 1);
            strncat(buf, " ", sizeof(buf) - 1);
        }
        ALOGD("%s:%d: uid=%d gid=%d argv=%s\n", __FILE__, __LINE__, getuid(), getgid(), buf);
    }
#endif

    if (argc == 2 && !strcmp(argv[1], "--help")) {
        printf("%s\n", usage);
        return 0;
    }

    if (argc == 5 && !strcmp(argv[1], "--fd")) {
        return maybe_create_fd(argv[2], argv[3], argv[4]);
    }

    if (argc == 5 && !strcmp(argv[1], "--path")) {
        return maybe_create_path(argv[2], argv[3], argv[4]);
    }

    if (argc == 6 && !strcmp(argv[1], "--scan")) {
        return maybe_scan(argv[2], argv[3], argv[4], argv[5]);
    }

    if (argc == 3 && !strcmp(argv[1], "--inspect")) {
        return maybe_inspect(argv[2]);
    }

    fprintf(stderr, "Usage: don't use this (cf dexopt usage).\n");
    return EXIT_FAILURE;
}
