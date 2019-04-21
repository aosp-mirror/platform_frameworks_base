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

#include "cipher/ProtoEncryption.h"

#include <android-base/file.h>
#include <gtest/gtest.h>

#include "FdBuffer.h"
#include "android/util/ProtoFileReader.h"

using namespace android::os::incidentd;
using android::sp;
using std::string;
using ::testing::Test;

const std::string kTestPath = GetExecutableDirectory();
const std::string kTestDataPath = kTestPath + "/testdata/";

TEST(ProtoEncryptionTest, test_encrypt_decrypt) {
    const std::string plaintextFile = kTestDataPath + "plaintext.txt";
    const std::string encryptedFile = kTestDataPath + "encrypted.txt";
    size_t msg1Size = 20 * 1024;

    // Create a file with plain text.
    {
        unique_fd fd(
                open(plaintextFile.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR));
        ASSERT_NE(fd.get(), -1);
        string content;
        content.resize(msg1Size, 'a');
        WriteFully(fd, content.data(), msg1Size);
    }

    // Read the plain text and encrypted
    {
        unique_fd readFd(open(plaintextFile.c_str(), O_RDONLY | O_CLOEXEC));
        unique_fd encryptedFd(
                open(encryptedFile.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR));

        ASSERT_NE(readFd.get(), -1);
        ASSERT_NE(encryptedFd.get(), -1);

        sp<ProtoFileReader> reader = new ProtoFileReader(readFd.get());
        ProtoEncryptor encryptor(reader);
        EXPECT_TRUE(encryptor.encrypt() > msg1Size);

        encryptor.flush(encryptedFd.get());
    }

    // Read the encrypted file, and decrypt
    unique_fd encryptedFd(open(encryptedFile.c_str(), O_RDONLY | O_CLOEXEC));
    ASSERT_NE(encryptedFd.get(), -1);
    FdBuffer output;
    sp<ProtoFileReader> reader2 = new ProtoFileReader(encryptedFd.get());
    ProtoDecryptor decryptor(reader2, reader2->size());
    decryptor.decryptAndFlush(&output);

    auto decryptedReader = output.data()->read();

    // Check the content.
    int count = 0;
    while (decryptedReader->hasNext()) {
        if (decryptedReader->next() == 'a') {
            count++;
        }
    }

    EXPECT_EQ(msg1Size, count);
}