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
#include "test/Builders.h"
#include "test/Context.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(SymbolTableWrapperTest, FindSymbolsWithIds) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple(u"@android:id/foo", ResourceId(0x01020000))
            .addSimple(u"@android:id/bar")
            .addValue(u"@android:attr/foo", ResourceId(0x01010000),
                      test::AttributeBuilder().build())
            .build();

    SymbolTableWrapper symbolTable(table.get());
    EXPECT_NE(symbolTable.findByName(test::parseNameOrDie(u"@android:id/foo")), nullptr);
    EXPECT_EQ(symbolTable.findByName(test::parseNameOrDie(u"@android:id/bar")), nullptr);

    const ISymbolTable::Symbol* s = symbolTable.findByName(
            test::parseNameOrDie(u"@android:attr/foo"));
    ASSERT_NE(s, nullptr);
    EXPECT_NE(s->attribute, nullptr);
}

TEST(SymbolTableWrapperTest, FindPrivateAttrSymbol) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addValue(u"@android:^attr-private/foo", ResourceId(0x01010000),
                      test::AttributeBuilder().build())
            .build();

    SymbolTableWrapper symbolTable(table.get());
    const ISymbolTable::Symbol* s = symbolTable.findByName(
                test::parseNameOrDie(u"@android:attr/foo"));
    ASSERT_NE(s, nullptr);
    EXPECT_NE(s->attribute, nullptr);
}

} // namespace aapt
