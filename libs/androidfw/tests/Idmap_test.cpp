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

using com::android::basic::R;

namespace android {

class IdmapTest : public ::testing::Test {
 protected:
  void SetUp() override {
    std::string contents;
    ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/basic/basic.apk",
                                        "resources.arsc", &contents));
    ASSERT_EQ(NO_ERROR,
              target_table_.add(contents.data(), contents.size(), 0, true));

    ASSERT_TRUE(
        ReadFileFromZipToString(GetTestDataPath() + "/overlay/overlay.apk",
                                "resources.arsc", &overlay_data_));
    ResTable overlay_table;
    ASSERT_EQ(NO_ERROR,
              overlay_table.add(overlay_data_.data(), overlay_data_.size()));

    char target_name[256] = "com.android.basic";
    ASSERT_EQ(NO_ERROR,
              target_table_.createIdmap(overlay_table, 0, 0, target_name,
                                        target_name, &data_, &data_size_));
  }

  void TearDown() override { ::free(data_); }

  ResTable target_table_;
  std::string overlay_data_;
  void* data_ = nullptr;
  size_t data_size_ = 0;
};

TEST_F(IdmapTest, canLoadIdmap) {
  ASSERT_EQ(NO_ERROR,
            target_table_.add(overlay_data_.data(), overlay_data_.size(), data_,
                              data_size_));
}

TEST_F(IdmapTest, overlayOverridesResourceValue) {
  Res_value val;
  ssize_t block = target_table_.getResource(R::string::test2, &val, false);
  ASSERT_GE(block, 0);
  ASSERT_EQ(Res_value::TYPE_STRING, val.dataType);
  const ResStringPool* pool = target_table_.getTableStringBlock(block);
  ASSERT_TRUE(pool != NULL);
  ASSERT_LT(val.data, pool->size());

  size_t strLen;
  const char16_t* targetStr16 = pool->stringAt(val.data, &strLen);
  ASSERT_TRUE(targetStr16 != NULL);
  ASSERT_EQ(String16("test2"), String16(targetStr16, strLen));

  ASSERT_EQ(NO_ERROR,
            target_table_.add(overlay_data_.data(), overlay_data_.size(), data_,
                              data_size_));

  ssize_t newBlock = target_table_.getResource(R::string::test2, &val, false);
  ASSERT_GE(newBlock, 0);
  ASSERT_NE(block, newBlock);
  ASSERT_EQ(Res_value::TYPE_STRING, val.dataType);
  pool = target_table_.getTableStringBlock(newBlock);
  ASSERT_TRUE(pool != NULL);
  ASSERT_LT(val.data, pool->size());

  targetStr16 = pool->stringAt(val.data, &strLen);
  ASSERT_TRUE(targetStr16 != NULL);
  ASSERT_EQ(String16("test2-overlay"), String16(targetStr16, strLen));
}

TEST_F(IdmapTest, overlaidResourceHasSameName) {
  ASSERT_EQ(NO_ERROR,
            target_table_.add(overlay_data_.data(), overlay_data_.size(), data_,
                              data_size_));

  ResTable::resource_name resName;
  ASSERT_TRUE(
      target_table_.getResourceName(R::array::integerArray1, false, &resName));

  ASSERT_TRUE(resName.package != NULL);
  ASSERT_TRUE(resName.type != NULL);
  ASSERT_TRUE(resName.name != NULL);

  EXPECT_EQ(String16("com.android.basic"),
            String16(resName.package, resName.packageLen));
  EXPECT_EQ(String16("array"), String16(resName.type, resName.typeLen));
  EXPECT_EQ(String16("integerArray1"), String16(resName.name, resName.nameLen));
}

}  // namespace
