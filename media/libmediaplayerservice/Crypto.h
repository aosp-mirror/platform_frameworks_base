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

#ifndef CRYPTO_H_

#define CRYPTO_H_

#include <media/ICrypto.h>
#include <utils/threads.h>

namespace android {

struct Crypto : public BnCrypto {
    Crypto();

    virtual status_t initialize();
    virtual status_t terminate();

    virtual status_t setEntitlementKey(
            const void *key, size_t keyLength);

    virtual status_t setEntitlementControlMessage(
            const void *msg, size_t msgLength);

    virtual ssize_t decryptVideo(
            const void *iv, size_t ivLength,
            const void *srcData, size_t srcDataSize,
            void *dstData, size_t dstDataOffset);

    virtual ssize_t decryptAudio(
            const void *iv, size_t ivLength,
            const void *srcData, size_t srcDataSize,
            void *dstData, size_t dstDataSize);

protected:
    virtual ~Crypto();

private:
    DISALLOW_EVIL_CONSTRUCTORS(Crypto);
};

}  // namespace android

#endif  // CRYPTO_H_
