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

#include "SdkConstants.h"
#include "format/binary/TableFlattener.h"
#include "test/Test.h"
#include "util/BigBuffer.h"

using ::testing::Eq;
using ::testing::IsNull;
using ::testing::Ne;
using ::testing::NotNull;

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
  EXPECT_THAT(symbol_source.FindByName(test::ParseNameOrDie("android:id/foo")), NotNull());
  EXPECT_THAT(symbol_source.FindByName(test::ParseNameOrDie("android:id/bar")), NotNull());

  std::unique_ptr<SymbolTable::Symbol> s =
      symbol_source.FindByName(test::ParseNameOrDie("android:attr/foo"));
  ASSERT_THAT(s, NotNull());
  EXPECT_THAT(s->attribute, NotNull());
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
  ASSERT_THAT(s, NotNull());
  EXPECT_THAT(s->attribute, NotNull());
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

  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("id/foo")), NotNull());
  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.lib:id/foo")), NotNull());
}

TEST(SymbolTableTest, FindByNameWhenSymbolIsMangledInResTable) {
  using namespace android;

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.android.app")
          .SetPackageId(0x7f)
          .SetPackageType(PackageType::kApp)
          .SetMinSdkVersion(SDK_LOLLIPOP_MR1)
          .SetNameManglerPolicy(NameManglerPolicy{"com.android.app"})
          .Build();

  // Create a ResourceTable with a mangled resource, simulating a static library being merged into
  // the main application package.
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("com.android.app:id/" + NameMangler::MangleEntry("com.android.lib", "foo"),
                     ResourceId(0x7f020000))
          .AddSimple("com.android.app:id/bar", ResourceId(0x7f020001))
          .Build();

  BigBuffer buffer(1024u);
  TableFlattener flattener({}, &buffer);
  ASSERT_TRUE(flattener.Consume(context.get(), table.get()));

  std::unique_ptr<uint8_t[]> data = util::Copy(buffer);

  // Construct the test AssetManager.
  auto asset_manager_source = util::make_unique<AssetManagerSymbolSource>();
  ResTable& res_table = const_cast<ResTable&>(
      asset_manager_source->GetAssetManager()->getResources(false /*required*/));
  ASSERT_THAT(res_table.add(data.get(), buffer.size()), Eq(NO_ERROR));

  SymbolTable symbol_table(context->GetNameMangler());
  symbol_table.AppendSource(std::move(asset_manager_source));

  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.lib:id/foo")), NotNull());
  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.app:id/bar")), NotNull());

  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.app:id/foo")), IsNull());
  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.lib:id/bar")), IsNull());
  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.other:id/foo")), IsNull());
}

TEST(SymbolTableTest, FindByNameInAnyPackage) {
  // This represents lib3 --depends-on--> lib2 --depends-on--> lib1

  NameMangler mangler(NameManglerPolicy{"com.example.lib3"});
  SymbolTable symbol_table(&mangler);
  // Lib2 has higher precedence than lib1, as it is closer to the current library (lib3)
  // in the dependency graph.

  symbol_table.AppendSource(test::StaticSymbolSourceBuilder()
                                .AddPublicSymbol("com.example.lib1:string/foo", ResourceId())
                                .AddSymbol("com.example.lib1:attr/foo", ResourceId(),
                                           test::AttributeBuilder()
                                               .SetTypeMask(android::ResTable_map::TYPE_FLAGS)
                                               .AddItem("one", 0x01)
                                               .AddItem("two", 0x02)
                                               .Build())
                                .Build());
  symbol_table.PrependSource(test::StaticSymbolSourceBuilder()
                                 .AddPublicSymbol("com.example.lib2:string/foo", ResourceId())
                                 .Build());

  // Sanity test
  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("string/foo")), IsNull());

  // Test public symbol resolution
  const SymbolTable::Symbol* const found_string =
      symbol_table.FindByNameInAnyPackage(test::ParseNameOrDie("string/foo"));
  ASSERT_THAT(found_string, NotNull());

  // Test attr resolution
  const SymbolTable::Symbol* const found_attr =
      symbol_table.FindByNameInAnyPackage(test::ParseNameOrDie("attr/foo"));
  ASSERT_THAT(found_attr, NotNull());
}

}  // namespace aapt
