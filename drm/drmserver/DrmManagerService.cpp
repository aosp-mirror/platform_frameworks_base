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

#include <errno.h>
#include <utils/threads.h>
#include <binder/IServiceManager.h>
#include <sys/stat.h>
#include "DrmManagerService.h"
#include "DrmManager.h"

using namespace android;

#define SUCCESS 0
#define DRM_DIRECTORY_PERMISSION 0700
#define DRM_PLUGINS_ROOT "/data/drm/plugins"
#define DRM_PLUGINS_NATIVE "/data/drm/plugins/native"
#define DRM_PLUGINS_NATIVE_DATABASES "/data/drm/plugins/native/databases"

void DrmManagerService::instantiate() {
    LOGV("instantiate");

    int res = mkdir(DRM_PLUGINS_ROOT, DRM_DIRECTORY_PERMISSION);
    if (SUCCESS == res || EEXIST == errno) {
        res = mkdir(DRM_PLUGINS_NATIVE, DRM_DIRECTORY_PERMISSION);
        if (SUCCESS == res || EEXIST == errno) {
            res = mkdir(DRM_PLUGINS_NATIVE_DATABASES, DRM_DIRECTORY_PERMISSION);
            if (SUCCESS == res || EEXIST == errno) {
                defaultServiceManager()
                    ->addService(String16("drm.drmManager"), new DrmManagerService());
            }
        }
    }
}

DrmManagerService::DrmManagerService() {
    LOGV("created");
    mDrmManager = NULL;
    mDrmManager = new DrmManager();
}

DrmManagerService::~DrmManagerService() {
    LOGV("Destroyed");
    delete mDrmManager; mDrmManager = NULL;
}

int DrmManagerService::addUniqueId(int uniqueId) {
    return mDrmManager->addUniqueId(uniqueId);
}

void DrmManagerService::removeUniqueId(int uniqueId) {
    mDrmManager->removeUniqueId(uniqueId);
}

status_t DrmManagerService::loadPlugIns(int uniqueId) {
    LOGV("Entering load plugins");
    return mDrmManager->loadPlugIns(uniqueId);
}

status_t DrmManagerService::loadPlugIns(int uniqueId, const String8& plugInDirPath) {
    LOGV("Entering load plugins from path");
    return mDrmManager->loadPlugIns(uniqueId, plugInDirPath);
}

status_t DrmManagerService::setDrmServiceListener(
            int uniqueId, const sp<IDrmServiceListener>& drmServiceListener) {
    LOGV("Entering setDrmServiceListener");
    mDrmManager->setDrmServiceListener(uniqueId, drmServiceListener);
    return DRM_NO_ERROR;
}

status_t DrmManagerService::unloadPlugIns(int uniqueId) {
    LOGV("Entering unload plugins");
    return mDrmManager->unloadPlugIns(uniqueId);
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
            int uniqueId, DecryptHandle* decryptHandle, int playbackStatus, int position) {
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
            int uniqueId, int fd, int offset, int length) {
    LOGV("Entering DrmManagerService::openDecryptSession");
    return mDrmManager->openDecryptSession(uniqueId, fd, offset, length);
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
            void* buffer, ssize_t numBytes, off_t offset) {
    LOGV("Entering pread");
    return mDrmManager->pread(uniqueId, decryptHandle, buffer, numBytes, offset);
}

