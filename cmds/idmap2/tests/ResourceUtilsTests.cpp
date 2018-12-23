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

#include <memory>
#include <string>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "androidfw/ApkAssets.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"

#include "TestHelpers.h"

using ::testing::NotNull;

namespace android::idmap2 {

class ResourceUtilsTests : public Idmap2Tests {
 protected:
  void SetUp() override {
    Idmap2Tests::SetUp();

    apk_assets_ = ApkAssets::Load(GetTargetApkPath());
    ASSERT_THAT(apk_assets_, NotNull());

    am_.SetApkAssets({apk_assets_.get()});
  }

  const AssetManager2& GetAssetManager() {
    return am_;
  }

 private:
  AssetManager2 am_;
  std::unique_ptr<const ApkAssets> apk_assets_;
};

TEST_F(ResourceUtilsTests, ResToTypeEntryName) {
  Result<std::string> name = utils::ResToTypeEntryName(GetAssetManager(), 0x7f010000U);
  ASSERT_TRUE(name);
  ASSERT_EQ(*name, "integer/int1");
}

TEST_F(ResourceUtilsTests, ResToTypeEntryNameNoSuchResourceId) {
  Result<std::string> name = utils::ResToTypeEntryName(GetAssetManager(), 0x7f123456U);
  ASSERT_FALSE(name);
}

}  // namespace android::idmap2
