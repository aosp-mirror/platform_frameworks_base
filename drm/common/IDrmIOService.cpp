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

#include <stdint.h>
#include <sys/types.h>
#include <binder/Parcel.h>
#include <binder/IPCThreadState.h>
#include <drm/drm_framework_common.h>
#include "IDrmIOService.h"

using namespace android;

void BpDrmIOService::writeToFile(const String8& filePath, const String8& dataBuffer) {
    Parcel data, reply;

    data.writeInterfaceToken(IDrmIOService::getInterfaceDescriptor());
    data.writeString8(filePath);
    data.writeString8(dataBuffer);

    remote()->transact(WRITE_TO_FILE, data, &reply);
}

String8 BpDrmIOService::readFromFile(const String8& filePath) {

    Parcel data, reply;

    data.writeInterfaceToken(IDrmIOService::getInterfaceDescriptor());
    data.writeString8(filePath);

    remote()->transact(READ_FROM_FILE, data, &reply);
    return reply.readString8();
}

IMPLEMENT_META_INTERFACE(DrmIOService, "drm.IDrmIOService");

status_t BnDrmIOService::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags) {

    switch (code) {
    case WRITE_TO_FILE:
    {
        CHECK_INTERFACE(IDrmIOService, data, reply);

        writeToFile(data.readString8(), data.readString8());
        return DRM_NO_ERROR;
    }

    case READ_FROM_FILE:
    {
        CHECK_INTERFACE(IDrmIOService, data, reply);

        String8 dataBuffer = readFromFile(data.readString8());
        reply->writeString8(dataBuffer);
        return DRM_NO_ERROR;
    }

    default:
        return BBinder::onTransact(code, data, reply, flags);
    }
}

