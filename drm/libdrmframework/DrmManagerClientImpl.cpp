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
#define LOG_TAG "DrmManagerClientImpl(Native)"
#include <utils/Log.h>

#include <utils/String8.h>
#include <utils/Vector.h>
#include <binder/IServiceManager.h>

#include "DrmManagerClientImpl.h"

using namespace android;

#define INVALID_VALUE -1

Mutex DrmManagerClientImpl::mMutex;
sp<IDrmManagerService> DrmManagerClientImpl::mDrmManagerService;
const String8 DrmManagerClientImpl::EMPTY_STRING("");

DrmManagerClientImpl* DrmManagerClientImpl::create(int* pUniqueId) {
    if (0 == *pUniqueId) {
        int uniqueId = getDrmManagerService()->addUniqueId(*pUniqueId);
        *pUniqueId = uniqueId;
    } else {
        getDrmManagerService()->addUniqueId(*pUniqueId);
    }
    return new DrmManagerClientImpl();
}

void DrmManagerClientImpl::remove(int uniqueId) {
    getDrmManagerService()->removeUniqueId(uniqueId);
}

DrmManagerClientImpl::DrmManagerClientImpl() {

}

DrmManagerClientImpl::~DrmManagerClientImpl() {

}

const sp<IDrmManagerService>& DrmManagerClientImpl::getDrmManagerService() {
    mMutex.lock();
    if (NULL == mDrmManagerService.get()) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder;
        do {
            binder = sm->getService(String16("drm.drmManager"));
            if (binder != 0) {
                break;
            }
            LOGW("DrmManagerService not published, waiting...");
            struct timespec reqt;
            reqt.tv_sec  = 0;
            reqt.tv_nsec = 500000000; //0.5 sec
            nanosleep(&reqt, NULL);
        } while (true);

        mDrmManagerService = interface_cast<IDrmManagerService>(binder);
    }
    mMutex.unlock();
    return mDrmManagerService;
}

status_t DrmManagerClientImpl::loadPlugIns(int uniqueId) {
    return getDrmManagerService()->loadPlugIns(uniqueId);
}

status_t DrmManagerClientImpl::loadPlugIns(int uniqueId, const String8& plugInDirPath) {
    status_t status = DRM_ERROR_UNKNOWN;
    if (EMPTY_STRING != plugInDirPath) {
        status = getDrmManagerService()->loadPlugIns(uniqueId, plugInDirPath);
    }
    return status;
}

status_t DrmManagerClientImpl::setOnInfoListener(
            int uniqueId, const sp<DrmManagerClient::OnInfoListener>& infoListener) {
    Mutex::Autolock _l(mLock);
    mOnInfoListener = infoListener;
    return getDrmManagerService()->setDrmServiceListener(uniqueId, this);
}

status_t DrmManagerClientImpl::unloadPlugIns(int uniqueId) {
    return getDrmManagerService()->unloadPlugIns(uniqueId);
}

status_t DrmManagerClientImpl::installDrmEngine(int uniqueId, const String8& drmEngineFile) {
    status_t status = DRM_ERROR_UNKNOWN;
    if (EMPTY_STRING != drmEngineFile) {
        status = getDrmManagerService()->installDrmEngine(uniqueId, drmEngineFile);
    }
    return status;
}

DrmConstraints* DrmManagerClientImpl::getConstraints(
        int uniqueId, const String8* path, const int action) {
    DrmConstraints *drmConstraints = NULL;
    if ((NULL != path) && (EMPTY_STRING != *path)) {
        drmConstraints = getDrmManagerService()->getConstraints(uniqueId, path, action);
    }
    return drmConstraints;
}

bool DrmManagerClientImpl::canHandle(int uniqueId, const String8& path, const String8& mimeType) {
    bool retCode = false;
    if ((EMPTY_STRING != path) || (EMPTY_STRING != mimeType)) {
        retCode = getDrmManagerService()->canHandle(uniqueId, path, mimeType);
    }
    return retCode;
}

DrmInfoStatus* DrmManagerClientImpl::processDrmInfo(int uniqueId, const DrmInfo* drmInfo) {
    DrmInfoStatus *drmInfoStatus = NULL;
    if (NULL != drmInfo) {
        drmInfoStatus = getDrmManagerService()->processDrmInfo(uniqueId, drmInfo);
    }
    return drmInfoStatus;
}

DrmInfo* DrmManagerClientImpl::acquireDrmInfo(int uniqueId, const DrmInfoRequest* drmInfoRequest) {
    DrmInfo* drmInfo = NULL;
    if (NULL != drmInfoRequest) {
        drmInfo = getDrmManagerService()->acquireDrmInfo(uniqueId, drmInfoRequest);
    }
    return drmInfo;
}

status_t DrmManagerClientImpl::saveRights(int uniqueId, const DrmRights& drmRights,
            const String8& rightsPath, const String8& contentPath) {
    status_t status = DRM_ERROR_UNKNOWN;
    if (EMPTY_STRING != contentPath) {
        status = getDrmManagerService()->saveRights(uniqueId, drmRights, rightsPath, contentPath);
    }
    return status;
}

String8 DrmManagerClientImpl::getOriginalMimeType(int uniqueId, const String8& path) {
    String8 mimeType = EMPTY_STRING;
    if (EMPTY_STRING != path) {
        mimeType = getDrmManagerService()->getOriginalMimeType(uniqueId, path);
    }
    return mimeType;
}

int DrmManagerClientImpl::getDrmObjectType(
            int uniqueId, const String8& path, const String8& mimeType) {
    int drmOjectType = DrmObjectType::UNKNOWN;
    if ((EMPTY_STRING != path) || (EMPTY_STRING != mimeType)) {
         drmOjectType = getDrmManagerService()->getDrmObjectType(uniqueId, path, mimeType);
    }
    return drmOjectType;
}

int DrmManagerClientImpl::checkRightsStatus(
            int uniqueId, const String8& path, int action) {
    int rightsStatus = RightsStatus::RIGHTS_INVALID;
    if (EMPTY_STRING != path) {
        rightsStatus = getDrmManagerService()->checkRightsStatus(uniqueId, path, action);
    }
    return rightsStatus;
}

status_t DrmManagerClientImpl::consumeRights(
            int uniqueId, DecryptHandle* decryptHandle, int action, bool reserve) {
    status_t status = DRM_ERROR_UNKNOWN;
    if (NULL != decryptHandle) {
        status = getDrmManagerService()->consumeRights(uniqueId, decryptHandle, action, reserve);
    }
    return status;
}

status_t DrmManagerClientImpl::setPlaybackStatus(
            int uniqueId, DecryptHandle* decryptHandle, int playbackStatus, int position) {
    status_t status = DRM_ERROR_UNKNOWN;
    if (NULL != decryptHandle) {
        status = getDrmManagerService()->setPlaybackStatus(
                uniqueId, decryptHandle, playbackStatus, position);
    }
    return status;
}

bool DrmManagerClientImpl::validateAction(
            int uniqueId, const String8& path, int action, const ActionDescription& description) {
    bool retCode = false;
    if (EMPTY_STRING != path) {
        retCode = getDrmManagerService()->validateAction(uniqueId, path, action, description);
    }
    return retCode;
}

status_t DrmManagerClientImpl::removeRights(int uniqueId, const String8& path) {
    status_t status = DRM_ERROR_UNKNOWN;
    if (EMPTY_STRING != path) {
        status = getDrmManagerService()->removeRights(uniqueId, path);
    }
    return status;
}

status_t DrmManagerClientImpl::removeAllRights(int uniqueId) {
    return getDrmManagerService()->removeAllRights(uniqueId);
}

int DrmManagerClientImpl::openConvertSession(int uniqueId, const String8& mimeType) {
    int retCode = INVALID_VALUE;
    if (EMPTY_STRING != mimeType) {
        retCode = getDrmManagerService()->openConvertSession(uniqueId, mimeType);
    }
    return retCode;
}

DrmConvertedStatus* DrmManagerClientImpl::convertData(
            int uniqueId, int convertId, const DrmBuffer* inputData) {
    DrmConvertedStatus* drmConvertedStatus = NULL;
    if (NULL != inputData) {
         drmConvertedStatus = getDrmManagerService()->convertData(uniqueId, convertId, inputData);
    }
    return drmConvertedStatus;
}

DrmConvertedStatus* DrmManagerClientImpl::closeConvertSession(int uniqueId, int convertId) {
    return getDrmManagerService()->closeConvertSession(uniqueId, convertId);
}

status_t DrmManagerClientImpl::getAllSupportInfo(
            int uniqueId, int* length, DrmSupportInfo** drmSupportInfoArray) {
    status_t status = DRM_ERROR_UNKNOWN;
    if ((NULL != drmSupportInfoArray) && (NULL != length)) {
        status = getDrmManagerService()->getAllSupportInfo(uniqueId, length, drmSupportInfoArray);
    }
    return status;
}

DecryptHandle* DrmManagerClientImpl::openDecryptSession(
            int uniqueId, int fd, int offset, int length) {
    return getDrmManagerService()->openDecryptSession(uniqueId, fd, offset, length);
}

status_t DrmManagerClientImpl::closeDecryptSession(int uniqueId, DecryptHandle* decryptHandle) {
    status_t status = DRM_ERROR_UNKNOWN;
    if (NULL != decryptHandle) {
        status = getDrmManagerService()->closeDecryptSession( uniqueId, decryptHandle);
    }
    return status;
}

status_t DrmManagerClientImpl::initializeDecryptUnit(int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* headerInfo) {
    status_t status = DRM_ERROR_UNKNOWN;
    if ((NULL != decryptHandle) && (NULL != headerInfo)) {
        status = getDrmManagerService()->initializeDecryptUnit(
                uniqueId, decryptHandle, decryptUnitId, headerInfo);
    }
    return status;
}

status_t DrmManagerClientImpl::decrypt(int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* encBuffer, DrmBuffer** decBuffer, DrmBuffer* IV) {
    status_t status = DRM_ERROR_UNKNOWN;
    if ((NULL != decryptHandle) && (NULL != encBuffer)
        && (NULL != decBuffer) && (NULL != *decBuffer)) {
        status = getDrmManagerService()->decrypt(
                uniqueId, decryptHandle, decryptUnitId, encBuffer, decBuffer, IV);
    }
    return status;
}

status_t DrmManagerClientImpl::finalizeDecryptUnit(
            int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId) {
    status_t status = DRM_ERROR_UNKNOWN;
    if (NULL != decryptHandle) {
        status
            = getDrmManagerService()->finalizeDecryptUnit(uniqueId, decryptHandle, decryptUnitId);
    }
    return status;
}

ssize_t DrmManagerClientImpl::pread(int uniqueId, DecryptHandle* decryptHandle,
            void* buffer, ssize_t numBytes, off_t offset) {
    ssize_t retCode = INVALID_VALUE;
    if ((NULL != decryptHandle) && (NULL != buffer) && (0 < numBytes)) {
        retCode = getDrmManagerService()->pread(uniqueId, decryptHandle, buffer, numBytes, offset);
    }
    return retCode;
}

status_t DrmManagerClientImpl::notify(const DrmInfoEvent& event) {
    if (NULL != mOnInfoListener.get()) {
        Mutex::Autolock _l(mLock);
        sp<DrmManagerClient::OnInfoListener> listener = mOnInfoListener;
        listener->onInfo(event);
    }
    return DRM_NO_ERROR;
}

