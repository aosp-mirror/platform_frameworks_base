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

#include "MtpDatabase.h"
#include "MtpStorage.h"

#include <sys/types.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <unistd.h>
#include <dirent.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <limits.h>


MtpStorage::MtpStorage(MtpStorageID id, const char* filePath, MtpDatabase* db)
    :   mStorageID(id),
        mFilePath(filePath),
        mDatabase(db),
        mMaxCapacity(0)
{
}

MtpStorage::~MtpStorage() {
}

int MtpStorage::getType() const {
    return MTP_STORAGE_FIXED_RAM;
}

int MtpStorage::getFileSystemType() const {
    return MTP_STORAGE_FILESYSTEM_HIERARCHICAL;
}

int MtpStorage::getAccessCapability() const {
    return MTP_STORAGE_READ_WRITE;
}

uint64_t MtpStorage::getMaxCapacity() {
    if (mMaxCapacity == 0) {
        struct statfs   stat;
        if (statfs(mFilePath, &stat))
            return -1;
        mMaxCapacity = (uint64_t)stat.f_blocks * (uint64_t)stat.f_bsize;
    }
    return mMaxCapacity;
}

uint64_t MtpStorage::getFreeSpace() {
    struct statfs   stat;
    if (statfs(mFilePath, &stat))
        return -1;
    return (uint64_t)stat.f_bavail * (uint64_t)stat.f_bsize;
}

const char* MtpStorage::getDescription() const {
    return "Phone Storage";
}

bool MtpStorage::scanFiles() {
    mDatabase->beginTransaction();
    int ret = scanDirectory(mFilePath, MTP_PARENT_ROOT);
    mDatabase->commitTransaction();
    return (ret == 0);
}

int MtpStorage::scanDirectory(const char* path, MtpObjectHandle parent)
{
    char buffer[PATH_MAX];
    struct dirent* entry;

    int length = strlen(path);
    if (length > sizeof(buffer) + 2) {
        fprintf(stderr, "path too long: %s\n", path);
    }

    DIR* dir = opendir(path);
    if (!dir) {
        fprintf(stderr, "opendir %s failed, errno: %d", path, errno);
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
            fprintf(stderr, "path too long for %s\n", name);
            continue;
        }
        strcpy(fileStart, name);

        struct stat statbuf;
        memset(&statbuf, 0, sizeof(statbuf));
        stat(buffer, &statbuf);

        if (entry->d_type == DT_DIR) {
            MtpObjectHandle handle = mDatabase->addFile(buffer, MTP_FORMAT_ASSOCIATION,
                    parent, mStorageID, 0, 0, statbuf.st_mtime);
            scanDirectory(buffer, handle);
        } else if (entry->d_type == DT_REG) {
            mDatabase->addFile(buffer, MTP_FORMAT_UNDEFINED, parent, mStorageID,
                    statbuf.st_size, 0, statbuf.st_mtime);
        }
    }

    closedir(dir);
    return 0;
}
