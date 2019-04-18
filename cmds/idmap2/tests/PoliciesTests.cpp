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
#include "gtest/gtest.h"
#include "idmap2/Policies.h"

using android::idmap2::PolicyBitmask;
using android::idmap2::PolicyFlags;

namespace android::idmap2 {

TEST(PoliciesTests, PoliciesToBitmasks) {
  const auto bitmask1 = PoliciesToBitmask({"system"});
  ASSERT_TRUE(bitmask1);
  ASSERT_EQ(*bitmask1, PolicyFlags::POLICY_SYSTEM_PARTITION);

  const auto bitmask2 = PoliciesToBitmask({"system", "vendor"});
  ASSERT_TRUE(bitmask2);
  ASSERT_EQ(*bitmask2, PolicyFlags::POLICY_SYSTEM_PARTITION | PolicyFlags::POLICY_VENDOR_PARTITION);

  const auto bitmask3 = PoliciesToBitmask({"vendor", "system"});
  ASSERT_TRUE(bitmask3);
  ASSERT_EQ(*bitmask3, PolicyFlags::POLICY_SYSTEM_PARTITION | PolicyFlags::POLICY_VENDOR_PARTITION);

  const auto bitmask4 = PoliciesToBitmask({"odm", "oem", "public", "product", "system", "vendor"});
  ASSERT_TRUE(bitmask4);
  ASSERT_EQ(*bitmask4, PolicyFlags::POLICY_ODM_PARTITION | PolicyFlags::POLICY_OEM_PARTITION |
                           PolicyFlags::POLICY_PUBLIC | PolicyFlags::POLICY_PRODUCT_PARTITION |
                           PolicyFlags::POLICY_SYSTEM_PARTITION |
                           PolicyFlags::POLICY_VENDOR_PARTITION);

  const auto bitmask5 = PoliciesToBitmask({"system", "system", "system"});
  ASSERT_TRUE(bitmask5);
  ASSERT_EQ(*bitmask5, PolicyFlags::POLICY_SYSTEM_PARTITION);

  const auto bitmask6 = PoliciesToBitmask({""});
  ASSERT_FALSE(bitmask6);

  const auto bitmask7 = PoliciesToBitmask({"foo"});
  ASSERT_FALSE(bitmask7);

  const auto bitmask8 = PoliciesToBitmask({"system", "foo"});
  ASSERT_FALSE(bitmask8);

  const auto bitmask9 = PoliciesToBitmask({"system", ""});
  ASSERT_FALSE(bitmask9);

  const auto bitmask10 = PoliciesToBitmask({"system "});
  ASSERT_FALSE(bitmask10);
}

TEST(PoliciesTests, BitmaskToPolicies) {
  const auto policies1 = BitmaskToPolicies(PolicyFlags::POLICY_PUBLIC);
  ASSERT_EQ(1, policies1.size());
  ASSERT_EQ(policies1[0], "public");

  const auto policies2 = BitmaskToPolicies(PolicyFlags::POLICY_SYSTEM_PARTITION |
                                           PolicyFlags::POLICY_VENDOR_PARTITION);
  ASSERT_EQ(2, policies2.size());
  ASSERT_EQ(policies2[0], "system");
  ASSERT_EQ(policies2[1], "vendor");

  const auto policies3 = BitmaskToPolicies(
      PolicyFlags::POLICY_ODM_PARTITION | PolicyFlags::POLICY_OEM_PARTITION |
      PolicyFlags::POLICY_PUBLIC | PolicyFlags::POLICY_PRODUCT_PARTITION |
      PolicyFlags::POLICY_SYSTEM_PARTITION | PolicyFlags::POLICY_VENDOR_PARTITION);
  ASSERT_EQ(2, policies2.size());
  ASSERT_EQ(policies3[0], "odm");
  ASSERT_EQ(policies3[1], "oem");
  ASSERT_EQ(policies3[2], "public");
  ASSERT_EQ(policies3[3], "product");
  ASSERT_EQ(policies3[4], "system");
  ASSERT_EQ(policies3[5], "vendor");
}

}  // namespace android::idmap2
