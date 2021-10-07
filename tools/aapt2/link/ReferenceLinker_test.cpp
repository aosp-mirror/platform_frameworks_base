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

#include "link/ReferenceLinker.h"

#include "test/Test.h"

using ::android::ResTable_map;
using ::testing::Eq;
using ::testing::IsNull;
using ::testing::NotNull;

namespace aapt {

TEST(ReferenceLinkerTest, LinkSimpleReferences) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddReference("com.app.test:string/foo", ResourceId(0x7f020000),
                        "com.app.test:string/bar")

          // Test use of local reference (w/o package name).
          .AddReference("com.app.test:string/bar", ResourceId(0x7f020001),
                        "string/baz")

          .AddReference("com.app.test:string/baz", ResourceId(0x7f020002),
                        "android:string/ok")
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(NameManglerPolicy{"com.app.test"})
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddPublicSymbol("android:string/ok", ResourceId(0x01040034))
                  .Build())
          .Build();

  ReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context.get(), table.get()));

  Reference* ref = test::GetValue<Reference>(table.get(), "com.app.test:string/foo");
  ASSERT_THAT(ref, NotNull());
  ASSERT_TRUE(ref->id);
  EXPECT_EQ(ResourceId(0x7f020001), ref->id.value());

  ref = test::GetValue<Reference>(table.get(), "com.app.test:string/bar");
  ASSERT_THAT(ref, NotNull());
  ASSERT_TRUE(ref->id);
  EXPECT_EQ(ResourceId(0x7f020002), ref->id.value());

  ref = test::GetValue<Reference>(table.get(), "com.app.test:string/baz");
  ASSERT_THAT(ref, NotNull());
  ASSERT_TRUE(ref->id);
  EXPECT_EQ(ResourceId(0x01040034), ref->id.value());
}

TEST(ReferenceLinkerTest, LinkStyleAttributes) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("com.app.test:style/Theme",
                    test::StyleBuilder()
                        .SetParent("android:style/Theme.Material")
                        .AddItem("android:attr/foo",
                                 ResourceUtils::TryParseColor("#ff00ff"))
                        .AddItem("android:attr/bar", {} /* placeholder */)
                        .Build())
          .Build();

  {
    // We need to fill in the value for the attribute android:attr/bar after we
    // build the table, because we need access to the string pool.
    Style* style = test::GetValue<Style>(table.get(), "com.app.test:style/Theme");
    ASSERT_THAT(style, NotNull());
    style->entries.back().value =
        util::make_unique<RawString>(table->string_pool.MakeRef("one|two"));
  }

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(NameManglerPolicy{"com.app.test"})
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddPublicSymbol("android:style/Theme.Material",
                                   ResourceId(0x01060000))
                  .AddPublicSymbol("android:attr/foo", ResourceId(0x01010001),
                                   test::AttributeBuilder()
                                       .SetTypeMask(ResTable_map::TYPE_COLOR)
                                       .Build())
                  .AddPublicSymbol("android:attr/bar", ResourceId(0x01010002),
                                   test::AttributeBuilder()
                                       .SetTypeMask(ResTable_map::TYPE_FLAGS)
                                       .AddItem("one", 0x01)
                                       .AddItem("two", 0x02)
                                       .Build())
                  .Build())
          .Build();

  ReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context.get(), table.get()));

  Style* style = test::GetValue<Style>(table.get(), "com.app.test:style/Theme");
  ASSERT_THAT(style, NotNull());
  ASSERT_TRUE(style->parent);
  ASSERT_TRUE(style->parent.value().id);
  EXPECT_EQ(ResourceId(0x01060000), style->parent.value().id.value());

  ASSERT_EQ(2u, style->entries.size());

  ASSERT_TRUE(style->entries[0].key.id);
  EXPECT_EQ(ResourceId(0x01010001), style->entries[0].key.id.value());
  ASSERT_THAT(ValueCast<BinaryPrimitive>(style->entries[0].value.get()), NotNull());

  ASSERT_TRUE(style->entries[1].key.id);
  EXPECT_EQ(ResourceId(0x01010002), style->entries[1].key.id.value());
  ASSERT_THAT(ValueCast<BinaryPrimitive>(style->entries[1].value.get()), NotNull());
}

TEST(ReferenceLinkerTest, LinkMangledReferencesAndAttributes) {
  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(
              NameManglerPolicy{"com.app.test", {"com.android.support"}})
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddPublicSymbol("com.app.test:attr/com.android.support$foo",
                                   ResourceId(0x7f010000),
                                   test::AttributeBuilder()
                                       .SetTypeMask(ResTable_map::TYPE_COLOR)
                                       .Build())
                  .Build())
          .Build();

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("com.app.test:style/Theme", ResourceId(0x7f020000),
                    test::StyleBuilder()
                        .AddItem("com.android.support:attr/foo",
                                 ResourceUtils::TryParseColor("#ff0000"))
                        .Build())
          .Build();

  ReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context.get(), table.get()));

  Style* style = test::GetValue<Style>(table.get(), "com.app.test:style/Theme");
  ASSERT_THAT(style, NotNull());
  ASSERT_EQ(1u, style->entries.size());
  ASSERT_TRUE(style->entries.front().key.id);
  EXPECT_EQ(ResourceId(0x7f010000), style->entries.front().key.id.value());
}

TEST(ReferenceLinkerTest, FailToLinkPrivateSymbols) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddReference("com.app.test:string/foo", ResourceId(0x7f020000),
                        "android:string/hidden")
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(NameManglerPolicy{"com.app.test"})
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddSymbol("android:string/hidden", ResourceId(0x01040034))
                  .Build())
          .Build();

  ReferenceLinker linker;
  ASSERT_FALSE(linker.Consume(context.get(), table.get()));
}

TEST(ReferenceLinkerTest, FailToLinkPrivateMangledSymbols) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddReference("com.app.test:string/foo", ResourceId(0x7f020000),
                        "com.app.lib:string/hidden")
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(
              NameManglerPolicy{"com.app.test", {"com.app.lib"}})
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddSymbol("com.app.test:string/com.app.lib$hidden",
                             ResourceId(0x7f040034))
                  .Build())

          .Build();

  ReferenceLinker linker;
  ASSERT_FALSE(linker.Consume(context.get(), table.get()));
}

TEST(ReferenceLinkerTest, FailToLinkPrivateStyleAttributes) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("com.app.test:style/Theme",
                    test::StyleBuilder()
                        .AddItem("android:attr/hidden",
                                 ResourceUtils::TryParseColor("#ff00ff"))
                        .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(NameManglerPolicy{"com.app.test"})
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .AddSymbolSource(
              test::StaticSymbolSourceBuilder()
                  .AddSymbol("android:attr/hidden", ResourceId(0x01010001),
                             test::AttributeBuilder()
                                 .SetTypeMask(android::ResTable_map::TYPE_COLOR)
                                 .Build())
                  .Build())
          .Build();

  ReferenceLinker linker;
  ASSERT_FALSE(linker.Consume(context.get(), table.get()));
}

TEST(ReferenceLinkerTest, AppsWithSamePackageButDifferentIdAreVisibleNonPublic) {
  NameMangler mangler(NameManglerPolicy{"com.app.test"});
  SymbolTable table(&mangler);
  table.AppendSource(test::StaticSymbolSourceBuilder()
                         .AddSymbol("com.app.test:string/foo", ResourceId(0x7f010000))
                         .Build());

  std::string error;
  const CallSite call_site{"com.app.test"};
  std::unique_ptr<IAaptContext> context =
    test::ContextBuilder()
        .SetCompilationPackage("com.app.test")
        .SetPackageId(0x7f)
        .Build();
  const SymbolTable::Symbol* symbol = ReferenceLinker::ResolveSymbolCheckVisibility(
      *test::BuildReference("com.app.test:string/foo"), call_site, context.get(), &table, &error);
  ASSERT_THAT(symbol, NotNull());
  EXPECT_TRUE(error.empty());
}

TEST(ReferenceLinkerTest, AppsWithDifferentPackageCanNotUseEachOthersAttribute) {
  NameMangler mangler(NameManglerPolicy{"com.app.ext"});
  SymbolTable table(&mangler);
  table.AppendSource(test::StaticSymbolSourceBuilder()
                         .AddSymbol("com.app.test:attr/foo", ResourceId(0x7f010000),
                                    test::AttributeBuilder().Build())
                         .AddPublicSymbol("com.app.test:attr/public_foo", ResourceId(0x7f010001),
                                          test::AttributeBuilder().Build())
                         .Build());
  std::unique_ptr<IAaptContext> context =
    test::ContextBuilder()
        .SetCompilationPackage("com.app.ext")
        .SetPackageId(0x7f)
        .Build();

  std::string error;
  const CallSite call_site{"com.app.ext"};

  EXPECT_FALSE(ReferenceLinker::CompileXmlAttribute(
      *test::BuildReference("com.app.test:attr/foo"), call_site, context.get(), &table, &error));
  EXPECT_FALSE(error.empty());

  error = "";
  ASSERT_TRUE(ReferenceLinker::CompileXmlAttribute(
      *test::BuildReference("com.app.test:attr/public_foo"), call_site, context.get(), &table,
      &error));
  EXPECT_TRUE(error.empty());
}

TEST(ReferenceLinkerTest, ReferenceWithNoPackageUsesCallSitePackage) {
  NameMangler mangler(NameManglerPolicy{"com.app.test"});
  SymbolTable table(&mangler);
  table.AppendSource(test::StaticSymbolSourceBuilder()
                         .AddSymbol("com.app.test:string/foo", ResourceId(0x7f010000))
                         .AddSymbol("com.app.lib:string/foo", ResourceId(0x7f010001))
                         .Build());
  std::unique_ptr<IAaptContext> context =
    test::ContextBuilder()
        .SetCompilationPackage("com.app.test")
        .SetPackageId(0x7f)
        .Build();

  const SymbolTable::Symbol* s = ReferenceLinker::ResolveSymbol(*test::BuildReference("string/foo"),
                                                                CallSite{"com.app.test"},
                                                                context.get(), &table);
  ASSERT_THAT(s, NotNull());
  EXPECT_THAT(s->id, Eq(make_value<ResourceId>(0x7f010000)));

  s = ReferenceLinker::ResolveSymbol(*test::BuildReference("string/foo"), CallSite{"com.app.lib"},
                                     context.get(), &table);
  ASSERT_THAT(s, NotNull());
  EXPECT_THAT(s->id, Eq(make_value<ResourceId>(0x7f010001)));

  EXPECT_THAT(ReferenceLinker::ResolveSymbol(*test::BuildReference("string/foo"),
                                             CallSite{"com.app.bad"}, context.get(), &table),
              IsNull());
}

TEST(ReferenceLinkerTest, ReferenceSymbolFromOtherSplit) {
  NameMangler mangler(NameManglerPolicy{"com.app.test"});
  SymbolTable table(&mangler);
  table.AppendSource(test::StaticSymbolSourceBuilder()
                         .AddSymbol("com.app.test.feature:string/bar", ResourceId(0x80010000))
                         .Build());
  std::set<std::string> split_name_dependencies;
  split_name_dependencies.insert("feature");
  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x81)
          .SetSplitNameDependencies(split_name_dependencies)
          .Build();

  const SymbolTable::Symbol* s = ReferenceLinker::ResolveSymbol(*test::BuildReference("string/bar"),
                                                                CallSite{"com.app.test"},
                                                                context.get(), &table);
  ASSERT_THAT(s, NotNull());
  EXPECT_THAT(s->id, Eq(make_value<ResourceId>(0x80010000)));

  s = ReferenceLinker::ResolveSymbol(*test::BuildReference("string/foo"), CallSite{"com.app.lib"},
                                     context.get(), &table);
  EXPECT_THAT(s, IsNull());

  context =
    test::ContextBuilder()
        .SetCompilationPackage("com.app.test")
        .SetPackageId(0x81)
        .Build();
  s = ReferenceLinker::ResolveSymbol(*test::BuildReference("string/bar"),CallSite{"com.app.test"},
                                     context.get(), &table);

  EXPECT_THAT(s, IsNull());
}

TEST(ReferenceLinkerTest, MacroFailToFindDefinition) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddReference("com.app.test:string/foo", ResourceId(0x7f020000), "com.app.test:macro/bar")
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.app.test")
          .SetPackageId(0x7f)
          .SetNameManglerPolicy(NameManglerPolicy{"com.app.test"})
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .Build();

  ReferenceLinker linker;
  ASSERT_FALSE(linker.Consume(context.get(), table.get()));
}

}  // namespace aapt
