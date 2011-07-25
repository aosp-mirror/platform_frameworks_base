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

#ifndef __IDRM_MANAGER_SERVICE_H__
#define __IDRM_MANAGER_SERVICE_H__

#include <utils/RefBase.h>
#include <binder/IInterface.h>
#include <binder/Parcel.h>
#include <drm/drm_framework_common.h>
#include "IDrmServiceListener.h"

namespace android {

class DrmConstraints;
class DrmMetadata;
class DrmRights;
class DrmInfo;
class DrmInfoStatus;
class DrmInfoRequest;
class DrmSupportInfo;
class DrmConvertedStatus;
class String8;
class ActionDescription;

/**
 * This is the interface class for DRM Manager service.
 *
 */
class IDrmManagerService : public IInterface
{
public:
    enum {
        ADD_UNIQUEID = IBinder::FIRST_CALL_TRANSACTION,
        REMOVE_UNIQUEID,
        ADD_CLIENT,
        REMOVE_CLIENT,
        SET_DRM_SERVICE_LISTENER,
        INSTALL_DRM_ENGINE,
        GET_CONSTRAINTS_FROM_CONTENT,
        GET_METADATA_FROM_CONTENT,
        CAN_HANDLE,
        PROCESS_DRM_INFO,
        ACQUIRE_DRM_INFO,
        SAVE_RIGHTS,
        GET_ORIGINAL_MIMETYPE,
        GET_DRM_OBJECT_TYPE,
        CHECK_RIGHTS_STATUS,
        CONSUME_RIGHTS,
        SET_PLAYBACK_STATUS,
        VALIDATE_ACTION,
        REMOVE_RIGHTS,
        REMOVE_ALL_RIGHTS,
        OPEN_CONVERT_SESSION,
        CONVERT_DATA,
        CLOSE_CONVERT_SESSION,
        GET_ALL_SUPPORT_INFO,
        OPEN_DECRYPT_SESSION,
        OPEN_DECRYPT_SESSION_FROM_URI,
        CLOSE_DECRYPT_SESSION,
        INITIALIZE_DECRYPT_UNIT,
        DECRYPT,
        FINALIZE_DECRYPT_UNIT,
        PREAD
    };

public:
    DECLARE_META_INTERFACE(DrmManagerService);

public:
    virtual int addUniqueId(bool isNative) = 0;

    virtual void removeUniqueId(int uniqueId) = 0;

    virtual void addClient(int uniqueId) = 0;

    virtual void removeClient(int uniqueId) = 0;

    virtual status_t setDrmServiceListener(
            int uniqueId, const sp<IDrmServiceListener>& infoListener) = 0;

    virtual status_t installDrmEngine(int uniqueId, const String8& drmEngineFile) = 0;

    virtual DrmConstraints* getConstraints(
            int uniqueId, const String8* path, const int action) = 0;

    virtual DrmMetadata* getMetadata(int uniqueId, const String8* path) = 0;

    virtual bool canHandle(int uniqueId, const String8& path, const String8& mimeType) = 0;

    virtual DrmInfoStatus* processDrmInfo(int uniqueId, const DrmInfo* drmInfo) = 0;

    virtual DrmInfo* acquireDrmInfo(int uniqueId, const DrmInfoRequest* drmInforequest) = 0;

    virtual status_t saveRights(int uniqueId, const DrmRights& drmRights,
            const String8& rightsPath, const String8& contentPath) = 0;

    virtual String8 getOriginalMimeType(int uniqueId, const String8& path) = 0;

    virtual int getDrmObjectType(
            int uniqueId, const String8& path, const String8& mimeType) = 0;

    virtual int checkRightsStatus(int uniqueId, const String8& path, int action) = 0;

    virtual status_t consumeRights(
            int uniqueId, DecryptHandle* decryptHandle, int action, bool reserve) = 0;

    virtual status_t setPlaybackStatus(
            int uniqueId, DecryptHandle* decryptHandle, int playbackStatus, int64_t position) = 0;

    virtual bool validateAction(
            int uniqueId, const String8& path,
            int action, const ActionDescription& description) = 0;

    virtual status_t removeRights(int uniqueId, const String8& path) = 0;

    virtual status_t removeAllRights(int uniqueId) = 0;

    virtual int openConvertSession(int uniqueId, const String8& mimeType) = 0;

    virtual DrmConvertedStatus* convertData(
            int uniqueId, int convertId, const DrmBuffer* inputData) = 0;

    virtual DrmConvertedStatus* closeConvertSession(int uniqueId, int convertId) = 0;

    virtual status_t getAllSupportInfo(
            int uniqueId, int* length, DrmSupportInfo** drmSupportInfoArray) = 0;

    virtual DecryptHandle* openDecryptSession(int uniqueId, int fd, off64_t offset, off64_t length) = 0;

    virtual DecryptHandle* openDecryptSession(int uniqueId, const char* uri) = 0;

    virtual status_t closeDecryptSession(int uniqueId, DecryptHandle* decryptHandle) = 0;

    virtual status_t initializeDecryptUnit(int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* headerInfo) = 0;

    virtual status_t decrypt(int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId,
            const DrmBuffer* encBuffer, DrmBuffer** decBuffer, DrmBuffer* IV) = 0;

    virtual status_t finalizeDecryptUnit(
            int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId) = 0;

    virtual ssize_t pread(int uniqueId, DecryptHandle* decryptHandle,
            void* buffer, ssize_t numBytes,off64_t offset) = 0;
};

/**
 * This is the Binder implementation class for DRM Manager service.
 */
class BpDrmManagerService: public BpInterface<IDrmManagerService>
{
public:
    BpDrmManagerService(const sp<IBinder>& impl)
            : BpInterface<IDrmManagerService>(impl) {}

    virtual int addUniqueId(bool isNative);

    virtual void removeUniqueId(int uniqueId);

    virtual void addClient(int uniqueId);

    virtual void removeClient(int uniqueId);

    virtual status_t setDrmServiceListener(
            int uniqueId, const sp<IDrmServiceListener>& infoListener);

    virtual status_t installDrmEngine(int uniqueId, const String8& drmEngineFile);

    virtual DrmConstraints* getConstraints(int uniqueId, const String8* path, const int action);

    virtual DrmMetadata* getMetadata(int uniqueId, const String8* path);

    virtual bool canHandle(int uniqueId, const String8& path, const String8& mimeType);

    virtual DrmInfoStatus* processDrmInfo(int uniqueId, const DrmInfo* drmInfo);

    virtual DrmInfo* acquireDrmInfo(int uniqueId, const DrmInfoRequest* drmInforequest);

    virtual status_t saveRights(int uniqueId, const DrmRights& drmRights,
            const String8& rightsPath, const String8& contentPath);

    virtual String8 getOriginalMimeType(int uniqueId, const String8& path);

    virtual int getDrmObjectType(int uniqueId, const String8& path, const String8& mimeType);

    virtual int checkRightsStatus(int uniqueId, const String8& path, int action);

    virtual status_t consumeRights(
            int uniqueId, DecryptHandle* decryptHandle, int action, bool reserve);

    virtual status_t setPlaybackStatus(
            int uniqueId, DecryptHandle* decryptHandle, int playbackStatus, int64_t position);

    virtual bool validateAction(
            int uniqueId, const String8& path, int action, const ActionDescription& description);

    virtual status_t removeRights(int uniqueId, const String8& path);

    virtual status_t removeAllRights(int uniqueId);

    virtual int openConvertSession(int uniqueId, const String8& mimeType);

    virtual DrmConvertedStatus* convertData(
            int uniqueId, int convertId, const DrmBuffer* inputData);

    virtual DrmConvertedStatus* closeConvertSession(int uniqueId, int convertId);

    virtual status_t getAllSupportInfo(
            int uniqueId, int* length, DrmSupportInfo** drmSupportInfoArray);

    virtual DecryptHandle* openDecryptSession(int uniqueId, int fd, off64_t offset, off64_t length);

    virtual DecryptHandle* openDecryptSession(int uniqueId, const char* uri);

    virtual status_t closeDecryptSession(int uniqueId, DecryptHandle* decryptHandle);

    virtual status_t initializeDecryptUnit(int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* headerInfo);

    virtual status_t decrypt(int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId,
            const DrmBuffer* encBuffer, DrmBuffer** decBuffer, DrmBuffer* IV);

    virtual status_t finalizeDecryptUnit(
            int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId);

    virtual ssize_t pread(int uniqueId, DecryptHandle* decryptHandle,
            void* buffer, ssize_t numBytes, off64_t offset);
};

/**
 * This is the Binder implementation class for DRM Manager service.
 */
class BnDrmManagerService: public BnInterface<IDrmManagerService>
{
public:
    virtual status_t onTransact(
            uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags = 0);
};

};

#endif /* __IDRM_MANAGER_SERVICE_H__ */

