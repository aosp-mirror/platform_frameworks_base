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

#ifndef _MTP_FILE_H
#define _MTP_FILE_H

#include "MtpTypes.h"

namespace android {

class MtpClient;
class MtpDevice;
class MtpObjectInfo;

// File-like abstraction for the interactive shell.
// This can be used to represent an MTP device, storage unit or object
// (either file or association).
class MtpFile {
private:
    MtpDevice*          mDevice;
    MtpStorageID        mStorage;
    MtpObjectHandle     mHandle;
    static MtpClient*   sClient;

public:
    MtpFile(MtpDevice* device);
    MtpFile(MtpDevice* device, MtpStorageID storage);
    MtpFile(MtpDevice* device, MtpStorageID storage, MtpObjectHandle handle);
    MtpFile(MtpFile* file);
    virtual ~MtpFile();

    MtpObjectInfo* getObjectInfo();
    void print();
    void list();

    inline MtpDevice* getDevice() const { return mDevice; }

    static void init(MtpClient* client);
    static MtpFile* parsePath(MtpFile* base, char* path);
};

}

#endif // _MTP_DIRECTORY_H
