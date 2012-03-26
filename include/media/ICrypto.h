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

#include <binder/IInterface.h>
#include <media/stagefright/foundation/ABase.h>

#ifndef ANDROID_ICRYPTO_H_

#define ANDROID_ICRYPTO_H_

namespace android {

struct ICrypto : public IInterface {
    DECLARE_META_INTERFACE(Crypto);

    virtual status_t initialize() = 0;
    virtual status_t terminate() = 0;

    virtual status_t setEntitlementKey(
            const void *key, size_t keyLength) = 0;

    virtual status_t setEntitlementControlMessage(
            const void *msg, size_t msgLength) = 0;

    // "dstData" is in media_server's address space (but inaccessible).
    virtual ssize_t decryptVideo(
            const void *iv, size_t ivLength,
            const void *srcData, size_t srcDataSize,
            void *dstData, size_t dstDataOffset) = 0;

    // "dstData" is in the calling process' address space.
    virtual ssize_t decryptAudio(
            const void *iv, size_t ivLength,
            const void *srcData, size_t srcDataSize,
            void *dstData, size_t dstDataSize) = 0;

private:
    DISALLOW_EVIL_CONSTRUCTORS(ICrypto);
};

struct BnCrypto : public BnInterface<ICrypto> {
    virtual status_t onTransact(
            uint32_t code, const Parcel &data, Parcel *reply,
            uint32_t flags = 0);
};

}  // namespace android

#endif // ANDROID_ICRYPTO_H_

