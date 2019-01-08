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

#include "gtest/gtest.h"

#include "TestHelpers.h"
#include "idmap2/Policies.h"

using android::idmap2::PolicyBitmask;
using android::idmap2::PolicyFlags;

namespace android::idmap2 {

TEST(PoliciesTests, PoliciesToBitmasks) {
  const Result<PolicyBitmask> bitmask1 = PoliciesToBitmask({"system"}, std::cerr);
  ASSERT_NE(bitmask1, kResultError);
  ASSERT_EQ(bitmask1, PolicyFlags::POLICY_SYSTEM_PARTITION);

  const Result<PolicyBitmask> bitmask2 = PoliciesToBitmask({"system", "vendor"}, std::cerr);
  ASSERT_NE(bitmask2, kResultError);
  ASSERT_EQ(bitmask2, PolicyFlags::POLICY_SYSTEM_PARTITION | PolicyFlags::POLICY_VENDOR_PARTITION);

  const Result<PolicyBitmask> bitmask3 = PoliciesToBitmask({"vendor", "system"}, std::cerr);
  ASSERT_NE(bitmask3, kResultError);
  ASSERT_EQ(bitmask3, PolicyFlags::POLICY_SYSTEM_PARTITION | PolicyFlags::POLICY_VENDOR_PARTITION);

  const Result<PolicyBitmask> bitmask4 =
      PoliciesToBitmask({"public", "product", "system", "vendor"}, std::cerr);
  ASSERT_NE(bitmask4, kResultError);
  ASSERT_EQ(bitmask4, PolicyFlags::POLICY_PUBLIC | PolicyFlags::POLICY_PRODUCT_PARTITION |
                          PolicyFlags::POLICY_SYSTEM_PARTITION |
                          PolicyFlags::POLICY_VENDOR_PARTITION);

  const Result<PolicyBitmask> bitmask5 =
      PoliciesToBitmask({"system", "system", "system"}, std::cerr);
  ASSERT_NE(bitmask5, kResultError);
  ASSERT_EQ(bitmask5, PolicyFlags::POLICY_SYSTEM_PARTITION);

  const Result<PolicyBitmask> bitmask6 = PoliciesToBitmask({""}, std::cerr);
  ASSERT_EQ(bitmask6, kResultError);

  const Result<PolicyBitmask> bitmask7 = PoliciesToBitmask({"foo"}, std::cerr);
  ASSERT_EQ(bitmask7, kResultError);

  const Result<PolicyBitmask> bitmask8 = PoliciesToBitmask({"system", "foo"}, std::cerr);
  ASSERT_EQ(bitmask8, kResultError);

  const Result<PolicyBitmask> bitmask9 = PoliciesToBitmask({"system", ""}, std::cerr);
  ASSERT_EQ(bitmask9, kResultError);

  const Result<PolicyBitmask> bitmask10 = PoliciesToBitmask({"system "}, std::cerr);
  ASSERT_EQ(bitmask10, kResultError);
}

}  // namespace android::idmap2
