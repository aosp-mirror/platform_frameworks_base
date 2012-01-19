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
#include <cutils/properties.h>
#include <utils/Log.h>

#include <media/mediascanner.h>

#include <sys/stat.h>
#include <dirent.h>

namespace android {

MediaScanner::MediaScanner()
    : mLocale(NULL), mSkipList(NULL), mSkipIndex(NULL) {
    loadSkipList();
}

MediaScanner::~MediaScanner() {
    setLocale(NULL);
    free(mSkipList);
    free(mSkipIndex);
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

void MediaScanner::loadSkipList() {
    mSkipList = (char *)malloc(PROPERTY_VALUE_MAX * sizeof(char));
    if (mSkipList) {
      property_get("testing.mediascanner.skiplist", mSkipList, "");
    }
    if (!mSkipList || (strlen(mSkipList) == 0)) {
        free(mSkipList);
        mSkipList = NULL;
        return;
    }
    mSkipIndex = (int *)malloc(PROPERTY_VALUE_MAX * sizeof(int));
    if (mSkipIndex) {
        // dup it because strtok will modify the string
        char *skipList = strdup(mSkipList);
        if (skipList) {
            char * path = strtok(skipList, ",");
            int i = 0;
            while (path) {
                mSkipIndex[i++] = strlen(path);
                path = strtok(NULL, ",");
            }
            mSkipIndex[i] = -1;
            free(skipList);
        }
    }
}

MediaScanResult MediaScanner::processDirectory(
        const char *path, MediaScannerClient &client) {
    int pathLength = strlen(path);
    if (pathLength >= PATH_MAX) {
        return MEDIA_SCAN_RESULT_SKIPPED;
    }
    char* pathBuffer = (char *)malloc(PATH_MAX + 1);
    if (!pathBuffer) {
        return MEDIA_SCAN_RESULT_ERROR;
    }

    int pathRemaining = PATH_MAX - pathLength;
    strcpy(pathBuffer, path);
    if (pathLength > 0 && pathBuffer[pathLength - 1] != '/') {
        pathBuffer[pathLength] = '/';
        pathBuffer[pathLength + 1] = 0;
        --pathRemaining;
    }

    client.setLocale(locale());

    MediaScanResult result = doProcessDirectory(pathBuffer, pathRemaining, client, false);

    free(pathBuffer);

    return result;
}

bool MediaScanner::shouldSkipDirectory(char *path) {
    if (path && mSkipList && mSkipIndex) {
        int len = strlen(path);
        int idx = 0;
        // track the start position of next path in the comma
        // separated list obtained from getprop
        int startPos = 0;
        while (mSkipIndex[idx] != -1) {
            // no point to match path name if strlen mismatch
            if ((len == mSkipIndex[idx])
                // pick out the path segment from comma separated list
                // to compare against current path parameter
                && (strncmp(path, &mSkipList[startPos], len) == 0)) {
                return true;
            }
            startPos += mSkipIndex[idx] + 1; // extra char for the delimiter
            idx++;
        }
    }
    return false;
}

MediaScanResult MediaScanner::doProcessDirectory(
        char *path, int pathRemaining, MediaScannerClient &client, bool noMedia) {
    // place to copy file or directory name
    char* fileSpot = path + strlen(path);
    struct dirent* entry;

    if (shouldSkipDirectory(path)) {
      ALOGD("Skipping: %s", path);
      return MEDIA_SCAN_RESULT_OK;
    }

    // Treat all files as non-media in directories that contain a  ".nomedia" file
    if (pathRemaining >= 8 /* strlen(".nomedia") */ ) {
        strcpy(fileSpot, ".nomedia");
        if (access(path, F_OK) == 0) {
            ALOGV("found .nomedia, setting noMedia flag\n");
            noMedia = true;
        }

        // restore path
        fileSpot[0] = 0;
    }

    DIR* dir = opendir(path);
    if (!dir) {
        LOGW("Error opening directory '%s', skipping: %s.", path, strerror(errno));
        return MEDIA_SCAN_RESULT_SKIPPED;
    }

    MediaScanResult result = MEDIA_SCAN_RESULT_OK;
    while ((entry = readdir(dir))) {
        if (doProcessDirectoryEntry(path, pathRemaining, client, noMedia, entry, fileSpot)
                == MEDIA_SCAN_RESULT_ERROR) {
            result = MEDIA_SCAN_RESULT_ERROR;
            break;
        }
    }
    closedir(dir);
    return result;
}

MediaScanResult MediaScanner::doProcessDirectoryEntry(
        char *path, int pathRemaining, MediaScannerClient &client, bool noMedia,
        struct dirent* entry, char* fileSpot) {
    struct stat statbuf;
    const char* name = entry->d_name;

    // ignore "." and ".."
    if (name[0] == '.' && (name[1] == 0 || (name[1] == '.' && name[2] == 0))) {
        return MEDIA_SCAN_RESULT_SKIPPED;
    }

    int nameLength = strlen(name);
    if (nameLength + 1 > pathRemaining) {
        // path too long!
        return MEDIA_SCAN_RESULT_SKIPPED;
    }
    strcpy(fileSpot, name);

    int type = entry->d_type;
    if (type == DT_UNKNOWN) {
        // If the type is unknown, stat() the file instead.
        // This is sometimes necessary when accessing NFS mounted filesystems, but
        // could be needed in other cases well.
        if (stat(path, &statbuf) == 0) {
            if (S_ISREG(statbuf.st_mode)) {
                type = DT_REG;
            } else if (S_ISDIR(statbuf.st_mode)) {
                type = DT_DIR;
            }
        } else {
            ALOGD("stat() failed for %s: %s", path, strerror(errno) );
        }
    }
    if (type == DT_DIR) {
        bool childNoMedia = noMedia;
        // set noMedia flag on directories with a name that starts with '.'
        // for example, the Mac ".Trashes" directory
        if (name[0] == '.')
            childNoMedia = true;

        // report the directory to the client
        if (stat(path, &statbuf) == 0) {
            status_t status = client.scanFile(path, statbuf.st_mtime, 0,
                    true /*isDirectory*/, childNoMedia);
            if (status) {
                return MEDIA_SCAN_RESULT_ERROR;
            }
        }

        // and now process its contents
        strcat(fileSpot, "/");
        MediaScanResult result = doProcessDirectory(path, pathRemaining - nameLength - 1,
                client, childNoMedia);
        if (result == MEDIA_SCAN_RESULT_ERROR) {
            return MEDIA_SCAN_RESULT_ERROR;
        }
    } else if (type == DT_REG) {
        stat(path, &statbuf);
        status_t status = client.scanFile(path, statbuf.st_mtime, statbuf.st_size,
                false /*isDirectory*/, noMedia);
        if (status) {
            return MEDIA_SCAN_RESULT_ERROR;
        }
    }

    return MEDIA_SCAN_RESULT_OK;
}

}  // namespace android
