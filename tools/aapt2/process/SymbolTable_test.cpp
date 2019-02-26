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

using SymbolTableTestFixture = CommandTestFixture;
TEST_F(SymbolTableTestFixture, FindByNameWhenSymbolIsMangledInResTable) {
  using namespace android;
  StdErrDiagnostics diag;

  // Create a static library.
  const std::string static_lib_compiled_files_dir = GetTestPath("static-lib-compiled");
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values.xml"),
         R"(<?xml version="1.0" encoding="utf-8"?>
         <resources>
             <item type="id" name="foo"/>
        </resources>)",
         static_lib_compiled_files_dir, &diag));

  const std::string static_lib_apk = GetTestPath("static_lib.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest("com.android.lib"),
      "--min-sdk-version", "22",
      "--static-lib",
      "-o", static_lib_apk,
  };
  ASSERT_TRUE(Link(link_args, static_lib_compiled_files_dir, &diag));

  // Merge the static library into the main application package. The static library resources will
  // be mangled with the library package name.
  const std::string app_compiled_files_dir = GetTestPath("app-compiled");
  ASSERT_TRUE(CompileFile(GetTestPath("res/values/values.xml"),
      R"(<?xml version="1.0" encoding="utf-8"?>
         <resources>
             <item type="id" name="bar"/>
        </resources>)",
        app_compiled_files_dir, &diag));

  const std::string out_apk = GetTestPath("out.apk");
  link_args = {
      "--manifest", GetDefaultManifest("com.android.app"),
      "--min-sdk-version", "22",
      "-o", out_apk,
      static_lib_apk
  };
  ASSERT_TRUE(Link(link_args, app_compiled_files_dir, &diag));

  // Construct the test AssetManager.
  auto asset_manager_source = util::make_unique<AssetManagerSymbolSource>();
  asset_manager_source->AddAssetPath(out_apk);

  NameMangler name_mangler(NameManglerPolicy{"com.android.app"});
  SymbolTable symbol_table(&name_mangler);
  symbol_table.AppendSource(std::move(asset_manager_source));

  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.lib:id/foo")), NotNull());
  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.app:id/bar")), NotNull());

  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.app:id/foo")), IsNull());
  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.lib:id/bar")), IsNull());
  EXPECT_THAT(symbol_table.FindByName(test::ParseNameOrDie("com.android.other:id/foo")), IsNull());
}

}  // namespace aapt
