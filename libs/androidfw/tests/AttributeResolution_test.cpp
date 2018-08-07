/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "androidfw/AttributeResolution.h"

#include <array>

#include "android-base/file.h"
#include "android-base/logging.h"
#include "android-base/macros.h"
#include "androidfw/AssetManager2.h"
#include "androidfw/ResourceUtils.h"

#include "TestHelpers.h"
#include "data/styles/R.h"

using com::android::app::R;

namespace android {

class AttributeResolutionTest : public ::testing::Test {
 public:
  virtual void SetUp() override {
    styles_assets_ = ApkAssets::Load(GetTestDataPath() + "/styles/styles.apk");
    ASSERT_NE(nullptr, styles_assets_);
    assetmanager_.SetApkAssets({styles_assets_.get()});
  }

 protected:
  std::unique_ptr<const ApkAssets> styles_assets_;
  AssetManager2 assetmanager_;
};

class AttributeResolutionXmlTest : public AttributeResolutionTest {
 public:
  virtual void SetUp() override {
    AttributeResolutionTest::SetUp();

    std::unique_ptr<Asset> asset =
        assetmanager_.OpenNonAsset("res/layout/layout.xml", Asset::ACCESS_BUFFER);
    ASSERT_NE(nullptr, asset);

    ASSERT_EQ(NO_ERROR,
              xml_parser_.setTo(asset->getBuffer(true), asset->getLength(), true /*copyData*/));

    // Skip to the first tag.
    while (xml_parser_.next() != ResXMLParser::START_TAG) {
    }
  }

 protected:
  ResXMLTree xml_parser_;
};

TEST(AttributeResolutionLibraryTest, ApplyStyleWithDefaultStyleResId) {
  AssetManager2 assetmanager;
  auto apk_assets = ApkAssets::LoadAsSharedLibrary(GetTestDataPath() + "/styles/styles.apk");
  ASSERT_NE(nullptr, apk_assets);
  assetmanager.SetApkAssets({apk_assets.get()});

  std::unique_ptr<Theme> theme = assetmanager.NewTheme();

  std::array<uint32_t, 2> attrs{
      {fix_package_id(R::attr::attr_one, 0x02), fix_package_id(R::attr::attr_two, 0x02)}};
  std::array<uint32_t, attrs.size() * STYLE_NUM_ENTRIES> values;
  std::array<uint32_t, attrs.size() + 1> indices;
  ApplyStyle(theme.get(), nullptr /*xml_parser*/, 0u /*def_style_attr*/,
             fix_package_id(R::style::StyleOne, 0x02), attrs.data(), attrs.size(), values.data(),
             indices.data());

  const uint32_t public_flag = ResTable_typeSpec::SPEC_PUBLIC;

  const uint32_t* values_cursor = values.data();
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(1u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(2u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);
}

TEST_F(AttributeResolutionTest, Theme) {
  std::unique_ptr<Theme> theme = assetmanager_.NewTheme();
  ASSERT_TRUE(theme->ApplyStyle(R::style::StyleTwo));

  std::array<uint32_t, 5> attrs{{R::attr::attr_one, R::attr::attr_two, R::attr::attr_three,
                                 R::attr::attr_four, R::attr::attr_empty}};
  std::array<uint32_t, attrs.size() * STYLE_NUM_ENTRIES> values;

  ASSERT_TRUE(ResolveAttrs(theme.get(), 0u /*def_style_attr*/, 0u /*def_style_res*/,
                           nullptr /*src_values*/, 0 /*src_values_length*/, attrs.data(),
                           attrs.size(), values.data(), nullptr /*out_indices*/));

  const uint32_t public_flag = ResTable_typeSpec::SPEC_PUBLIC;

  const uint32_t* values_cursor = values.data();
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(1u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_STRING, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(3u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_NULL, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(Res_value::DATA_NULL_UNDEFINED, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  // @empty comes from the theme, so it has the same asset cookie and changing configurations flags
  // as the theme.
  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_NULL, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(Res_value::DATA_NULL_EMPTY, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);
}

TEST_F(AttributeResolutionXmlTest, XmlParser) {
  std::array<uint32_t, 5> attrs{{R::attr::attr_one, R::attr::attr_two, R::attr::attr_three,
                                 R::attr::attr_four, R::attr::attr_empty}};
  std::array<uint32_t, attrs.size() * STYLE_NUM_ENTRIES> values;

  ASSERT_TRUE(RetrieveAttributes(&assetmanager_, &xml_parser_, attrs.data(), attrs.size(),
                                 values.data(), nullptr /*out_indices*/));

  uint32_t* values_cursor = values.data();
  EXPECT_EQ(Res_value::TYPE_NULL, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(Res_value::DATA_NULL_EMPTY, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_NULL, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(10u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_ATTRIBUTE, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(R::attr::attr_indirect, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_NULL, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(Res_value::DATA_NULL_UNDEFINED, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);
}

TEST_F(AttributeResolutionXmlTest, ThemeAndXmlParser) {
  std::unique_ptr<Theme> theme = assetmanager_.NewTheme();
  ASSERT_TRUE(theme->ApplyStyle(R::style::StyleTwo));

  std::array<uint32_t, 6> attrs{{R::attr::attr_one, R::attr::attr_two, R::attr::attr_three,
                                 R::attr::attr_four, R::attr::attr_five, R::attr::attr_empty}};
  std::array<uint32_t, attrs.size() * STYLE_NUM_ENTRIES> values;
  std::array<uint32_t, attrs.size() + 1> indices;

  ApplyStyle(theme.get(), &xml_parser_, 0u /*def_style_attr*/, 0u /*def_style_res*/, attrs.data(),
             attrs.size(), values.data(), indices.data());

  const uint32_t public_flag = ResTable_typeSpec::SPEC_PUBLIC;

  uint32_t* values_cursor = values.data();
  EXPECT_EQ(Res_value::TYPE_NULL, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(Res_value::DATA_NULL_EMPTY, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_STRING, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(10u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(uint32_t(-1), values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(0u, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_INT_DEC, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(3u, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_STRING, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(R::string::string_one, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  // @empty comes from the theme, so it has the same asset cookie and changing configurations flags
  // as the theme.
  values_cursor += STYLE_NUM_ENTRIES;
  EXPECT_EQ(Res_value::TYPE_NULL, values_cursor[STYLE_TYPE]);
  EXPECT_EQ(Res_value::DATA_NULL_EMPTY, values_cursor[STYLE_DATA]);
  EXPECT_EQ(0u, values_cursor[STYLE_RESOURCE_ID]);
  EXPECT_EQ(1u, values_cursor[STYLE_ASSET_COOKIE]);
  EXPECT_EQ(0u, values_cursor[STYLE_DENSITY]);
  EXPECT_EQ(public_flag, values_cursor[STYLE_CHANGING_CONFIGURATIONS]);

  // The first element of indices contains the number of indices.
  std::array<uint32_t, 7> expected_indices = {{6u, 0u, 1u, 2u, 3u, 4u, 5u}};
  EXPECT_EQ(expected_indices, indices);
}

} // namespace android

