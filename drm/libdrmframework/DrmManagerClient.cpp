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

#include <utils/String8.h>
#include <binder/IServiceManager.h>
#include <drm/DrmManagerClient.h>

#include "DrmManagerClientImpl.h"

using namespace android;

DrmManagerClient::DrmManagerClient():
        mUniqueId(0), mDrmManagerClientImpl(NULL) {
    mDrmManagerClientImpl = DrmManagerClientImpl::create(&mUniqueId, true);
    mDrmManagerClientImpl->addClient(mUniqueId);
}

DrmManagerClient::~DrmManagerClient() {
    DrmManagerClientImpl::remove(mUniqueId);
    mDrmManagerClientImpl->removeClient(mUniqueId);
    mDrmManagerClientImpl->setOnInfoListener(mUniqueId, NULL);
}

status_t DrmManagerClient::setOnInfoListener(
                    const sp<DrmManagerClient::OnInfoListener>& infoListener) {
    return mDrmManagerClientImpl->setOnInfoListener(mUniqueId, infoListener);
}

DrmConstraints* DrmManagerClient::getConstraints(const String8* path, const int action) {
    return mDrmManagerClientImpl->getConstraints(mUniqueId, path, action);
}

DrmMetadata* DrmManagerClient::getMetadata(const String8* path) {
    return mDrmManagerClientImpl->getMetadata(mUniqueId, path);
}

bool DrmManagerClient::canHandle(const String8& path, const String8& mimeType) {
    return mDrmManagerClientImpl->canHandle(mUniqueId, path, mimeType);
}

DrmInfoStatus* DrmManagerClient::processDrmInfo(const DrmInfo* drmInfo) {
    return mDrmManagerClientImpl->processDrmInfo(mUniqueId, drmInfo);
}

DrmInfo* DrmManagerClient::acquireDrmInfo(const DrmInfoRequest* drmInfoRequest) {
    return mDrmManagerClientImpl->acquireDrmInfo(mUniqueId, drmInfoRequest);
}

status_t DrmManagerClient::saveRights(
        const DrmRights& drmRights, const String8& rightsPath, const String8& contentPath) {
    return mDrmManagerClientImpl->saveRights(mUniqueId, drmRights, rightsPath, contentPath);
}

String8 DrmManagerClient::getOriginalMimeType(const String8& path) {
    return mDrmManagerClientImpl->getOriginalMimeType(mUniqueId, path);
}

int DrmManagerClient::getDrmObjectType(const String8& path, const String8& mimeType) {
    return mDrmManagerClientImpl->getDrmObjectType( mUniqueId, path, mimeType);
}

int DrmManagerClient::checkRightsStatus(const String8& path, int action) {
    return mDrmManagerClientImpl->checkRightsStatus(mUniqueId, path, action);
}

status_t DrmManagerClient::consumeRights(
            sp<DecryptHandle> &decryptHandle, int action, bool reserve) {
    return mDrmManagerClientImpl->consumeRights(mUniqueId, decryptHandle, action, reserve);
}

status_t DrmManagerClient::setPlaybackStatus(
            sp<DecryptHandle> &decryptHandle, int playbackStatus, int64_t position) {
    return mDrmManagerClientImpl
            ->setPlaybackStatus(mUniqueId, decryptHandle, playbackStatus, position);
}

bool DrmManagerClient::validateAction(
            const String8& path, int action, const ActionDescription& description) {
    return mDrmManagerClientImpl->validateAction(mUniqueId, path, action, description);
}

status_t DrmManagerClient::removeRights(const String8& path) {
    return mDrmManagerClientImpl->removeRights(mUniqueId, path);
}

status_t DrmManagerClient::removeAllRights() {
    return mDrmManagerClientImpl->removeAllRights(mUniqueId);
}

int DrmManagerClient::openConvertSession(const String8& mimeType) {
    return mDrmManagerClientImpl->openConvertSession(mUniqueId, mimeType);
}

DrmConvertedStatus* DrmManagerClient::convertData(int convertId, const DrmBuffer* inputData) {
    return mDrmManagerClientImpl->convertData(mUniqueId, convertId, inputData);
}

DrmConvertedStatus* DrmManagerClient::closeConvertSession(int convertId) {
    return mDrmManagerClientImpl->closeConvertSession(mUniqueId, convertId);
}

status_t DrmManagerClient::getAllSupportInfo(int* length, DrmSupportInfo** drmSupportInfoArray) {
    return mDrmManagerClientImpl->getAllSupportInfo(mUniqueId, length, drmSupportInfoArray);
}

sp<DecryptHandle> DrmManagerClient::openDecryptSession(
        int fd, off64_t offset, off64_t length, const char* mime) {

    return mDrmManagerClientImpl->openDecryptSession(
                    mUniqueId, fd, offset, length, mime);
}

sp<DecryptHandle> DrmManagerClient::openDecryptSession(
        const char* uri, const char* mime) {

    return mDrmManagerClientImpl->openDecryptSession(
                    mUniqueId, uri, mime);
}

status_t DrmManagerClient::closeDecryptSession(sp<DecryptHandle> &decryptHandle) {
    return mDrmManagerClientImpl->closeDecryptSession(mUniqueId, decryptHandle);
}

status_t DrmManagerClient::initializeDecryptUnit(
            sp<DecryptHandle> &decryptHandle, int decryptUnitId, const DrmBuffer* headerInfo) {
    return mDrmManagerClientImpl->initializeDecryptUnit(
            mUniqueId, decryptHandle, decryptUnitId, headerInfo);
}

status_t DrmManagerClient::decrypt(
            sp<DecryptHandle> &decryptHandle, int decryptUnitId,
            const DrmBuffer* encBuffer, DrmBuffer** decBuffer, DrmBuffer* IV) {
    return mDrmManagerClientImpl->decrypt(
            mUniqueId, decryptHandle, decryptUnitId, encBuffer, decBuffer, IV);
}

status_t DrmManagerClient::finalizeDecryptUnit(
            sp<DecryptHandle> &decryptHandle, int decryptUnitId) {
    return mDrmManagerClientImpl->finalizeDecryptUnit(mUniqueId,
            decryptHandle, decryptUnitId);
}

ssize_t DrmManagerClient::pread(
            sp<DecryptHandle> &decryptHandle, void* buffer, ssize_t numBytes, off64_t offset) {
    return mDrmManagerClientImpl->pread(mUniqueId, decryptHandle, buffer, numBytes, offset);
}

