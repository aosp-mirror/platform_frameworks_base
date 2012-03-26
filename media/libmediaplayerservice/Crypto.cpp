/*
 * Copyright (C) 2012 The Android Open Source Project
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
#define LOG_TAG "Crypto"
#include <utils/Log.h>

#include "Crypto.h"

#include <media/stagefright/MediaErrors.h>

namespace android {

Crypto::Crypto() {
}

Crypto::~Crypto() {
}

status_t Crypto::initialize() {
    return ERROR_UNSUPPORTED;
}

status_t Crypto::terminate() {
    return ERROR_UNSUPPORTED;
}

status_t Crypto::setEntitlementKey(
        const void *key, size_t keyLength) {
    return ERROR_UNSUPPORTED;
}

status_t Crypto::setEntitlementControlMessage(
        const void *msg, size_t msgLength) {
    return ERROR_UNSUPPORTED;
}

ssize_t Crypto::decryptVideo(
        const void *iv, size_t ivLength,
        const void *srcData, size_t srcDataSize,
        void *dstData, size_t dstDataOffset) {
    return ERROR_UNSUPPORTED;
}

ssize_t Crypto::decryptAudio(
        const void *iv, size_t ivLength,
        const void *srcData, size_t srcDataSize,
        void *dstData, size_t dstDataSize) {
    return ERROR_UNSUPPORTED;
}

}  // namespace android
