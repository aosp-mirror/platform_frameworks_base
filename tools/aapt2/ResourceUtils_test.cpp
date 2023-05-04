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

#include "SdkConstants.h"
#include "Resource.h"
#include "test/Test.h"

using ::aapt::test::ValueEq;
using ::android::Res_value;
using ::android::ResTable_map;
using ::testing::Eq;
using ::testing::NotNull;
using ::testing::Pointee;

namespace aapt {

TEST(ResourceUtilsTest, ParseBool) {
  EXPECT_THAT(ResourceUtils::ParseBool("true"), Eq(std::optional<bool>(true)));
  EXPECT_THAT(ResourceUtils::ParseBool("TRUE"), Eq(std::optional<bool>(true)));
  EXPECT_THAT(ResourceUtils::ParseBool("True"), Eq(std::optional<bool>(true)));

  EXPECT_THAT(ResourceUtils::ParseBool("false"), Eq(std::optional<bool>(false)));
  EXPECT_THAT(ResourceUtils::ParseBool("FALSE"), Eq(std::optional<bool>(false)));
  EXPECT_THAT(ResourceUtils::ParseBool("False"), Eq(std::optional<bool>(false)));

  EXPECT_THAT(ResourceUtils::ParseBool(" False\n "), Eq(std::optional<bool>(false)));
}

TEST(ResourceUtilsTest, ParseResourceName) {
  ResourceNameRef actual;
  bool actual_priv = false;
  EXPECT_TRUE(ResourceUtils::ParseResourceName("android:color/foo", &actual, &actual_priv));
  EXPECT_THAT(actual, Eq(ResourceNameRef("android", ResourceType::kColor, "foo")));
  EXPECT_FALSE(actual_priv);

  EXPECT_TRUE(ResourceUtils::ParseResourceName("color/foo", &actual, &actual_priv));
  EXPECT_THAT(actual, Eq(ResourceNameRef({}, ResourceType::kColor, "foo")));
  EXPECT_FALSE(actual_priv);

  EXPECT_TRUE(ResourceUtils::ParseResourceName("*android:color/foo", &actual, &actual_priv));
  EXPECT_THAT(actual, Eq(ResourceNameRef("android", ResourceType::kColor, "foo")));
  EXPECT_TRUE(actual_priv);

  EXPECT_FALSE(ResourceUtils::ParseResourceName(android::StringPiece(), &actual, &actual_priv));
}

TEST(ResourceUtilsTest, ParseReferenceWithNoPackage) {
  ResourceNameRef actual;
  bool create = false;
  bool private_ref = false;
  EXPECT_TRUE(ResourceUtils::ParseReference("@color/foo", &actual, &create, &private_ref));
  EXPECT_THAT(actual, Eq(ResourceNameRef({}, ResourceType::kColor, "foo")));
  EXPECT_FALSE(create);
  EXPECT_FALSE(private_ref);
}

TEST(ResourceUtilsTest, ParseReferenceWithPackage) {
  ResourceNameRef actual;
  bool create = false;
  bool private_ref = false;
  EXPECT_TRUE(ResourceUtils::ParseReference("@android:color/foo", &actual, &create, &private_ref));
  EXPECT_THAT(actual, Eq(ResourceNameRef("android", ResourceType::kColor, "foo")));
  EXPECT_FALSE(create);
  EXPECT_FALSE(private_ref);
}

TEST(ResourceUtilsTest, ParseReferenceWithSurroundingWhitespace) {
  ResourceNameRef actual;
  bool create = false;
  bool private_ref = false;
  EXPECT_TRUE(ResourceUtils::ParseReference("\t @android:color/foo\n \n\t", &actual, &create, &private_ref));
  EXPECT_THAT(actual, Eq(ResourceNameRef("android", ResourceType::kColor, "foo")));
  EXPECT_FALSE(create);
  EXPECT_FALSE(private_ref);
}

TEST(ResourceUtilsTest, ParseAutoCreateIdReference) {
  ResourceNameRef actual;
  bool create = false;
  bool private_ref = false;
  EXPECT_TRUE(ResourceUtils::ParseReference("@+android:id/foo", &actual, &create, &private_ref));
  EXPECT_THAT(actual, Eq(ResourceNameRef("android", ResourceType::kId, "foo")));
  EXPECT_TRUE(create);
  EXPECT_FALSE(private_ref);
}

TEST(ResourceUtilsTest, ParsePrivateReference) {
  ResourceNameRef actual;
  bool create = false;
  bool private_ref = false;
  EXPECT_TRUE(ResourceUtils::ParseReference("@*android:id/foo", &actual, &create, &private_ref));
  EXPECT_THAT(actual, Eq(ResourceNameRef("android", ResourceType::kId, "foo")));
  EXPECT_FALSE(create);
  EXPECT_TRUE(private_ref);
}

TEST(ResourceUtilsTest, ParseBinaryDynamicReference) {
  android::Res_value value = {};
  value.data = android::util::HostToDevice32(0x01);
  value.dataType = android::Res_value::TYPE_DYNAMIC_REFERENCE;
  std::unique_ptr<Item> item = ResourceUtils::ParseBinaryResValue(ResourceType::kId,
                                                                  android::ConfigDescription(),
                                                                  android::ResStringPool(), value,
                                                                  nullptr);

  Reference* ref = ValueCast<Reference>(item.get());
  EXPECT_TRUE(ref->is_dynamic);
  EXPECT_EQ(ref->id.value().id, 0x01);
}

TEST(ResourceUtilsTest, FailToParseAutoCreateNonIdReference) {
  bool create = false;
  bool private_ref = false;
  ResourceNameRef actual;
  EXPECT_FALSE(ResourceUtils::ParseReference("@+android:color/foo", &actual, &create, &private_ref));
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
  const ResourceName kAndroidStyleFooName("android", ResourceType::kStyle, "foo");
  const ResourceName kStyleFooName({}, ResourceType::kStyle, "foo");

  std::string err_str;
  std::optional<Reference> ref =
      ResourceUtils::ParseStyleParentReference("@android:style/foo", &err_str);
  ASSERT_TRUE(ref);
  EXPECT_THAT(ref.value().name, Eq(kAndroidStyleFooName));

  ref = ResourceUtils::ParseStyleParentReference("@style/foo", &err_str);
  ASSERT_TRUE(ref);
  EXPECT_THAT(ref.value().name, Eq(kStyleFooName));

  ref = ResourceUtils::ParseStyleParentReference("?android:style/foo", &err_str);
  ASSERT_TRUE(ref);
  EXPECT_THAT(ref.value().name, Eq(kAndroidStyleFooName));

  ref = ResourceUtils::ParseStyleParentReference("?style/foo", &err_str);
  ASSERT_TRUE(ref);
  EXPECT_THAT(ref.value().name, Eq(kStyleFooName));

  ref = ResourceUtils::ParseStyleParentReference("android:style/foo", &err_str);
  ASSERT_TRUE(ref);
  EXPECT_THAT(ref.value().name, Eq(kAndroidStyleFooName));

  ref = ResourceUtils::ParseStyleParentReference("android:foo", &err_str);
  ASSERT_TRUE(ref);
  EXPECT_THAT(ref.value().name, Eq(kAndroidStyleFooName));

  ref = ResourceUtils::ParseStyleParentReference("@android:foo", &err_str);
  ASSERT_TRUE(ref);
  EXPECT_THAT(ref.value().name, Eq(kAndroidStyleFooName));

  ref = ResourceUtils::ParseStyleParentReference("foo", &err_str);
  ASSERT_TRUE(ref);
  EXPECT_THAT(ref.value().name, Eq(kStyleFooName));

  ref = ResourceUtils::ParseStyleParentReference("*android:style/foo", &err_str);
  ASSERT_TRUE(ref);
  EXPECT_THAT(ref.value().name, Eq(kAndroidStyleFooName));
  EXPECT_TRUE(ref.value().private_reference);
}

TEST(ResourceUtilsTest, ParseEmptyFlag) {
  std::unique_ptr<Attribute> attr = test::AttributeBuilder()
                                        .SetTypeMask(ResTable_map::TYPE_FLAGS)
                                        .AddItem("one", 0x01)
                                        .AddItem("two", 0x02)
                                        .Build();

  std::unique_ptr<BinaryPrimitive> result = ResourceUtils::TryParseFlagSymbol(attr.get(), "");
  ASSERT_THAT(result, NotNull());
  EXPECT_THAT(result->value.data, Eq(0u));
}

TEST(ResourceUtilsTest, NullIsEmptyReference) {
  ASSERT_THAT(ResourceUtils::MakeNull(), Pointee(ValueEq(Reference())));
  ASSERT_THAT(ResourceUtils::TryParseNullOrEmpty("@null"), Pointee(ValueEq(Reference())));
}

TEST(ResourceUtilsTest, EmptyIsBinaryPrimitive) {
  ASSERT_THAT(ResourceUtils::MakeEmpty(), Pointee(ValueEq(BinaryPrimitive(Res_value::TYPE_NULL, Res_value::DATA_NULL_EMPTY))));
  ASSERT_THAT(ResourceUtils::TryParseNullOrEmpty("@empty"), Pointee(ValueEq(BinaryPrimitive(Res_value::TYPE_NULL, Res_value::DATA_NULL_EMPTY))));
}

TEST(ResourceUtilsTest, ItemsWithWhitespaceAreParsedCorrectly) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  EXPECT_THAT(ResourceUtils::TryParseItemForAttribute(context->GetDiagnostics(), " 12\n   ",
                                                      ResTable_map::TYPE_INTEGER),
              Pointee(ValueEq(BinaryPrimitive(Res_value::TYPE_INT_DEC, 12u))));
  EXPECT_THAT(ResourceUtils::TryParseItemForAttribute(context->GetDiagnostics(), " true\n   ",
                                                      ResTable_map::TYPE_BOOLEAN),
              Pointee(ValueEq(BinaryPrimitive(Res_value::TYPE_INT_BOOLEAN, 0xffffffffu))));

  const float expected_float = 12.0f;
  const uint32_t expected_float_flattened = *(uint32_t*)&expected_float;
  EXPECT_THAT(ResourceUtils::TryParseItemForAttribute(context->GetDiagnostics(), " 12.0\n   ",
                                                      ResTable_map::TYPE_FLOAT),
              Pointee(ValueEq(BinaryPrimitive(Res_value::TYPE_FLOAT, expected_float_flattened))));
}

TEST(ResourceUtilsTest, FloatAndBigIntegerParsedCorrectly) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  const float expected_float = 0.125f;
  const uint32_t expected_float_flattened = *(uint32_t*)&expected_float;
  EXPECT_THAT(ResourceUtils::TryParseItemForAttribute(context->GetDiagnostics(), "0.125",
                                                      ResTable_map::TYPE_FLOAT),
              Pointee(ValueEq(BinaryPrimitive(Res_value::TYPE_FLOAT, expected_float_flattened))));

  const float special_float = 1.0f;
  const uint32_t special_float_flattened = *(uint32_t*)&special_float;
  EXPECT_THAT(ResourceUtils::TryParseItemForAttribute(context->GetDiagnostics(), "1.0",
                                                      ResTable_map::TYPE_FLOAT),
              Pointee(ValueEq(BinaryPrimitive(Res_value::TYPE_FLOAT, special_float_flattened))));

  EXPECT_EQ(ResourceUtils::TryParseItemForAttribute(context->GetDiagnostics(), "1099511627776",
                                                    ResTable_map::TYPE_INTEGER),
            std::unique_ptr<Item>(nullptr));

  const float big_float = 1099511627776.0f;
  const uint32_t big_flattened = *(uint32_t*)&big_float;
  EXPECT_THAT(ResourceUtils::TryParseItemForAttribute(context->GetDiagnostics(), "1099511627776",
                                                      ResTable_map::TYPE_FLOAT),
              Pointee(ValueEq(BinaryPrimitive(Res_value::TYPE_FLOAT, big_flattened))));
}

TEST(ResourceUtilsTest, ParseSdkVersionWithCodename) {
  EXPECT_THAT(ResourceUtils::ParseSdkVersion("Q"), Eq(std::optional<int>(10000)));
  EXPECT_THAT(ResourceUtils::ParseSdkVersion("Q.fingerprint"), Eq(std::optional<int>(10000)));

  EXPECT_THAT(ResourceUtils::ParseSdkVersion("R"), Eq(std::optional<int>(10000)));
  EXPECT_THAT(ResourceUtils::ParseSdkVersion("R.fingerprint"), Eq(std::optional<int>(10000)));
}

TEST(ResourceUtilsTest, StringBuilderWhitespaceRemoval) {
  EXPECT_THAT(ResourceUtils::StringBuilder()
                  .AppendText("    hey guys ")
                  .AppendText(" this is so cool ")
                  .to_string(),
              Eq(" hey guys this is so cool "));
  EXPECT_THAT(ResourceUtils::StringBuilder()
                  .AppendText(" \" wow,  so many \t ")
                  .AppendText("spaces. \"what? ")
                  .to_string(),
              Eq("  wow,  so many \t spaces. what? "));
  EXPECT_THAT(ResourceUtils::StringBuilder()
                  .AppendText("  where \t ")
                  .AppendText(" \nis the pie?")
                  .to_string(),
              Eq(" where is the pie?"));
}

TEST(ResourceUtilsTest, StringBuilderEscaping) {
  EXPECT_THAT(ResourceUtils::StringBuilder()
                  .AppendText("hey guys\\n ")
                  .AppendText(" this \\t is so\\\\ cool")
                  .to_string(),
              Eq("hey guys\n this \t is so\\ cool"));
  EXPECT_THAT(ResourceUtils::StringBuilder().AppendText("\\@\\?\\#\\\\\\'").to_string(),
              Eq("@?#\\\'"));
}

TEST(ResourceUtilsTest, StringBuilderMisplacedQuote) {
  ResourceUtils::StringBuilder builder;
  EXPECT_FALSE(builder.AppendText("they're coming!"));
}

TEST(ResourceUtilsTest, StringBuilderUnicodeCodes) {
  EXPECT_THAT(ResourceUtils::StringBuilder().AppendText("\\u00AF\\u0AF0 woah").to_string(),
              Eq("\u00AF\u0AF0 woah"));
  EXPECT_FALSE(ResourceUtils::StringBuilder().AppendText("\\u00 yo"));
}

TEST(ResourceUtilsTest, StringBuilderPreserveSpaces) {
  EXPECT_THAT(ResourceUtils::StringBuilder(true /*preserve_spaces*/).AppendText("\"").to_string(),
              Eq("\""));
}

}  // namespace aapt
