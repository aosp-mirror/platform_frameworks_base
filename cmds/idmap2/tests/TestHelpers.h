/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef IDMAP2_TESTS_TESTHELPERS_H_
#define IDMAP2_TESTS_TESTHELPERS_H_

#include <string>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

namespace android::idmap2 {

const unsigned char idmap_raw_data[] = {
    // IDMAP HEADER
    // 0x0: magic
    0x49, 0x44, 0x4d, 0x50,

    // 0x4: version
    0x06, 0x00, 0x00, 0x00,

    // 0x8: target crc
    0x34, 0x12, 0x00, 0x00,

    // 0xc: overlay crc
    0x78, 0x56, 0x00, 0x00,

    // 0x10: fulfilled policies
    0x11, 0x00, 0x00, 0x00,

    // 0x14: enforce overlayable
    0x01, 0x00, 0x00, 0x00,

    // 0x18: target path length
    0x0b, 0x00, 0x00, 0x00,

    // 0x1c: target path "targetX.apk"
    0x74, 0x61, 0x72, 0x67, 0x65, 0x74, 0x58, 0x2e, 0x61, 0x70, 0x6b, 0x00,

    // 0x28: overlay path length
    0x0c, 0x00, 0x00, 0x00,

    // 0x2c: overlay path "overlayX.apk"
    0x6f, 0x76, 0x65, 0x72, 0x6c, 0x61, 0x79, 0x58, 0x2e, 0x61, 0x70, 0x6b,

    // 0x38: debug string
    // string length,
    0x05, 0x00, 0x00, 0x00,

    // 0x3c string contents "debug\0\0\0" (padded to word alignment)
    0x64, 0x65, 0x62, 0x75, 0x67, 0x00, 0x00, 0x00,

    // DATA HEADER
    // 0x44: target_package_id
    0x7f,

    // 0x45: overlay_package_id
    0x7f,

    // 0x46: padding
    0x00, 0x00,

    // 0x48: target_entry_count
    0x03, 0x00, 0x00, 0x00,

    // 0x4c: target_inline_entry_count
    0x01, 0x00, 0x00, 0x00,

    // 0x50: overlay_entry_count
    0x03, 0x00, 0x00, 0x00,

    // 0x54: string_pool_offset
    0x00, 0x00, 0x00, 0x00,

    // TARGET ENTRIES
    // 0x58: target id (0x7f020000)
    0x00, 0x00, 0x02, 0x7f,

    // 0x5c: overlay_id (0x7f020000)
    0x00, 0x00, 0x02, 0x7f,

    // 0x60: target id (0x7f030000)
    0x00, 0x00, 0x03, 0x7f,

    // 0x64: overlay_id (0x7f030000)
    0x00, 0x00, 0x03, 0x7f,

    // 0x68: target id (0x7f030002)
    0x02, 0x00, 0x03, 0x7f,

    // 0x6c: overlay_id (0x7f030001)
    0x01, 0x00, 0x03, 0x7f,

    // INLINE TARGET ENTRIES

    // 0x70: target_id
    0x00, 0x00, 0x04, 0x7f,

    // 0x74: Res_value::size (value ignored by idmap)
    0x08, 0x00,

    // 0x77: Res_value::res0 (value ignored by idmap)
    0x00,

    // 0x78: Res_value::dataType (TYPE_INT_HEX)
    0x11,

    // 0x7c: Res_value::data
    0x78, 0x56, 0x34, 0x12,

    // OVERLAY ENTRIES
    // 0x80: 0x7f020000 -> 0x7f020000
    0x00, 0x00, 0x02, 0x7f, 0x00, 0x00, 0x02, 0x7f,

    // 0x88: 0x7f030000 -> 0x7f030000
    0x00, 0x00, 0x03, 0x7f, 0x00, 0x00, 0x03, 0x7f,

    // 0x90: 0x7f030001 -> 0x7f030002
    0x01, 0x00, 0x03, 0x7f, 0x02, 0x00, 0x03, 0x7f,

    // 0x94: string pool
    // string length,
    0x04, 0x00, 0x00, 0x00,

    // 0x98 string contents "test"
    0x74, 0x65, 0x73, 0x74};

const unsigned int idmap_raw_data_len = 0x9c;
const unsigned int idmap_raw_data_offset = 0x44;
const unsigned int idmap_raw_data_target_crc = 0x1234;
const unsigned int idmap_raw_data_overlay_crc = 0x5678;
const unsigned int idmap_raw_data_policies = 0x11;
inline const std::string idmap_raw_data_target_path = "targetX.apk";
inline const std::string idmap_raw_data_overlay_path = "overlayX.apk";

std::string GetTestDataPath();

class Idmap2Tests : public testing::Test {
 protected:
  void SetUp() override {
#ifdef __ANDROID__
    tmp_dir_path_ = "/data/local/tmp/idmap2-tests-XXXXXX";
#else
    tmp_dir_path_ = "/tmp/idmap2-tests-XXXXXX";
#endif
    EXPECT_NE(mkdtemp(const_cast<char*>(tmp_dir_path_.c_str())), nullptr)
        << "Failed to create temporary directory: " << strerror(errno);
    target_apk_path_ = GetTestDataPath() + "/target/target.apk";
    overlay_apk_path_ = GetTestDataPath() + "/overlay/overlay.apk";
    idmap_path_ = tmp_dir_path_ + "/a.idmap";
  }

  void TearDown() override {
    EXPECT_EQ(rmdir(tmp_dir_path_.c_str()), 0)
        << "Failed to remove temporary directory " << tmp_dir_path_ << ": " << strerror(errno);
  }

  const std::string& GetTempDirPath() {
    return tmp_dir_path_;
  }

  const std::string& GetTargetApkPath() {
    return target_apk_path_;
  }

  const std::string& GetOverlayApkPath() {
    return overlay_apk_path_;
  }

  const std::string& GetIdmapPath() {
    return idmap_path_;
  }

 private:
  std::string tmp_dir_path_;
  std::string target_apk_path_;
  std::string overlay_apk_path_;
  std::string idmap_path_;
};

}  // namespace android::idmap2

#endif  // IDMAP2_TESTS_TESTHELPERS_H_
