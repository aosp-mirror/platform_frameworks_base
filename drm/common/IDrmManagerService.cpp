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
#include <drm/DrmRights.h>
#include <drm/DrmInfoStatus.h>
#include <drm/DrmConvertedStatus.h>
#include <drm/DrmInfoRequest.h>
#include <drm/DrmSupportInfo.h>

#include "IDrmManagerService.h"

#define INVALID_BUFFER_LENGTH -1

using namespace android;

int BpDrmManagerService::addUniqueId(int uniqueId) {
    LOGV("add uniqueid");
    Parcel data, reply;
    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
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

status_t BpDrmManagerService::loadPlugIns(int uniqueId) {
    LOGV("load plugins");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    remote()->transact(LOAD_PLUGINS, data, &reply);
    return reply.readInt32();
}

status_t BpDrmManagerService::loadPlugIns(int uniqueId, const String8& plugInDirPath) {
    LOGV("load plugins from path");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);
    data.writeString8(plugInDirPath);

    remote()->transact(LOAD_PLUGINS_FROM_PATH, data, &reply);
    return reply.readInt32();
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

status_t BpDrmManagerService::unloadPlugIns(int uniqueId) {
    LOGV("unload plugins");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    remote()->transact(UNLOAD_PLUGINS, data, &reply);
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
        drmInfoStatus = new DrmInfoStatus(statusCode, drmBuffer, mimeType);
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

    data.writeInt32(decryptHandle->decryptId);
    data.writeString8(decryptHandle->mimeType);
    data.writeInt32(decryptHandle->decryptApiType);
    data.writeInt32(decryptHandle->status);

    if (NULL != decryptHandle->decryptInfo) {
        data.writeInt32(decryptHandle->decryptInfo->decryptBufferLength);
    } else {
        data.writeInt32(INVALID_BUFFER_LENGTH);
    }

    data.writeInt32(action);
    data.writeInt32(static_cast< int>(reserve));

    remote()->transact(CONSUME_RIGHTS, data, &reply);
    return reply.readInt32();
}

status_t BpDrmManagerService::setPlaybackStatus(
            int uniqueId, DecryptHandle* decryptHandle, int playbackStatus, int position) {
    LOGV("setPlaybackStatus");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    data.writeInt32(decryptHandle->decryptId);
    data.writeString8(decryptHandle->mimeType);
    data.writeInt32(decryptHandle->decryptApiType);
    data.writeInt32(decryptHandle->status);

    if (NULL != decryptHandle->decryptInfo) {
        data.writeInt32(decryptHandle->decryptInfo->decryptBufferLength);
    } else {
        data.writeInt32(INVALID_BUFFER_LENGTH);
    }

    data.writeInt32(playbackStatus);
    data.writeInt32(position);

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
        const int offset = reply.readInt32();

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
        const int offset = reply.readInt32();

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
            int uniqueId, int fd, int offset, int length) {
    LOGV("Entering BpDrmManagerService::openDecryptSession");
    Parcel data, reply;

    const String16 interfaceDescriptor = IDrmManagerService::getInterfaceDescriptor();
    data.writeInterfaceToken(interfaceDescriptor);
    data.writeInt32(uniqueId);
    data.writeFileDescriptor(fd);
    data.writeInt32(offset);
    data.writeInt32(length);

    remote()->transact(OPEN_DECRYPT_SESSION, data, &reply);

    DecryptHandle* handle = NULL;
    if (0 != reply.dataAvail()) {
        handle = new DecryptHandle();
        handle->decryptId = reply.readInt32();
        handle->mimeType = reply.readString8();
        handle->decryptApiType = reply.readInt32();
        handle->status = reply.readInt32();
        handle->decryptInfo = NULL;
        if (0 != reply.dataAvail()) {
            handle->decryptInfo = new DecryptInfo();
            handle->decryptInfo->decryptBufferLength = reply.readInt32();
        }
    }
    return handle;
}

status_t BpDrmManagerService::closeDecryptSession(int uniqueId, DecryptHandle* decryptHandle) {
    LOGV("closeDecryptSession");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    data.writeInt32(decryptHandle->decryptId);
    data.writeString8(decryptHandle->mimeType);
    data.writeInt32(decryptHandle->decryptApiType);
    data.writeInt32(decryptHandle->status);

    if (NULL != decryptHandle->decryptInfo) {
        data.writeInt32(decryptHandle->decryptInfo->decryptBufferLength);
    } else {
        data.writeInt32(INVALID_BUFFER_LENGTH);
    }

    remote()->transact(CLOSE_DECRYPT_SESSION, data, &reply);

    if (NULL != decryptHandle->decryptInfo) {
        LOGV("deleting decryptInfo");
        delete decryptHandle->decryptInfo; decryptHandle->decryptInfo = NULL;
    }
    delete decryptHandle; decryptHandle = NULL;
    return reply.readInt32();
}

status_t BpDrmManagerService::initializeDecryptUnit(
            int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* headerInfo) {
    LOGV("initializeDecryptUnit");
    Parcel data, reply;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    data.writeInt32(decryptHandle->decryptId);
    data.writeString8(decryptHandle->mimeType);
    data.writeInt32(decryptHandle->decryptApiType);
    data.writeInt32(decryptHandle->status);

    if (NULL != decryptHandle->decryptInfo) {
        data.writeInt32(decryptHandle->decryptInfo->decryptBufferLength);
    } else {
        data.writeInt32(INVALID_BUFFER_LENGTH);
    }
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

    data.writeInt32(decryptHandle->decryptId);
    data.writeString8(decryptHandle->mimeType);
    data.writeInt32(decryptHandle->decryptApiType);
    data.writeInt32(decryptHandle->status);

    if (NULL != decryptHandle->decryptInfo) {
        data.writeInt32(decryptHandle->decryptInfo->decryptBufferLength);
    } else {
        data.writeInt32(INVALID_BUFFER_LENGTH);
    }

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

    data.writeInt32(decryptHandle->decryptId);
    data.writeString8(decryptHandle->mimeType);
    data.writeInt32(decryptHandle->decryptApiType);
    data.writeInt32(decryptHandle->status);

    if (NULL != decryptHandle->decryptInfo) {
        data.writeInt32(decryptHandle->decryptInfo->decryptBufferLength);
    } else {
        data.writeInt32(INVALID_BUFFER_LENGTH);
    }

    data.writeInt32(decryptUnitId);

    remote()->transact(FINALIZE_DECRYPT_UNIT, data, &reply);
    return reply.readInt32();
}

ssize_t BpDrmManagerService::pread(
            int uniqueId, DecryptHandle* decryptHandle, void* buffer,
            ssize_t numBytes, off_t offset) {
    LOGV("read");
    Parcel data, reply;
    int result;

    data.writeInterfaceToken(IDrmManagerService::getInterfaceDescriptor());
    data.writeInt32(uniqueId);

    data.writeInt32(decryptHandle->decryptId);
    data.writeString8(decryptHandle->mimeType);
    data.writeInt32(decryptHandle->decryptApiType);
    data.writeInt32(decryptHandle->status);

    if (NULL != decryptHandle->decryptInfo) {
        data.writeInt32(decryptHandle->decryptInfo->decryptBufferLength);
    } else {
        data.writeInt32(INVALID_BUFFER_LENGTH);
    }

    data.writeInt32(numBytes);
    data.writeInt32(offset);

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

    case LOAD_PLUGINS:
    {
        LOGV("BnDrmManagerService::onTransact :LOAD_PLUGINS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        status_t status = loadPlugIns(data.readInt32());

        reply->writeInt32(status);
        return DRM_NO_ERROR;
    }

    case LOAD_PLUGINS_FROM_PATH:
    {
        LOGV("BnDrmManagerService::onTransact :LOAD_PLUGINS_FROM_PATH");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        status_t status = loadPlugIns(data.readInt32(), data.readString8());

        reply->writeInt32(status);
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

    case UNLOAD_PLUGINS:
    {
        LOGV("BnDrmManagerService::onTransact :UNLOAD_PLUGINS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        status_t status = unloadPlugIns(uniqueId);

        reply->writeInt32(status);
        return DRM_NO_ERROR;
    }

    case INSTALL_DRM_ENGINE:
    {
        LOGV("BnDrmManagerService::onTransact :INSTALL_DRM_ENGINE");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        status_t status = installDrmEngine(data.readInt32(), data.readString8());

        reply->writeInt32(status);
        return DRM_NO_ERROR;
    }

    case GET_CONSTRAINTS_FROM_CONTENT:
    {
        LOGV("BnDrmManagerService::onTransact :GET_CONSTRAINTS_FROM_CONTENT");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();
        const String8 path = data.readString8();

        DrmConstraints* drmConstraints = getConstraints(uniqueId, &path, data.readInt32());

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
        DrmInfoRequest* drmInfoRequest = new DrmInfoRequest(data.readInt32(), data.readString8());

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

        const String8 originalMimeType = getOriginalMimeType(data.readInt32(), data.readString8());

        reply->writeString8(originalMimeType);
        return DRM_NO_ERROR;
    }

    case GET_DRM_OBJECT_TYPE:
    {
        LOGV("BnDrmManagerService::onTransact :GET_DRM_OBJECT_TYPE");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int drmObjectType
            = getDrmObjectType(data.readInt32(), data.readString8(), data.readString8());

        reply->writeInt32(drmObjectType);
        return DRM_NO_ERROR;
    }

    case CHECK_RIGHTS_STATUS:
    {
        LOGV("BnDrmManagerService::onTransact :CHECK_RIGHTS_STATUS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int result
            = checkRightsStatus(data.readInt32(), data.readString8(), data.readInt32());

        reply->writeInt32(result);
        return DRM_NO_ERROR;
    }

    case CONSUME_RIGHTS:
    {
        LOGV("BnDrmManagerService::onTransact :CONSUME_RIGHTS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle handle;
        handle.decryptId = data.readInt32();
        handle.mimeType = data.readString8();
        handle.decryptApiType = data.readInt32();
        handle.status = data.readInt32();
        handle.decryptInfo = NULL;

        const int bufferLength = data.readInt32();
        if (INVALID_BUFFER_LENGTH != bufferLength) {
            handle.decryptInfo = new DecryptInfo();
            handle.decryptInfo->decryptBufferLength = bufferLength;
        }

        const status_t status
            = consumeRights(uniqueId, &handle, data.readInt32(),
                static_cast<bool>(data.readInt32()));
        reply->writeInt32(status);

        delete handle.decryptInfo; handle.decryptInfo = NULL;
        return DRM_NO_ERROR;
    }

    case SET_PLAYBACK_STATUS:
    {
        LOGV("BnDrmManagerService::onTransact :SET_PLAYBACK_STATUS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle handle;
        handle.decryptId = data.readInt32();
        handle.mimeType = data.readString8();
        handle.decryptApiType = data.readInt32();
        handle.status = data.readInt32();
        handle.decryptInfo = NULL;

        const int bufferLength = data.readInt32();
        if (INVALID_BUFFER_LENGTH != bufferLength) {
            handle.decryptInfo = new DecryptInfo();
            handle.decryptInfo->decryptBufferLength = bufferLength;
        }

        const status_t status
            = setPlaybackStatus(uniqueId, &handle, data.readInt32(), data.readInt32());
        reply->writeInt32(status);

        delete handle.decryptInfo; handle.decryptInfo = NULL;
        return DRM_NO_ERROR;
    }

    case VALIDATE_ACTION:
    {
        LOGV("BnDrmManagerService::onTransact :VALIDATE_ACTION");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        bool result = validateAction(
                                data.readInt32(),
                                data.readString8(),
                                data.readInt32(),
                                ActionDescription(data.readInt32(), data.readInt32()));

        reply->writeInt32(result);
        return DRM_NO_ERROR;
    }

    case REMOVE_RIGHTS:
    {
        LOGV("BnDrmManagerService::onTransact :REMOVE_RIGHTS");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const status_t status = removeRights(data.readInt32(), data.readString8());
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

        const int convertId = openConvertSession(data.readInt32(), data.readString8());

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
            reply->writeInt32(drmConvertedStatus->offset);

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

        DrmConvertedStatus*    drmConvertedStatus
            = closeConvertSession(data.readInt32(), data.readInt32());

        if (NULL != drmConvertedStatus) {
            //Filling Drm Converted Ststus
            reply->writeInt32(drmConvertedStatus->statusCode);
            reply->writeInt32(drmConvertedStatus->offset);

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

        DecryptHandle* handle
            = openDecryptSession(uniqueId, fd, data.readInt32(), data.readInt32());

        if (NULL != handle) {
            reply->writeInt32(handle->decryptId);
            reply->writeString8(handle->mimeType);
            reply->writeInt32(handle->decryptApiType);
            reply->writeInt32(handle->status);
            if (NULL != handle->decryptInfo) {
                reply->writeInt32(handle->decryptInfo->decryptBufferLength);
                delete handle->decryptInfo; handle->decryptInfo = NULL;
            }
        }
        delete handle; handle = NULL;
        return DRM_NO_ERROR;
    }

    case CLOSE_DECRYPT_SESSION:
    {
        LOGV("BnDrmManagerService::onTransact :CLOSE_DECRYPT_SESSION");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle* handle = new DecryptHandle();
        handle->decryptId = data.readInt32();
        handle->mimeType = data.readString8();
        handle->decryptApiType = data.readInt32();
        handle->status = data.readInt32();
        handle->decryptInfo = NULL;

        const int bufferLength = data.readInt32();
        if (INVALID_BUFFER_LENGTH != bufferLength) {
            handle->decryptInfo = new DecryptInfo();
            handle->decryptInfo->decryptBufferLength = bufferLength;
        }

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
        handle.decryptId = data.readInt32();
        handle.mimeType = data.readString8();
        handle.decryptApiType = data.readInt32();
        handle.status = data.readInt32();
        handle.decryptInfo = NULL;

        const int bufferLength = data.readInt32();
        if (INVALID_BUFFER_LENGTH != bufferLength) {
            handle.decryptInfo = new DecryptInfo();
            handle.decryptInfo->decryptBufferLength = bufferLength;
        }
        const int decryptUnitId = data.readInt32();

        //Filling Header info
        const int bufferSize = data.readInt32();
        DrmBuffer* headerInfo = NULL;
        headerInfo = new DrmBuffer((char *)data.readInplace(bufferSize), bufferSize);

        const status_t status
            = initializeDecryptUnit(uniqueId, &handle, decryptUnitId, headerInfo);
        reply->writeInt32(status);

        delete handle.decryptInfo; handle.decryptInfo = NULL;
        delete headerInfo; headerInfo = NULL;
        return DRM_NO_ERROR;
    }

    case DECRYPT:
    {
        LOGV("BnDrmManagerService::onTransact :DECRYPT");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle handle;
        handle.decryptId = data.readInt32();
        handle.mimeType = data.readString8();
        handle.decryptApiType = data.readInt32();
        handle.status = data.readInt32();
        handle.decryptInfo = NULL;

        const int bufferLength = data.readInt32();
        if (INVALID_BUFFER_LENGTH != bufferLength) {
            handle.decryptInfo = new DecryptInfo();
            handle.decryptInfo->decryptBufferLength = bufferLength;
        }
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

        delete handle.decryptInfo; handle.decryptInfo = NULL;
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
        handle.decryptId = data.readInt32();
        handle.mimeType = data.readString8();
        handle.decryptApiType = data.readInt32();
        handle.status = data.readInt32();
        handle.decryptInfo = NULL;

        const int bufferLength = data.readInt32();
        if (INVALID_BUFFER_LENGTH != bufferLength) {
            handle.decryptInfo = new DecryptInfo();
            handle.decryptInfo->decryptBufferLength = bufferLength;
        }

        const status_t status = finalizeDecryptUnit(uniqueId, &handle, data.readInt32());
        reply->writeInt32(status);

        delete handle.decryptInfo; handle.decryptInfo = NULL;
        return DRM_NO_ERROR;
    }

    case PREAD:
    {
        LOGV("BnDrmManagerService::onTransact :READ");
        CHECK_INTERFACE(IDrmManagerService, data, reply);

        const int uniqueId = data.readInt32();

        DecryptHandle handle;
        handle.decryptId = data.readInt32();
        handle.mimeType = data.readString8();
        handle.decryptApiType = data.readInt32();
        handle.status = data.readInt32();
        handle.decryptInfo = NULL;

        const int bufferLength = data.readInt32();
        if (INVALID_BUFFER_LENGTH != bufferLength) {
            handle.decryptInfo = new DecryptInfo();
            handle.decryptInfo->decryptBufferLength = bufferLength;
        }

        const int numBytes = data.readInt32();
        char* buffer = new char[numBytes];

        const off_t offset = data.readInt32();

        ssize_t result = pread(uniqueId, &handle, buffer, numBytes, offset);
        reply->writeInt32(result);
        if (0 < result) {
            reply->write(buffer, result);
        }

        delete handle.decryptInfo; handle.decryptInfo = NULL;
        delete [] buffer, buffer = NULL;
        return DRM_NO_ERROR;
    }

    default:
        return BBinder::onTransact(code, data, reply, flags);
    }
}

