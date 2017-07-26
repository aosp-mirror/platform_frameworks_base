/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "Resource.h"

#include "test/Test.h"

namespace aapt {

TEST(ResourceTypeTest, ParseResourceTypes) {
  const ResourceType* type = ParseResourceType("anim");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kAnim);

  type = ParseResourceType("animator");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kAnimator);

  type = ParseResourceType("array");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kArray);

  type = ParseResourceType("attr");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kAttr);

  type = ParseResourceType("^attr-private");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kAttrPrivate);

  type = ParseResourceType("bool");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kBool);

  type = ParseResourceType("color");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kColor);

  type = ParseResourceType("configVarying");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kConfigVarying);

  type = ParseResourceType("dimen");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kDimen);

  type = ParseResourceType("drawable");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kDrawable);

  type = ParseResourceType("font");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kFont);

  type = ParseResourceType("fraction");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kFraction);

  type = ParseResourceType("id");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kId);

  type = ParseResourceType("integer");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kInteger);

  type = ParseResourceType("interpolator");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kInterpolator);

  type = ParseResourceType("layout");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kLayout);

  type = ParseResourceType("menu");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kMenu);

  type = ParseResourceType("mipmap");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kMipmap);

  type = ParseResourceType("navigation");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kNavigation);

  type = ParseResourceType("plurals");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kPlurals);

  type = ParseResourceType("raw");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kRaw);

  type = ParseResourceType("string");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kString);

  type = ParseResourceType("style");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kStyle);

  type = ParseResourceType("transition");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kTransition);

  type = ParseResourceType("xml");
  ASSERT_NE(type, nullptr);
  EXPECT_EQ(*type, ResourceType::kXml);

  type = ParseResourceType("blahaha");
  EXPECT_EQ(type, nullptr);
}

}  // namespace aapt
