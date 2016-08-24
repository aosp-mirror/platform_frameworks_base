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

#include <gtest/gtest.h>

#include "Resource.h"

namespace aapt {

TEST(ResourceTypeTest, ParseResourceTypes) {
    const ResourceType* type = parseResourceType(u"anim");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kAnim);

    type = parseResourceType(u"animator");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kAnimator);

    type = parseResourceType(u"array");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kArray);

    type = parseResourceType(u"attr");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kAttr);

    type = parseResourceType(u"^attr-private");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kAttrPrivate);

    type = parseResourceType(u"bool");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kBool);

    type = parseResourceType(u"color");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kColor);

    type = parseResourceType(u"dimen");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kDimen);

    type = parseResourceType(u"drawable");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kDrawable);

    type = parseResourceType(u"fraction");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kFraction);

    type = parseResourceType(u"id");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kId);

    type = parseResourceType(u"integer");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kInteger);

    type = parseResourceType(u"interpolator");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kInterpolator);

    type = parseResourceType(u"layout");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kLayout);

    type = parseResourceType(u"menu");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kMenu);

    type = parseResourceType(u"mipmap");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kMipmap);

    type = parseResourceType(u"plurals");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kPlurals);

    type = parseResourceType(u"raw");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kRaw);

    type = parseResourceType(u"string");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kString);

    type = parseResourceType(u"style");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kStyle);

    type = parseResourceType(u"transition");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kTransition);

    type = parseResourceType(u"xml");
    ASSERT_NE(type, nullptr);
    EXPECT_EQ(*type, ResourceType::kXml);

    type = parseResourceType(u"blahaha");
    EXPECT_EQ(type, nullptr);
}

} // namespace aapt
