/*
 * Copyright (C) 2020 The Android Open Source Project
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

#define LOG_TAG "BackupHelpers_test"
#include <androidfw/BackupHelpers.h>

#include <gtest/gtest.h>

#include <fcntl.h>
#include <utils/String8.h>
#include <android-base/file.h>

namespace android {

class BackupHelpersTest : public testing::Test {
protected:

    virtual void SetUp() {
    }
    virtual void TearDown() {
    }
};

TEST_F(BackupHelpersTest, WriteTarFileWithSizeLessThan2GB) {
  TemporaryFile tf;
  // Allocate a 1 KB file.
  off64_t fileSize = 1024;
  ASSERT_EQ(0, posix_fallocate64(tf.fd, 0, fileSize));
  off64_t tarSize = 0;
  int err = write_tarfile(/* packageName */ String8("test-pkg"), /* domain */ String8(""), /* rootpath */ String8(""), /* filePath */ String8(tf.path), /* outSize */ &tarSize, /* writer */ NULL);
  ASSERT_EQ(err, 0);
  // Returned tarSize includes 512 B for the header.
  off64_t expectedTarSize = fileSize + 512;
  ASSERT_EQ(tarSize, expectedTarSize);
}

TEST_F(BackupHelpersTest, WriteTarFileWithSizeGreaterThan2GB) {
  TemporaryFile tf;
  // Allocate a 2 GB file.
  off64_t fileSize = 2ll * 1024ll * 1024ll * 1024ll + 512ll;
  ASSERT_EQ(0, posix_fallocate64(tf.fd, 0, fileSize));
  off64_t tarSize = 0;
  int err = write_tarfile(/* packageName */ String8("test-pkg"), /* domain */ String8(""), /* rootpath */ String8(""), /* filePath */ String8(tf.path), /* outSize */ &tarSize, /* writer */ NULL);
  ASSERT_EQ(err, 0);
  // Returned tarSize includes 512 B for the header.
  off64_t expectedTarSize = fileSize + 512;
  ASSERT_EQ(tarSize, expectedTarSize);
}
}

