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
#include "ResourceUtils.h"

#include "test/Common.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(ResourceUtilsTest, ParseReferenceWithNoPackage) {
    ResourceNameRef expected = { {}, ResourceType::kColor, u"foo" };
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::tryParseReference(u"@color/foo", &actual, &create, &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceUtilsTest, ParseReferenceWithPackage) {
    ResourceNameRef expected = { u"android", ResourceType::kColor, u"foo" };
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::tryParseReference(u"@android:color/foo", &actual, &create,
                                                 &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceUtilsTest, ParseReferenceWithSurroundingWhitespace) {
    ResourceNameRef expected = { u"android", ResourceType::kColor, u"foo" };
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::tryParseReference(u"\t @android:color/foo\n \n\t", &actual,
                                                 &create, &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceUtilsTest, ParseAutoCreateIdReference) {
    ResourceNameRef expected = { u"android", ResourceType::kId, u"foo" };
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::tryParseReference(u"@+android:id/foo", &actual, &create,
                                                 &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_TRUE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceUtilsTest, ParsePrivateReference) {
    ResourceNameRef expected = { u"android", ResourceType::kId, u"foo" };
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::tryParseReference(u"@*android:id/foo", &actual, &create,
                                                 &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_TRUE(privateRef);
}

TEST(ResourceUtilsTest, FailToParseAutoCreateNonIdReference) {
    bool create = false;
    bool privateRef = false;
    ResourceNameRef actual;
    EXPECT_FALSE(ResourceUtils::tryParseReference(u"@+android:color/foo", &actual, &create,
                                                  &privateRef));
}

TEST(ResourceUtilsTest, ParseStyleParentReference) {
    const ResourceName kAndroidStyleFooName = { u"android", ResourceType::kStyle, u"foo" };
    const ResourceName kStyleFooName = { {}, ResourceType::kStyle, u"foo" };

    std::string errStr;
    Maybe<Reference> ref = ResourceUtils::parseStyleParentReference(u"@android:style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

    ref = ResourceUtils::parseStyleParentReference(u"@style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kStyleFooName);

    ref = ResourceUtils::parseStyleParentReference(u"?android:style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

    ref = ResourceUtils::parseStyleParentReference(u"?style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kStyleFooName);

    ref = ResourceUtils::parseStyleParentReference(u"android:style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

    ref = ResourceUtils::parseStyleParentReference(u"android:foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

    ref = ResourceUtils::parseStyleParentReference(u"foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kStyleFooName);
}

} // namespace aapt
