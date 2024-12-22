/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "ResourceValues.h"

#include "test/Test.h"

using ::testing::Eq;
using ::testing::SizeIs;
using ::testing::StrEq;

namespace aapt {

namespace {

// Attribute types.
constexpr const uint32_t TYPE_DIMENSION = android::ResTable_map::TYPE_DIMENSION;
constexpr const uint32_t TYPE_ENUM = android::ResTable_map::TYPE_ENUM;
constexpr const uint32_t TYPE_FLAGS = android::ResTable_map::TYPE_FLAGS;
constexpr const uint32_t TYPE_INTEGER = android::ResTable_map::TYPE_INTEGER;
constexpr const uint32_t TYPE_REFERENCE = android::Res_value::TYPE_REFERENCE;
constexpr const uint32_t TYPE_STRING = android::ResTable_map::TYPE_STRING;

}  // namespace

TEST(ResourceValuesTest, PluralEquals) {
  android::StringPool pool;

  Plural a;
  a.values[Plural::One] = util::make_unique<String>(pool.MakeRef("one"));
  a.values[Plural::Other] = util::make_unique<String>(pool.MakeRef("other"));

  Plural b;
  b.values[Plural::One] = util::make_unique<String>(pool.MakeRef("une"));
  b.values[Plural::Other] = util::make_unique<String>(pool.MakeRef("autre"));

  Plural c;
  c.values[Plural::One] = util::make_unique<String>(pool.MakeRef("one"));
  c.values[Plural::Other] = util::make_unique<String>(pool.MakeRef("other"));

  EXPECT_FALSE(a.Equals(&b));
  EXPECT_TRUE(a.Equals(&c));
}

TEST(ResourceValuesTest, PluralClone) {
  android::StringPool pool;

  Plural a;
  a.values[Plural::One] = util::make_unique<String>(pool.MakeRef("one"));
  a.values[Plural::Other] = util::make_unique<String>(pool.MakeRef("other"));

  CloningValueTransformer cloner(&pool);
  std::unique_ptr<Plural> b(a.Transform(cloner));
  EXPECT_TRUE(a.Equals(b.get()));
}

TEST(ResourceValuesTest, ArrayEquals) {
  android::StringPool pool;

  Array a;
  a.elements.push_back(util::make_unique<String>(pool.MakeRef("one")));
  a.elements.push_back(util::make_unique<String>(pool.MakeRef("two")));

  Array b;
  b.elements.push_back(util::make_unique<String>(pool.MakeRef("une")));
  b.elements.push_back(util::make_unique<String>(pool.MakeRef("deux")));

  Array c;
  c.elements.push_back(util::make_unique<String>(pool.MakeRef("uno")));

  Array d;
  d.elements.push_back(util::make_unique<String>(pool.MakeRef("one")));
  d.elements.push_back(util::make_unique<String>(pool.MakeRef("two")));

  EXPECT_FALSE(a.Equals(&b));
  EXPECT_FALSE(a.Equals(&c));
  EXPECT_FALSE(b.Equals(&c));
  EXPECT_TRUE(a.Equals(&d));
}

TEST(ResourceValuesTest, ArrayClone) {
  android::StringPool pool;

  Array a;
  a.elements.push_back(util::make_unique<String>(pool.MakeRef("one")));
  a.elements.push_back(util::make_unique<String>(pool.MakeRef("two")));

  CloningValueTransformer cloner(&pool);
  std::unique_ptr<Array> b(a.Transform(cloner));
  EXPECT_TRUE(a.Equals(b.get()));
}

TEST(ResourceValuesTest, StyleEquals) {
  android::StringPool pool;

  std::unique_ptr<Style> a = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("2"))
      .Build();

  std::unique_ptr<Style> b = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("3"))
      .Build();

  std::unique_ptr<Style> c = test::StyleBuilder()
      .SetParent("android:style/NoParent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("2"))
      .Build();

  std::unique_ptr<Style> d = test::StyleBuilder()
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("2"))
      .Build();

  std::unique_ptr<Style> e = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bat", ResourceUtils::TryParseInt("2"))
      .Build();

  std::unique_ptr<Style> f = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .Build();

  std::unique_ptr<Style> g = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("2"))
      .Build();

  EXPECT_FALSE(a->Equals(b.get()));
  EXPECT_FALSE(a->Equals(c.get()));
  EXPECT_FALSE(a->Equals(d.get()));
  EXPECT_FALSE(a->Equals(e.get()));
  EXPECT_FALSE(a->Equals(f.get()));

  EXPECT_TRUE(a->Equals(g.get()));
}

TEST(ResourceValuesTest, StyleClone) {
  std::unique_ptr<Style> a = test::StyleBuilder()
      .SetParent("android:style/Parent")
      .AddItem("android:attr/foo", ResourceUtils::TryParseInt("1"))
      .AddItem("android:attr/bar", ResourceUtils::TryParseInt("2"))
      .Build();

  CloningValueTransformer cloner(nullptr);
  std::unique_ptr<Style> b(a->Transform(cloner));
  EXPECT_TRUE(a->Equals(b.get()));
}

TEST(ResourcesValuesTest, StringClones) {
  android::StringPool pool_a;
  android::StringPool pool_b;

  String str_a(pool_a.MakeRef("hello", android::StringPool::Context(test::ParseConfigOrDie("en"))));

  ASSERT_THAT(pool_a, SizeIs(1u));
  EXPECT_THAT(pool_a.strings()[0]->context.config, Eq(test::ParseConfigOrDie("en")));
  EXPECT_THAT(pool_a.strings()[0]->value, StrEq("hello"));

  CloningValueTransformer cloner(&pool_b);
  str_a.Transform(cloner);
  ASSERT_THAT(pool_b, SizeIs(1u));
  EXPECT_THAT(pool_b.strings()[0]->context.config, Eq(test::ParseConfigOrDie("en")));
  EXPECT_THAT(pool_b.strings()[0]->value, StrEq("hello"));
}

TEST(ResourcesValuesTest, StringEquals) {
  android::StringPool pool;

  String str(pool.MakeRef("hello", android::StringPool::Context(test::ParseConfigOrDie("en"))));
  String str2(pool.MakeRef("hello"));
  EXPECT_TRUE(str.Equals(&str2));
  EXPECT_TRUE(str2.Equals(&str));

  String str3(pool.MakeRef("how are you"));
  EXPECT_FALSE(str.Equals(&str3));
}

TEST(ResourcesValuesTest, StyledStringEquals) {
  android::StringPool pool;

  StyledString ss(pool.MakeRef(android::StyleString{"hello", {{"b", 0, 1}, {"u", 2, 4}}}));
  StyledString ss2(pool.MakeRef(android::StyleString{"hello", {{"b", 0, 1}, {"u", 2, 4}}}));
  StyledString ss3(pool.MakeRef(android::StyleString{"hi", {{"b", 0, 1}, {"u", 2, 4}}}));
  StyledString ss4(pool.MakeRef(android::StyleString{"hello", {{"b", 0, 1}}}));
  StyledString ss5(pool.MakeRef(android::StyleString{"hello", {{"b", 0, 1}, {"u", 3, 4}}}));
  StyledString ss6(pool.MakeRef(android::StyleString{"hello", {{"b", 0, 1}, {"s", 2, 4}}}));
  EXPECT_TRUE(ss.Equals(&ss2));
  EXPECT_TRUE(ss2.Equals(&ss));
  EXPECT_FALSE(ss.Equals(&ss3));
  EXPECT_FALSE(ss.Equals(&ss4));
  EXPECT_FALSE(ss.Equals(&ss5));
  EXPECT_FALSE(ss.Equals(&ss6));
}

TEST(ResourceValuesTest, StyleMerges) {
  android::StringPool pool_a;
  android::StringPool pool_b;

  std::unique_ptr<Style> a =
      test::StyleBuilder()
          .SetParent("android:style/Parent")
          .AddItem("android:attr/a", util::make_unique<String>(pool_a.MakeRef("FooA")))
          .AddItem("android:attr/b", util::make_unique<String>(pool_a.MakeRef("FooB")))
          .Build();

  std::unique_ptr<Style> b =
      test::StyleBuilder()
          .SetParent("android:style/OverlayParent")
          .AddItem("android:attr/c", util::make_unique<String>(pool_b.MakeRef("OverlayFooC")))
          .AddItem("android:attr/a", util::make_unique<String>(pool_b.MakeRef("OverlayFooA")))
          .Build();

  a->MergeWith(b.get(), &pool_a);

  android::StringPool pool;
  std::unique_ptr<Style> expected =
      test::StyleBuilder()
          .SetParent("android:style/OverlayParent")
          .AddItem("android:attr/a", util::make_unique<String>(pool.MakeRef("OverlayFooA")))
          .AddItem("android:attr/b", util::make_unique<String>(pool.MakeRef("FooB")))
          .AddItem("android:attr/c", util::make_unique<String>(pool.MakeRef("OverlayFooC")))
          .Build();

  EXPECT_TRUE(a->Equals(expected.get()));
}

// TYPE_NULL is encoded as TYPE_REFERENCE with a value of 0. This is represented in AAPT2
// by a default constructed Reference value.
TEST(ResourcesValuesTest, EmptyReferenceFlattens) {
  android::Res_value value = {};
  ASSERT_TRUE(Reference().Flatten(&value));

  EXPECT_THAT(value.dataType, Eq(android::Res_value::TYPE_REFERENCE));
  EXPECT_THAT(value.data, Eq(0u));
}

TEST(ResourcesValuesTest, AttributeMatches) {
  constexpr const uint8_t TYPE_INT_DEC = android::Res_value::TYPE_INT_DEC;

  Attribute attr1(TYPE_DIMENSION);
  EXPECT_FALSE(attr1.Matches(*ResourceUtils::TryParseColor("#7fff00")));
  EXPECT_TRUE(attr1.Matches(*ResourceUtils::TryParseFloat("23dp")));
  EXPECT_TRUE(attr1.Matches(*ResourceUtils::TryParseReference("@android:string/foo")));

  Attribute attr2(TYPE_INTEGER | TYPE_ENUM);
  attr2.min_int = 0;
  attr2.symbols.push_back(Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/foo")),
                                            static_cast<uint32_t>(-1)});
  EXPECT_FALSE(attr2.Matches(*ResourceUtils::TryParseColor("#7fff00")));
  EXPECT_TRUE(attr2.Matches(BinaryPrimitive(TYPE_INT_DEC, static_cast<uint32_t>(-1))));
  EXPECT_TRUE(attr2.Matches(BinaryPrimitive(TYPE_INT_DEC, 1u)));
  EXPECT_FALSE(attr2.Matches(BinaryPrimitive(TYPE_INT_DEC, static_cast<uint32_t>(-2))));

  Attribute attr3(TYPE_INTEGER | TYPE_FLAGS);
  attr3.max_int = 100;
  attr3.symbols.push_back(
      Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/foo")), 0x01u});
  attr3.symbols.push_back(
      Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/bar")), 0x02u});
  attr3.symbols.push_back(
      Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/baz")), 0x04u});
  attr3.symbols.push_back(
      Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/bat")), 0x80u});
  EXPECT_FALSE(attr3.Matches(*ResourceUtils::TryParseColor("#7fff00")));
  EXPECT_TRUE(attr3.Matches(BinaryPrimitive(TYPE_INT_DEC, 0x01u | 0x02u)));
  EXPECT_TRUE(attr3.Matches(BinaryPrimitive(TYPE_INT_DEC, 0x01u | 0x02u | 0x80u)));

  // Not a flag, but a value less than max_int.
  EXPECT_TRUE(attr3.Matches(BinaryPrimitive(TYPE_INT_DEC, 0x08u)));

  // Not a flag and greater than max_int.
  EXPECT_FALSE(attr3.Matches(BinaryPrimitive(TYPE_INT_DEC, 127u)));

  Attribute attr4(TYPE_ENUM);
  attr4.symbols.push_back(
      Attribute::Symbol{Reference(test::ParseNameOrDie("android:id/foo")), 0x01u});
  EXPECT_TRUE(attr4.Matches(BinaryPrimitive(TYPE_INT_DEC, 0x01u)));
  EXPECT_FALSE(attr4.Matches(BinaryPrimitive(TYPE_INT_DEC, 0x02u)));
}

TEST(ResourcesValuesTest, AttributeIsCompatible) {
  Attribute attr_one(TYPE_STRING | TYPE_REFERENCE);
  Attribute attr_two(TYPE_STRING);
  Attribute attr_three(TYPE_ENUM);
  Attribute attr_four(TYPE_REFERENCE);

  EXPECT_TRUE(attr_one.IsCompatibleWith(attr_one));
  EXPECT_TRUE(attr_one.IsCompatibleWith(attr_two));
  EXPECT_FALSE(attr_one.IsCompatibleWith(attr_three));
  EXPECT_FALSE(attr_one.IsCompatibleWith(attr_four));

  EXPECT_TRUE(attr_two.IsCompatibleWith(attr_one));
  EXPECT_TRUE(attr_two.IsCompatibleWith(attr_two));
  EXPECT_FALSE(attr_two.IsCompatibleWith(attr_three));
  EXPECT_FALSE(attr_two.IsCompatibleWith(attr_four));

  EXPECT_FALSE(attr_three.IsCompatibleWith(attr_one));
  EXPECT_FALSE(attr_three.IsCompatibleWith(attr_two));
  EXPECT_FALSE(attr_three.IsCompatibleWith(attr_three));
  EXPECT_FALSE(attr_three.IsCompatibleWith(attr_four));
}

} // namespace aapt
