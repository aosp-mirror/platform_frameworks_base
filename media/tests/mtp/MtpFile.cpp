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

#include "MtpClient.h"
#include "MtpDevice.h"
#include "MtpDeviceInfo.h"
#include "MtpObjectInfo.h"
#include "MtpStorage.h"
#include "MtpUtils.h"

#include "MtpFile.h"

namespace android {

MtpClient* MtpFile::sClient = NULL;

MtpFile::MtpFile(MtpDevice* device)
    :   mDevice(device),
        mStorage(0),
        mHandle(0)
{
}

MtpFile::MtpFile(MtpDevice* device, MtpStorageID storage)
    :   mDevice(device),
        mStorage(storage),
        mHandle(0)
{
}

MtpFile::MtpFile(MtpDevice* device, MtpStorageID storage, MtpObjectHandle handle)
    :   mDevice(device),
        mStorage(storage),
        mHandle(handle)
{
}

MtpFile::MtpFile(MtpFile* file)
    :   mDevice(file->mDevice),
        mStorage(file->mStorage),
        mHandle(file->mHandle)
{
}

MtpFile::~MtpFile() {
}

void MtpFile::print() {
    if (mHandle) {

    } else if (mStorage) {
        printf("%x\n", mStorage);
    } else {
        int id = mDevice->getID();
        MtpDeviceInfo* info = mDevice->getDeviceInfo();
        if (info)
            printf("%d\t%s %s %s\n", id, info->mManufacturer, info->mModel, info->mSerial);
        else
            printf("%d\t(no device info available)\n", id);
        delete info;
    }
}

MtpObjectInfo* MtpFile::getObjectInfo() {
    return mDevice->getObjectInfo(mHandle);
}

void MtpFile::list() {
    if (mStorage) {
        MtpObjectHandleList* handles = mDevice->getObjectHandles(mStorage, 0,
                                                (mHandle ? mHandle : -1));
        if (handles) {
            for (int i = 0; i < handles->size(); i++) {
                MtpObjectHandle handle = (*handles)[i];
                MtpObjectInfo* info = mDevice->getObjectInfo(handle);
                if (info) {
                    char modified[100];
                    struct tm tm;

                    gmtime_r(&info->mDateModified, &tm);
                    strftime(modified, sizeof(modified), "%a %b %e %H:%M:%S GMT %Y", &tm);
                    printf("%s Handle: %d Format: %04X Size: %d Modified: %s\n",
                            info->mName, handle, info->mFormat, info->mCompressedSize, modified);
                    delete info;
                }
            }
            delete handles;
        }
    } else {
        // list storage units for device
        MtpStorageIDList* storageList = mDevice->getStorageIDs();
        for (int i = 0; i < storageList->size(); i++) {
            MtpStorageID storageID = (*storageList)[i];
            printf("%x\n", storageID);
        }
    }
}

void MtpFile::init(MtpClient* client) {
    sClient = client;
}

MtpFile* MtpFile::parsePath(MtpFile* base, char* path) {
    MtpDevice* device = NULL;
    MtpStorageID storage = 0;
    MtpObjectHandle handle = 0;

    if (path[0] != '/' && base) {
        device = base->mDevice;
        storage = base->mStorage;
        handle = base->mHandle;
    }

    // parse an absolute path
    if (path[0] == '/')
        path++;
    char* tok = strtok(path, "/");
    while (tok) {
        if (storage) {
            // find child of current handle
            MtpObjectHandleList* handles = device->getObjectHandles(storage, 0,
                                                    (handle ? handle : -1));
            MtpObjectHandle childHandle = 0;

            if (handles) {
                for (int i = 0; i < handles->size() && !childHandle; i++) {
                    MtpObjectHandle handle = (*handles)[i];
                    MtpObjectInfo* info = device->getObjectInfo(handle);
                    if (info && !strcmp(tok, info->mName))
                        childHandle = handle;
                    delete info;
                }
                delete handles;
            }
            if (childHandle)
                handle = childHandle;
            else
                return NULL;
        } else if (device) {
            unsigned int id;
            // find storage for the device
            if (sscanf(tok, "%x", &id) == 1) {
                MtpStorageIDList* storageList = device->getStorageIDs();
                bool found = false;
                for (int i = 0; i < storageList->size(); i++) {
                    if ((*storageList)[i] == id) {
                        found = true;
                        break;
                    }
                }
                if (found)
                    storage = id;
                else
                    return NULL;
            }
        } else {
            // find device
            unsigned int id;
            if (sscanf(tok, "%d", &id) == 1)
                device = sClient->getDevice(id);
            if (!device)
                return NULL;
        }

        tok = strtok(NULL, "/");
    }

    if (device)
        return new MtpFile(device, storage, handle);
    else
        return NULL;
}

}
