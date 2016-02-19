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

#include "process/SymbolTable.h"
#include "test/Test.h"

namespace aapt {

TEST(ResourceTableSymbolSourceTest, FindSymbols) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple(u"@android:id/foo", ResourceId(0x01020000))
            .addSimple(u"@android:id/bar")
            .addValue(u"@android:attr/foo", ResourceId(0x01010000),
                      test::AttributeBuilder().build())
            .build();

    ResourceTableSymbolSource symbolSource(table.get());
    EXPECT_NE(nullptr, symbolSource.findByName(test::parseNameOrDie(u"@android:id/foo")));
    EXPECT_NE(nullptr, symbolSource.findByName(test::parseNameOrDie(u"@android:id/bar")));

    std::unique_ptr<SymbolTable::Symbol> s = symbolSource.findByName(
            test::parseNameOrDie(u"@android:attr/foo"));
    ASSERT_NE(nullptr, s);
    EXPECT_NE(nullptr, s->attribute);
}

TEST(ResourceTableSymbolSourceTest, FindPrivateAttrSymbol) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addValue(u"@android:^attr-private/foo", ResourceId(0x01010000),
                      test::AttributeBuilder().build())
            .build();

    ResourceTableSymbolSource symbolSource(table.get());
    std::unique_ptr<SymbolTable::Symbol> s = symbolSource.findByName(
                test::parseNameOrDie(u"@android:attr/foo"));
    ASSERT_NE(nullptr, s);
    EXPECT_NE(nullptr, s->attribute);
}

} // namespace aapt
