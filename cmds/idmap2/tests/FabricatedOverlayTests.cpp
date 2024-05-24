/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <android-base/file.h>
#include <gtest/gtest.h>
#include <idmap2/FabricatedOverlay.h>
#include "TestHelpers.h"

#include <fstream>
#include <utility>

namespace android::idmap2 {

TEST(FabricatedOverlayTests, OverlayInfo) {
  auto overlay =
      FabricatedOverlay::Builder("com.example.overlay", "SandTheme", "com.example.target")
          .SetOverlayable("TestResources")
          .Build();

  ASSERT_TRUE(overlay);
  auto container = FabricatedOverlayContainer::FromOverlay(std::move(*overlay));
  auto info = container->FindOverlayInfo("SandTheme");
  ASSERT_TRUE(info);
  EXPECT_EQ("SandTheme", (*info).name);
  EXPECT_EQ("TestResources", (*info).target_name);

  info = container->FindOverlayInfo("OceanTheme");
  ASSERT_FALSE(info);
}

TEST(FabricatedOverlayTests, SetResourceValue) {
  auto path = GetTestDataPath() + "/overlay/res/drawable/android.png";
  auto fd = android::base::unique_fd(::open(path.c_str(), O_RDONLY | O_CLOEXEC));
  ASSERT_TRUE(fd > 0) << "errno " << errno << " for path " << path;

  auto overlay =
      FabricatedOverlay::Builder("com.example.overlay", "SandTheme", "com.example.target")
          .SetResourceValue(
              "com.example.target:integer/int1", Res_value::TYPE_INT_DEC, 1U, "port")
          .SetResourceValue(
              "com.example.target.split:integer/int2", Res_value::TYPE_INT_DEC, 2U, "land")
          .SetResourceValue(
              "string/int3", Res_value::TYPE_REFERENCE, 0x7f010000, "xxhdpi-v7")
          .SetResourceValue(
              "com.example.target:string/string1",
              Res_value::TYPE_STRING,
              "foobar",
              "en-rUS-normal-xxhdpi-v21")
          .SetResourceValue("com.example.target:drawable/dr1", fd, 0, 8341, "port-xxhdpi-v7", false)
          .setFrroPath("/foo/bar/biz.frro")
          .Build();
  ASSERT_TRUE(overlay);
  auto container = FabricatedOverlayContainer::FromOverlay(std::move(*overlay));
  auto info = container->FindOverlayInfo("SandTheme");
  ASSERT_TRUE(info);
  ASSERT_TRUE((*info).target_name.empty());

  auto crc = (*container).GetCrc();
  ASSERT_TRUE(crc) << crc.GetErrorMessage();
  EXPECT_NE(0U, *crc);

  auto pairs = container->GetOverlayData(*info);
  ASSERT_TRUE(pairs);
  ASSERT_EQ(5U, pairs->pairs.size());
  auto string_pool = ResStringPool(pairs->string_pool_data->data.get(),
                                        pairs->string_pool_data->data_length, false);

  auto& it = pairs->pairs[0];
  ASSERT_EQ("com.example.target:drawable/dr1", it.resource_name);
  auto entry = std::get_if<TargetValueWithConfig>(&it.value);
  ASSERT_NE(nullptr, entry);
  ASSERT_EQ(std::string("frro://foo/bar/biz.frro?offset=16&size=8341"),
      string_pool.string8At(entry->value.data_value).value_or(""));
  ASSERT_EQ(Res_value::TYPE_STRING, entry->value.data_type);
  ASSERT_EQ("port-xxhdpi-v7", entry->config);

  it = pairs->pairs[1];
  ASSERT_EQ("com.example.target:integer/int1", it.resource_name);
  entry = std::get_if<TargetValueWithConfig>(&it.value);
  ASSERT_NE(nullptr, entry);
  ASSERT_EQ(1U, entry->value.data_value);
  ASSERT_EQ(Res_value::TYPE_INT_DEC, entry->value.data_type);
  ASSERT_EQ("port", entry->config);

  it = pairs->pairs[2];
  ASSERT_EQ("com.example.target:string/int3", it.resource_name);
  entry = std::get_if<TargetValueWithConfig>(&it.value);
  ASSERT_NE(nullptr, entry);
  ASSERT_EQ(0x7f010000, entry->value.data_value);
  ASSERT_EQ(Res_value::TYPE_REFERENCE, entry->value.data_type);
  ASSERT_EQ("xxhdpi-v7", entry->config);

  it = pairs->pairs[3];
  ASSERT_EQ("com.example.target:string/string1", it.resource_name);
  entry = std::get_if<TargetValueWithConfig>(&it.value);
  ASSERT_NE(nullptr, entry);
  ASSERT_EQ(Res_value::TYPE_STRING, entry->value.data_type);
  ASSERT_EQ(std::string("foobar"), string_pool.string8At(entry->value.data_value).value_or(""));
  ASSERT_EQ("en-rUS-normal-xxhdpi-v21", entry->config);

  it = pairs->pairs[4];
  ASSERT_EQ("com.example.target.split:integer/int2", it.resource_name);
  entry = std::get_if<TargetValueWithConfig>(&it.value);
  ASSERT_NE(nullptr, entry);
  ASSERT_EQ(2U, entry->value.data_value);
  ASSERT_EQ(Res_value::TYPE_INT_DEC, entry->value.data_type);
  ASSERT_EQ("land", entry->config);
}

TEST(FabricatedOverlayTests, SetResourceValueBadArgs) {
  {
    auto builder =
        FabricatedOverlay::Builder("com.example.overlay", "SandTheme", "com.example.target")
            .SetResourceValue("int1", Res_value::TYPE_INT_DEC, 1U, "");
    ASSERT_FALSE(builder.Build());
  }
  {
    auto builder =
        FabricatedOverlay::Builder("com.example.overlay", "SandTheme", "com.example.target")
            .SetResourceValue("com.example.target:int2", Res_value::TYPE_INT_DEC, 1U, "");
    ASSERT_FALSE(builder.Build());
  }
}

TEST(FabricatedOverlayTests, SerializeAndDeserialize) {
  auto overlay =
      FabricatedOverlay::Builder("com.example.overlay", "SandTheme", "com.example.target")
          .SetOverlayable("TestResources")
          .SetResourceValue("com.example.target:integer/int1", Res_value::TYPE_INT_DEC, 1U, "")
          .SetResourceValue(
              "com.example.target:string/string1", Res_value::TYPE_STRING, "foobar", "")
          .Build();
  ASSERT_TRUE(overlay);
  TempFrroFile tf;
  std::ofstream out(tf.path);
  ASSERT_TRUE((*overlay).ToBinaryStream(out));
  out.close();

  auto container = OverlayResourceContainer::FromPath(tf.path);
  ASSERT_TRUE(container) << container.GetErrorMessage();
  EXPECT_EQ(tf.path, (*container)->GetPath());

  auto crc = (*container)->GetCrc();
  ASSERT_TRUE(crc) << crc.GetErrorMessage();
  EXPECT_NE(0U, *crc);

  auto info = (*container)->FindOverlayInfo("SandTheme");
  ASSERT_TRUE(info) << info.GetErrorMessage();
  EXPECT_EQ("SandTheme", (*info).name);
  EXPECT_EQ("TestResources", (*info).target_name);

  auto pairs = (*container)->GetOverlayData(*info);
  ASSERT_TRUE(pairs) << pairs.GetErrorMessage();
  EXPECT_EQ(2U, pairs->pairs.size());
  auto string_pool = ResStringPool(pairs->string_pool_data->data.get(),
                                        pairs->string_pool_data->data_length, false);

  auto& it = pairs->pairs[0];
  ASSERT_EQ("com.example.target:integer/int1", it.resource_name);
  auto entry = std::get_if<TargetValueWithConfig>(&it.value);
  ASSERT_NE(nullptr, entry);
  EXPECT_EQ(1U, entry->value.data_value);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, entry->value.data_type);

  it = pairs->pairs[1];
  ASSERT_EQ("com.example.target:string/string1", it.resource_name);
  entry = std::get_if<TargetValueWithConfig>(&it.value);
  ASSERT_NE(nullptr, entry);
  ASSERT_EQ(Res_value::TYPE_STRING, entry->value.data_type);
  ASSERT_EQ(std::string("foobar"), string_pool.string8At(entry->value.data_value).value_or(""));
}

}  // namespace android::idmap2
