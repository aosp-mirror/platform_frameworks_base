/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include "androidfw/ResourceTypes.h"

#include "utils/String16.h"
#include "utils/String8.h"

#include "TestHelpers.h"
#include "data/basic/R.h"

using ::com::android::basic::R;

namespace android {

class IdmapTest : public ::testing::Test {
 protected:
  void SetUp() override {
    std::string contents;
    ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/basic/basic.apk", "resources.arsc",
                                        &contents));
    ASSERT_EQ(NO_ERROR, target_table_.add(contents.data(), contents.size(), 0, true));

    ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/overlay/overlay.apk",
                                        "resources.arsc", &overlay_data_));
    ResTable overlay_table;
    ASSERT_EQ(NO_ERROR, overlay_table.add(overlay_data_.data(), overlay_data_.size()));

    char target_name[256] = "com.android.basic";
    ASSERT_EQ(NO_ERROR, target_table_.createIdmap(overlay_table, 0, 0, target_name, target_name,
                                                  &data_, &data_size_));
  }

  void TearDown() override {
    ::free(data_);
  }

  ResTable target_table_;
  std::string overlay_data_;
  void* data_ = nullptr;
  size_t data_size_ = 0;
};

TEST_F(IdmapTest, CanLoadIdmap) {
  ASSERT_EQ(NO_ERROR,
            target_table_.add(overlay_data_.data(), overlay_data_.size(), data_, data_size_));
}

TEST_F(IdmapTest, OverlayOverridesResourceValue) {
  Res_value val;
  ssize_t block = target_table_.getResource(R::string::test2, &val, false);
  ASSERT_GE(block, 0);
  ASSERT_EQ(Res_value::TYPE_STRING, val.dataType);
  const ResStringPool* pool = target_table_.getTableStringBlock(block);
  ASSERT_TRUE(pool != NULL);
  ASSERT_LT(val.data, pool->size());

  size_t str_len;
  const char16_t* target_str16 = pool->stringAt(val.data, &str_len);
  ASSERT_TRUE(target_str16 != NULL);
  ASSERT_EQ(String16("test2"), String16(target_str16, str_len));

  ASSERT_EQ(NO_ERROR,
            target_table_.add(overlay_data_.data(), overlay_data_.size(), data_, data_size_));

  ssize_t new_block = target_table_.getResource(R::string::test2, &val, false);
  ASSERT_GE(new_block, 0);
  ASSERT_NE(block, new_block);
  ASSERT_EQ(Res_value::TYPE_STRING, val.dataType);
  pool = target_table_.getTableStringBlock(new_block);
  ASSERT_TRUE(pool != NULL);
  ASSERT_LT(val.data, pool->size());

  target_str16 = pool->stringAt(val.data, &str_len);
  ASSERT_TRUE(target_str16 != NULL);
  ASSERT_EQ(String16("test2-overlay"), String16(target_str16, str_len));
}

TEST_F(IdmapTest, OverlaidResourceHasSameName) {
  ASSERT_EQ(NO_ERROR,
            target_table_.add(overlay_data_.data(), overlay_data_.size(), data_, data_size_));

  ResTable::resource_name res_name;
  ASSERT_TRUE(target_table_.getResourceName(R::array::integerArray1, false, &res_name));

  ASSERT_TRUE(res_name.package != NULL);
  ASSERT_TRUE(res_name.type != NULL);
  ASSERT_TRUE(res_name.name != NULL);

  EXPECT_EQ(String16("com.android.basic"), String16(res_name.package, res_name.packageLen));
  EXPECT_EQ(String16("array"), String16(res_name.type, res_name.typeLen));
  EXPECT_EQ(String16("integerArray1"), String16(res_name.name, res_name.nameLen));
}

constexpr const uint32_t kNonOverlaidResourceId = 0x7fff0000u;

TEST_F(IdmapTest, OverlayDoesNotIncludeNonOverlaidResources) {
  // First check that the resource we're trying to not include when overlaid is present when
  // the overlay is loaded as a standalone APK.
  ResTable table;
  ASSERT_EQ(NO_ERROR, table.add(overlay_data_.data(), overlay_data_.size(), 0, true));

  Res_value val;
  ssize_t block = table.getResource(kNonOverlaidResourceId, &val, false /*mayBeBag*/);
  ASSERT_GE(block, 0);

  // Now add the overlay and verify that the unoverlaid resource is gone.
  ASSERT_EQ(NO_ERROR,
            target_table_.add(overlay_data_.data(), overlay_data_.size(), data_, data_size_));
  block = target_table_.getResource(kNonOverlaidResourceId, &val, false /*mayBeBag*/);
  ASSERT_LT(block, 0);
}

}  // namespace
