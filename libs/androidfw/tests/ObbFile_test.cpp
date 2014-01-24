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

#define LOG_TAG "ObbFile_test"
#include <androidfw/ObbFile.h>
#include <utils/Log.h>
#include <utils/RefBase.h>
#include <utils/String8.h>

#include <gtest/gtest.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <string.h>

namespace android {

#define TEST_FILENAME "/test.obb"

class ObbFileTest : public testing::Test {
protected:
    sp<ObbFile> mObbFile;
    char* mExternalStorage;
    char* mFileName;

    virtual void SetUp() {
        mObbFile = new ObbFile();
        mExternalStorage = getenv("EXTERNAL_STORAGE");

        const int totalLen = strlen(mExternalStorage) + strlen(TEST_FILENAME) + 1;
        mFileName = new char[totalLen];
        snprintf(mFileName, totalLen, "%s%s", mExternalStorage, TEST_FILENAME);

        int fd = ::open(mFileName, O_CREAT | O_TRUNC, S_IRUSR | S_IWUSR);
        if (fd < 0) {
            FAIL() << "Couldn't create " << mFileName << " for tests";
        }
    }

    virtual void TearDown() {
    }
};

TEST_F(ObbFileTest, ReadFailure) {
    EXPECT_FALSE(mObbFile->readFrom(-1))
            << "No failure on invalid file descriptor";
}

TEST_F(ObbFileTest, WriteThenRead) {
    const char* packageName = "com.example.obbfile";
    const int32_t versionNum = 1;

    mObbFile->setPackageName(String8(packageName));
    mObbFile->setVersion(versionNum);
#define SALT_SIZE 8
    unsigned char salt[SALT_SIZE] = {0x01, 0x10, 0x55, 0xAA, 0xFF, 0x00, 0x5A, 0xA5};
    EXPECT_TRUE(mObbFile->setSalt(salt, SALT_SIZE))
            << "Salt should be successfully set";

    EXPECT_TRUE(mObbFile->writeTo(mFileName))
            << "couldn't write to fake .obb file";

    mObbFile = new ObbFile();

    EXPECT_TRUE(mObbFile->readFrom(mFileName))
            << "couldn't read from fake .obb file";

    EXPECT_EQ(versionNum, mObbFile->getVersion())
            << "version didn't come out the same as it went in";
    const char* currentPackageName = mObbFile->getPackageName().string();
    EXPECT_STREQ(packageName, currentPackageName)
            << "package name didn't come out the same as it went in";

    size_t saltLen;
    const unsigned char* newSalt = mObbFile->getSalt(&saltLen);

    EXPECT_EQ(sizeof(salt), saltLen)
            << "salt sizes were not the same";

    for (int i = 0; i < sizeof(salt); i++) {
        EXPECT_EQ(salt[i], newSalt[i])
                << "salt character " << i << " should be equal";
    }
    EXPECT_TRUE(memcmp(newSalt, salt, sizeof(salt)) == 0)
            << "salts should be the same";
}

}
