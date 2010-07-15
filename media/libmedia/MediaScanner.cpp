/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaScanner"
#include <utils/Log.h>

#include <media/mediascanner.h>

#include <sys/stat.h>
#include <dirent.h>

namespace android {

MediaScanner::MediaScanner()
    : mLocale(NULL) {
}

MediaScanner::~MediaScanner() {
    setLocale(NULL);
}

void MediaScanner::setLocale(const char *locale) {
    if (mLocale) {
        free(mLocale);
        mLocale = NULL;
    }
    if (locale) {
        mLocale = strdup(locale);
    }
}

const char *MediaScanner::locale() const {
    return mLocale;
}

status_t MediaScanner::processDirectory(
        const char *path, const char *extensions,
        MediaScannerClient &client,
        ExceptionCheck exceptionCheck, void *exceptionEnv) {
    int pathLength = strlen(path);
    if (pathLength >= PATH_MAX) {
        return UNKNOWN_ERROR;
    }
    char* pathBuffer = (char *)malloc(PATH_MAX + 1);
    if (!pathBuffer) {
        return UNKNOWN_ERROR;
    }

    int pathRemaining = PATH_MAX - pathLength;
    strcpy(pathBuffer, path);
    if (pathLength > 0 && pathBuffer[pathLength - 1] != '/') {
        pathBuffer[pathLength] = '/';
        pathBuffer[pathLength + 1] = 0;
        --pathRemaining;
    }

    client.setLocale(locale());

    status_t result =
        doProcessDirectory(
                pathBuffer, pathRemaining, extensions, client,
                exceptionCheck, exceptionEnv);

    free(pathBuffer);

    return result;
}

static bool fileMatchesExtension(const char* path, const char* extensions) {
    const char* extension = strrchr(path, '.');
    if (!extension) return false;
    ++extension;    // skip the dot
    if (extension[0] == 0) return false;

    while (extensions[0]) {
        const char* comma = strchr(extensions, ',');
        size_t length = (comma ? comma - extensions : strlen(extensions));
        if (length == strlen(extension) && strncasecmp(extension, extensions, length) == 0) return true;
        extensions += length;
        if (extensions[0] == ',') ++extensions;
    }

    return false;
}

status_t MediaScanner::doProcessDirectory(
        char *path, int pathRemaining, const char *extensions,
        MediaScannerClient &client, ExceptionCheck exceptionCheck,
        void *exceptionEnv) {
    // place to copy file or directory name
    char* fileSpot = path + strlen(path);
    struct dirent* entry;

    // ignore directories that contain a  ".nomedia" file
    if (pathRemaining >= 8 /* strlen(".nomedia") */ ) {
        strcpy(fileSpot, ".nomedia");
        if (access(path, F_OK) == 0) {
            LOGD("found .nomedia, skipping directory\n");
            fileSpot[0] = 0;
            client.addNoMediaFolder(path);
            return OK;
        }

        // restore path
        fileSpot[0] = 0;
    }

    DIR* dir = opendir(path);
    if (!dir) {
        LOGD("opendir %s failed, errno: %d", path, errno);
        return UNKNOWN_ERROR;
    }

    while ((entry = readdir(dir))) {
        const char* name = entry->d_name;

        // ignore "." and ".."
        if (name[0] == '.' && (name[1] == 0 || (name[1] == '.' && name[2] == 0))) {
            continue;
        }

        int nameLength = strlen(name);
        if (nameLength + 1 > pathRemaining) {
            // path too long!
            continue;
        }
        strcpy(fileSpot, name);

        int type = entry->d_type;
        if (type == DT_UNKNOWN) {
            // If the type is unknown, stat() the file instead.
            // This is sometimes necessary when accessing NFS mounted filesystems, but
            // could be needed in other cases well.
            struct stat statbuf;
            if (stat(path, &statbuf) == 0) {
                if (S_ISREG(statbuf.st_mode)) {
                    type = DT_REG;
                } else if (S_ISDIR(statbuf.st_mode)) {
                    type = DT_DIR;
                }
            } else {
                LOGD("stat() failed for %s: %s", path, strerror(errno) );
            }
        }
        if (type == DT_REG || type == DT_DIR) {
            if (type == DT_DIR) {
                // ignore directories with a name that starts with '.'
                // for example, the Mac ".Trashes" directory
                if (name[0] == '.') continue;

                strcat(fileSpot, "/");
                int err = doProcessDirectory(path, pathRemaining - nameLength - 1, extensions, client, exceptionCheck, exceptionEnv);
                if (err) {
                    // pass exceptions up - ignore other errors
                    if (exceptionCheck && exceptionCheck(exceptionEnv)) goto failure;
                    LOGE("Error processing '%s' - skipping\n", path);
                    continue;
                }
            } else if (fileMatchesExtension(path, extensions)) {
                struct stat statbuf;
                stat(path, &statbuf);
                if (statbuf.st_size > 0) {
                    client.scanFile(path, statbuf.st_mtime, statbuf.st_size);
                }
                if (exceptionCheck && exceptionCheck(exceptionEnv)) goto failure;
            }
        }
    }

    closedir(dir);
    return OK;
failure:
    closedir(dir);
    return -1;
}

}  // namespace android
