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
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "TestHelpers.h"
#include "androidfw/ApkAssets.h"
#include "androidfw/Idmap.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/Idmap.h"

using ::testing::NotNull;

namespace android::idmap2 {

TEST(BinaryStreamVisitorTests, CreateBinaryStreamViaBinaryStreamVisitor) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream raw_stream(raw);

  auto result1 = Idmap::FromBinaryStream(raw_stream);
  ASSERT_TRUE(result1);
  const auto idmap1 = std::move(*result1);

  std::stringstream stream;
  BinaryStreamVisitor visitor(stream);
  idmap1->accept(&visitor);

  auto result2 = Idmap::FromBinaryStream(stream);
  ASSERT_TRUE(result2);
  const auto idmap2 = std::move(*result2);

  ASSERT_EQ(idmap1->GetHeader()->GetFulfilledPolicies(),
            idmap2->GetHeader()->GetFulfilledPolicies());
  ASSERT_EQ(idmap1->GetHeader()->GetEnforceOverlayable(),
            idmap2->GetHeader()->GetEnforceOverlayable());
  ASSERT_EQ(idmap1->GetHeader()->GetTargetPath(), idmap2->GetHeader()->GetTargetPath());
  ASSERT_EQ(idmap1->GetHeader()->GetTargetCrc(), idmap2->GetHeader()->GetTargetCrc());
  ASSERT_EQ(idmap1->GetHeader()->GetTargetPath(), idmap2->GetHeader()->GetTargetPath());
  ASSERT_EQ(idmap1->GetData().size(), 1U);
  ASSERT_EQ(idmap1->GetData().size(), idmap2->GetData().size());

  const std::vector<std::unique_ptr<const IdmapData>>& data_blocks1 = idmap1->GetData();
  ASSERT_EQ(data_blocks1.size(), 1U);
  const std::unique_ptr<const IdmapData>& data1 = data_blocks1[0];
  ASSERT_THAT(data1, NotNull());

  const std::vector<std::unique_ptr<const IdmapData>>& data_blocks2 = idmap2->GetData();
  ASSERT_EQ(data_blocks2.size(), 1U);
  const std::unique_ptr<const IdmapData>& data2 = data_blocks2[0];
  ASSERT_THAT(data2, NotNull());

  const auto& target_entries1 = data1->GetTargetEntries();
  const auto& target_entries2 = data2->GetTargetEntries();
  ASSERT_EQ(target_entries1.size(), target_entries2.size());
  ASSERT_EQ(target_entries1[0].target_id, target_entries2[0].target_id);
  ASSERT_EQ(target_entries1[0].overlay_id, target_entries2[0].overlay_id);

  ASSERT_EQ(target_entries1[1].target_id, target_entries2[1].target_id);
  ASSERT_EQ(target_entries1[1].overlay_id, target_entries2[1].overlay_id);

  ASSERT_EQ(target_entries1[2].target_id, target_entries2[2].target_id);
  ASSERT_EQ(target_entries1[2].overlay_id, target_entries2[2].overlay_id);

  const auto& target_inline_entries1 = data1->GetTargetInlineEntries();
  const auto& target_inline_entries2 = data2->GetTargetInlineEntries();
  ASSERT_EQ(target_inline_entries1.size(), target_inline_entries2.size());
  ASSERT_EQ(target_inline_entries1[0].target_id, target_inline_entries2[0].target_id);
  ASSERT_EQ(target_inline_entries1[0].value.data_type, target_inline_entries2[0].value.data_type);
  ASSERT_EQ(target_inline_entries1[0].value.data_value, target_inline_entries2[0].value.data_value);

  const auto& overlay_entries1 = data1->GetOverlayEntries();
  const auto& overlay_entries2 = data2->GetOverlayEntries();
  ASSERT_EQ(overlay_entries1.size(), overlay_entries2.size());
  ASSERT_EQ(overlay_entries1[0].overlay_id, overlay_entries2[0].overlay_id);
  ASSERT_EQ(overlay_entries1[0].target_id, overlay_entries2[0].target_id);

  ASSERT_EQ(overlay_entries1[1].overlay_id, overlay_entries2[1].overlay_id);
  ASSERT_EQ(overlay_entries1[1].target_id, overlay_entries2[1].target_id);

  ASSERT_EQ(overlay_entries1[2].overlay_id, overlay_entries2[2].overlay_id);
  ASSERT_EQ(overlay_entries1[2].target_id, overlay_entries2[2].target_id);
}

}  // namespace android::idmap2
