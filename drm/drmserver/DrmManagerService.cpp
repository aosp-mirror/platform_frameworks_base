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
#define LOG_TAG "DrmManagerService(Native)"
#include <utils/Log.h>

#include <private/android_filesystem_config.h>
#include <media/MemoryLeakTrackUtil.h>

#include <errno.h>
#include <utils/threads.h>
#include <binder/IServiceManager.h>
#include <binder/IPCThreadState.h>
#include <sys/stat.h>
#include "DrmManagerService.h"
#include "DrmManager.h"

using namespace android;

static Vector<uid_t> trustedUids;

static bool isProtectedCallAllowed() {
    // TODO
    // Following implementation is just for reference.
    // Each OEM manufacturer should implement/replace with their own solutions.
    bool result = false;

    IPCThreadState* ipcState = IPCThreadState::self();
    uid_t uid = ipcState->getCallingUid();

    for (unsigned int i = 0; i < trustedUids.size(); ++i) {
        if (trustedUids[i] == uid) {
            result = true;
            break;
        }
    }
    return result;
}

void DrmManagerService::instantiate() {
    LOGV("instantiate");
    defaultServiceManager()->addService(String16("drm.drmManager"), new DrmManagerService());

    if (0 >= trustedUids.size()) {
        // TODO
        // Following implementation is just for reference.
        // Each OEM manufacturer should implement/replace with their own solutions.

        // Add trusted uids here
        trustedUids.push(AID_MEDIA);
    }
}

DrmManagerService::DrmManagerService() :
        mDrmManager(NULL) {
    LOGV("created");
    mDrmManager = new DrmManager();
    mDrmManager->loadPlugIns();
}

DrmManagerService::~DrmManagerService() {
    LOGV("Destroyed");
    mDrmManager->unloadPlugIns();
    delete mDrmManager; mDrmManager = NULL;
}

int DrmManagerService::addUniqueId(bool isNative) {
    return mDrmManager->addUniqueId(isNative);
}

void DrmManagerService::removeUniqueId(int uniqueId) {
    mDrmManager->removeUniqueId(uniqueId);
}

void DrmManagerService::addClient(int uniqueId) {
    mDrmManager->addClient(uniqueId);
}

void DrmManagerService::removeClient(int uniqueId) {
    mDrmManager->removeClient(uniqueId);
}

status_t DrmManagerService::setDrmServiceListener(
            int uniqueId, const sp<IDrmServiceListener>& drmServiceListener) {
    LOGV("Entering setDrmServiceListener");
    mDrmManager->setDrmServiceListener(uniqueId, drmServiceListener);
    return DRM_NO_ERROR;
}

status_t DrmManagerService::installDrmEngine(int uniqueId, const String8& drmEngineFile) {
    LOGV("Entering installDrmEngine");
    return mDrmManager->installDrmEngine(uniqueId, drmEngineFile);
}

DrmConstraints* DrmManagerService::getConstraints(
            int uniqueId, const String8* path, const int action) {
    LOGV("Entering getConstraints from content");
    return mDrmManager->getConstraints(uniqueId, path, action);
}

DrmMetadata* DrmManagerService::getMetadata(int uniqueId, const String8* path) {
    LOGV("Entering getMetadata from content");
    return mDrmManager->getMetadata(uniqueId, path);
}

bool DrmManagerService::canHandle(int uniqueId, const String8& path, const String8& mimeType) {
    LOGV("Entering canHandle");
    return mDrmManager->canHandle(uniqueId, path, mimeType);
}

DrmInfoStatus* DrmManagerService::processDrmInfo(int uniqueId, const DrmInfo* drmInfo) {
    LOGV("Entering processDrmInfo");
    return mDrmManager->processDrmInfo(uniqueId, drmInfo);
}

DrmInfo* DrmManagerService::acquireDrmInfo(int uniqueId, const DrmInfoRequest* drmInfoRequest) {
    LOGV("Entering acquireDrmInfo");
    return mDrmManager->acquireDrmInfo(uniqueId, drmInfoRequest);
}

status_t DrmManagerService::saveRights(
            int uniqueId, const DrmRights& drmRights,
            const String8& rightsPath, const String8& contentPath) {
    LOGV("Entering saveRights");
    return mDrmManager->saveRights(uniqueId, drmRights, rightsPath, contentPath);
}

String8 DrmManagerService::getOriginalMimeType(int uniqueId, const String8& path) {
    LOGV("Entering getOriginalMimeType");
    return mDrmManager->getOriginalMimeType(uniqueId, path);
}

int DrmManagerService::getDrmObjectType(
           int uniqueId, const String8& path, const String8& mimeType) {
    LOGV("Entering getDrmObjectType");
    return mDrmManager->getDrmObjectType(uniqueId, path, mimeType);
}

int DrmManagerService::checkRightsStatus(
            int uniqueId, const String8& path, int action) {
    LOGV("Entering checkRightsStatus");
    return mDrmManager->checkRightsStatus(uniqueId, path, action);
}

status_t DrmManagerService::consumeRights(
            int uniqueId, DecryptHandle* decryptHandle, int action, bool reserve) {
    LOGV("Entering consumeRights");
    return mDrmManager->consumeRights(uniqueId, decryptHandle, action, reserve);
}

status_t DrmManagerService::setPlaybackStatus(
            int uniqueId, DecryptHandle* decryptHandle, int playbackStatus, int64_t position) {
    LOGV("Entering setPlaybackStatus");
    return mDrmManager->setPlaybackStatus(uniqueId, decryptHandle, playbackStatus, position);
}

bool DrmManagerService::validateAction(
            int uniqueId, const String8& path,
            int action, const ActionDescription& description) {
    LOGV("Entering validateAction");
    return mDrmManager->validateAction(uniqueId, path, action, description);
}

status_t DrmManagerService::removeRights(int uniqueId, const String8& path) {
    LOGV("Entering removeRights");
    return mDrmManager->removeRights(uniqueId, path);
}

status_t DrmManagerService::removeAllRights(int uniqueId) {
    LOGV("Entering removeAllRights");
    return mDrmManager->removeAllRights(uniqueId);
}

int DrmManagerService::openConvertSession(int uniqueId, const String8& mimeType) {
    LOGV("Entering openConvertSession");
    return mDrmManager->openConvertSession(uniqueId, mimeType);
}

DrmConvertedStatus* DrmManagerService::convertData(
            int uniqueId, int convertId, const DrmBuffer* inputData) {
    LOGV("Entering convertData");
    return mDrmManager->convertData(uniqueId, convertId, inputData);
}

DrmConvertedStatus* DrmManagerService::closeConvertSession(int uniqueId, int convertId) {
    LOGV("Entering closeConvertSession");
    return mDrmManager->closeConvertSession(uniqueId, convertId);
}

status_t DrmManagerService::getAllSupportInfo(
            int uniqueId, int* length, DrmSupportInfo** drmSupportInfoArray) {
    LOGV("Entering getAllSupportInfo");
    return mDrmManager->getAllSupportInfo(uniqueId, length, drmSupportInfoArray);
}

DecryptHandle* DrmManagerService::openDecryptSession(
            int uniqueId, int fd, off64_t offset, off64_t length) {
    LOGV("Entering DrmManagerService::openDecryptSession");
    if (isProtectedCallAllowed()) {
        return mDrmManager->openDecryptSession(uniqueId, fd, offset, length);
    }

    return NULL;
}

DecryptHandle* DrmManagerService::openDecryptSession(
            int uniqueId, const char* uri) {
    LOGV("Entering DrmManagerService::openDecryptSession with uri");
    if (isProtectedCallAllowed()) {
        return mDrmManager->openDecryptSession(uniqueId, uri);
    }

    return NULL;
}

status_t DrmManagerService::closeDecryptSession(int uniqueId, DecryptHandle* decryptHandle) {
    LOGV("Entering closeDecryptSession");
    return mDrmManager->closeDecryptSession(uniqueId, decryptHandle);
}

status_t DrmManagerService::initializeDecryptUnit(int uniqueId, DecryptHandle* decryptHandle,
            int decryptUnitId, const DrmBuffer* headerInfo) {
    LOGV("Entering initializeDecryptUnit");
    return mDrmManager->initializeDecryptUnit(uniqueId,decryptHandle, decryptUnitId, headerInfo);
}

status_t DrmManagerService::decrypt(
            int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId,
            const DrmBuffer* encBuffer, DrmBuffer** decBuffer, DrmBuffer* IV) {
    LOGV("Entering decrypt");
    return mDrmManager->decrypt(uniqueId, decryptHandle, decryptUnitId, encBuffer, decBuffer, IV);
}

status_t DrmManagerService::finalizeDecryptUnit(
            int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId) {
    LOGV("Entering finalizeDecryptUnit");
    return mDrmManager->finalizeDecryptUnit(uniqueId, decryptHandle, decryptUnitId);
}

ssize_t DrmManagerService::pread(int uniqueId, DecryptHandle* decryptHandle,
            void* buffer, ssize_t numBytes, off64_t offset) {
    LOGV("Entering pread");
    return mDrmManager->pread(uniqueId, decryptHandle, buffer, numBytes, offset);
}

status_t DrmManagerService::dump(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    if (checkCallingPermission(String16("android.permission.DUMP")) == false) {
        snprintf(buffer, SIZE, "Permission Denial: "
                "can't dump DrmManagerService from pid=%d, uid=%d\n",
                IPCThreadState::self()->getCallingPid(),
                IPCThreadState::self()->getCallingUid());
        result.append(buffer);
    } else {
#if DRM_MEMORY_LEAK_TRACK
        bool dumpMem = false;
        for (size_t i = 0; i < args.size(); i++) {
            if (args[i] == String16("-m")) {
                dumpMem = true;
            }
        }
        if (dumpMem) {
            dumpMemoryAddresses(fd);
        }
#endif
    }
    write(fd, result.string(), result.size());
    return NO_ERROR;
}

