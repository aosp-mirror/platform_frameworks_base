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
    const ResourceType* type = parseResourceType("anim");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kAnim);

    type = parseResourceType("animator");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kAnimator);

    type = parseResourceType("array");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kArray);

    type = parseResourceType("attr");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kAttr);

    type = parseResourceType("^attr-private");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kAttrPrivate);

    type = parseResourceType("bool");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kBool);

    type = parseResourceType("color");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kColor);

    type = parseResourceType("dimen");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kDimen);

    type = parseResourceType("drawable");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kDrawable);

    type = parseResourceType("fraction");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kFraction);

    type = parseResourceType("id");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kId);

    type = parseResourceType("integer");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kInteger);

    type = parseResourceType("interpolator");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kInterpolator);

    type = parseResourceType("layout");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kLayout);

    type = parseResourceType("menu");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kMenu);

    type = parseResourceType("mipmap");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kMipmap);

    type = parseResourceType("plurals");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kPlurals);

    type = parseResourceType("raw");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kRaw);

    type = parseResourceType("string");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kString);

    type = parseResourceType("style");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kStyle);

    type = parseResourceType("transition");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kTransition);

    type = parseResourceType("xml");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kXml);

    type = parseResourceType("blahaha");
    EXPECT_EQ(type, nullptr);
}

} // namespace aapt
