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

#include "R.h"
#include "TestHelpers.h"
#include "androidfw/ApkAssets.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "idmap2/ResourceContainer.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"

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
  Result<std::string> name = utils::ResToTypeEntryName(GetAssetManager(), R::target::integer::int1);
  ASSERT_TRUE(name) << name.GetErrorMessage();
  ASSERT_EQ(*name, "integer/int1");
}

TEST_F(ResourceUtilsTests, ResToTypeEntryNameNoSuchResourceId) {
  Result<std::string> name = utils::ResToTypeEntryName(GetAssetManager(), 0x7f123456U);
  ASSERT_FALSE(name);
}

TEST_F(ResourceUtilsTests, InvalidValidOverlayNameInvalidAttributes) {
  auto overlay =
      OverlayResourceContainer::FromPath(GetTestDataPath() + "/overlay/overlay-invalid.apk");
  ASSERT_TRUE(overlay);

  auto info = (*overlay)->FindOverlayInfo("InvalidName");
  ASSERT_FALSE(info);
}

TEST_F(ResourceUtilsTests, ValidOverlayNameInvalidAttributes) {
  auto overlay =
      OverlayResourceContainer::FromPath(GetTestDataPath() + "/overlay/overlay-invalid.apk");
  ASSERT_TRUE(overlay);

  auto info = (*overlay)->FindOverlayInfo("ValidName");
  ASSERT_FALSE(info);
}

TEST_F(ResourceUtilsTests, ValidOverlayNameAndTargetPackageInvalidAttributes) {
  auto overlay =
      OverlayResourceContainer::FromPath(GetTestDataPath() + "/overlay/overlay-invalid.apk");
  ASSERT_TRUE(overlay);

  auto info = (*overlay)->FindOverlayInfo("ValidNameAndTargetPackage");
  ASSERT_TRUE(info);
  ASSERT_EQ("ValidNameAndTargetPackage", info->name);
  ASSERT_EQ("Valid", info->target_package);
  ASSERT_EQ("", info->target_name);      // Attribute resource id could not be found
  ASSERT_EQ(0, info->resource_mapping);  // Attribute resource id could not be found
}

}  // namespace android::idmap2
