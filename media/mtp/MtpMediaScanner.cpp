/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "MtpMediaScanner"

#include "MtpDebug.h"
#include "MtpDatabase.h"
#include "MtpMediaScanner.h"
#include "mtp.h"

#include <sys/types.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <unistd.h>
#include <dirent.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <limits.h>

namespace android {

MtpMediaScanner::MtpMediaScanner(MtpStorageID id, const char* filePath, MtpDatabase* db)
    :   mStorageID(id),
        mFilePath(filePath),
        mDatabase(db),
        mFileList(NULL),
        mFileCount(0)
{
}

MtpMediaScanner::~MtpMediaScanner() {
}

bool MtpMediaScanner::scanFiles() {
    mDatabase->beginTransaction();
    mFileCount = 0;
    mFileList = mDatabase->getFileList(mFileCount);

    int ret = scanDirectory(mFilePath, MTP_PARENT_ROOT);

    for (int i = 0; i < mFileCount; i++) {
        MtpObjectHandle test = mFileList[i];
        if (! (test & kObjectHandleMarkBit)) {
            LOGV("delete missing file %08X", test);
            mDatabase->deleteFile(test);
        }
    }

    delete[] mFileList;
    mFileCount = 0;
    mDatabase->commitTransaction();
    return (ret == 0);
}


static const struct MediaFileTypeEntry
{
    const char*     extension;
    MtpObjectFormat format;
} sFileTypes[] =
{
    { "MP3",    MTP_FORMAT_MP3,             },
    { "M4A",    MTP_FORMAT_UNDEFINED_AUDIO, },
    { "WAV",    MTP_FORMAT_WAV,             },
    { "AMR",    MTP_FORMAT_UNDEFINED_AUDIO, },
    { "AWB",    MTP_FORMAT_UNDEFINED_AUDIO, },
    { "WMA",    MTP_FORMAT_WMA,             },
    { "OGG",    MTP_FORMAT_OGG,             },
    { "OGA",    MTP_FORMAT_UNDEFINED_AUDIO, },
    { "AAC",    MTP_FORMAT_AAC,             },
    { "MID",    MTP_FORMAT_UNDEFINED_AUDIO, },
    { "MIDI",   MTP_FORMAT_UNDEFINED_AUDIO, },
    { "XMF",    MTP_FORMAT_UNDEFINED_AUDIO, },
    { "RTTTL",  MTP_FORMAT_UNDEFINED_AUDIO, },
    { "SMF",    MTP_FORMAT_UNDEFINED_AUDIO, },
    { "IMY",    MTP_FORMAT_UNDEFINED_AUDIO, },
    { "RTX",    MTP_FORMAT_UNDEFINED_AUDIO, },
    { "OTA",    MTP_FORMAT_UNDEFINED_AUDIO, },
    { "MPEG",   MTP_FORMAT_UNDEFINED_VIDEO, },
    { "MP4",    MTP_FORMAT_UNDEFINED_VIDEO, },
    { "M4V",    MTP_FORMAT_UNDEFINED_VIDEO, },
    { "3GP",    MTP_FORMAT_UNDEFINED_VIDEO, },
    { "3GPP",   MTP_FORMAT_UNDEFINED_VIDEO, },
    { "3G2",    MTP_FORMAT_UNDEFINED_VIDEO, },
    { "3GPP2",  MTP_FORMAT_UNDEFINED_VIDEO, },
    { "WMV",    MTP_FORMAT_UNDEFINED_VIDEO, },
    { "ASF",    MTP_FORMAT_UNDEFINED_VIDEO, },
    { "JPG",    MTP_FORMAT_EXIF_JPEG,       },
    { "JPEG",   MTP_FORMAT_EXIF_JPEG,       },
    { "GIF",    MTP_FORMAT_GIF,             },
    { "PNG",    MTP_FORMAT_PNG,             },
    { "BMP",    MTP_FORMAT_BMP,             },
    { "WBMP",   MTP_FORMAT_BMP,             },
    { "M3U",    MTP_FORMAT_M3U_PLAYLIST,    },
    { "PLS",    MTP_FORMAT_PLS_PLAYLIST,    },
    { "WPL",    MTP_FORMAT_WPL_PLAYLIST,    },
};

MtpObjectFormat MtpMediaScanner::getFileFormat(const char* path)
{
    const char* extension = strrchr(path, '.');
    if (!extension)
        return MTP_FORMAT_UNDEFINED;
    extension++; // skip the dot

    for (unsigned i = 0; i < sizeof(sFileTypes) / sizeof(sFileTypes[0]); i++) {
        if (!strcasecmp(extension, sFileTypes[i].extension)) {
            return sFileTypes[i].format;
        }
    }
    return MTP_FORMAT_UNDEFINED;
}

int MtpMediaScanner::scanDirectory(const char* path, MtpObjectHandle parent)
{
    char buffer[PATH_MAX];
    struct dirent* entry;

    unsigned length = strlen(path);
    if (length > sizeof(buffer) + 2) {
        LOGE("path too long: %s", path);
    }

    DIR* dir = opendir(path);
    if (!dir) {
        LOGE("opendir %s failed, errno: %d", path, errno);
        return -1;
    }

    strncpy(buffer, path, sizeof(buffer));
    char* fileStart = buffer + length;
    // make sure we have a trailing slash
    if (fileStart[-1] != '/') {
        *(fileStart++) = '/';
    }
    int fileNameLength = sizeof(buffer) + fileStart - buffer;

    while ((entry = readdir(dir))) {
        const char* name = entry->d_name;

        // ignore "." and "..", as well as any files or directories staring with dot
        if (name[0] == '.') {
            continue;
        }
        if (strlen(name) + 1 > fileNameLength) {
            LOGE("path too long for %s", name);
            continue;
        }
        strcpy(fileStart, name);

        struct stat statbuf;
        memset(&statbuf, 0, sizeof(statbuf));
        stat(buffer, &statbuf);

        if (S_ISDIR(statbuf.st_mode)) {
            MtpObjectHandle handle = mDatabase->getObjectHandle(buffer);
            if (handle) {
                markFile(handle);
            } else {
                handle = mDatabase->addFile(buffer, MTP_FORMAT_ASSOCIATION,
                        parent, mStorageID, 0, statbuf.st_mtime);
            }
            scanDirectory(buffer, handle);
        } else if (S_ISREG(statbuf.st_mode)) {
            scanFile(buffer, parent, statbuf);
        }
    }

    closedir(dir);
    return 0;
}

void MtpMediaScanner::scanFile(const char* path, MtpObjectHandle parent, struct stat& statbuf) {
    MtpObjectFormat format = getFileFormat(path);
    // don't scan unknown file types
    if (format == MTP_FORMAT_UNDEFINED)
        return;
    MtpObjectHandle handle = mDatabase->getObjectHandle(path);
    // fixme - rescan if mod date changed
    if (handle) {
        markFile(handle);
    } else {
        mDatabase->beginTransaction();
        handle = mDatabase->addFile(path, format, parent, mStorageID,
                statbuf.st_size, statbuf.st_mtime);
        if (handle <= 0) {
            LOGE("addFile failed in MtpMediaScanner::scanFile()");
            mDatabase->rollbackTransaction();
            return;
        }
        mDatabase->commitTransaction();
    }
}

void MtpMediaScanner::markFile(MtpObjectHandle handle) {
    if (mFileList) {
        handle &= kObjectHandleIndexMask;
        // binary search for the file in mFileList
        int low = 0;
        int high = mFileCount;
        int index;

        while (low < high) {
            index = (low + high) >> 1;
            MtpObjectHandle test = (mFileList[index] & kObjectHandleIndexMask);
            if (handle < test)
                high = index;       // item is less than index
            else if (handle > test)
                low = index + 1;    // item is greater than index
            else {
                mFileList[index] |= kObjectHandleMarkBit;
                return;
            }
        }
        LOGE("file %d not found in mFileList", handle);
    }
}

}  // namespace android
