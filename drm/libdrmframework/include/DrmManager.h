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

#ifndef __DRM_MANAGER_H__
#define __DRM_MANAGER_H__

#include <utils/Errors.h>
#include <utils/threads.h>
#include <drm/drm_framework_common.h>
#include "IDrmEngine.h"
#include "PlugInManager.h"
#include "IDrmServiceListener.h"

namespace android {

class IDrmManager;
class DrmRegistrationInfo;
class DrmUnregistrationInfo;
class DrmRightsAcquisitionInfo;
class DrmContentIds;
class DrmConstraints;
class DrmRights;
class DrmInfo;
class DrmInfoStatus;
class DrmConvertedStatus;
class DrmInfoRequest;
class DrmSupportInfo;
class ActionDescription;

/**
 * This is implementation class for DRM Manager. This class delegates the
 * functionality to corresponding DRM Engine.
 *
 * The DrmManagerService class creates an instance of this class.
 *
 */
class DrmManager : public IDrmEngine::OnInfoListener {
public:
    DrmManager();
    virtual ~DrmManager();

public:

    status_t loadPlugIns(int uniqueId);

    status_t loadPlugIns(int uniqueId, const String8& plugInDirPath);

    status_t setDrmServiceListener(
            int uniqueId, const sp<IDrmServiceListener>& drmServiceListener);

    status_t unloadPlugIns(int uniqueId);

    status_t installDrmEngine(int uniqueId, const String8& drmEngineFile);

    DrmConstraints* getConstraints(int uniqueId, const String8* path, const int action);

    bool canHandle(int uniqueId, const String8& path, const String8& mimeType);

    DrmInfoStatus* processDrmInfo(int uniqueId, const DrmInfo* drmInfo);

    DrmInfo* acquireDrmInfo(int uniqueId, const DrmInfoRequest* drmInfoRequest);

    void saveRights(int uniqueId, const DrmRights& drmRights,
            const String8& rightsPath, const String8& contentPath);

    String8 getOriginalMimeType(int uniqueId, const String8& path);

    int getDrmObjectType(int uniqueId, const String8& path, const String8& mimeType);

    int checkRightsStatus(int uniqueId, const String8& path, int action);

    void consumeRights(int uniqueId, DecryptHandle* decryptHandle, int action, bool reserve);

    void setPlaybackStatus(
            int uniqueId, DecryptHandle* decryptHandle, int playbackStatus, int position);

    bool validateAction(
            int uniqueId, const String8& path, int action, const ActionDescription& description);

    void removeRights(int uniqueId, const String8& path);

    void removeAllRights(int uniqueId);

    int openConvertSession(int uniqueId, const String8& mimeType);

    DrmConvertedStatus* convertData(int uniqueId, int convertId, const DrmBuffer* inputData);

    DrmConvertedStatus* closeConvertSession(int uniqueId, int convertId);

    status_t getAllSupportInfo(int uniqueId, int* length, DrmSupportInfo** drmSupportInfoArray);

    DecryptHandle* openDecryptSession(int uniqueId, int fd, int offset, int length);

    void closeDecryptSession(int uniqueId, DecryptHandle* decryptHandle);

    void initializeDecryptUnit(int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* headerInfo);

    status_t decrypt(int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* encBuffer,DrmBuffer** decBuffer);

    void finalizeDecryptUnit(int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId);

    ssize_t pread(int uniqueId, DecryptHandle* decryptHandle,
            void* buffer, ssize_t numBytes, off_t offset);

    void onInfo(const DrmInfoEvent& event);

private:
    String8 getSupportedPlugInId(int uniqueId, const String8& path, const String8& mimeType);

    String8 getSupportedPlugInId(const String8& mimeType);

    String8 getSupportedPlugInIdFromPath(int uniqueId, const String8& path);

    void populate(int uniqueId);

    bool canHandle(int uniqueId, const String8& path);

    void initializePlugIns(int uniqueId);

private:
    static const String8 EMPTY_STRING;

    int mDecryptSessionId;
    int mConvertId;
    Mutex mLock;
    Mutex mDecryptLock;
    Mutex mConvertLock;
    TPlugInManager<IDrmEngine> mPlugInManager;
    KeyedVector< DrmSupportInfo, String8 > mSupportInfoToPlugInIdMap;
    KeyedVector< int, IDrmEngine*> mConvertSessionMap;
    KeyedVector< int, sp<IDrmServiceListener> > mServiceListeners;
    KeyedVector< int, IDrmEngine*> mDecryptSessionMap;
};

};

#endif /* __DRM_MANAGER_H__ */

