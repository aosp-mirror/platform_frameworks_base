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

//#define LOG_NDEBUG 0
#define LOG_TAG "IDrmManagerService(Native)"
#include <utils/Log.h>

#include <stdint.h>
#include <sys/types.h>
#include <binder/IPCThreadState.h>

#include <drm/DrmInfo.h>
#include <drm/DrmConstraints.h>
#include <drm/DrmMetadata.h>
#include <drm/DrmRights.h>
#include <drm/DrmInfoStatus.h>
#include <drm/DrmConvertedStatus.h>
#include <drm/DrmInfoRequest.h>
#include <drm/DrmSupportInfo.h>

#include "IDrmManagerService.h"

#define INVALID_BUFFER_LENGTH -1

using namespace android;

static void writeDecryptHandleToParcelData(
        const DecryptHandle* handle, Parcel* data) {
    data->writeInt32(handle->decryptId);
    data->writeString8(handle->mimeType);
    data->writeInt32(handle->decryptApiType);
    data->writeInt32(handle->status);

    int size = handle->copyControlVector.size();
    data->writeInt32(size);
    for (int i = 0; i < size; i++) {
        data->writeInt32(handle->copyControlVector.keyAt(i));
        data->writeInt32(handle->copyControlVector.valueAt(i));
    }

    size = handle->extendedData.size();
    data->writeInt32(size);
    for (int i = 0; i < size; i++) {
        data->writeString8(handle->extendedData.keyAt(i));
        data->writeString8(handle->extendedData.valueAt(i));
    }

    if (NULL != handle->decryptInfo) {
        data->writeInt32(handle->decryptInfo->decryptBufferLength);
    } else {
        data->writeInt32(INVALID_BUFFER_LENGTH);
    }
}

static void readDecryptHandleFromParcelData(
        DecryptHandle* handle, const Parcel& data) {
    if (0 == data.dataAvail()) {
        return;
    }

    handle->decryptId = data.readInt32();
    handle->mimeType = data.readString8();
    handle->decryptApiType = data.readInt32();
    handle->status = data.readInt32();

    int size = data.readInt32();
    for (int i = 0; i < size; i++) {
        DrmCopyControl key = (DrmCopyControl)data.readInt32();
        int value = data.readInt32();
        handle->copyControlVector.add(key, value);
    }

    size = data.readInt32();
    for (int i = 0; i < size; i++) {
        String8 key = data.readString8();
        String8 value = data.readString8();
        handle->extendedData.add(key, value);
    }

    handle->decryptInfo = NULL;
    const int bufferLen = data.readInt32();
    if (INVALID_BUFFER_LENGTH != bufferLen) {
        handle->decryptInfo = new DecryptInfo();
        handle->decryptInfo->decryptBufferLength = bufferLen;
    }
}

static void clearDecryptHandle(DecryptHandle* handle) {
    if (handle == NULL) {
        return;
    }
    if (handle->decryptInfo) {
        delete handle->decryptInfo;
        handle->decryptInfo = NULL;
    }
    handle->copyControlVector.clear();
    handle->extendedData.clear();
}

int BpDrmManagerService::addUniqueId(bool isNative) {
    LOGV("add uniqueid");
    Parcel data, reply;
    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(isNative);
    remote()->transact(ADD_UNIQUEID, data, &reply);
    return reply.readInt32();
}

void BpDrmManagerService::removeUniqueId(int uniqueId) {
    LOGV("remove uniqueid");
    Parcel data, reply;
    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    remote()->transact(REMOVE_UNIQUEID, data, &reply);
}

void BpDrmManagerService::addClient(int uniqueId) {
    Parcel data, reply;
    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    remote()->transact(ADD_CLIENT, data, &reply);
}

void BpDrmManagerService::removeClient(int uniqueId) {
    Parcel data, reply;
    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    remote()->transact(REMOVE_CLIENT, data, &reply);
}

status_t BpDrmManagerService::setDrmServiceListener(
            int uniqueId, const sp<IDrmServiceListener>& drmServiceListener) {
    LOGV("setDrmServiceListener");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeStrongBinder(drmServiceListener->asBinder());
    remote()->transact(SET_DRM_SERVICE_LISTENER, data, &reply);
    return reply.readInt32();
}

status_t BpDrmManagerService::installDrmEngine(int uniqueId, const String8& drmEngineFile) {
    LOGV("Install DRM Engine");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeString8(drmEngineFile);

    remote()->transact(INSTALL_DRM_ENGINE, data, &reply);
    return reply.readInt32();
}

DrmConstraints* BpDrmManagerService::getConstraints(
            int uniqueId, const String8* path, const int action) {
    LOGV("Get Constraints");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeString8(*path);
    data.writeInt32(action);

    remote()->transact(GET_CONSTRAINTS_FROM_CONTENT, data, &reply);

    DrmConstraints* drmConstraints = NULL;
    if (0 != reply.dataAvail()) {
        //Filling Drm Constraints
        drmConstraints = new DrmConstraints();

        const int size = reply.readInt32();
        for (int index = 0; index < size; ++index) {
            const String8 key(reply.readString8());
            const int bufferSize = reply.readInt32();
            char* data = NULL;
            if (0 < bufferSize) {
                data = new char[bufferSize];
                reply.read(data, bufferSize);
            }
            drmConstraints->put(&key, data);
        }
    }
    return drmConstraints;
}

DrmMetadata* BpDrmManagerService::getMetadata(int uniqueId, const String8* path) {
    LOGV("Get Metadata");
    Parcel data, reply;
    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    DrmMetadata* drmMetadata = NULL;
    data.writeString8(*path);
    remote()->transact(GET_METADATA_FROM_CONTENT, data, &reply);

    if (0 != reply.dataAvail()) {
        //Filling Drm Metadata
        drmMetadata = new DrmMetadata();

        const int size = reply.readInt32();
        for (int index = 0; index < size; ++index) {
            const String8 key(reply.readString8());
            const int bufferSize = reply.readInt32();
            char* data = NULL;
            if (0 < bufferSize) {
                data = new char[bufferSize];
                reply.read(data, bufferSize);
            }
            drmMetadata->put(&key, data);
        }
    }
    return drmMetadata;
}

bool BpDrmManagerService::canHandle(int uniqueId, const String8& path, const String8& mimeType) {
    LOGV("Can Handle");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    data.writeString8(path);
    data.writeString8(mimeType);

    remote()->transact(CAN_HANDLE, data, &reply);

    return static_cast<bool>(reply.readInt32());
}

DrmInfoStatus* BpDrmManagerService::processDrmInfo(int uniqueId, const DrmInfo* drmInfo) {
    LOGV("Process DRM Info");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    //Filling DRM info
    data.writeInt32(drmInfo->getInfoType());
    const DrmBuffer dataBuffer = drmInfo->getData();
    const int dataBufferSize = dataBuffer.length;
    data.writeInt32(dataBufferSize);
    if (0 < dataBufferSize) {
        data.write(dataBuffer.data, dataBufferSize);
    }
    data.writeString8(drmInfo->getMimeType());

    data.writeInt32(drmInfo->getCount());
    DrmInfo::KeyIterator keyIt = drmInfo->keyIterator();

    while (keyIt.hasNext()) {
        const String8 key = keyIt.next();
        data.writeString8(key);
        const String8 value = drmInfo->get(key);
        data.writeString8((value == String8("")) ? String8("NULL") : value);
    }

    remote()->transact(PROCESS_DRM_INFO, data, &reply);

    DrmInfoStatus* drmInfoStatus = NULL;
    if (0 != reply.dataAvail()) {
        //Filling DRM Info Status
        const int statusCode = reply.readInt32();
        const int infoType = reply.readInt32();
        const String8 mimeType = reply.readString8();

        DrmBuffer* drmBuffer = NULL;
        if (0 != reply.dataAvail()) {
            const int bufferSize = reply.readInt32();
            char* data = NULL;
            if (0 < bufferSize) {
                data = new char[bufferSize];
                reply.read(data, bufferSize);
            }
            drmBuffer = new DrmBuffer(data, bufferSize);
        }
        drmInfoStatus = new DrmInfoStatus(statusCode, infoType, drmBuffer, mimeType);
    }
    return drmInfoStatus;
}

DrmInfo* BpDrmManagerService::acquireDrmInfo(int uniqueId, const DrmInfoRequest* drmInforequest) {
    LOGV("Acquire DRM Info");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    //Filling DRM Info Request
    data.writeInt32(drmInforequest->getInfoType());
    data.writeString8(drmInforequest->getMimeType());

    data.writeInt32(drmInforequest->getCount());
    DrmInfoRequest::KeyIterator keyIt = drmInforequest->keyIterator();

    while (keyIt.hasNext()) {
        const String8 key = keyIt.next();
        data.writeString8(key);
        const String8 value = drmInforequest->get(key);
        data.writeString8((value == String8("")) ? String8("NULL") : value);
    }

    remote()->transact(ACQUIRE_DRM_INFO, data, &reply);

    DrmInfo* drmInfo = NULL;
    if (0 != reply.dataAvail()) {
        //Filling DRM Info
        const int infoType = reply.readInt32();
        const int bufferSize = reply.readInt32();
        char* data = NULL;

        if (0 < bufferSize) {
            data = new char[bufferSize];
            reply.read(data, bufferSize);
        }
        drmInfo = new DrmInfo(infoType, DrmBuffer(data, bufferSize), reply.readString8());

        const int size = reply.readInt32();
        for (int index = 0; index < size; ++index) {
            const String8 key(reply.readString8());
            const String8 value(reply.readString8());
            drmInfo->put(key, (value == String8("NULL")) ? String8("") : value);
        }
    }
    return drmInfo;
}

status_t BpDrmManagerService::saveRights(
            int uniqueId, const DrmRights& drmRights,
            const String8& rightsPath, const String8& contentPath) {
    LOGV("Save Rights");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    //Filling Drm Rights
    const DrmBuffer dataBuffer = drmRights.getData();
    data.writeInt32(dataBuffer.length);
    data.write(dataBuffer.data, dataBuffer.length);

    const String8 mimeType = drmRights.getMimeType();
    data.writeString8((mimeType == String8("")) ? String8("NULL") : mimeType);

    const String8 accountId = drmRights.getAccountId();
    data.writeString8((accountId == String8("")) ? String8("NULL") : accountId);

    const String8 subscriptionId = drmRights.getSubscriptionId();
    data.writeString8((subscriptionId == String8("")) ? String8("NULL") : subscriptionId);

    data.writeString8((rightsPath == String8("")) ? String8("NULL") : rightsPath);
    data.writeString8((contentPath == String8("")) ? String8("NULL") : contentPath);

    remote()->transact(SAVE_RIGHTS, data, &reply);
    return reply.readInt32();
}

String8 BpDrmManagerService::getOriginalMimeType(int uniqueId, const String8& path) {
    LOGV("Get Original MimeType");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeString8(path);

    remote()->transact(GET_ORIGINAL_MIMETYPE, data, &reply);
    return reply.readString8();
}

int BpDrmManagerService::getDrmObjectType(
            int uniqueId, const String8& path, const String8& mimeType) {
    LOGV("Get Drm object type");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeString8(path);
    data.writeString8(mimeType);

    remote()->transact(GET_DRM_OBJECT_TYPE, data, &reply);

    return reply.readInt32();
}

int BpDrmManagerService::checkRightsStatus(int uniqueId, const String8& path, int action) {
    LOGV("checkRightsStatus");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeString8(path);
    data.writeInt32(action);

    remote()->transact(CHECK_RIGHTS_STATUS, data, &reply);

    return reply.readInt32();
}

status_t BpDrmManagerService::consumeRights(
            int uniqueId, DecryptHandle* decryptHandle, int action, bool reserve) {
    LOGV("consumeRights");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    writeDecryptHandleToParcelData(decryptHandle, &data);

    data.writeInt32(action);
    data.writeInt32(static_cast< int>(reserve));

    remote()->transact(CONSUME_RIGHTS, data, &reply);
    return reply.readInt32();
}

status_t BpDrmManagerService::setPlaybackStatus(
            int uniqueId, DecryptHandle* decryptHandle, int playbackStatus, int64_t position) {
    LOGV("setPlaybackStatus");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    writeDecryptHandleToParcelData(decryptHandle, &data);

    data.writeInt32(playbackStatus);
    data.writeInt64(position);

    remote()->transact(SET_PLAYBACK_STATUS, data, &reply);
    return reply.readInt32();
}

bool BpDrmManagerService::validateAction(
            int uniqueId, const String8& path,
            int action, const ActionDescription& description) {
    LOGV("validateAction");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeString8(path);
    data.writeInt32(action);
    data.writeInt32(description.outputType);
    data.writeInt32(description.configuration);

    remote()->transact(VALIDATE_ACTION, data, &reply);

    return static_cast<bool>(reply.readInt32());
}

status_t BpDrmManagerService::removeRights(int uniqueId, const String8& path) {
    LOGV("removeRights");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeString8(path);

    remote()->transact(REMOVE_RIGHTS, data, &reply);
    return reply.readInt32();
}

status_t BpDrmManagerService::removeAllRights(int uniqueId) {
    LOGV("removeAllRights");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    remote()->transact(REMOVE_ALL_RIGHTS, data, &reply);
    return reply.readInt32();
}

int BpDrmManagerService::openConvertSession(int uniqueId, const String8& mimeType) {
    LOGV("openConvertSession");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeString8(mimeType);

    remote()->transact(OPEN_CONVERT_SESSION, data, &reply);
    return reply.readInt32();
}

DrmConvertedStatus* BpDrmManagerService::convertData(
            int uniqueId, int convertId, const DrmBuffer* inputData) {
    LOGV("convertData");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeInt32(convertId);
    data.writeInt32(inputData->length);
    data.write(inputData->data, inputData->length);

    remote()->transact(CONVERT_DATA, data, &reply);

    DrmConvertedStatus* drmConvertedStatus = NULL;

    if (0 != reply.dataAvail()) {
        //Filling DRM Converted Status
        const int statusCode = reply.readInt32();
        const off64_t offset = reply.readInt64();

        DrmBuffer* convertedData = NULL;
        if (0 != reply.dataAvail()) {
            const int bufferSize = reply.readInt32();
            char* data = NULL;
            if (0 < bufferSize) {
                data = new char[bufferSize];
                reply.read(data, bufferSize);
            }
            convertedData = new DrmBuffer(data, bufferSize);
        }
        drmConvertedStatus = new DrmConvertedStatus(statusCode, convertedData, offset);
    }
    return drmConvertedStatus;
}

DrmConvertedStatus* BpDrmManagerService::closeConvertSession(int uniqueId, int convertId) {
    LOGV("closeConvertSession");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeInt32(convertId);

    remote()->transact(CLOSE_CONVERT_SESSION, data, &reply);

    DrmConvertedStatus* drmConvertedStatus = NULL;

    if (0 != reply.dataAvail()) {
        //Filling DRM Converted Status
        const int statusCode = reply.readInt32();
        const off64_t offset = reply.readInt64();

        DrmBuffer* convertedData = NULL;
        if (0 != reply.dataAvail()) {
            const int bufferSize = reply.readInt32();
            char* data = NULL;
            if (0 < bufferSize) {
                data = new char[bufferSize];
                reply.read(data, bufferSize);
            }
            convertedData = new DrmBuffer(data, bufferSize);
        }
        drmConvertedStatus = new DrmConvertedStatus(statusCode, convertedData, offset);
    }
    return drmConvertedStatus;
}

status_t BpDrmManagerService::getAllSupportInfo(
            int uniqueId, int* length, DrmSupportInfo** drmSupportInfoArray) {
    LOGV("Get All Support Info");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    remote()->transact(GET_ALL_SUPPORT_INFO, data, &reply);

    //Filling DRM Support Info
    const int arraySize = reply.readInt32();
    if (0 < arraySize) {
        *drmSupportInfoArray = new DrmSupportInfo[arraySize];

        for (int index = 0; index < arraySize; ++index) {
            DrmSupportInfo drmSupportInfo;

            const int fileSuffixVectorSize = reply.readInt32();
            for (int i = 0; i < fileSuffixVectorSize; ++i) {
                drmSupportInfo.addFileSuffix(reply.readString8());
            }

            const int mimeTypeVectorSize = reply.readInt32();
            for (int i = 0; i < mimeTypeVectorSize; ++i) {
                drmSupportInfo.addMimeType(reply.readString8());
            }

            drmSupportInfo.setDescription(reply.readString8());
            (*drmSupportInfoArray)[index] = drmSupportInfo;
        }
    }
    *length = arraySize;
    return reply.readInt32();
}

DecryptHandle* BpDrmManagerService::openDecryptSession(
            int uniqueId, int fd, off64_t offset, off64_t length) {
    LOGV("Entering BpDrmManagerService::openDecryptSession");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeFileDescriptor(fd);
    data.writeInt64(offset);
    data.writeInt64(length);

    remote()->transact(OPEN_DECRYPT_SESSION, data, &reply);

    DecryptHandle* handle = NULL;
    if (0 != reply.dataAvail()) {
        handle = new DecryptHandle();
        readDecryptHandleFromParcelData(handle, reply);
    }
    return handle;
}

DecryptHandle* BpDrmManagerService::openDecryptSession(int uniqueId, const char* uri) {
    LOGV("Entering BpDrmManagerService::openDecryptSession");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeString8(String8(uri));

    remote()->transact(OPEN_DECRYPT_SESSION_FROM_URI, data, &reply);

    DecryptHandle* handle = NULL;
    if (0 != reply.dataAvail()) {
        handle = new DecryptHandle();
        readDecryptHandleFromParcelData(handle, reply);
    } else {
        LOGV("no decryptHandle is generated in service side");
    }
    return handle;
}

status_t BpDrmManagerService::closeDecryptSession(int uniqueId, DecryptHandle* decryptHandle) {
    LOGV("closeDecryptSession");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    writeDecryptHandleToParcelData(decryptHandle, &data);

    remote()->transact(CLOSE_DECRYPT_SESSION, data, &reply);

    return reply.readInt32();
}

status_t BpDrmManagerService::initializeDecryptUnit(
            int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* headerInfo) {
    LOGV("initializeDecryptUnit");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    writeDecryptHandleToParcelData(decryptHandle, &data);

    data.writeInt32(decryptUnitId);

    data.writeInt32(headerInfo->length);
    data.write(headerInfo->data, headerInfo->length);

    remote()->transact(INITIALIZE_DECRYPT_UNIT, data, &reply);
    return reply.readInt32();
}

status_t BpDrmManagerService::decrypt(
            int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId,
            const DrmBuffer* encBuffer, DrmBuffer** decBuffer, DrmBuffer* IV) {
    LOGV("decrypt");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    writeDecryptHandleToParcelData(decryptHandle, &data);

    data.writeInt32(decryptUnitId);
    data.writeInt32((*decBuffer)->length);

    data.writeInt32(encBuffer->length);
    data.write(encBuffer->data, encBuffer->length);

    if (NULL != IV) {
        data.writeInt32(IV->length);
        data.write(IV->data, IV->length);
    }

    remote()->transact(DECRYPT, data, &reply);

    const status_t status = reply.readInt32();
    LOGV("Return value of decrypt() is %d", status);

    const int size = reply.readInt32();
    (*decBuffer)->length = size;
    reply.read((void *)(*decBuffer)->data, size);

    return status;
}

status_t BpDrmManagerService::finalizeDecryptUnit(
            int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId) {
    LOGV("finalizeDecryptUnit");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    writeDecryptHandleToParcelData(decryptHandle, &data);

    data.writeInt32(decryptUnitId);

    remote()->transact(FINALIZE_DECRYPT_UNIT, data, &reply);
    return reply.readInt32();
}

ssize_t BpDrmManagerService::pread(
            int uniqueId, DecryptHandle* decryptHandle, void* buffer,
            ssize_t numBytes, off64_t offset) {
    LOGV("read");
    Parcel data, reply;
    int result;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    writeDecryptHandleToParcelData(decryptHandle, &data);

    data.writeInt32(numBytes);
    data.writeInt64(offset);

    remote()->transact(PREAD, data, &reply);
    result = reply.readInt32();
    if (0 < result) {
        reply.read(buffer, result);
    }
    return result;
}

IMPLEMENT_META_INTERFACE(DrmManagerService, "drm.IDrmManagerService");

status_t BnDrmManagerService::onTransact(
            uint32_t code, const Parcel& data,
            Parcel* reply, uint32_t flags) {
    LOGV("Entering BnDrmManagerService::onTransact with code %d", code);

    switch (code) {
    case ADD_UNIQUEID:
    {
        LOGV("BnDrmManagerService::onTransact :ADD_UNIQUEID");
        CHECK_INTERFACE(IDrmManagerService, data, reply);
        int uniqueId = addUniqueId(data.readInt32());
        reply->writeInt32(uniqueId);
        return DRM_NO_ERROR;
    }

    case REMOVE_UNIQUEID:
    {
        LOGV("BnDrmManagerService::onTransact :REMOVE_UNIQUEID");
        CHECK_INTERFACE(IDrmManagerService, data, reply);
        removeUniqueId(data.readInt32());
        return DRM_NO_ERROR;
    }

    case ADD_CLIENT:
    {
        LOGV("BnDrmManagerService::onTransact :ADD_CLIENT");
        CHECK_INTERFACE(IDrmManagerService, data, reply);
        addClient(data.readInt32());
        return DRM_NO_ERROR;
    }

    case REMOVE_CLIENT:
    {
        LOGV("BnDrmManagerService::onTransact :REMOVE_CLIENT");
        CHECK_INTERFACE(IDrmManagerService, data, reply);
        removeClient(data.readInt32());
        return DRM_NO_ERROR;
    }

    case SET_DRM_SERVICE_LISTENER:
    {
        LOGV("BnDrmManagerService::onTransact :SET_DRM_SERVICE_LISTENER");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const sp<IDrmServiceListener> drmServiceListener
            = interface_cast<IDrmServiceListener> (data.readStrongBinder());

        status_t status = setDrmServiceListener(uniqueId, drmServiceListener);

        reply->writeInt32(status);
        return DRM_NO_ERROR;
    }

    case INSTALL_DRM_ENGINE:
    {
        LOGV("BnDrmManagerService::onTransact :INSTALL_DRM_ENGINE");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 engineFile = data.readString8();
        status_t status = installDrmEngine(uniqueId, engineFile);

        reply->writeInt32(status);
        return DRM_NO_ERROR;
    }

    case GET_CONSTRAINTS_FROM_CONTENT:
    {
        LOGV("BnDrmManagerService::onTransact :GET_CONSTRAINTS_FROM_CONTENT");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 path = data.readString8();

        DrmConstraints* drmConstraints
            = getConstraints(uniqueId, &path, data.readInt32());

        if (NULL != drmConstraints) {
            //Filling DRM Constraints contents
            reply->writeInt32(drmConstraints->getCount());

            DrmConstraints::KeyIterator keyIt = drmConstraints->keyIterator();
            while (keyIt.hasNext()) {
                const String8 key = keyIt.next();
                reply->writeString8(key);
                const char* value = drmConstraints->getAsByteArray(&key);
                int bufferSize = 0;
                if (NULL != value) {
                    bufferSize = strlen(value);
                }
                reply->writeInt32(bufferSize + 1);
                reply->write(value, bufferSize + 1);
            }
        }
        delete drmConstraints; drmConstraints = NULL;
        return DRM_NO_ERROR;
    }

    case GET_METADATA_FROM_CONTENT:
    {
        LOGV("BnDrmManagerService::onTransact :GET_METADATA_FROM_CONTENT");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 path = data.readString8();

        DrmMetadata* drmMetadata = getMetadata(uniqueId, &path);
        if (NULL != drmMetadata) {
            //Filling DRM Metadata contents
            reply->writeInt32(drmMetadata->getCount());

            DrmMetadata::KeyIterator keyIt = drmMetadata->keyIterator();
            while (keyIt.hasNext()) {
                const String8 key = keyIt.next();
                reply->writeString8(key);
                const char* value = drmMetadata->getAsByteArray(&key);
                int bufferSize = 0;
                if (NULL != value) {
                    bufferSize = strlen(value);
                    reply->writeInt32(bufferSize + 1);
                    reply->write(value, bufferSize + 1);
                } else {
                    reply->writeInt32(0);
                }
            }
        }
        delete drmMetadata; drmMetadata = NULL;
        return NO_ERROR;
    }

    case CAN_HANDLE:
    {
        LOGV("BnDrmManagerService::onTransact :CAN_HANDLE");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 path = data.readString8();
        const String8 mimeType = data.readString8();

        bool result = canHandle(uniqueId, path, mimeType);

        reply->writeInt32(result);
        return DRM_NO_ERROR;
    }

    case PROCESS_DRM_INFO:
    {
        LOGV("BnDrmManagerService::onTransact :PROCESS_DRM_INFO");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        //Filling DRM info
        const int infoType = data.readInt32();
        const int bufferSize = data.readInt32();
        char* buffer = NULL;
        if (0 < bufferSize) {
            buffer = (char *)data.readInplace(bufferSize);
        }
        const DrmBuffer drmBuffer(buffer, bufferSize);
        DrmInfo* drmInfo = new DrmInfo(infoType, drmBuffer, data.readString8());

        const int size = data.readInt32();
        for (int index = 0; index < size; ++index) {
            const String8 key(data.readString8());
            const String8 value(data.readString8());
            drmInfo->put(key, (value == String8("NULL")) ? String8("") : value);
        }

        DrmInfoStatus* drmInfoStatus = processDrmInfo(uniqueId, drmInfo);

        if (NULL != drmInfoStatus) {
            //Filling DRM Info Status contents
            reply->writeInt32(drmInfoStatus->statusCode);
            reply->writeInt32(drmInfoStatus->infoType);
            reply->writeString8(drmInfoStatus->mimeType);

            if (NULL != drmInfoStatus->drmBuffer) {
                const DrmBuffer* drmBuffer = drmInfoStatus->drmBuffer;
                const int bufferSize = drmBuffer->length;
                reply->writeInt32(bufferSize);
                if (0 < bufferSize) {
                    reply->write(drmBuffer->data, bufferSize);
                }
                delete [] drmBuffer->data;
                delete drmBuffer; drmBuffer = NULL;
            }
        }
        delete drmInfo; drmInfo = NULL;
        delete drmInfoStatus; drmInfoStatus = NULL;
        return DRM_NO_ERROR;
    }

    case ACQUIRE_DRM_INFO:
    {
        LOGV("BnDrmManagerService::onTransact :ACQUIRE_DRM_INFO");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        //Filling DRM info Request
        const int infoType = data.readInt32();
        const String8 mimeType = data.readString8();
        DrmInfoRequest* drmInfoRequest = new DrmInfoRequest(infoType, mimeType);

        const int size = data.readInt32();
        for (int index = 0; index < size; ++index) {
            const String8 key(data.readString8());
            const String8 value(data.readString8());
            drmInfoRequest->put(key, (value == String8("NULL")) ? String8("") : value);
        }

        DrmInfo* drmInfo = acquireDrmInfo(uniqueId, drmInfoRequest);

        if (NULL != drmInfo) {
            //Filling DRM Info
            const DrmBuffer drmBuffer = drmInfo->getData();
            reply->writeInt32(drmInfo->getInfoType());

            const int bufferSize = drmBuffer.length;
            reply->writeInt32(bufferSize);
            if (0 < bufferSize) {
                reply->write(drmBuffer.data, bufferSize);
            }
            reply->writeString8(drmInfo->getMimeType());
            reply->writeInt32(drmInfo->getCount());

            DrmInfo::KeyIterator keyIt = drmInfo->keyIterator();
            while (keyIt.hasNext()) {
                const String8 key = keyIt.next();
                reply->writeString8(key);
                const String8 value = drmInfo->get(key);
                reply->writeString8((value == String8("")) ? String8("NULL") : value);
            }
            delete [] drmBuffer.data;
        }
        delete drmInfoRequest; drmInfoRequest = NULL;
        delete drmInfo; drmInfo = NULL;
        return DRM_NO_ERROR;
    }

    case SAVE_RIGHTS:
    {
        LOGV("BnDrmManagerService::onTransact :SAVE_RIGHTS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        //Filling DRM Rights
        const int bufferSize = data.readInt32();
        const DrmBuffer drmBuffer((char *)data.readInplace(bufferSize), bufferSize);

        const String8 mimeType(data.readString8());
        const String8 accountId(data.readString8());
        const String8 subscriptionId(data.readString8());
        const String8 rightsPath(data.readString8());
        const String8 contentPath(data.readString8());

        DrmRights drmRights(drmBuffer,
                            ((mimeType == String8("NULL")) ? String8("") : mimeType),
                            ((accountId == String8("NULL")) ? String8("") : accountId),
                            ((subscriptionId == String8("NULL")) ? String8("") : subscriptionId));

        const status_t status = saveRights(uniqueId, drmRights,
                            ((rightsPath == String8("NULL")) ? String8("") : rightsPath),
                            ((contentPath == String8("NULL")) ? String8("") : contentPath));

        reply->writeInt32(status);
        return DRM_NO_ERROR;
    }

    case GET_ORIGINAL_MIMETYPE:
    {
        LOGV("BnDrmManagerService::onTransact :GET_ORIGINAL_MIMETYPE");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 path = data.readString8();
        const String8 originalMimeType = getOriginalMimeType(uniqueId, path);

        reply->writeString8(originalMimeType);
        return DRM_NO_ERROR;
    }

    case GET_DRM_OBJECT_TYPE:
    {
        LOGV("BnDrmManagerService::onTransact :GET_DRM_OBJECT_TYPE");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 path = data.readString8();
        const String8 mimeType = data.readString8();
        const int drmObjectType = getDrmObjectType(uniqueId, path, mimeType);

        reply->writeInt32(drmObjectType);
        return DRM_NO_ERROR;
    }

    case CHECK_RIGHTS_STATUS:
    {
        LOGV("BnDrmManagerService::onTransact :CHECK_RIGHTS_STATUS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 path = data.readString8();
        const int action = data.readInt32();
        const int result = checkRightsStatus(uniqueId, path, action);

        reply->writeInt32(result);
        return DRM_NO_ERROR;
    }

    case CONSUME_RIGHTS:
    {
        LOGV("BnDrmManagerService::onTransact :CONSUME_RIGHTS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle handle;
        readDecryptHandleFromParcelData(&handle, data);

        const int action = data.readInt32();
        const bool reserve = static_cast<bool>(data.readInt32());
        const status_t status
            = consumeRights(uniqueId, &handle, action, reserve);
        reply->writeInt32(status);

        clearDecryptHandle(&handle);
        return DRM_NO_ERROR;
    }

    case SET_PLAYBACK_STATUS:
    {
        LOGV("BnDrmManagerService::onTransact :SET_PLAYBACK_STATUS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle handle;
        readDecryptHandleFromParcelData(&handle, data);

        const int playbackStatus = data.readInt32();
        const int64_t position = data.readInt64();
        const status_t status
            = setPlaybackStatus(uniqueId, &handle, playbackStatus, position);
        reply->writeInt32(status);

        clearDecryptHandle(&handle);
        return DRM_NO_ERROR;
    }

    case VALIDATE_ACTION:
    {
        LOGV("BnDrmManagerService::onTransact :VALIDATE_ACTION");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 path = data.readString8();
        const int action = data.readInt32();
        const int outputType = data.readInt32();
        const int configuration = data.readInt32();
        bool result = validateAction(uniqueId, path, action,
                ActionDescription(outputType, configuration));

        reply->writeInt32(result);
        return DRM_NO_ERROR;
    }

    case REMOVE_RIGHTS:
    {
        LOGV("BnDrmManagerService::onTransact :REMOVE_RIGHTS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        int uniqueId = data.readInt32();
        String8 path = data.readString8();
        const status_t status = removeRights(uniqueId, path);
        reply->writeInt32(status);

        return DRM_NO_ERROR;
    }

    case REMOVE_ALL_RIGHTS:
    {
        LOGV("BnDrmManagerService::onTransact :REMOVE_ALL_RIGHTS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const status_t status = removeAllRights(data.readInt32());
        reply->writeInt32(status);

        return DRM_NO_ERROR;
    }

    case OPEN_CONVERT_SESSION:
    {
        LOGV("BnDrmManagerService::onTransact :OPEN_CONVERT_SESSION");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 mimeType = data.readString8();
        const int convertId = openConvertSession(uniqueId, mimeType);

        reply->writeInt32(convertId);
        return DRM_NO_ERROR;
    }

    case CONVERT_DATA:
    {
        LOGV("BnDrmManagerService::onTransact :CONVERT_DATA");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const int convertId = data.readInt32();

        //Filling input data
        const int bufferSize = data.readInt32();
        DrmBuffer* inputData = new DrmBuffer((char *)data.readInplace(bufferSize), bufferSize);

        DrmConvertedStatus*    drmConvertedStatus = convertData(uniqueId, convertId, inputData);

        if (NULL != drmConvertedStatus) {
            //Filling Drm Converted Ststus
            reply->writeInt32(drmConvertedStatus->statusCode);
            reply->writeInt64(drmConvertedStatus->offset);

            if (NULL != drmConvertedStatus->convertedData) {
                const DrmBuffer* convertedData = drmConvertedStatus->convertedData;
                const int bufferSize = convertedData->length;
                reply->writeInt32(bufferSize);
                if (0 < bufferSize) {
                    reply->write(convertedData->data, bufferSize);
                }
                delete [] convertedData->data;
                delete convertedData; convertedData = NULL;
            }
        }
        delete inputData; inputData = NULL;
        delete drmConvertedStatus; drmConvertedStatus = NULL;
        return DRM_NO_ERROR;
    }

    case CLOSE_CONVERT_SESSION:
    {
        LOGV("BnDrmManagerService::onTransact :CLOSE_CONVERT_SESSION");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const int convertId = data.readInt32();
        DrmConvertedStatus* drmConvertedStatus
            = closeConvertSession(uniqueId, convertId);

        if (NULL != drmConvertedStatus) {
            //Filling Drm Converted Ststus
            reply->writeInt32(drmConvertedStatus->statusCode);
            reply->writeInt64(drmConvertedStatus->offset);

            if (NULL != drmConvertedStatus->convertedData) {
                const DrmBuffer* convertedData = drmConvertedStatus->convertedData;
                const int bufferSize = convertedData->length;
                reply->writeInt32(bufferSize);
                if (0 < bufferSize) {
                    reply->write(convertedData->data, bufferSize);
                }
                delete [] convertedData->data;
                delete convertedData; convertedData = NULL;
            }
        }
        delete drmConvertedStatus; drmConvertedStatus = NULL;
        return DRM_NO_ERROR;
    }

    case GET_ALL_SUPPORT_INFO:
    {
        LOGV("BnDrmManagerService::onTransact :GET_ALL_SUPPORT_INFO");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        int length = 0;
        DrmSupportInfo* drmSupportInfoArray = NULL;

        status_t status = getAllSupportInfo(uniqueId, &length, &drmSupportInfoArray);

        reply->writeInt32(length);
        for (int i = 0; i < length; ++i) {
            DrmSupportInfo drmSupportInfo = drmSupportInfoArray[i];

            reply->writeInt32(drmSupportInfo.getFileSuffixCount());
            DrmSupportInfo::FileSuffixIterator fileSuffixIt
                = drmSupportInfo.getFileSuffixIterator();
            while (fileSuffixIt.hasNext()) {
                reply->writeString8(fileSuffixIt.next());
            }

            reply->writeInt32(drmSupportInfo.getMimeTypeCount());
            DrmSupportInfo::MimeTypeIterator mimeTypeIt = drmSupportInfo.getMimeTypeIterator();
            while (mimeTypeIt.hasNext()) {
                reply->writeString8(mimeTypeIt.next());
            }
            reply->writeString8(drmSupportInfo.getDescription());
        }
        delete [] drmSupportInfoArray; drmSupportInfoArray = NULL;
        reply->writeInt32(status);
        return DRM_NO_ERROR;
    }

    case OPEN_DECRYPT_SESSION:
    {
        LOGV("BnDrmManagerService::onTransact :OPEN_DECRYPT_SESSION");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const int fd = data.readFileDescriptor();

        const off64_t offset = data.readInt64();
        const off64_t length = data.readInt64();
        DecryptHandle* handle
            = openDecryptSession(uniqueId, fd, offset, length);

        if (NULL != handle) {
            writeDecryptHandleToParcelData(handle, reply);
            clearDecryptHandle(handle);
            delete handle; handle = NULL;
        }
        return DRM_NO_ERROR;
    }

    case OPEN_DECRYPT_SESSION_FROM_URI:
    {
        LOGV("BnDrmManagerService::onTransact :OPEN_DECRYPT_SESSION_FROM_URI");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 uri = data.readString8();

        DecryptHandle* handle = openDecryptSession(uniqueId, uri.string());

        if (NULL != handle) {
            writeDecryptHandleToParcelData(handle, reply);

            clearDecryptHandle(handle);
            delete handle; handle = NULL;
        } else {
            LOGV("NULL decryptHandle is returned");
        }
        return DRM_NO_ERROR;
    }

    case CLOSE_DECRYPT_SESSION:
    {
        LOGV("BnDrmManagerService::onTransact :CLOSE_DECRYPT_SESSION");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle* handle = new DecryptHandle();
        readDecryptHandleFromParcelData(handle, data);

        const status_t status = closeDecryptSession(uniqueId, handle);
        reply->writeInt32(status);
        return DRM_NO_ERROR;
    }

    case INITIALIZE_DECRYPT_UNIT:
    {
        LOGV("BnDrmManagerService::onTransact :INITIALIZE_DECRYPT_UNIT");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle handle;
        readDecryptHandleFromParcelData(&handle, data);

        const int decryptUnitId = data.readInt32();

        //Filling Header info
        const int bufferSize = data.readInt32();
        DrmBuffer* headerInfo = NULL;
        headerInfo = new DrmBuffer((char *)data.readInplace(bufferSize), bufferSize);

        const status_t status
            = initializeDecryptUnit(uniqueId, &handle, decryptUnitId, headerInfo);
        reply->writeInt32(status);

        clearDecryptHandle(&handle);
        delete headerInfo; headerInfo = NULL;
        return DRM_NO_ERROR;
    }

    case DECRYPT:
    {
        LOGV("BnDrmManagerService::onTransact :DECRYPT");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle handle;
        readDecryptHandleFromParcelData(&handle, data);

        const int decryptUnitId = data.readInt32();
        const int decBufferSize = data.readInt32();

        const int encBufferSize = data.readInt32();
        DrmBuffer* encBuffer
            = new DrmBuffer((char *)data.readInplace(encBufferSize), encBufferSize);

        char* buffer = NULL;
        buffer = new char[decBufferSize];
        DrmBuffer* decBuffer = new DrmBuffer(buffer, decBufferSize);

        DrmBuffer* IV = NULL;
        if (0 != data.dataAvail()) {
            const int ivBufferlength = data.readInt32();
            IV = new DrmBuffer((char *)data.readInplace(ivBufferlength), ivBufferlength);
        }

        const status_t status
            = decrypt(uniqueId, &handle, decryptUnitId, encBuffer, &decBuffer, IV);

        reply->writeInt32(status);

        const int size = decBuffer->length;
        reply->writeInt32(size);
        reply->write(decBuffer->data, size);

        clearDecryptHandle(&handle);
        delete encBuffer; encBuffer = NULL;
        delete decBuffer; decBuffer = NULL;
        delete [] buffer; buffer = NULL;
        delete IV; IV = NULL;
        return DRM_NO_ERROR;
    }

    case FINALIZE_DECRYPT_UNIT:
    {
        LOGV("BnDrmManagerService::onTransact :FINALIZE_DECRYPT_UNIT");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle handle;
        readDecryptHandleFromParcelData(&handle, data);

        const status_t status = finalizeDecryptUnit(uniqueId, &handle, data.readInt32());
        reply->writeInt32(status);

        clearDecryptHandle(&handle);
        return DRM_NO_ERROR;
    }

    case PREAD:
    {
        LOGV("BnDrmManagerService::onTransact :READ");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle handle;
        readDecryptHandleFromParcelData(&handle, data);

        const int numBytes = data.readInt32();
        char* buffer = new char[numBytes];

        const off64_t offset = data.readInt64();

        ssize_t result = pread(uniqueId, &handle, buffer, numBytes, offset);
        reply->writeInt32(result);
        if (0 < result) {
            reply->write(buffer, result);
        }

        clearDecryptHandle(&handle);
        delete [] buffer, buffer = NULL;
        return DRM_NO_ERROR;
    }

    default:
        return BBinder::onTransact(code, data, reply, flags);
    }
}

