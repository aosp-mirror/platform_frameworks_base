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

#ifndef __IDRM_IO_SERVICE_H__
#define __IDRM_IO_SERVICE_H__

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>

namespace android {

/**
 * This is the interface class for DRM IO service.
 *
 */
class IDrmIOService : public IInterface
{
public:
    enum {
        WRITE_TO_FILE = IBinder::FIRST_CALL_TRANSACTION,
        READ_FROM_FILE
    };

public:
    DECLARE_META_INTERFACE(DrmIOService);

public:
    /**
     * Writes the data into the file path provided
     *
     * @param[in] filePath Path of the file
     * @param[in] dataBuffer Data to write
     */
    virtual void writeToFile(const String8& filePath, const String8& dataBuffer) = 0;

    /**
     * Reads the data from the file path provided
     *
     * @param[in] filePath Path of the file
     * @return Data read from the file
     */
    virtual String8 readFromFile(const String8& filePath) = 0;
};

/**
 * This is the Binder implementation class for DRM IO service.
 */
class BpDrmIOService: public BpInterface<IDrmIOService>
{
public:
    BpDrmIOService(const sp<IBinder>& impl)
            : BpInterface<IDrmIOService>(impl) {}

    virtual void writeToFile(const String8& filePath, const String8& dataBuffer);

    virtual String8 readFromFile(const String8& filePath);
};

/**
 * This is the Binder implementation class for DRM IO service.
 */
class BnDrmIOService: public BnInterface<IDrmIOService>
{
public:
    virtual status_t onTransact(
            uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags = 0);
};

};

#endif /* __IDRM_IO_SERVICE_H__ */

