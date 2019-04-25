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
#pragma once

#include <keystore/keystore_client_impl.h>

namespace android {
namespace os {
namespace incidentd {

class IncidentKeyStore {
public:
    static IncidentKeyStore& getInstance();

    IncidentKeyStore(keystore::KeystoreClient* client) : mClient(client) {}

    /**
     * Encrypt the plainText and output the encrypted message.
     *
     * Returns true on success and false otherwise.
     * If the key has not been created yet, it will generate the key in KeyMaster.
     */
    bool encrypt(const std::string& plainText, int32_t flags, std::string* output);

    /**
     * Decrypt and output the decrypted message.
     *
     * Returns true on success and false otherwise.
     */
    bool decrypt(const std::string& encryptedData, std::string* output);

private:
    std::unique_ptr<keystore::KeystoreClient> mClient;
    std::mutex mMutex;
    keystore::KeyStoreNativeReturnCode generateKeyLocked(const std::string& name, int32_t flags);
};

}  // namespace incidentd
}  // namespace os
}  // namespace android
