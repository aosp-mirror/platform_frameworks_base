/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <string>

#include "TestHelpers.h"
#include "androidfw/ResourceTypes.h"
#include "gtest/gtest.h"
#include "idmap2/PolicyUtils.h"

using android::idmap2::utils::BitmaskToPolicies;
using android::idmap2::utils::PoliciesToBitmaskResult;

using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;
using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace android::idmap2 {

TEST(PoliciesTests, PoliciesToBitmaskResults) {
  const auto bitmask1 = PoliciesToBitmaskResult({"system"});
  ASSERT_TRUE(bitmask1);
  ASSERT_EQ(*bitmask1, PolicyFlags::SYSTEM_PARTITION);

  const auto bitmask2 = PoliciesToBitmaskResult({"system", "vendor"});
  ASSERT_TRUE(bitmask2);
  ASSERT_EQ(*bitmask2, PolicyFlags::SYSTEM_PARTITION | PolicyFlags::VENDOR_PARTITION);

  const auto bitmask3 = PoliciesToBitmaskResult({"vendor", "system"});
  ASSERT_TRUE(bitmask3);
  ASSERT_EQ(*bitmask3, PolicyFlags::SYSTEM_PARTITION | PolicyFlags::VENDOR_PARTITION);

  const auto bitmask4 =
      PoliciesToBitmaskResult({"odm", "oem", "public", "product", "system", "vendor"});
  ASSERT_TRUE(bitmask4);
  ASSERT_EQ(*bitmask4, PolicyFlags::ODM_PARTITION | PolicyFlags::OEM_PARTITION |
                           PolicyFlags::PUBLIC | PolicyFlags::PRODUCT_PARTITION |
                           PolicyFlags::SYSTEM_PARTITION | PolicyFlags::VENDOR_PARTITION);

  const auto bitmask5 = PoliciesToBitmaskResult({"system", "system", "system"});
  ASSERT_TRUE(bitmask5);
  ASSERT_EQ(*bitmask5, PolicyFlags::SYSTEM_PARTITION);

  const auto bitmask6 = PoliciesToBitmaskResult({""});
  ASSERT_FALSE(bitmask6);

  const auto bitmask7 = PoliciesToBitmaskResult({"foo"});
  ASSERT_FALSE(bitmask7);

  const auto bitmask8 = PoliciesToBitmaskResult({"system", "foo"});
  ASSERT_FALSE(bitmask8);

  const auto bitmask9 = PoliciesToBitmaskResult({"system", ""});
  ASSERT_FALSE(bitmask9);

  const auto bitmask10 = PoliciesToBitmaskResult({"system "});
  ASSERT_FALSE(bitmask10);

  const auto bitmask11 = PoliciesToBitmaskResult({"signature"});
  ASSERT_TRUE(bitmask11);
  ASSERT_EQ(*bitmask11, PolicyFlags::SIGNATURE);

  const auto bitmask12 = PoliciesToBitmaskResult({"actor"});
  ASSERT_TRUE(bitmask12);
  ASSERT_EQ(*bitmask12, PolicyFlags::ACTOR_SIGNATURE);
}

TEST(PoliciesTests, BitmaskToPolicies) {
  const auto policies1 = BitmaskToPolicies(PolicyFlags::PUBLIC);
  ASSERT_EQ(1, policies1.size());
  ASSERT_EQ(policies1[0], "public");

  const auto policies2 =
      BitmaskToPolicies(PolicyFlags::SYSTEM_PARTITION | PolicyFlags::VENDOR_PARTITION);
  ASSERT_EQ(2, policies2.size());
  ASSERT_EQ(policies2[0], "system");
  ASSERT_EQ(policies2[1], "vendor");

  const auto policies3 =
      BitmaskToPolicies(PolicyFlags::ODM_PARTITION | PolicyFlags::OEM_PARTITION |
                        PolicyFlags::PUBLIC | PolicyFlags::PRODUCT_PARTITION |
                        PolicyFlags::SYSTEM_PARTITION | PolicyFlags::VENDOR_PARTITION);
  ASSERT_EQ(2, policies2.size());
  ASSERT_EQ(policies3[0], "odm");
  ASSERT_EQ(policies3[1], "oem");
  ASSERT_EQ(policies3[2], "product");
  ASSERT_EQ(policies3[3], "public");
  ASSERT_EQ(policies3[4], "system");
  ASSERT_EQ(policies3[5], "vendor");

  const auto policies4 = BitmaskToPolicies(PolicyFlags::SIGNATURE);
  ASSERT_EQ(1, policies4.size());
  ASSERT_EQ(policies4[0], "signature");

  const auto policies5 = BitmaskToPolicies(PolicyFlags::ACTOR_SIGNATURE);
  ASSERT_EQ(1, policies5.size());
  ASSERT_EQ(policies5[0], "actor");
}

}  // namespace android::idmap2
