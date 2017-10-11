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

#include "test/Test.h"
#include "util/Util.h"

using android::StringPiece;
using android::StringPiece16;

namespace aapt {

TEST(StringPoolTest, InsertOneString) {
  StringPool pool;

  StringPool::Ref ref = pool.MakeRef("wut");
  EXPECT_EQ(*ref, "wut");
}

TEST(StringPoolTest, InsertTwoUniqueStrings) {
  StringPool pool;

  StringPool::Ref ref = pool.MakeRef("wut");
  StringPool::Ref ref2 = pool.MakeRef("hey");

  EXPECT_EQ(*ref, "wut");
  EXPECT_EQ(*ref2, "hey");
}

TEST(StringPoolTest, DoNotInsertNewDuplicateString) {
  StringPool pool;

  StringPool::Ref ref = pool.MakeRef("wut");
  StringPool::Ref ref2 = pool.MakeRef("wut");

  EXPECT_EQ(*ref, "wut");
  EXPECT_EQ(*ref2, "wut");
  EXPECT_EQ(1u, pool.size());
}

TEST(StringPoolTest, MaintainInsertionOrderIndex) {
  StringPool pool;

  StringPool::Ref ref = pool.MakeRef("z");
  StringPool::Ref ref2 = pool.MakeRef("a");
  StringPool::Ref ref3 = pool.MakeRef("m");

  EXPECT_EQ(0u, ref.index());
  EXPECT_EQ(1u, ref2.index());
  EXPECT_EQ(2u, ref3.index());
}

TEST(StringPoolTest, PruneStringsWithNoReferences) {
  StringPool pool;

  StringPool::Ref refA = pool.MakeRef("foo");
  {
    StringPool::Ref ref = pool.MakeRef("wut");
    EXPECT_EQ(*ref, "wut");
    EXPECT_EQ(2u, pool.size());
  }
  StringPool::Ref refB = pool.MakeRef("bar");

  EXPECT_EQ(3u, pool.size());
  pool.Prune();
  EXPECT_EQ(2u, pool.size());
  StringPool::const_iterator iter = begin(pool);
  EXPECT_EQ((*iter)->value, "foo");
  EXPECT_LT((*iter)->index, 2u);
  ++iter;
  EXPECT_EQ((*iter)->value, "bar");
  EXPECT_LT((*iter)->index, 2u);
}

TEST(StringPoolTest, SortAndMaintainIndexesInReferences) {
  StringPool pool;

  StringPool::Ref ref = pool.MakeRef("z");
  StringPool::StyleRef ref2 = pool.MakeRef(StyleString{{"a"}});
  StringPool::Ref ref3 = pool.MakeRef("m");

  EXPECT_EQ(*ref, "z");
  EXPECT_EQ(0u, ref.index());

  EXPECT_EQ(*(ref2->str), "a");
  EXPECT_EQ(1u, ref2.index());

  EXPECT_EQ(*ref3, "m");
  EXPECT_EQ(2u, ref3.index());

  pool.Sort([](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
    return a.value < b.value;
  });

  EXPECT_EQ(*ref, "z");
  EXPECT_EQ(2u, ref.index());

  EXPECT_EQ(*(ref2->str), "a");
  EXPECT_EQ(0u, ref2.index());

  EXPECT_EQ(*ref3, "m");
  EXPECT_EQ(1u, ref3.index());
}

TEST(StringPoolTest, SortAndStillDedupe) {
  StringPool pool;

  StringPool::Ref ref = pool.MakeRef("z");
  StringPool::Ref ref2 = pool.MakeRef("a");
  StringPool::Ref ref3 = pool.MakeRef("m");

  pool.Sort([](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
    return a.value < b.value;
  });

  StringPool::Ref ref4 = pool.MakeRef("z");
  StringPool::Ref ref5 = pool.MakeRef("a");
  StringPool::Ref ref6 = pool.MakeRef("m");

  EXPECT_EQ(ref4.index(), ref.index());
  EXPECT_EQ(ref5.index(), ref2.index());
  EXPECT_EQ(ref6.index(), ref3.index());
}

TEST(StringPoolTest, AddStyles) {
  StringPool pool;

  StyleString str{{"android"}, {Span{{"b"}, 2, 6}}};

  StringPool::StyleRef ref = pool.MakeRef(str);

  EXPECT_EQ(0u, ref.index());
  EXPECT_EQ(std::string("android"), *(ref->str));
  ASSERT_EQ(1u, ref->spans.size());

  const StringPool::Span& span = ref->spans.front();
  EXPECT_EQ(*(span.name), "b");
  EXPECT_EQ(2u, span.first_char);
  EXPECT_EQ(6u, span.last_char);
}

TEST(StringPoolTest, DoNotDedupeStyleWithSameStringAsNonStyle) {
  StringPool pool;

  StringPool::Ref ref = pool.MakeRef("android");

  StyleString str{{"android"}};
  StringPool::StyleRef styleRef = pool.MakeRef(str);

  EXPECT_NE(ref.index(), styleRef.index());
}

TEST(StringPoolTest, FlattenEmptyStringPoolUtf8) {
  using namespace android;  // For NO_ERROR on Windows.

  StringPool pool;
  BigBuffer buffer(1024);
  StringPool::FlattenUtf8(&buffer, pool);

  std::unique_ptr<uint8_t[]> data = util::Copy(buffer);
  ResStringPool test;
  ASSERT_EQ(test.setTo(data.get(), buffer.size()), NO_ERROR);
}

TEST(StringPoolTest, FlattenOddCharactersUtf16) {
  using namespace android;  // For NO_ERROR on Windows.

  StringPool pool;
  pool.MakeRef("\u093f");
  BigBuffer buffer(1024);
  StringPool::FlattenUtf16(&buffer, pool);

  std::unique_ptr<uint8_t[]> data = util::Copy(buffer);
  ResStringPool test;
  ASSERT_EQ(test.setTo(data.get(), buffer.size()), NO_ERROR);
  size_t len = 0;
  const char16_t* str = test.stringAt(0, &len);
  EXPECT_EQ(1u, len);
  EXPECT_EQ(u'\u093f', *str);
  EXPECT_EQ(0u, str[1]);
}

constexpr const char* sLongString =
    "バッテリーを長持ちさせるため、バッテリーセーバーは端末のパフォーマンスを抑"
    "え、バイブレーション、位置情報サービス、大半のバックグラウンドデータを制限"
    "します。メール、SMSや、同期を使 "
    "用するその他のアプリは、起動しても更新されないことがあります。バッテリーセ"
    "ーバーは端末の充電中は自動的にOFFになります。";

TEST(StringPoolTest, Flatten) {
  using namespace android;  // For NO_ERROR on Windows.

  StringPool pool;

  StringPool::Ref ref1 = pool.MakeRef("hello");
  StringPool::Ref ref2 = pool.MakeRef("goodbye");
  StringPool::Ref ref3 = pool.MakeRef(sLongString);
  StringPool::Ref ref4 = pool.MakeRef("");
  StringPool::StyleRef ref5 = pool.MakeRef(
      StyleString{{"style"}, {Span{{"b"}, 0, 1}, Span{{"i"}, 2, 3}}});

  EXPECT_EQ(0u, ref1.index());
  EXPECT_EQ(1u, ref2.index());
  EXPECT_EQ(2u, ref3.index());
  EXPECT_EQ(3u, ref4.index());
  EXPECT_EQ(4u, ref5.index());

  BigBuffer buffers[2] = {BigBuffer(1024), BigBuffer(1024)};
  StringPool::FlattenUtf8(&buffers[0], pool);
  StringPool::FlattenUtf16(&buffers[1], pool);

  // Test both UTF-8 and UTF-16 buffers.
  for (const BigBuffer& buffer : buffers) {
    std::unique_ptr<uint8_t[]> data = util::Copy(buffer);

    ResStringPool test;
    ASSERT_EQ(test.setTo(data.get(), buffer.size()), NO_ERROR);

    EXPECT_EQ(std::string("hello"), util::GetString(test, 0));
    EXPECT_EQ(StringPiece16(u"hello"), util::GetString16(test, 0));

    EXPECT_EQ(std::string("goodbye"), util::GetString(test, 1));
    EXPECT_EQ(StringPiece16(u"goodbye"), util::GetString16(test, 1));

    EXPECT_EQ(StringPiece(sLongString), util::GetString(test, 2));
    EXPECT_EQ(util::Utf8ToUtf16(sLongString), util::GetString16(test, 2).to_string());

    size_t len;
    EXPECT_TRUE(test.stringAt(3, &len) != nullptr ||
                test.string8At(3, &len) != nullptr);

    EXPECT_EQ(std::string("style"), util::GetString(test, 4));
    EXPECT_EQ(StringPiece16(u"style"), util::GetString16(test, 4));

    const ResStringPool_span* span = test.styleAt(4);
    ASSERT_NE(nullptr, span);
    EXPECT_EQ(std::string("b"), util::GetString(test, span->name.index));
    EXPECT_EQ(StringPiece16(u"b"), util::GetString16(test, span->name.index));
    EXPECT_EQ(0u, span->firstChar);
    EXPECT_EQ(1u, span->lastChar);
    span++;

    ASSERT_NE(ResStringPool_span::END, span->name.index);
    EXPECT_EQ(std::string("i"), util::GetString(test, span->name.index));
    EXPECT_EQ(StringPiece16(u"i"), util::GetString16(test, span->name.index));
    EXPECT_EQ(2u, span->firstChar);
    EXPECT_EQ(3u, span->lastChar);
    span++;

    EXPECT_EQ(ResStringPool_span::END, span->name.index);
  }
}

}  // namespace aapt
