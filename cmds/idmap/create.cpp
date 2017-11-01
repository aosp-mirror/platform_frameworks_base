#include "idmap.h"

#include <memory>
#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>
#include <androidfw/ZipFileRO.h>
#include <utils/String8.h>

#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>

using namespace android;

namespace {
    int get_zip_entry_crc(const char *zip_path, const char *entry_name, uint32_t *crc)
    {
        std::unique_ptr<ZipFileRO> zip(ZipFileRO::open(zip_path));
        if (zip.get() == NULL) {
            return -1;
        }
        ZipEntryRO entry = zip->findEntryByName(entry_name);
        if (entry == NULL) {
            return -1;
        }
        if (!zip->getEntryInfo(entry, NULL, NULL, NULL, NULL, NULL, crc)) {
            return -1;
        }
        zip->releaseEntry(entry);
        return 0;
    }

    int open_idmap(const char *path)
    {
        int fd = TEMP_FAILURE_RETRY(open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644));
        if (fd == -1) {
            ALOGD("error: open %s: %s\n", path, strerror(errno));
            goto fail;
        }
        if (fchmod(fd, S_IRUSR | S_IWUSR | S_IRGRP | S_IROTH) < 0) {
            ALOGD("error: fchmod %s: %s\n", path, strerror(errno));
            goto fail;
        }
        if (TEMP_FAILURE_RETRY(flock(fd, LOCK_EX)) != 0) {
            ALOGD("error: flock %s: %s\n", path, strerror(errno));
            goto fail;
        }

        return fd;
fail:
        if (fd != -1) {
            close(fd);
            unlink(path);
        }
        return -1;
    }

    int write_idmap(int fd, const uint32_t *data, size_t size)
    {
        if (lseek(fd, 0, SEEK_SET) < 0) {
            return -1;
        }
        size_t bytesLeft = size;
        while (bytesLeft > 0) {
            ssize_t w = TEMP_FAILURE_RETRY(write(fd, data + size - bytesLeft, bytesLeft));
            if (w < 0) {
                fprintf(stderr, "error: write: %s\n", strerror(errno));
                return -1;
            }
            bytesLeft -= static_cast<size_t>(w);
        }
        return 0;
    }

    bool is_idmap_stale_fd(const char *target_apk_path, const char *overlay_apk_path, int idmap_fd)
    {
        static const size_t N = ResTable::IDMAP_HEADER_SIZE_BYTES;
        struct stat st;
        if (fstat(idmap_fd, &st) == -1) {
            return true;
        }
        if (st.st_size < static_cast<off_t>(N)) {
            // file is empty or corrupt
            return true;
        }

        char buf[N];
        size_t bytesLeft = N;
        if (lseek(idmap_fd, 0, SEEK_SET) < 0) {
            return true;
        }
        for (;;) {
            ssize_t r = TEMP_FAILURE_RETRY(read(idmap_fd, buf + N - bytesLeft, bytesLeft));
            if (r < 0) {
                return true;
            }
            bytesLeft -= static_cast<size_t>(r);
            if (bytesLeft == 0) {
                break;
            }
            if (r == 0) {
                // "shouldn't happen"
                return true;
            }
        }

        uint32_t version, cached_target_crc, cached_overlay_crc;
        String8 cached_target_path, cached_overlay_path;
        if (!ResTable::getIdmapInfo(buf, N, &version, &cached_target_crc, &cached_overlay_crc,
                    &cached_target_path, &cached_overlay_path)) {
            return true;
        }

        if (version != ResTable::IDMAP_CURRENT_VERSION) {
            return true;
        }

        if (cached_target_path != target_apk_path) {
            return true;
        }
        if (cached_overlay_path != overlay_apk_path) {
            return true;
        }

        uint32_t actual_target_crc, actual_overlay_crc;
        if (get_zip_entry_crc(target_apk_path, AssetManager::RESOURCES_FILENAME,
				&actual_target_crc) == -1) {
            return true;
        }
        if (get_zip_entry_crc(overlay_apk_path, AssetManager::RESOURCES_FILENAME,
				&actual_overlay_crc) == -1) {
            return true;
        }

        return cached_target_crc != actual_target_crc || cached_overlay_crc != actual_overlay_crc;
    }

    bool is_idmap_stale_path(const char *target_apk_path, const char *overlay_apk_path,
            const char *idmap_path)
    {
        struct stat st;
        if (stat(idmap_path, &st) == -1) {
            // non-existing idmap is always stale; on other errors, abort idmap generation
            return errno == ENOENT;
        }

        int idmap_fd = TEMP_FAILURE_RETRY(open(idmap_path, O_RDONLY));
        if (idmap_fd == -1) {
            return false;
        }
        bool is_stale = is_idmap_stale_fd(target_apk_path, overlay_apk_path, idmap_fd);
        close(idmap_fd);
        return is_stale;
    }

    int create_idmap(const char *target_apk_path, const char *overlay_apk_path,
            uint32_t **data, size_t *size)
    {
        uint32_t target_crc, overlay_crc;
        if (get_zip_entry_crc(target_apk_path, AssetManager::RESOURCES_FILENAME,
				&target_crc) == -1) {
            return -1;
        }
        if (get_zip_entry_crc(overlay_apk_path, AssetManager::RESOURCES_FILENAME,
				&overlay_crc) == -1) {
            return -1;
        }

        AssetManager am;
        bool b = am.createIdmap(target_apk_path, overlay_apk_path, target_crc, overlay_crc,
                data, size);
        return b ? 0 : -1;
    }

    int create_and_write_idmap(const char *target_apk_path, const char *overlay_apk_path,
            int fd, bool check_if_stale)
    {
        if (check_if_stale) {
            if (!is_idmap_stale_fd(target_apk_path, overlay_apk_path, fd)) {
                // already up to date -- nothing to do
                return 0;
            }
        }

        uint32_t *data = NULL;
        size_t size;

        if (create_idmap(target_apk_path, overlay_apk_path, &data, &size) == -1) {
            return -1;
        }

        if (write_idmap(fd, data, size) == -1) {
            free(data);
            return -1;
        }

        free(data);
        return 0;
    }
}

int idmap_create_path(const char *target_apk_path, const char *overlay_apk_path,
        const char *idmap_path)
{
    if (!is_idmap_stale_path(target_apk_path, overlay_apk_path, idmap_path)) {
        // already up to date -- nothing to do
        return EXIT_SUCCESS;
    }

    int fd = open_idmap(idmap_path);
    if (fd == -1) {
        return EXIT_FAILURE;
    }

    int r = create_and_write_idmap(target_apk_path, overlay_apk_path, fd, false);
    close(fd);
    if (r != 0) {
        unlink(idmap_path);
    }
    return r == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

int idmap_create_fd(const char *target_apk_path, const char *overlay_apk_path, int fd)
{
    return create_and_write_idmap(target_apk_path, overlay_apk_path, fd, true) == 0 ?
        EXIT_SUCCESS : EXIT_FAILURE;
}

int idmap_verify_fd(const char *target_apk_path, const char *overlay_apk_path, int fd)
{
    return !is_idmap_stale_fd(target_apk_path, overlay_apk_path, fd) ?
            EXIT_SUCCESS : EXIT_FAILURE;
}
