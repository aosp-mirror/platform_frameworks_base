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

#include "StringPool.h"

#include <string>

#include "androidfw/StringPiece.h"

#include "Diagnostics.h"
#include "test/Test.h"
#include "util/Util.h"

using ::android::StringPiece;
using ::android::StringPiece16;
using ::testing::Eq;
using ::testing::Ne;
using ::testing::NotNull;
using ::testing::Pointee;

namespace aapt {

TEST(StringPoolTest, InsertOneString) {
  StringPool pool;

  StringPool::Ref ref = pool.MakeRef("wut");
  EXPECT_THAT(*ref, Eq("wut"));
}

TEST(StringPoolTest, InsertTwoUniqueStrings) {
  StringPool pool;

  StringPool::Ref ref_a = pool.MakeRef("wut");
  StringPool::Ref ref_b = pool.MakeRef("hey");

  EXPECT_THAT(*ref_a, Eq("wut"));
  EXPECT_THAT(*ref_b, Eq("hey"));
}

TEST(StringPoolTest, DoNotInsertNewDuplicateString) {
  StringPool pool;

  StringPool::Ref ref_a = pool.MakeRef("wut");
  StringPool::Ref ref_b = pool.MakeRef("wut");

  EXPECT_THAT(*ref_a, Eq("wut"));
  EXPECT_THAT(*ref_b, Eq("wut"));
  EXPECT_THAT(pool.size(), Eq(1u));
}

TEST(StringPoolTest, DoNotDedupeSameStringDifferentPriority) {
  StringPool pool;

  StringPool::Ref ref_a = pool.MakeRef("wut", StringPool::Context(0x81010001));
  StringPool::Ref ref_b = pool.MakeRef("wut", StringPool::Context(0x81010002));

  EXPECT_THAT(*ref_a, Eq("wut"));
  EXPECT_THAT(*ref_b, Eq("wut"));
  EXPECT_THAT(pool.size(), Eq(2u));
}

TEST(StringPoolTest, MaintainInsertionOrderIndex) {
  StringPool pool;

  StringPool::Ref ref_a = pool.MakeRef("z");
  StringPool::Ref ref_b = pool.MakeRef("a");
  StringPool::Ref ref_c = pool.MakeRef("m");

  EXPECT_THAT(ref_a.index(), Eq(0u));
  EXPECT_THAT(ref_b.index(), Eq(1u));
  EXPECT_THAT(ref_c.index(), Eq(2u));
}

TEST(StringPoolTest, PruneStringsWithNoReferences) {
  StringPool pool;

  StringPool::Ref ref_a = pool.MakeRef("foo");

  {
    StringPool::Ref ref_b = pool.MakeRef("wut");
    EXPECT_THAT(*ref_b, Eq("wut"));
    EXPECT_THAT(pool.size(), Eq(2u));
    pool.Prune();
    EXPECT_THAT(pool.size(), Eq(2u));
  }
  EXPECT_THAT(pool.size(), Eq(2u));

  {
    StringPool::Ref ref_c = pool.MakeRef("bar");
    EXPECT_THAT(pool.size(), Eq(3u));

    pool.Prune();
    EXPECT_THAT(pool.size(), Eq(2u));
  }
  EXPECT_THAT(pool.size(), Eq(2u));

  pool.Prune();
  EXPECT_THAT(pool.size(), Eq(1u));
}

TEST(StringPoolTest, SortAndMaintainIndexesInStringReferences) {
  StringPool pool;

  StringPool::Ref ref_a = pool.MakeRef("z");
  StringPool::Ref ref_b = pool.MakeRef("a");
  StringPool::Ref ref_c = pool.MakeRef("m");

  EXPECT_THAT(*ref_a, Eq("z"));
  EXPECT_THAT(ref_a.index(), Eq(0u));

  EXPECT_THAT(*ref_b, Eq("a"));
  EXPECT_THAT(ref_b.index(), Eq(1u));

  EXPECT_THAT(*ref_c, Eq("m"));
  EXPECT_THAT(ref_c.index(), Eq(2u));

  pool.Sort();

  EXPECT_THAT(*ref_a, Eq("z"));
  EXPECT_THAT(ref_a.index(), Eq(2u));

  EXPECT_THAT(*ref_b, Eq("a"));
  EXPECT_THAT(ref_b.index(), Eq(0u));

  EXPECT_THAT(*ref_c, Eq("m"));
  EXPECT_THAT(ref_c.index(), Eq(1u));
}

TEST(StringPoolTest, SortAndStillDedupe) {
  StringPool pool;

  StringPool::Ref ref_a = pool.MakeRef("z");
  StringPool::Ref ref_b = pool.MakeRef("a");
  StringPool::Ref ref_c = pool.MakeRef("m");

  pool.Sort();

  StringPool::Ref ref_d = pool.MakeRef("z");
  StringPool::Ref ref_e = pool.MakeRef("a");
  StringPool::Ref ref_f = pool.MakeRef("m");

  EXPECT_THAT(ref_d.index(), Eq(ref_a.index()));
  EXPECT_THAT(ref_e.index(), Eq(ref_b.index()));
  EXPECT_THAT(ref_f.index(), Eq(ref_c.index()));
}

TEST(StringPoolTest, AddStyles) {
  StringPool pool;

  StringPool::StyleRef ref = pool.MakeRef(StyleString{{"android"}, {Span{{"b"}, 2, 6}}});
  EXPECT_THAT(ref.index(), Eq(0u));
  EXPECT_THAT(ref->value, Eq("android"));
  ASSERT_THAT(ref->spans.size(), Eq(1u));

  const StringPool::Span& span = ref->spans.front();
  EXPECT_THAT(*span.name, Eq("b"));
  EXPECT_THAT(span.first_char, Eq(2u));
  EXPECT_THAT(span.last_char, Eq(6u));
}

TEST(StringPoolTest, DoNotDedupeStyleWithSameStringAsNonStyle) {
  StringPool pool;

  StringPool::Ref ref = pool.MakeRef("android");

  StyleString str{{"android"}};
  StringPool::StyleRef style_ref = pool.MakeRef(StyleString{{"android"}});

  EXPECT_THAT(ref.index(), Ne(style_ref.index()));
}

TEST(StringPoolTest, StylesAndStringsAreSeparateAfterSorting) {
  StringPool pool;

  StringPool::StyleRef ref_a = pool.MakeRef(StyleString{{"beta"}});
  StringPool::Ref ref_b = pool.MakeRef("alpha");
  StringPool::StyleRef ref_c = pool.MakeRef(StyleString{{"alpha"}});

  EXPECT_THAT(ref_b.index(), Ne(ref_c.index()));

  pool.Sort();

  EXPECT_THAT(ref_c.index(), Eq(0u));
  EXPECT_THAT(ref_a.index(), Eq(1u));
  EXPECT_THAT(ref_b.index(), Eq(2u));
}

TEST(StringPoolTest, FlattenEmptyStringPoolUtf8) {
  using namespace android;  // For NO_ERROR on Windows.
  StdErrDiagnostics diag;

  StringPool pool;
  BigBuffer buffer(1024);
  StringPool::FlattenUtf8(&buffer, pool, &diag);

  std::unique_ptr<uint8_t[]> data = util::Copy(buffer);
  ResStringPool test;
  ASSERT_THAT(test.setTo(data.get(), buffer.size()), Eq(NO_ERROR));
}

TEST(StringPoolTest, FlattenOddCharactersUtf16) {
  using namespace android;  // For NO_ERROR on Windows.
  StdErrDiagnostics diag;

  StringPool pool;
  pool.MakeRef("\u093f");
  BigBuffer buffer(1024);
  StringPool::FlattenUtf16(&buffer, pool, &diag);

  std::unique_ptr<uint8_t[]> data = util::Copy(buffer);
  ResStringPool test;
  ASSERT_EQ(test.setTo(data.get(), buffer.size()), NO_ERROR);
  auto str = test.stringAt(0);
  ASSERT_TRUE(str.has_value());
  EXPECT_THAT(str->size(), Eq(1u));
  EXPECT_THAT(str->data(), Pointee(Eq(u'\u093f')));
  EXPECT_THAT(str->data()[1], Eq(0u));
}

constexpr const char* sLongString =
    "バッテリーを長持ちさせるため、バッテリーセーバーは端末のパフォーマンスを抑"
    "え、バイブレーション、位置情報サービス、大半のバックグラウンドデータを制限"
    "します。メール、SMSや、同期を使 "
    "用するその他のアプリは、起動しても更新されないことがあります。バッテリーセ"
    "ーバーは端末の充電中は自動的にOFFになります。";

TEST(StringPoolTest, Flatten) {
  using namespace android;  // For NO_ERROR on Windows.
  StdErrDiagnostics diag;

  StringPool pool;

  StringPool::Ref ref_a = pool.MakeRef("hello");
  StringPool::Ref ref_b = pool.MakeRef("goodbye");
  StringPool::Ref ref_c = pool.MakeRef(sLongString);
  StringPool::Ref ref_d = pool.MakeRef("");
  StringPool::StyleRef ref_e =
      pool.MakeRef(StyleString{{"style"}, {Span{{"b"}, 0, 1}, Span{{"i"}, 2, 3}}});

  // Styles are always first.
  EXPECT_THAT(ref_e.index(), Eq(0u));

  EXPECT_THAT(ref_a.index(), Eq(1u));
  EXPECT_THAT(ref_b.index(), Eq(2u));
  EXPECT_THAT(ref_c.index(), Eq(3u));
  EXPECT_THAT(ref_d.index(), Eq(4u));

  BigBuffer buffers[2] = {BigBuffer(1024), BigBuffer(1024)};
  StringPool::FlattenUtf8(&buffers[0], pool, &diag);
  StringPool::FlattenUtf16(&buffers[1], pool, &diag);

  // Test both UTF-8 and UTF-16 buffers.
  for (const BigBuffer& buffer : buffers) {
    std::unique_ptr<uint8_t[]> data = util::Copy(buffer);

    ResStringPool test;
    ASSERT_EQ(test.setTo(data.get(), buffer.size()), NO_ERROR);

    EXPECT_THAT(util::GetString(test, 1), Eq("hello"));
    EXPECT_THAT(util::GetString16(test, 1), Eq(u"hello"));

    EXPECT_THAT(util::GetString(test, 2), Eq("goodbye"));
    EXPECT_THAT(util::GetString16(test, 2), Eq(u"goodbye"));

    EXPECT_THAT(util::GetString(test, 3), Eq(sLongString));
    EXPECT_THAT(util::GetString16(test, 3), Eq(util::Utf8ToUtf16(sLongString)));

    EXPECT_TRUE(test.stringAt(4).has_value() || test.string8At(4).has_value());

    EXPECT_THAT(util::GetString(test, 0), Eq("style"));
    EXPECT_THAT(util::GetString16(test, 0), Eq(u"style"));

    auto span_result = test.styleAt(0);
    ASSERT_TRUE(span_result.has_value());

    const ResStringPool_span* span = span_result->unsafe_ptr();
    EXPECT_THAT(util::GetString(test, span->name.index), Eq("b"));
    EXPECT_THAT(util::GetString16(test, span->name.index), Eq(u"b"));
    EXPECT_THAT(span->firstChar, Eq(0u));
    EXPECT_THAT(span->lastChar, Eq(1u));
    span++;

    ASSERT_THAT(span->name.index, Ne(ResStringPool_span::END));
    EXPECT_THAT(util::GetString(test, span->name.index), Eq("i"));
    EXPECT_THAT(util::GetString16(test, span->name.index), Eq(u"i"));
    EXPECT_THAT(span->firstChar, Eq(2u));
    EXPECT_THAT(span->lastChar, Eq(3u));
    span++;

    EXPECT_THAT(span->name.index, Eq(ResStringPool_span::END));
  }
}

TEST(StringPoolTest, ModifiedUTF8) {
  using namespace android;  // For NO_ERROR on Windows.
  StdErrDiagnostics diag;
  StringPool pool;
  StringPool::Ref ref_a = pool.MakeRef("\xF0\x90\x90\x80"); // 𐐀 (U+10400)
  StringPool::Ref ref_b = pool.MakeRef("foo \xF0\x90\x90\xB7 bar"); // 𐐷 (U+10437)
  StringPool::Ref ref_c = pool.MakeRef("\xF0\x90\x90\x80\xF0\x90\x90\xB7");

  BigBuffer buffer(1024);
  StringPool::FlattenUtf8(&buffer, pool, &diag);
  std::unique_ptr<uint8_t[]> data = util::Copy(buffer);

  // Check that the codepoints are encoded using two three-byte surrogate pairs
  ResStringPool test;
  ASSERT_EQ(test.setTo(data.get(), buffer.size()), NO_ERROR);
  auto str = test.string8At(0);
  ASSERT_TRUE(str.has_value());
  EXPECT_THAT(str->to_string(), Eq("\xED\xA0\x81\xED\xB0\x80"));

  str = test.string8At(1);
  ASSERT_TRUE(str.has_value());
  EXPECT_THAT(str->to_string(), Eq("foo \xED\xA0\x81\xED\xB0\xB7 bar"));

  str = test.string8At(2);
  ASSERT_TRUE(str.has_value());
  EXPECT_THAT(str->to_string(), Eq("\xED\xA0\x81\xED\xB0\x80\xED\xA0\x81\xED\xB0\xB7"));

  // Check that retrieving the strings returns the original UTF-8 character bytes
  EXPECT_THAT(util::GetString(test, 0), Eq("\xF0\x90\x90\x80"));
  EXPECT_THAT(util::GetString(test, 1), Eq("foo \xF0\x90\x90\xB7 bar"));
  EXPECT_THAT(util::GetString(test, 2), Eq("\xF0\x90\x90\x80\xF0\x90\x90\xB7"));
}

TEST(StringPoolTest, MaxEncodingLength) {
  StdErrDiagnostics diag;
  using namespace android;  // For NO_ERROR on Windows.
  ResStringPool test;

  StringPool pool;
  pool.MakeRef("aaaaaaaaaa");
  BigBuffer buffers[2] = {BigBuffer(1024), BigBuffer(1024)};

  // Make sure a UTF-8 string under the maximum length does not produce an error
  EXPECT_THAT(StringPool::FlattenUtf8(&buffers[0], pool, &diag), Eq(true));
  std::unique_ptr<uint8_t[]> data = util::Copy(buffers[0]);
  test.setTo(data.get(), buffers[0].size());
  EXPECT_THAT(util::GetString(test, 0), Eq("aaaaaaaaaa"));

  // Make sure a UTF-16 string under the maximum length does not produce an error
  EXPECT_THAT(StringPool::FlattenUtf16(&buffers[1], pool, &diag), Eq(true));
  data = util::Copy(buffers[1]);
  test.setTo(data.get(), buffers[1].size());
  EXPECT_THAT(util::GetString16(test, 0), Eq(u"aaaaaaaaaa"));

  StringPool pool2;
  std::string longStr(50000, 'a');
  pool2.MakeRef("this fits1");
  pool2.MakeRef(longStr);
  pool2.MakeRef("this fits2");
  BigBuffer buffers2[2] = {BigBuffer(1024), BigBuffer(1024)};

  // Make sure a string that exceeds the maximum length of UTF-8 produces an
  // error and writes a shorter error string instead
  EXPECT_THAT(StringPool::FlattenUtf8(&buffers2[0], pool2, &diag), Eq(false));
  data = util::Copy(buffers2[0]);
  test.setTo(data.get(), buffers2[0].size());
  EXPECT_THAT(util::GetString(test, 0), "this fits1");
  EXPECT_THAT(util::GetString(test, 1), "STRING_TOO_LARGE");
  EXPECT_THAT(util::GetString(test, 2), "this fits2");

  // Make sure a string that a string that exceeds the maximum length of UTF-8
  // but not UTF-16 does not error for UTF-16
  StringPool pool3;
  std::u16string longStr16(50000, 'a');
  pool3.MakeRef(longStr);
  EXPECT_THAT(StringPool::FlattenUtf16(&buffers2[1], pool3, &diag), Eq(true));
  data = util::Copy(buffers2[1]);
  test.setTo(data.get(), buffers2[1].size());
  EXPECT_THAT(util::GetString16(test, 0), Eq(longStr16));
}

}  // namespace aapt
