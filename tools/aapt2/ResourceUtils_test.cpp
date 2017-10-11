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

#include "ResourceUtils.h"

#include "Resource.h"
#include "test/Test.h"

using ::aapt::test::ValueEq;
using ::testing::Pointee;

namespace aapt {

TEST(ResourceUtilsTest, ParseBool) {
  EXPECT_EQ(Maybe<bool>(true), ResourceUtils::ParseBool("true"));
  EXPECT_EQ(Maybe<bool>(true), ResourceUtils::ParseBool("TRUE"));
  EXPECT_EQ(Maybe<bool>(true), ResourceUtils::ParseBool("True"));
  EXPECT_EQ(Maybe<bool>(false), ResourceUtils::ParseBool("false"));
  EXPECT_EQ(Maybe<bool>(false), ResourceUtils::ParseBool("FALSE"));
  EXPECT_EQ(Maybe<bool>(false), ResourceUtils::ParseBool("False"));
}

TEST(ResourceUtilsTest, ParseResourceName) {
  ResourceNameRef actual;
  bool actual_priv = false;
  EXPECT_TRUE(ResourceUtils::ParseResourceName("android:color/foo", &actual,
                                               &actual_priv));
  EXPECT_EQ(ResourceNameRef("android", ResourceType::kColor, "foo"), actual);
  EXPECT_FALSE(actual_priv);

  EXPECT_TRUE(
      ResourceUtils::ParseResourceName("color/foo", &actual, &actual_priv));
  EXPECT_EQ(ResourceNameRef({}, ResourceType::kColor, "foo"), actual);
  EXPECT_FALSE(actual_priv);

  EXPECT_TRUE(ResourceUtils::ParseResourceName("*android:color/foo", &actual,
                                               &actual_priv));
  EXPECT_EQ(ResourceNameRef("android", ResourceType::kColor, "foo"), actual);
  EXPECT_TRUE(actual_priv);

  EXPECT_FALSE(ResourceUtils::ParseResourceName(android::StringPiece(), &actual, &actual_priv));
}

TEST(ResourceUtilsTest, ParseReferenceWithNoPackage) {
  ResourceNameRef expected({}, ResourceType::kColor, "foo");
  ResourceNameRef actual;
  bool create = false;
  bool private_ref = false;
  EXPECT_TRUE(ResourceUtils::ParseReference("@color/foo", &actual, &create,
                                            &private_ref));
  EXPECT_EQ(expected, actual);
  EXPECT_FALSE(create);
  EXPECT_FALSE(private_ref);
}

TEST(ResourceUtilsTest, ParseReferenceWithPackage) {
  ResourceNameRef expected("android", ResourceType::kColor, "foo");
  ResourceNameRef actual;
  bool create = false;
  bool private_ref = false;
  EXPECT_TRUE(ResourceUtils::ParseReference("@android:color/foo", &actual,
                                            &create, &private_ref));
  EXPECT_EQ(expected, actual);
  EXPECT_FALSE(create);
  EXPECT_FALSE(private_ref);
}

TEST(ResourceUtilsTest, ParseReferenceWithSurroundingWhitespace) {
  ResourceNameRef expected("android", ResourceType::kColor, "foo");
  ResourceNameRef actual;
  bool create = false;
  bool private_ref = false;
  EXPECT_TRUE(ResourceUtils::ParseReference("\t @android:color/foo\n \n\t",
                                            &actual, &create, &private_ref));
  EXPECT_EQ(expected, actual);
  EXPECT_FALSE(create);
  EXPECT_FALSE(private_ref);
}

TEST(ResourceUtilsTest, ParseAutoCreateIdReference) {
  ResourceNameRef expected("android", ResourceType::kId, "foo");
  ResourceNameRef actual;
  bool create = false;
  bool private_ref = false;
  EXPECT_TRUE(ResourceUtils::ParseReference("@+android:id/foo", &actual,
                                            &create, &private_ref));
  EXPECT_EQ(expected, actual);
  EXPECT_TRUE(create);
  EXPECT_FALSE(private_ref);
}

TEST(ResourceUtilsTest, ParsePrivateReference) {
  ResourceNameRef expected("android", ResourceType::kId, "foo");
  ResourceNameRef actual;
  bool create = false;
  bool private_ref = false;
  EXPECT_TRUE(ResourceUtils::ParseReference("@*android:id/foo", &actual,
                                            &create, &private_ref));
  EXPECT_EQ(expected, actual);
  EXPECT_FALSE(create);
  EXPECT_TRUE(private_ref);
}

TEST(ResourceUtilsTest, FailToParseAutoCreateNonIdReference) {
  bool create = false;
  bool private_ref = false;
  ResourceNameRef actual;
  EXPECT_FALSE(ResourceUtils::ParseReference("@+android:color/foo", &actual,
                                             &create, &private_ref));
}

TEST(ResourceUtilsTest, ParseAttributeReferences) {
  EXPECT_TRUE(ResourceUtils::IsAttributeReference("?android"));
  EXPECT_TRUE(ResourceUtils::IsAttributeReference("?android:foo"));
  EXPECT_TRUE(ResourceUtils::IsAttributeReference("?attr/foo"));
  EXPECT_TRUE(ResourceUtils::IsAttributeReference("?android:attr/foo"));
}

TEST(ResourceUtilsTest, FailParseIncompleteReference) {
  EXPECT_FALSE(ResourceUtils::IsAttributeReference("?style/foo"));
  EXPECT_FALSE(ResourceUtils::IsAttributeReference("?android:style/foo"));
  EXPECT_FALSE(ResourceUtils::IsAttributeReference("?android:"));
  EXPECT_FALSE(ResourceUtils::IsAttributeReference("?android:attr/"));
  EXPECT_FALSE(ResourceUtils::IsAttributeReference("?:attr/"));
  EXPECT_FALSE(ResourceUtils::IsAttributeReference("?:attr/foo"));
  EXPECT_FALSE(ResourceUtils::IsAttributeReference("?:/"));
  EXPECT_FALSE(ResourceUtils::IsAttributeReference("?:/foo"));
  EXPECT_FALSE(ResourceUtils::IsAttributeReference("?attr/"));
  EXPECT_FALSE(ResourceUtils::IsAttributeReference("?/foo"));
}

TEST(ResourceUtilsTest, ParseStyleParentReference) {
  const ResourceName kAndroidStyleFooName("android", ResourceType::kStyle,
                                          "foo");
  const ResourceName kStyleFooName({}, ResourceType::kStyle, "foo");

  std::string err_str;
  Maybe<Reference> ref =
      ResourceUtils::ParseStyleParentReference("@android:style/foo", &err_str);
  AAPT_ASSERT_TRUE(ref);
  EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

  ref = ResourceUtils::ParseStyleParentReference("@style/foo", &err_str);
  AAPT_ASSERT_TRUE(ref);
  EXPECT_EQ(ref.value().name.value(), kStyleFooName);

  ref =
      ResourceUtils::ParseStyleParentReference("?android:style/foo", &err_str);
  AAPT_ASSERT_TRUE(ref);
  EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

  ref = ResourceUtils::ParseStyleParentReference("?style/foo", &err_str);
  AAPT_ASSERT_TRUE(ref);
  EXPECT_EQ(ref.value().name.value(), kStyleFooName);

  ref = ResourceUtils::ParseStyleParentReference("android:style/foo", &err_str);
  AAPT_ASSERT_TRUE(ref);
  EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

  ref = ResourceUtils::ParseStyleParentReference("android:foo", &err_str);
  AAPT_ASSERT_TRUE(ref);
  EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

  ref = ResourceUtils::ParseStyleParentReference("@android:foo", &err_str);
  AAPT_ASSERT_TRUE(ref);
  EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);

  ref = ResourceUtils::ParseStyleParentReference("foo", &err_str);
  AAPT_ASSERT_TRUE(ref);
  EXPECT_EQ(ref.value().name.value(), kStyleFooName);

  ref =
      ResourceUtils::ParseStyleParentReference("*android:style/foo", &err_str);
  AAPT_ASSERT_TRUE(ref);
  EXPECT_EQ(ref.value().name.value(), kAndroidStyleFooName);
  EXPECT_TRUE(ref.value().private_reference);
}

TEST(ResourceUtilsTest, ParseEmptyFlag) {
  std::unique_ptr<Attribute> attr =
      test::AttributeBuilder(false)
          .SetTypeMask(android::ResTable_map::TYPE_FLAGS)
          .AddItem("one", 0x01)
          .AddItem("two", 0x02)
          .Build();

  std::unique_ptr<BinaryPrimitive> result =
      ResourceUtils::TryParseFlagSymbol(attr.get(), "");
  ASSERT_NE(nullptr, result);
  EXPECT_EQ(0u, result->value.data);
}

TEST(ResourceUtilsTest, NullIsEmptyReference) {
  auto null_value = ResourceUtils::MakeNull();
  ASSERT_THAT(null_value, Pointee(ValueEq(Reference())));

  auto value = ResourceUtils::TryParseNullOrEmpty("@null");
  ASSERT_THAT(value, Pointee(ValueEq(Reference())));
}

TEST(ResourceUtilsTest, EmptyIsBinaryPrimitive) {
  auto empty_value = ResourceUtils::MakeEmpty();
  ASSERT_THAT(empty_value, Pointee(ValueEq(BinaryPrimitive(android::Res_value::TYPE_NULL,
                                                           android::Res_value::DATA_NULL_EMPTY))));

  auto value = ResourceUtils::TryParseNullOrEmpty("@empty");
  ASSERT_THAT(value, Pointee(ValueEq(BinaryPrimitive(android::Res_value::TYPE_NULL,
                                                     android::Res_value::DATA_NULL_EMPTY))));
}

}  // namespace aapt
