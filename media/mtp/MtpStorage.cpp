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
#include "MtpMediaScanner.h"

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
    return "Device Storage";
}

bool MtpStorage::scanFiles() {
    MtpMediaScanner scanner(mStorageID, mFilePath, mDatabase);
    return scanner.scanFiles();
}

}  // namespace android
