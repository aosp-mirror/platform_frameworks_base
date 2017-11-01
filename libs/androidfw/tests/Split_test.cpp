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

static void makeConfigFrench(ResTable_config* config) {
  memset(config, 0, sizeof(*config));
  config->language[0] = 'f';
  config->language[1] = 'r';
}

class SplitTest : public ::testing::Test {
 public:
  void SetUp() override {
    ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/basic/basic.apk",
                                        "resources.arsc", &basic_contents_));
    ASSERT_TRUE(
        ReadFileFromZipToString(GetTestDataPath() + "/basic/basic_de_fr.apk",
                                "resources.arsc", &basic_de_fr_contents_));
    ASSERT_TRUE(
        ReadFileFromZipToString(GetTestDataPath() + "/basic/basic_hdpi-v4.apk",
                                "resources.arsc", &basic_hdpi_contents_));
    ASSERT_TRUE(
        ReadFileFromZipToString(GetTestDataPath() + "/basic/basic_xhdpi-v4.apk",
                                "resources.arsc", &basic_xhdpi_contents_));
    ASSERT_TRUE(ReadFileFromZipToString(
        GetTestDataPath() + "/basic/basic_xxhdpi-v4.apk", "resources.arsc",
        &basic_xxhdpi_contents_));
    ASSERT_TRUE(
        ReadFileFromZipToString(GetTestDataPath() + "/feature/feature.apk",
                                "resources.arsc", &feature_contents_));
  }

 protected:
  std::string basic_contents_;
  std::string basic_de_fr_contents_;
  std::string basic_hdpi_contents_;
  std::string basic_xhdpi_contents_;
  std::string basic_xxhdpi_contents_;
  std::string feature_contents_;
};

TEST_F(SplitTest, TestLoadBase) {
  ResTable table;
  ASSERT_EQ(NO_ERROR,
            table.add(basic_contents_.data(), basic_contents_.size()));
}

TEST_F(SplitTest, TestGetResourceFromBase) {
  ResTable_config frenchConfig;
  makeConfigFrench(&frenchConfig);

  ResTable table;
  table.setParameters(&frenchConfig);

  ASSERT_EQ(NO_ERROR,
            table.add(basic_contents_.data(), basic_contents_.size()));

  ResTable_config expectedConfig;
  memset(&expectedConfig, 0, sizeof(expectedConfig));

  Res_value val;
  ResTable_config config;
  ssize_t block = table.getResource(R::string::test1, &val, MAY_NOT_BE_BAG, 0,
                                    NULL, &config);

  // The returned block should tell us which string pool to get the value, if it
  // is a string.
  EXPECT_GE(block, 0);

  // We expect the default resource to be selected since it is the only resource
  // configuration.
  EXPECT_EQ(0, expectedConfig.compare(config));

  EXPECT_EQ(Res_value::TYPE_STRING, val.dataType);
}

TEST_F(SplitTest, TestGetResourceFromSplit) {
  ResTable_config expectedConfig;
  makeConfigFrench(&expectedConfig);

  ResTable table;
  table.setParameters(&expectedConfig);

  ASSERT_EQ(NO_ERROR,
            table.add(basic_contents_.data(), basic_contents_.size()));
  ASSERT_EQ(NO_ERROR, table.add(basic_de_fr_contents_.data(),
                                basic_de_fr_contents_.size()));

  Res_value val;
  ResTable_config config;
  ssize_t block = table.getResource(R::string::test1, &val, MAY_NOT_BE_BAG, 0,
                                    NULL, &config);

  EXPECT_GE(block, 0);

  EXPECT_EQ(0, expectedConfig.compare(config));

  EXPECT_EQ(Res_value::TYPE_STRING, val.dataType);
}

TEST_F(SplitTest, ResourcesFromBaseAndSplitHaveSameNames) {
  ResTable_config expectedConfig;
  makeConfigFrench(&expectedConfig);

  ResTable table;
  table.setParameters(&expectedConfig);

  ASSERT_EQ(NO_ERROR,
            table.add(basic_contents_.data(), basic_contents_.size()));

  ResTable::resource_name baseName;
  EXPECT_TRUE(table.getResourceName(R::string::test1, false, &baseName));

  ASSERT_EQ(NO_ERROR, table.add(basic_de_fr_contents_.data(),
                                basic_de_fr_contents_.size()));

  ResTable::resource_name frName;
  EXPECT_TRUE(table.getResourceName(R::string::test1, false, &frName));

  EXPECT_EQ(String16(baseName.package, baseName.packageLen),
            String16(frName.package, frName.packageLen));

  EXPECT_EQ(String16(baseName.type, baseName.typeLen),
            String16(frName.type, frName.typeLen));

  EXPECT_EQ(String16(baseName.name, baseName.nameLen),
            String16(frName.name, frName.nameLen));
}

TEST_F(SplitTest, TypeEntrySpecFlagsAreUpdated) {
  ResTable_config defaultConfig;
  memset(&defaultConfig, 0, sizeof(defaultConfig));

  ResTable table;
  ASSERT_EQ(NO_ERROR,
            table.add(basic_contents_.data(), basic_contents_.size()));

  Res_value val;
  uint32_t specFlags = 0;
  ssize_t block = table.getResource(R::string::test1, &val, MAY_NOT_BE_BAG, 0,
                                    &specFlags, NULL);
  EXPECT_GE(block, 0);

  EXPECT_EQ(static_cast<uint32_t>(ResTable_typeSpec::SPEC_PUBLIC), specFlags);

  ASSERT_EQ(NO_ERROR, table.add(basic_de_fr_contents_.data(),
                                basic_de_fr_contents_.size()));

  uint32_t frSpecFlags = 0;
  block = table.getResource(R::string::test1, &val, MAY_NOT_BE_BAG, 0,
                            &frSpecFlags, NULL);
  ASSERT_GE(block, 0);

  EXPECT_EQ(static_cast<uint32_t>(ResTable_config::CONFIG_LOCALE | ResTable_typeSpec::SPEC_PUBLIC),
            frSpecFlags);
}

TEST_F(SplitTest, SelectBestDensity) {
  ResTable_config baseConfig;
  memset(&baseConfig, 0, sizeof(baseConfig));
  baseConfig.density = ResTable_config::DENSITY_XHIGH;
  baseConfig.sdkVersion = 21;

  ResTable table;
  table.setParameters(&baseConfig);
  ASSERT_EQ(NO_ERROR,
            table.add(basic_contents_.data(), basic_contents_.size()));
  ASSERT_EQ(NO_ERROR, table.add(basic_hdpi_contents_.data(),
                                basic_hdpi_contents_.size()));

  EXPECT_TRUE(IsStringEqual(table, R::string::density, "hdpi"));

  ASSERT_EQ(NO_ERROR, table.add(basic_xhdpi_contents_.data(),
                                basic_xhdpi_contents_.size()));

  EXPECT_TRUE(IsStringEqual(table, R::string::density, "xhdpi"));

  ASSERT_EQ(NO_ERROR, table.add(basic_xxhdpi_contents_.data(),
                                basic_xxhdpi_contents_.size()));

  EXPECT_TRUE(IsStringEqual(table, R::string::density, "xhdpi"));

  baseConfig.density = ResTable_config::DENSITY_XXHIGH;
  table.setParameters(&baseConfig);

  EXPECT_TRUE(IsStringEqual(table, R::string::density, "xxhdpi"));
}

TEST_F(SplitTest, TestNewResourceIsAccessible) {
  ResTable table;
  ASSERT_EQ(NO_ERROR,
            table.add(basic_contents_.data(), basic_contents_.size()));

  Res_value val;
  ssize_t block = table.getResource(R::string::test3, &val, MAY_NOT_BE_BAG);
  EXPECT_LT(block, 0);

  ASSERT_EQ(NO_ERROR,
            table.add(feature_contents_.data(), feature_contents_.size()));

  block = table.getResource(R::string::test3, &val, MAY_NOT_BE_BAG);
  ASSERT_GE(block, 0);

  EXPECT_EQ(Res_value::TYPE_STRING, val.dataType);
}

TEST_F(SplitTest, TestNewResourceNameHasCorrectName) {
  ResTable table;
  ASSERT_EQ(NO_ERROR,
            table.add(basic_contents_.data(), basic_contents_.size()));

  ResTable::resource_name name;
  EXPECT_FALSE(table.getResourceName(R::string::test3, false, &name));

  ASSERT_EQ(NO_ERROR,
            table.add(feature_contents_.data(), feature_contents_.size()));

  ASSERT_TRUE(table.getResourceName(R::string::test3, false, &name));

  EXPECT_EQ(String16("com.android.basic"),
            String16(name.package, name.packageLen));

  EXPECT_EQ(String16("string"), String16(name.type, name.typeLen));

  EXPECT_EQ(String16("test3"), String16(name.name, name.nameLen));
}

TEST_F(SplitTest, TestNewResourceIsAccessibleByName) {
  ResTable table;
  ASSERT_EQ(NO_ERROR,
            table.add(basic_contents_.data(), basic_contents_.size()));
  ASSERT_EQ(NO_ERROR,
            table.add(feature_contents_.data(), feature_contents_.size()));

  const String16 name("test3");
  const String16 type("string");
  const String16 package("com.android.basic");
  ASSERT_EQ(
      R::string::test3,
      table.identifierForName(name.string(), name.size(), type.string(),
                              type.size(), package.string(), package.size()));
}

}  // namespace
