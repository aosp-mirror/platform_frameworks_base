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

#include "cipher/IncidentKeyStore.h"

#include <binder/ProcessState.h>
#include <gtest/gtest.h>

#include <fstream>

using namespace android::os::incidentd;

class IncidentKeyStoreTest : public ::testing::Test {
protected:
    std::unique_ptr<IncidentKeyStore> incidentKeyStore;
    void SetUp() override {
        android::ProcessState::self()->startThreadPool();
        incidentKeyStore = std::make_unique<IncidentKeyStore>(
                static_cast<keystore::KeystoreClient*>(new keystore::KeystoreClientImpl));
    };
    void TearDown() override { incidentKeyStore = nullptr; };
};

TEST_F(IncidentKeyStoreTest, test_encrypt_decrypt) {
    std::string plaintext;
    plaintext.resize(4 * 1024, 'a');

    std::string encrypted;
    EXPECT_TRUE(incidentKeyStore->encrypt(plaintext, 0, &encrypted));
    std::string decrypted;
    EXPECT_TRUE(incidentKeyStore->decrypt(encrypted, &decrypted));

    EXPECT_FALSE(encrypted.empty());
    EXPECT_EQ(plaintext, decrypted);
}

TEST_F(IncidentKeyStoreTest, test_encrypt_empty_hash) {
    std::string hash = "";

    std::string encrypted;
    EXPECT_FALSE(incidentKeyStore->encrypt(hash, 0, &encrypted));

    EXPECT_TRUE(encrypted.empty());
}

TEST_F(IncidentKeyStoreTest, test_decrypt_empty_hash) {
    std::string hash = "";

    std::string decrypted;
    EXPECT_FALSE(incidentKeyStore->decrypt(hash, &decrypted));

    EXPECT_TRUE(decrypted.empty());
}