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
#include "test/Test.h"
#include "util/Util.h"

#include <string>

namespace aapt {

TEST(StringPoolTest, InsertOneString) {
    StringPool pool;

    StringPool::Ref ref = pool.makeRef("wut");
    EXPECT_EQ(*ref, "wut");
}

TEST(StringPoolTest, InsertTwoUniqueStrings) {
    StringPool pool;

    StringPool::Ref ref = pool.makeRef("wut");
    StringPool::Ref ref2 = pool.makeRef("hey");

    EXPECT_EQ(*ref, "wut");
    EXPECT_EQ(*ref2, "hey");
}

TEST(StringPoolTest, DoNotInsertNewDuplicateString) {
    StringPool pool;

    StringPool::Ref ref = pool.makeRef("wut");
    StringPool::Ref ref2 = pool.makeRef("wut");

    EXPECT_EQ(*ref, "wut");
    EXPECT_EQ(*ref2, "wut");
    EXPECT_EQ(1u, pool.size());
}

TEST(StringPoolTest, MaintainInsertionOrderIndex) {
    StringPool pool;

    StringPool::Ref ref = pool.makeRef("z");
    StringPool::Ref ref2 = pool.makeRef("a");
    StringPool::Ref ref3 = pool.makeRef("m");

    EXPECT_EQ(0u, ref.getIndex());
    EXPECT_EQ(1u, ref2.getIndex());
    EXPECT_EQ(2u, ref3.getIndex());
}

TEST(StringPoolTest, PruneStringsWithNoReferences) {
    StringPool pool;

    StringPool::Ref refA = pool.makeRef("foo");
    {
        StringPool::Ref ref = pool.makeRef("wut");
        EXPECT_EQ(*ref, "wut");
        EXPECT_EQ(2u, pool.size());
    }
    StringPool::Ref refB = pool.makeRef("bar");

    EXPECT_EQ(3u, pool.size());
    pool.prune();
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

    StringPool::Ref ref = pool.makeRef("z");
    StringPool::StyleRef ref2 = pool.makeRef(StyleString{ {"a"} });
    StringPool::Ref ref3 = pool.makeRef("m");

    EXPECT_EQ(*ref, "z");
    EXPECT_EQ(0u, ref.getIndex());

    EXPECT_EQ(*(ref2->str), "a");
    EXPECT_EQ(1u, ref2.getIndex());

    EXPECT_EQ(*ref3, "m");
    EXPECT_EQ(2u, ref3.getIndex());

    pool.sort([](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
        return a.value < b.value;
    });


    EXPECT_EQ(*ref, "z");
    EXPECT_EQ(2u, ref.getIndex());

    EXPECT_EQ(*(ref2->str), "a");
    EXPECT_EQ(0u, ref2.getIndex());

    EXPECT_EQ(*ref3, "m");
    EXPECT_EQ(1u, ref3.getIndex());
}

TEST(StringPoolTest, SortAndStillDedupe) {
    StringPool pool;

    StringPool::Ref ref = pool.makeRef("z");
    StringPool::Ref ref2 = pool.makeRef("a");
    StringPool::Ref ref3 = pool.makeRef("m");

    pool.sort([](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
        return a.value < b.value;
    });

    StringPool::Ref ref4 = pool.makeRef("z");
    StringPool::Ref ref5 = pool.makeRef("a");
    StringPool::Ref ref6 = pool.makeRef("m");

    EXPECT_EQ(ref4.getIndex(), ref.getIndex());
    EXPECT_EQ(ref5.getIndex(), ref2.getIndex());
    EXPECT_EQ(ref6.getIndex(), ref3.getIndex());
}

TEST(StringPoolTest, AddStyles) {
    StringPool pool;

    StyleString str {
        { "android" },
        {
            Span{ { "b" }, 2, 6 }
        }
    };

    StringPool::StyleRef ref = pool.makeRef(str);

    EXPECT_EQ(0u, ref.getIndex());
    EXPECT_EQ(std::string("android"), *(ref->str));
    ASSERT_EQ(1u, ref->spans.size());

    const StringPool::Span& span = ref->spans.front();
    EXPECT_EQ(*(span.name), "b");
    EXPECT_EQ(2u, span.firstChar);
    EXPECT_EQ(6u, span.lastChar);
}

TEST(StringPoolTest, DoNotDedupeStyleWithSameStringAsNonStyle) {
    StringPool pool;

    StringPool::Ref ref = pool.makeRef("android");

    StyleString str { { "android" } };
    StringPool::StyleRef styleRef = pool.makeRef(str);

    EXPECT_NE(ref.getIndex(), styleRef.getIndex());
}

TEST(StringPoolTest, FlattenEmptyStringPoolUtf8) {
    using namespace android; // For NO_ERROR on Windows.

    StringPool pool;
    BigBuffer buffer(1024);
    StringPool::flattenUtf8(&buffer, pool);

    std::unique_ptr<uint8_t[]> data = util::copy(buffer);
    ResStringPool test;
    ASSERT_EQ(test.setTo(data.get(), buffer.size()), NO_ERROR);
}

TEST(StringPoolTest, FlattenOddCharactersUtf16) {
    using namespace android; // For NO_ERROR on Windows.

    StringPool pool;
    pool.makeRef("\u093f");
    BigBuffer buffer(1024);
    StringPool::flattenUtf16(&buffer, pool);

    std::unique_ptr<uint8_t[]> data = util::copy(buffer);
    ResStringPool test;
    ASSERT_EQ(test.setTo(data.get(), buffer.size()), NO_ERROR);
    size_t len = 0;
    const char16_t* str = test.stringAt(0, &len);
    EXPECT_EQ(1u, len);
    EXPECT_EQ(u'\u093f', *str);
    EXPECT_EQ(0u, str[1]);
}

constexpr const char* sLongString = "バッテリーを長持ちさせるため、バッテリーセーバーは端末のパフォーマンスを抑え、バイブレーション、位置情報サービス、大半のバックグラウンドデータを制限します。メール、SMSや、同期を使 用するその他のアプリは、起動しても更新されないことがあります。バッテリーセーバーは端末の充電中は自動的にOFFになります。";

TEST(StringPoolTest, Flatten) {
    using namespace android; // For NO_ERROR on Windows.

    StringPool pool;

    StringPool::Ref ref1 = pool.makeRef("hello");
    StringPool::Ref ref2 = pool.makeRef("goodbye");
    StringPool::Ref ref3 = pool.makeRef(sLongString);
    StringPool::Ref ref4 = pool.makeRef("");
    StringPool::StyleRef ref5 = pool.makeRef(StyleString{
            { "style" },
            { Span{ { "b" }, 0, 1 }, Span{ { "i" }, 2, 3 } }
    });

    EXPECT_EQ(0u, ref1.getIndex());
    EXPECT_EQ(1u, ref2.getIndex());
    EXPECT_EQ(2u, ref3.getIndex());
    EXPECT_EQ(3u, ref4.getIndex());
    EXPECT_EQ(4u, ref5.getIndex());

    BigBuffer buffers[2] = { BigBuffer(1024), BigBuffer(1024) };
    StringPool::flattenUtf8(&buffers[0], pool);
    StringPool::flattenUtf16(&buffers[1], pool);

    // Test both UTF-8 and UTF-16 buffers.
    for (const BigBuffer& buffer : buffers) {
        std::unique_ptr<uint8_t[]> data = util::copy(buffer);

        ResStringPool test;
        ASSERT_EQ(test.setTo(data.get(), buffer.size()), NO_ERROR);

        EXPECT_EQ(std::string("hello"), util::getString(test, 0));
        EXPECT_EQ(StringPiece16(u"hello"), util::getString16(test, 0));

        EXPECT_EQ(std::string("goodbye"), util::getString(test, 1));
        EXPECT_EQ(StringPiece16(u"goodbye"), util::getString16(test, 1));

        EXPECT_EQ(StringPiece(sLongString), util::getString(test, 2));
        EXPECT_EQ(util::utf8ToUtf16(sLongString), util::getString16(test, 2).toString());

        size_t len;
        EXPECT_TRUE(test.stringAt(3, &len) != nullptr || test.string8At(3, &len) != nullptr);

        EXPECT_EQ(std::string("style"), util::getString(test, 4));
        EXPECT_EQ(StringPiece16(u"style"), util::getString16(test, 4));

        const ResStringPool_span* span = test.styleAt(4);
        ASSERT_NE(nullptr, span);
        EXPECT_EQ(std::string("b"), util::getString(test, span->name.index));
        EXPECT_EQ(StringPiece16(u"b"), util::getString16(test, span->name.index));
        EXPECT_EQ(0u, span->firstChar);
        EXPECT_EQ(1u, span->lastChar);
        span++;

        ASSERT_NE(ResStringPool_span::END, span->name.index);
        EXPECT_EQ(std::string("i"), util::getString(test, span->name.index));
        EXPECT_EQ(StringPiece16(u"i"), util::getString16(test, span->name.index));
        EXPECT_EQ(2u, span->firstChar);
        EXPECT_EQ(3u, span->lastChar);
        span++;

        EXPECT_EQ(ResStringPool_span::END, span->name.index);
    }
}

} // namespace aapt
