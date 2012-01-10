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

#include "DrmEngineBase.h"

using namespace android;

DrmEngineBase::DrmEngineBase() {

}

DrmEngineBase::~DrmEngineBase() {

}

DrmConstraints* DrmEngineBase::getConstraints(
    int uniqueId, const String8* path, int action) {
    return onGetConstraints(uniqueId, path, action);
}

DrmMetadata* DrmEngineBase::getMetadata(int uniqueId, const String8* path) {
    return onGetMetadata(uniqueId, path);
}

status_t DrmEngineBase::initialize(int uniqueId) {
    return onInitialize(uniqueId);
}

status_t DrmEngineBase::setOnInfoListener(
    int uniqueId, const IDrmEngine::OnInfoListener* infoListener) {
    return onSetOnInfoListener(uniqueId, infoListener);
}

status_t DrmEngineBase::terminate(int uniqueId) {
    return onTerminate(uniqueId);
}

bool DrmEngineBase::canHandle(int uniqueId, const String8& path) {
    return onCanHandle(uniqueId, path);
}

DrmInfoStatus* DrmEngineBase::processDrmInfo(int uniqueId, const DrmInfo* drmInfo) {
    return onProcessDrmInfo(uniqueId, drmInfo);
}

status_t DrmEngineBase::saveRights(
            int uniqueId, const DrmRights& drmRights,
            const String8& rightsPath, const String8& contentPath) {
    return onSaveRights(uniqueId, drmRights, rightsPath, contentPath);
}

DrmInfo* DrmEngineBase::acquireDrmInfo(int uniqueId, const DrmInfoRequest* drmInfoRequest) {
    return onAcquireDrmInfo(uniqueId, drmInfoRequest);
}

String8 DrmEngineBase::getOriginalMimeType(int uniqueId, const String8& path) {
    return onGetOriginalMimeType(uniqueId, path);
}

int DrmEngineBase::getDrmObjectType(int uniqueId, const String8& path, const String8& mimeType) {
    return onGetDrmObjectType(uniqueId, path, mimeType);
}

int DrmEngineBase::checkRightsStatus(int uniqueId, const String8& path, int action) {
    return onCheckRightsStatus(uniqueId, path, action);
}

status_t DrmEngineBase::consumeRights(
    int uniqueId, DecryptHandle* decryptHandle, int action, bool reserve) {
    return onConsumeRights(uniqueId, decryptHandle, action, reserve);
}

status_t DrmEngineBase::setPlaybackStatus(
    int uniqueId, DecryptHandle* decryptHandle, int playbackStatus, int64_t position) {
    return onSetPlaybackStatus(uniqueId, decryptHandle, playbackStatus, position);
}

bool DrmEngineBase::validateAction(
    int uniqueId, const String8& path,
    int action, const ActionDescription& description) {
    return onValidateAction(uniqueId, path, action, description);
}

status_t DrmEngineBase::removeRights(int uniqueId, const String8& path) {
    return onRemoveRights(uniqueId, path);
}

status_t DrmEngineBase::removeAllRights(int uniqueId) {
    return onRemoveAllRights(uniqueId);
}

status_t DrmEngineBase::openConvertSession(int uniqueId, int convertId) {
    return onOpenConvertSession(uniqueId, convertId);
}

DrmConvertedStatus* DrmEngineBase::convertData(
    int uniqueId, int convertId, const DrmBuffer* inputData) {
    return onConvertData(uniqueId, convertId, inputData);
}

DrmConvertedStatus* DrmEngineBase::closeConvertSession(int uniqueId, int convertId) {
    return onCloseConvertSession(uniqueId, convertId);
}

DrmSupportInfo* DrmEngineBase::getSupportInfo(int uniqueId) {
    return onGetSupportInfo(uniqueId);
}

status_t DrmEngineBase::openDecryptSession(
    int uniqueId, DecryptHandle* decryptHandle,
    int fd, off64_t offset, off64_t length, const char* mime) {

    if (!mime || mime[0] == '\0') {
        return onOpenDecryptSession(uniqueId, decryptHandle, fd, offset, length);
    }

    return onOpenDecryptSession(uniqueId, decryptHandle, fd, offset, length, mime);
}

status_t DrmEngineBase::openDecryptSession(
    int uniqueId, DecryptHandle* decryptHandle,
    const char* uri, const char* mime) {
    if (!mime || mime[0] == '\0') {
        return onOpenDecryptSession(uniqueId, decryptHandle, uri);
    }
    return onOpenDecryptSession(uniqueId, decryptHandle, uri, mime);
}

status_t DrmEngineBase::closeDecryptSession(int uniqueId, DecryptHandle* decryptHandle) {
    return onCloseDecryptSession(uniqueId, decryptHandle);
}

status_t DrmEngineBase::initializeDecryptUnit(
    int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId, const DrmBuffer* headerInfo) {
    return onInitializeDecryptUnit(uniqueId, decryptHandle, decryptUnitId, headerInfo);
}

status_t DrmEngineBase::decrypt(
    int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId,
    const DrmBuffer* encBuffer, DrmBuffer** decBuffer, DrmBuffer* IV) {
    return onDecrypt(uniqueId, decryptHandle, decryptUnitId, encBuffer, decBuffer, IV);
}

status_t DrmEngineBase::finalizeDecryptUnit(
    int uniqueId, DecryptHandle* decryptHandle, int decryptUnitId) {
    return onFinalizeDecryptUnit(uniqueId, decryptHandle, decryptUnitId);
}

ssize_t DrmEngineBase::pread(
    int uniqueId, DecryptHandle* decryptHandle, void* buffer, ssize_t numBytes, off64_t offset) {
    return onPread(uniqueId, decryptHandle, buffer, numBytes, offset);
}

