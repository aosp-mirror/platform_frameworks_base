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

#include "SdkConstants.h"

#include "gtest/gtest.h"

namespace aapt {

TEST(SdkConstantsTest, FirstAttributeIsSdk1) {
  EXPECT_EQ(1, FindAttributeSdkLevel(ResourceId(0x01010000)));
}

TEST(SdkConstantsTest, NonFrameworkAttributeIsSdk0) {
  EXPECT_EQ(0, FindAttributeSdkLevel(ResourceId(0x7f010345)));
}

TEST(SdkConstantsTest, GetDevelopmentSdkCodeNameVersionValid) {
  EXPECT_EQ(std::optional<ApiVersion>(10000), GetDevelopmentSdkCodeNameVersion("Q"));
  EXPECT_EQ(std::optional<ApiVersion>(10000), GetDevelopmentSdkCodeNameVersion("VanillaIceCream"));
}

TEST(SdkConstantsTest, GetDevelopmentSdkCodeNameVersionPrivacySandbox) {
  EXPECT_EQ(std::optional<ApiVersion>(10000), GetDevelopmentSdkCodeNameVersion("QPrivacySandbox"));
  EXPECT_EQ(std::optional<ApiVersion>(10000),
            GetDevelopmentSdkCodeNameVersion("VanillaIceCreamPrivacySandbox"));
}

TEST(SdkConstantsTest, GetDevelopmentSdkCodeNameVersionInvalid) {
  EXPECT_EQ(std::optional<ApiVersion>(), GetDevelopmentSdkCodeNameVersion("A"));
  EXPECT_EQ(std::optional<ApiVersion>(), GetDevelopmentSdkCodeNameVersion("Sv3"));
  EXPECT_EQ(std::optional<ApiVersion>(),
            GetDevelopmentSdkCodeNameVersion("VanillaIceCream_PrivacySandbox"));
  EXPECT_EQ(std::optional<ApiVersion>(), GetDevelopmentSdkCodeNameVersion("PrivacySandbox"));
  EXPECT_EQ(std::optional<ApiVersion>(), GetDevelopmentSdkCodeNameVersion("QQQQQQQQQQQQQQQ"));
}

}  // namespace aapt
