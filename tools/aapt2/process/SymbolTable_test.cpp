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
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("android:id/foo", ResourceId(0x01020000))
          .AddSimple("android:id/bar")
          .AddValue("android:attr/foo", ResourceId(0x01010000),
                    test::AttributeBuilder().Build())
          .Build();

  ResourceTableSymbolSource symbol_source(table.get());
  EXPECT_NE(nullptr, symbol_source.FindByName(test::ParseNameOrDie("android:id/foo")));
  EXPECT_NE(nullptr, symbol_source.FindByName(test::ParseNameOrDie("android:id/bar")));

  std::unique_ptr<SymbolTable::Symbol> s =
      symbol_source.FindByName(test::ParseNameOrDie("android:attr/foo"));
  ASSERT_NE(nullptr, s);
  EXPECT_NE(nullptr, s->attribute);
}

TEST(ResourceTableSymbolSourceTest, FindPrivateAttrSymbol) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("android:^attr-private/foo", ResourceId(0x01010000),
                    test::AttributeBuilder().Build())
          .Build();

  ResourceTableSymbolSource symbol_source(table.get());
  std::unique_ptr<SymbolTable::Symbol> s =
      symbol_source.FindByName(test::ParseNameOrDie("android:attr/foo"));
  ASSERT_NE(nullptr, s);
  EXPECT_NE(nullptr, s->attribute);
}

TEST(SymbolTableTest, FindByName) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("com.android.app:id/foo")
          .AddSimple("com.android.app:id/" + NameMangler::MangleEntry("com.android.lib", "foo"))
          .Build();

  NameMangler mangler(NameManglerPolicy{"com.android.app", {"com.android.lib"}});
  SymbolTable symbol_table(&mangler);
  symbol_table.AppendSource(util::make_unique<ResourceTableSymbolSource>(table.get()));

  EXPECT_NE(nullptr, symbol_table.FindByName(test::ParseNameOrDie("id/foo")));
  EXPECT_NE(nullptr, symbol_table.FindByName(test::ParseNameOrDie("com.android.lib:id/foo")));
}

}  // namespace aapt
