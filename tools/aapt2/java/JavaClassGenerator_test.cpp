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

#include "java/JavaClassGenerator.h"

#include <sstream>
#include <string>

#include "test/Test.h"
#include "util/Util.h"

using android::StringPiece;

namespace aapt {

TEST(JavaClassGeneratorTest, FailWhenEntryIsJavaKeyword) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x01)
          .AddSimple("android:id/class", ResourceId(0x01020000))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::stringstream out;
  EXPECT_FALSE(generator.Generate("android", &out));
}

TEST(JavaClassGeneratorTest, TransformInvalidJavaIdentifierCharacter) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x01)
          .AddSimple("android:id/hey-man", ResourceId(0x01020000))
          .AddValue("android:attr/cool.attr", ResourceId(0x01010000),
                    test::AttributeBuilder(false).Build())
          .AddValue(
              "android:styleable/hey.dude", ResourceId(0x01030000),
              test::StyleableBuilder()
                  .AddItem("android:attr/cool.attr", ResourceId(0x01010000))
                  .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::stringstream out;
  EXPECT_TRUE(generator.Generate("android", &out));

  std::string output = out.str();

  EXPECT_NE(std::string::npos,
            output.find("public static final int hey_man=0x01020000;"));

  EXPECT_NE(std::string::npos,
            output.find("public static final int[] hey_dude={"));

  EXPECT_NE(std::string::npos,
            output.find("public static final int hey_dude_cool_attr=0;"));
}

TEST(JavaClassGeneratorTest, CorrectPackageNameIsUsed) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x01)
          .AddSimple("android:id/one", ResourceId(0x01020000))
          .AddSimple("android:id/com.foo$two", ResourceId(0x01020001))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});
  std::stringstream out;
  ASSERT_TRUE(generator.Generate("android", "com.android.internal", &out));

  std::string output = out.str();
  EXPECT_NE(std::string::npos, output.find("package com.android.internal;"));
  EXPECT_NE(std::string::npos,
            output.find("public static final int one=0x01020000;"));
  EXPECT_EQ(std::string::npos, output.find("two"));
  EXPECT_EQ(std::string::npos, output.find("com_foo$two"));
}

TEST(JavaClassGeneratorTest, AttrPrivateIsWrittenAsAttr) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x01)
          .AddSimple("android:attr/two", ResourceId(0x01010001))
          .AddSimple("android:^attr-private/one", ResourceId(0x01010000))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});
  std::stringstream out;
  ASSERT_TRUE(generator.Generate("android", &out));

  std::string output = out.str();
  EXPECT_NE(std::string::npos, output.find("public static final class attr"));
  EXPECT_EQ(std::string::npos,
            output.find("public static final class ^attr-private"));
}

TEST(JavaClassGeneratorTest, OnlyWritePublicResources) {
  StdErrDiagnostics diag;
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x01)
          .AddSimple("android:id/one", ResourceId(0x01020000))
          .AddSimple("android:id/two", ResourceId(0x01020001))
          .AddSimple("android:id/three", ResourceId(0x01020002))
          .SetSymbolState("android:id/one", ResourceId(0x01020000),
                          SymbolState::kPublic)
          .SetSymbolState("android:id/two", ResourceId(0x01020001),
                          SymbolState::kPrivate)
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();

  JavaClassGeneratorOptions options;
  options.types = JavaClassGeneratorOptions::SymbolTypes::kPublic;
  {
    JavaClassGenerator generator(context.get(), table.get(), options);
    std::stringstream out;
    ASSERT_TRUE(generator.Generate("android", &out));
    std::string output = out.str();
    EXPECT_NE(std::string::npos,
              output.find("public static final int one=0x01020000;"));
    EXPECT_EQ(std::string::npos, output.find("two"));
    EXPECT_EQ(std::string::npos, output.find("three"));
  }

  options.types = JavaClassGeneratorOptions::SymbolTypes::kPublicPrivate;
  {
    JavaClassGenerator generator(context.get(), table.get(), options);
    std::stringstream out;
    ASSERT_TRUE(generator.Generate("android", &out));
    std::string output = out.str();
    EXPECT_NE(std::string::npos,
              output.find("public static final int one=0x01020000;"));
    EXPECT_NE(std::string::npos,
              output.find("public static final int two=0x01020001;"));
    EXPECT_EQ(std::string::npos, output.find("three"));
  }

  options.types = JavaClassGeneratorOptions::SymbolTypes::kAll;
  {
    JavaClassGenerator generator(context.get(), table.get(), options);
    std::stringstream out;
    ASSERT_TRUE(generator.Generate("android", &out));
    std::string output = out.str();
    EXPECT_NE(std::string::npos,
              output.find("public static final int one=0x01020000;"));
    EXPECT_NE(std::string::npos,
              output.find("public static final int two=0x01020001;"));
    EXPECT_NE(std::string::npos,
              output.find("public static final int three=0x01020002;"));
  }
}

/*
 * TODO(adamlesinski): Re-enable this once we get merging working again.
 * TEST(JavaClassGeneratorTest, EmitPackageMangledSymbols) {
    ASSERT_TRUE(addResource(ResourceName{ {}, ResourceType::kId, u"foo" },
                            ResourceId{ 0x01, 0x02, 0x0000 }));
    ResourceTable table;
    table.setPackage(u"com.lib");
    ASSERT_TRUE(table.addResource(ResourceName{ {}, ResourceType::kId, u"test"
}, {},
                                  Source{ "lib.xml", 33 },
util::make_unique<Id>()));
    ASSERT_TRUE(mTable->merge(std::move(table)));

    Linker linker(mTable,
                  std::make_shared<MockResolver>(mTable, std::map<ResourceName,
ResourceId>()),
                  {});
    ASSERT_TRUE(linker.linkAndValidate());

    JavaClassGenerator generator(mTable, {});

    std::stringstream out;
    EXPECT_TRUE(generator.generate(mTable->getPackage(), out));
    std::string output = out.str();
    EXPECT_NE(std::string::npos, output.find("int foo ="));
    EXPECT_EQ(std::string::npos, output.find("int test ="));

    out.str("");
    EXPECT_TRUE(generator.generate(u"com.lib", out));
    output = out.str();
    EXPECT_NE(std::string::npos, output.find("int test ="));
    EXPECT_EQ(std::string::npos, output.find("int foo ="));
}*/

TEST(JavaClassGeneratorTest, EmitOtherPackagesAttributesInStyleable) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x01)
          .SetPackageId("com.lib", 0x02)
          .AddValue("android:attr/bar", ResourceId(0x01010000),
                    test::AttributeBuilder(false).Build())
          .AddValue("com.lib:attr/bar", ResourceId(0x02010000),
                    test::AttributeBuilder(false).Build())
          .AddValue("android:styleable/foo", ResourceId(0x01030000),
                    test::StyleableBuilder()
                        .AddItem("android:attr/bar", ResourceId(0x01010000))
                        .AddItem("com.lib:attr/bar", ResourceId(0x02010000))
                        .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::stringstream out;
  EXPECT_TRUE(generator.Generate("android", &out));

  std::string output = out.str();
  EXPECT_NE(std::string::npos, output.find("int foo_bar="));
  EXPECT_NE(std::string::npos, output.find("int foo_com_lib_bar="));
}

TEST(JavaClassGeneratorTest, CommentsForSimpleResourcesArePresent) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x01)
          .AddSimple("android:id/foo", ResourceId(0x01010000))
          .Build();
  test::GetValue<Id>(table.get(), "android:id/foo")
      ->SetComment(std::string("This is a comment\n@deprecated"));

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});
  std::stringstream out;
  ASSERT_TRUE(generator.Generate("android", &out));
  std::string actual = out.str();

  const char* expectedText =
      R"EOF(/**
     * This is a comment
     * @deprecated
     */
    @Deprecated
    public static final int foo=0x01010000;)EOF";

  EXPECT_NE(std::string::npos, actual.find(expectedText));
}

TEST(JavaClassGeneratorTest, CommentsForEnumAndFlagAttributesArePresent) {}

TEST(JavaClassGeneratorTest, CommentsForStyleablesAndNestedAttributesArePresent) {
  Attribute attr(false);
  attr.SetComment(StringPiece("This is an attribute"));

  Styleable styleable;
  styleable.entries.push_back(
      Reference(test::ParseNameOrDie("android:attr/one")));
  styleable.SetComment(StringPiece("This is a styleable"));

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x01)
          .AddValue("android:attr/one", util::make_unique<Attribute>(attr))
          .AddValue("android:styleable/Container",
                    std::unique_ptr<Styleable>(styleable.Clone(nullptr)))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGeneratorOptions options;
  options.use_final = false;
  JavaClassGenerator generator(context.get(), table.get(), options);
  std::stringstream out;
  ASSERT_TRUE(generator.Generate("android", &out));
  std::string actual = out.str();

  EXPECT_NE(std::string::npos, actual.find("attr name android:one"));
  EXPECT_NE(std::string::npos, actual.find("attr description"));
  EXPECT_NE(std::string::npos, actual.find(attr.GetComment().data()));
  EXPECT_NE(std::string::npos, actual.find(styleable.GetComment().data()));
}

TEST(JavaClassGeneratorTest, CommentsForRemovedAttributesAreNotPresentInClass) {
  Attribute attr(false);
  attr.SetComment(StringPiece("removed"));

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x01)
          .AddValue("android:attr/one", util::make_unique<Attribute>(attr))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGeneratorOptions options;
  options.use_final = false;
  JavaClassGenerator generator(context.get(), table.get(), options);
  std::stringstream out;
  ASSERT_TRUE(generator.Generate("android", &out));
  std::string actual = out.str();

  EXPECT_EQ(std::string::npos, actual.find("@attr name android:one"));
  EXPECT_EQ(std::string::npos, actual.find("@attr description"));

  // We should find @removed only in the attribute javadoc and not anywhere else
  // (i.e. the class
  // javadoc).
  const size_t pos = actual.find("removed");
  EXPECT_NE(std::string::npos, pos);
  EXPECT_EQ(std::string::npos, actual.find("removed", pos + 1));
}

TEST(JavaClassGeneratorTest, GenerateOnResourcesLoadedCallbackForSharedLibrary) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x00)
          .AddValue("android:attr/foo", ResourceId(0x00010000), util::make_unique<Attribute>(false))
          .AddValue("android:id/foo", ResourceId(0x00020000), util::make_unique<Id>())
          .AddValue(
              "android:style/foo", ResourceId(0x00030000),
              test::StyleBuilder()
                  .AddItem("android:attr/foo", ResourceId(0x00010000), util::make_unique<Id>())
                  .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder().SetPackageId(0x00).SetCompilationPackage("android").Build();

  JavaClassGeneratorOptions options;
  options.use_final = false;
  options.rewrite_callback_options = OnResourcesLoadedCallbackOptions{
      {"com.foo", "com.boo"},
  };
  JavaClassGenerator generator(context.get(), table.get(), options);

  std::stringstream out;
  ASSERT_TRUE(generator.Generate("android", &out));

  std::string actual = out.str();

  EXPECT_NE(std::string::npos, actual.find("void onResourcesLoaded"));
  EXPECT_NE(std::string::npos, actual.find("com.foo.R.onResourcesLoaded"));
  EXPECT_NE(std::string::npos, actual.find("com.boo.R.onResourcesLoaded"));
}

}  // namespace aapt
