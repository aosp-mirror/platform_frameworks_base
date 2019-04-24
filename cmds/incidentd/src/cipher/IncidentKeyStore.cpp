/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "Log.h"

#include "IncidentKeyStore.h"

#include <sys/stat.h>

static constexpr size_t AES_KEY_BYTES = 32;
static constexpr size_t GCM_MAC_BYTES = 16;
constexpr char kKeyname[] = "IncidentKey";

namespace android {
namespace os {
namespace incidentd {

using namespace keystore;
using std::string;

IncidentKeyStore& IncidentKeyStore::getInstance() {
    static IncidentKeyStore sInstance(new keystore::KeystoreClientImpl);
    return sInstance;
}

bool IncidentKeyStore::encrypt(const string& data, int32_t flags, string* output) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (data.empty()) {
        ALOGW("IncidentKeyStore: Encrypt empty data?!");
        return false;
    }
    if (!mClient->doesKeyExist(kKeyname)) {
        auto gen_result = generateKeyLocked(kKeyname, 0);
        if (!gen_result.isOk()) {
            ALOGE("IncidentKeyStore: Key generate failed.");
            return false;
        }
    }
    if (!mClient->encryptWithAuthentication(kKeyname, data, flags, output)) {
        ALOGE("IncidentKeyStore: Encryption failed.");
        return false;
    }
    return true;
}

bool IncidentKeyStore::decrypt(const std::string& input, string* output) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (input.empty()) {
        ALOGE("IncidentKeyStore: Decrypt empty input?");
        return false;
    }
    if (!mClient->decryptWithAuthentication(kKeyname, input, output)) {
        ALOGE("IncidentKeyStore: Decryption failed.");
        return false;
    }
    return true;
}

KeyStoreNativeReturnCode IncidentKeyStore::generateKeyLocked(const std::string& name,
                                                             int32_t flags) {
    auto paramBuilder = AuthorizationSetBuilder()
                                .AesEncryptionKey(AES_KEY_BYTES * 8)
                                .GcmModeMinMacLen(GCM_MAC_BYTES * 8)
                                .Authorization(TAG_NO_AUTH_REQUIRED);

    AuthorizationSet hardware_enforced_characteristics;
    AuthorizationSet software_enforced_characteristics;
    return mClient->generateKey(name, paramBuilder, flags, &hardware_enforced_characteristics,
                                &software_enforced_characteristics);
}

}  // namespace incidentd
}  // namespace os
}  // namespace android
