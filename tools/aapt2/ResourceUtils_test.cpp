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
#include "test/Builders.h"
#include "test/Common.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(ResourceUtilsTest, ParseBool) {
    bool val = false;
    EXPECT_TRUE(ResourceUtils::tryParseBool(u"true", &val));
    EXPECT_TRUE(val);

    EXPECT_TRUE(ResourceUtils::tryParseBool(u"TRUE", &val));
    EXPECT_TRUE(val);

    EXPECT_TRUE(ResourceUtils::tryParseBool(u"True", &val));
    EXPECT_TRUE(val);

    EXPECT_TRUE(ResourceUtils::tryParseBool(u"false", &val));
    EXPECT_FALSE(val);

    EXPECT_TRUE(ResourceUtils::tryParseBool(u"FALSE", &val));
    EXPECT_FALSE(val);

    EXPECT_TRUE(ResourceUtils::tryParseBool(u"False", &val));
    EXPECT_FALSE(val);
}

TEST(ResourceUtilsTest, ParseResourceName) {
    ResourceNameRef actual;
    bool actualPriv = false;
    EXPECT_TRUE(ResourceUtils::parseResourceName(u"android:color/foo", &actual, &actualPriv));
    EXPECT_EQ(ResourceNameRef(u"android", ResourceType::kColor, u"foo"), actual);
    EXPECT_FALSE(actualPriv);

    EXPECT_TRUE(ResourceUtils::parseResourceName(u"color/foo", &actual, &actualPriv));
    EXPECT_EQ(ResourceNameRef({}, ResourceType::kColor, u"foo"), actual);
    EXPECT_FALSE(actualPriv);

    EXPECT_TRUE(ResourceUtils::parseResourceName(u"*android:color/foo", &actual, &actualPriv));
    EXPECT_EQ(ResourceNameRef(u"android", ResourceType::kColor, u"foo"), actual);
    EXPECT_TRUE(actualPriv);

    EXPECT_FALSE(ResourceUtils::parseResourceName(StringPiece16(), &actual, &actualPriv));
}

TEST(ResourceUtilsTest, ParseReferenceWithNoPackage) {
    ResourceNameRef expected({}, ResourceType::kColor, u"foo");
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::tryParseReference(u"@color/foo", &actual, &create, &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceUtilsTest, ParseReferenceWithPackage) {
    ResourceNameRef expected(u"android", ResourceType::kColor, u"foo");
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
    ResourceNameRef expected(u"android", ResourceType::kColor, u"foo");
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
    ResourceNameRef expected(u"android", ResourceType::kId, u"foo");
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
    ResourceNameRef expected(u"android", ResourceType::kId, u"foo");
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

TEST(ResourceUtilsTest, ParseAttributeReferences) {
    EXPECT_TRUE(ResourceUtils::isAttributeReference(u"?android"));
    EXPECT_TRUE(ResourceUtils::isAttributeReference(u"?android:foo"));
    EXPECT_TRUE(ResourceUtils::isAttributeReference(u"?attr/foo"));
    EXPECT_TRUE(ResourceUtils::isAttributeReference(u"?android:attr/foo"));
}

TEST(ResourceUtilsTest, FailParseIncompleteReference) {
    EXPECT_FALSE(ResourceUtils::isAttributeReference(u"?style/foo"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference(u"?android:style/foo"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference(u"?android:"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference(u"?android:attr/"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference(u"?:attr/"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference(u"?:attr/foo"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference(u"?:/"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference(u"?:/foo"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference(u"?attr/"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference(u"?/foo"));
}

TEST(ResourceUtilsTest, ParseStyleParentReference) {
    const ResourceName kAndroidStyleFooName(u"android", ResourceType::kStyle, u"foo");
    const ResourceName kStyleFooName({}, ResourceType::kStyle, u"foo");

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

    ref = ResourceUtils::parseStyleParentReference(u"@android:foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

    ref = ResourceUtils::parseStyleParentReference(u"foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kStyleFooName);

    ref = ResourceUtils::parseStyleParentReference(u"*android:style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);
    EXPECT_TRUE(ref.value().privateReference);
}

TEST(ResourceUtilsTest, ParseEmptyFlag) {
    std::unique_ptr<Attribute> attr = test::AttributeBuilder(false)
            .setTypeMask(android::ResTable_map::TYPE_FLAGS)
            .addItem(u"one", 0x01)
            .addItem(u"two", 0x02)
            .build();

    std::unique_ptr<BinaryPrimitive> result = ResourceUtils::tryParseFlagSymbol(attr.get(), u"");
    ASSERT_NE(nullptr, result);
    EXPECT_EQ(0u, result->value.data);
}

} // namespace aapt
