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

using ::testing::Eq;
using ::testing::Optional;

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

TEST(ResourceTypeTest, ParseResourceNamedType) {
  auto type = ParseResourceNamedType("anim");
  EXPECT_THAT(type, Optional(Eq(ResourceNamedType("anim", ResourceType::kAnim))));

  type = ParseResourceNamedType("layout");
  EXPECT_THAT(type, Optional(Eq(ResourceNamedType("layout", ResourceType::kLayout))));

  type = ParseResourceNamedType("layout:2");
  EXPECT_THAT(type, Optional(Eq(ResourceNamedType("layout:2", ResourceType::kLayout))));

  type = ParseResourceNamedType("layout:another");
  EXPECT_THAT(type, Optional(Eq(ResourceNamedType("layout:another", ResourceType::kLayout))));

  type = ParseResourceNamedType("layout:");
  EXPECT_THAT(type, Eq(std::nullopt));

  type = ParseResourceNamedType("layout2");
  EXPECT_THAT(type, Eq(std::nullopt));

  type = ParseResourceNamedType("blahaha");
  EXPECT_THAT(type, Eq(std::nullopt));
}

TEST(ResourceTypeTest, ResourceNamedTypeWithDefaultName) {
  auto type = ResourceNamedTypeWithDefaultName(ResourceType::kAnim);
  EXPECT_THAT(type, Eq(ResourceNamedType("anim", ResourceType::kAnim)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kAnimator);
  EXPECT_THAT(type, Eq(ResourceNamedType("animator", ResourceType::kAnimator)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kArray);
  EXPECT_THAT(type, Eq(ResourceNamedType("array", ResourceType::kArray)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kAttr);
  EXPECT_THAT(type, Eq(ResourceNamedType("attr", ResourceType::kAttr)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kAttrPrivate);
  EXPECT_THAT(type, Eq(ResourceNamedType("^attr-private", ResourceType::kAttrPrivate)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kBool);
  EXPECT_THAT(type, Eq(ResourceNamedType("bool", ResourceType::kBool)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kColor);
  EXPECT_THAT(type, Eq(ResourceNamedType("color", ResourceType::kColor)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kConfigVarying);
  EXPECT_THAT(type, Eq(ResourceNamedType("configVarying", ResourceType::kConfigVarying)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kDimen);
  EXPECT_THAT(type, Eq(ResourceNamedType("dimen", ResourceType::kDimen)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kDrawable);
  EXPECT_THAT(type, Eq(ResourceNamedType("drawable", ResourceType::kDrawable)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kFont);
  EXPECT_THAT(type, Eq(ResourceNamedType("font", ResourceType::kFont)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kFraction);
  EXPECT_THAT(type, Eq(ResourceNamedType("fraction", ResourceType::kFraction)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kId);
  EXPECT_THAT(type, Eq(ResourceNamedType("id", ResourceType::kId)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kInteger);
  EXPECT_THAT(type, Eq(ResourceNamedType("integer", ResourceType::kInteger)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kInterpolator);
  EXPECT_THAT(type, Eq(ResourceNamedType("interpolator", ResourceType::kInterpolator)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kLayout);
  EXPECT_THAT(type, Eq(ResourceNamedType("layout", ResourceType::kLayout)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kMenu);
  EXPECT_THAT(type, Eq(ResourceNamedType("menu", ResourceType::kMenu)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kMipmap);
  EXPECT_THAT(type, Eq(ResourceNamedType("mipmap", ResourceType::kMipmap)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kNavigation);
  EXPECT_THAT(type, Eq(ResourceNamedType("navigation", ResourceType::kNavigation)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kPlurals);
  EXPECT_THAT(type, Eq(ResourceNamedType("plurals", ResourceType::kPlurals)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kRaw);
  EXPECT_THAT(type, Eq(ResourceNamedType("raw", ResourceType::kRaw)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kString);
  EXPECT_THAT(type, Eq(ResourceNamedType("string", ResourceType::kString)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kStyle);
  EXPECT_THAT(type, Eq(ResourceNamedType("style", ResourceType::kStyle)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kTransition);
  EXPECT_THAT(type, Eq(ResourceNamedType("transition", ResourceType::kTransition)));

  type = ResourceNamedTypeWithDefaultName(ResourceType::kXml);
  EXPECT_THAT(type, Eq(ResourceNamedType("xml", ResourceType::kXml)));
}

}  // namespace aapt
