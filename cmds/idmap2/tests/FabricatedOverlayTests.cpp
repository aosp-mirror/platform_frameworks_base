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
  auto overlay =
      FabricatedOverlay::Builder("com.example.overlay", "SandTheme", "com.example.target")
          .SetResourceValue("com.example.target:integer/int1", Res_value::TYPE_INT_DEC, 1U)
          .SetResourceValue("com.example.target.split:integer/int2", Res_value::TYPE_INT_DEC, 2U)
          .SetResourceValue("string/int3", Res_value::TYPE_REFERENCE, 0x7f010000)
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
  EXPECT_FALSE(pairs->string_pool_data.has_value());
  ASSERT_EQ(3U, pairs->pairs.size());

  auto& it = pairs->pairs[0];
  ASSERT_EQ("com.example.target:integer/int1", it.resource_name);
  auto entry = std::get_if<TargetValue>(&it.value);
  ASSERT_NE(nullptr, entry);
  ASSERT_EQ(1U, entry->data_value);
  ASSERT_EQ(Res_value::TYPE_INT_DEC, entry->data_type);

  it = pairs->pairs[1];
  ASSERT_EQ("com.example.target:string/int3", it.resource_name);
  entry = std::get_if<TargetValue>(&it.value);
  ASSERT_NE(nullptr, entry);
  ASSERT_EQ(0x7f010000, entry->data_value);
  ASSERT_EQ(Res_value::TYPE_REFERENCE, entry->data_type);

  it = pairs->pairs[2];
  ASSERT_EQ("com.example.target.split:integer/int2", it.resource_name);
  entry = std::get_if<TargetValue>(&it.value);
  ASSERT_NE(nullptr, entry);
  ASSERT_EQ(2U, entry->data_value);
  ASSERT_EQ(Res_value::TYPE_INT_DEC, entry->data_type);
}

TEST(FabricatedOverlayTests, SetResourceValueBadArgs) {
  {
    auto builder =
        FabricatedOverlay::Builder("com.example.overlay", "SandTheme", "com.example.target")
            .SetResourceValue("int1", Res_value::TYPE_INT_DEC, 1U);
    ASSERT_FALSE(builder.Build());
  }
  {
    auto builder =
        FabricatedOverlay::Builder("com.example.overlay", "SandTheme", "com.example.target")
            .SetResourceValue("com.example.target:int2", Res_value::TYPE_INT_DEC, 1U);
    ASSERT_FALSE(builder.Build());
  }
}

TEST(FabricatedOverlayTests, SerializeAndDeserialize) {
  auto overlay =
      FabricatedOverlay::Builder("com.example.overlay", "SandTheme", "com.example.target")
          .SetOverlayable("TestResources")
          .SetResourceValue("com.example.target:integer/int1", Res_value::TYPE_INT_DEC, 1U)
          .Build();
  ASSERT_TRUE(overlay);
  TemporaryFile tf;
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
  EXPECT_EQ(1U, pairs->pairs.size());

  auto& it = pairs->pairs[0];
  ASSERT_EQ("com.example.target:integer/int1", it.resource_name);
  auto entry = std::get_if<TargetValue>(&it.value);
  ASSERT_NE(nullptr, entry);
  EXPECT_EQ(1U, entry->data_value);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, entry->data_type);
}

}  // namespace android::idmap2
