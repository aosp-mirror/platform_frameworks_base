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
#include "test/Test.h"

namespace aapt {

TEST(ResourceUtilsTest, ParseBool) {
    EXPECT_EQ(Maybe<bool>(true), ResourceUtils::parseBool("true"));
    EXPECT_EQ(Maybe<bool>(true), ResourceUtils::parseBool("TRUE"));
    EXPECT_EQ(Maybe<bool>(true), ResourceUtils::parseBool("True"));
    EXPECT_EQ(Maybe<bool>(false), ResourceUtils::parseBool("false"));
    EXPECT_EQ(Maybe<bool>(false), ResourceUtils::parseBool("FALSE"));
    EXPECT_EQ(Maybe<bool>(false), ResourceUtils::parseBool("False"));
}

TEST(ResourceUtilsTest, ParseResourceName) {
    ResourceNameRef actual;
    bool actualPriv = false;
    EXPECT_TRUE(ResourceUtils::parseResourceName("android:color/foo", &actual, &actualPriv));
    EXPECT_EQ(ResourceNameRef("android", ResourceType::kColor, "foo"), actual);
    EXPECT_FALSE(actualPriv);

    EXPECT_TRUE(ResourceUtils::parseResourceName("color/foo", &actual, &actualPriv));
    EXPECT_EQ(ResourceNameRef({}, ResourceType::kColor, "foo"), actual);
    EXPECT_FALSE(actualPriv);

    EXPECT_TRUE(ResourceUtils::parseResourceName("*android:color/foo", &actual, &actualPriv));
    EXPECT_EQ(ResourceNameRef("android", ResourceType::kColor, "foo"), actual);
    EXPECT_TRUE(actualPriv);

    EXPECT_FALSE(ResourceUtils::parseResourceName(StringPiece(), &actual, &actualPriv));
}

TEST(ResourceUtilsTest, ParseReferenceWithNoPackage) {
    ResourceNameRef expected({}, ResourceType::kColor, "foo");
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::parseReference("@color/foo", &actual, &create, &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceUtilsTest, ParseReferenceWithPackage) {
    ResourceNameRef expected("android", ResourceType::kColor, "foo");
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::parseReference("@android:color/foo", &actual, &create,
                                                 &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceUtilsTest, ParseReferenceWithSurroundingWhitespace) {
    ResourceNameRef expected("android", ResourceType::kColor, "foo");
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::parseReference("\t @android:color/foo\n \n\t", &actual,
                                                 &create, &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceUtilsTest, ParseAutoCreateIdReference) {
    ResourceNameRef expected("android", ResourceType::kId, "foo");
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::parseReference("@+android:id/foo", &actual, &create,
                                                 &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_TRUE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceUtilsTest, ParsePrivateReference) {
    ResourceNameRef expected("android", ResourceType::kId, "foo");
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceUtils::parseReference("@*android:id/foo", &actual, &create,
                                                 &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_TRUE(privateRef);
}

TEST(ResourceUtilsTest, FailToParseAutoCreateNonIdReference) {
    bool create = false;
    bool privateRef = false;
    ResourceNameRef actual;
    EXPECT_FALSE(ResourceUtils::parseReference("@+android:color/foo", &actual, &create,
                                                  &privateRef));
}

TEST(ResourceUtilsTest, ParseAttributeReferences) {
    EXPECT_TRUE(ResourceUtils::isAttributeReference("?android"));
    EXPECT_TRUE(ResourceUtils::isAttributeReference("?android:foo"));
    EXPECT_TRUE(ResourceUtils::isAttributeReference("?attr/foo"));
    EXPECT_TRUE(ResourceUtils::isAttributeReference("?android:attr/foo"));
}

TEST(ResourceUtilsTest, FailParseIncompleteReference) {
    EXPECT_FALSE(ResourceUtils::isAttributeReference("?style/foo"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference("?android:style/foo"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference("?android:"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference("?android:attr/"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference("?:attr/"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference("?:attr/foo"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference("?:/"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference("?:/foo"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference("?attr/"));
    EXPECT_FALSE(ResourceUtils::isAttributeReference("?/foo"));
}

TEST(ResourceUtilsTest, ParseStyleParentReference) {
    const ResourceName kAndroidStyleFooName("android", ResourceType::kStyle, "foo");
    const ResourceName kStyleFooName({}, ResourceType::kStyle, "foo");

    std::string errStr;
    Maybe<Reference> ref = ResourceUtils::parseStyleParentReference("@android:style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

    ref = ResourceUtils::parseStyleParentReference("@style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kStyleFooName);

    ref = ResourceUtils::parseStyleParentReference("?android:style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

    ref = ResourceUtils::parseStyleParentReference("?style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kStyleFooName);

    ref = ResourceUtils::parseStyleParentReference("android:style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

    ref = ResourceUtils::parseStyleParentReference("android:foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

    ref = ResourceUtils::parseStyleParentReference("@android:foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

    ref = ResourceUtils::parseStyleParentReference("foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kStyleFooName);

    ref = ResourceUtils::parseStyleParentReference("*android:style/foo", &errStr);
    AAPT_ASSERT_TRUE(ref);
    EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);
    EXPECT_TRUE(ref.value().privateReference);
}

TEST(ResourceUtilsTest, ParseEmptyFlag) {
    std::unique_ptr<Attribute> attr = test::AttributeBuilder(false)
            .setTypeMask(android::ResTable_map::TYPE_FLAGS)
            .addItem("one", 0x01)
            .addItem("two", 0x02)
            .build();

    std::unique_ptr<BinaryPrimitive> result = ResourceUtils::tryParseFlagSymbol(attr.get(), "");
    ASSERT_NE(nullptr, result);
    EXPECT_EQ(0u, result->value.data);
}

} // namespace aapt
