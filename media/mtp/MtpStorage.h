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

#ifndef _MTP_STORAGE_H
#define _MTP_STORAGE_H

#include "MtpTypes.h"
#include "mtp.h"

namespace android {

class MtpDatabase;

class MtpStorage {

private:
    MtpStorageID            mStorageID;
    MtpString               mFilePath;
    MtpString               mDescription;
    uint64_t                mMaxCapacity;
    uint64_t                mMaxFileSize;
    // amount of free space to leave unallocated
    uint64_t                mReserveSpace;
    bool                    mRemovable;

public:
                            MtpStorage(MtpStorageID id, const char* filePath,
                                    const char* description, uint64_t reserveSpace,
                                    bool removable, uint64_t maxFileSize);
    virtual                 ~MtpStorage();

    inline MtpStorageID     getStorageID() const { return mStorageID; }
    int                     getType() const;
    int                     getFileSystemType() const;
    int                     getAccessCapability() const;
    uint64_t                getMaxCapacity();
    uint64_t                getFreeSpace();
    const char*             getDescription() const;
    inline const char*      getPath() const { return (const char *)mFilePath; }
    inline bool             isRemovable() const { return mRemovable; }
    inline uint64_t         getMaxFileSize() const { return mMaxFileSize; }
};

}; // namespace android

#endif // _MTP_STORAGE_H
