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

#include <string>

#include "io/StringStream.h"
#include "test/Test.h"
#include "util/Util.h"

using ::aapt::io::StringOutputStream;
using ::android::StringPiece;
using ::testing::HasSubstr;
using ::testing::Lt;
using ::testing::Ne;
using ::testing::Not;

namespace aapt {

TEST(JavaClassGeneratorTest, FailWhenEntryIsJavaKeyword) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("android:id/class", ResourceId(0x01020000))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(
              util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::string result;
  StringOutputStream out(&result);
  EXPECT_FALSE(generator.Generate("android", &out));
}

TEST(JavaClassGeneratorTest, TransformInvalidJavaIdentifierCharacter) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("android:id/hey-man", ResourceId(0x01020000))
          .AddValue("android:attr/cool.attr", ResourceId(0x01010000),
                    test::AttributeBuilder().Build())
          .AddValue("android:styleable/hey.dude", ResourceId(0x01030000),
                    test::StyleableBuilder()
                        .AddItem("android:attr/cool.attr", ResourceId(0x01010000))
                        .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::string output;
  StringOutputStream out(&output);
  EXPECT_TRUE(generator.Generate("android", &out));
  out.Flush();

  EXPECT_THAT(output, HasSubstr("public static final int hey_man=0x01020000;"));
  EXPECT_THAT(output, HasSubstr("public static final int[] hey_dude={"));
  EXPECT_THAT(output, HasSubstr("public static final int hey_dude_cool_attr=0;"));
}

TEST(JavaClassGeneratorTest, CorrectPackageNameIsUsed) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("android:id/one", ResourceId(0x01020000))
          .AddSimple("android:id/com.foo$two", ResourceId(0x01020001))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::string output;
  StringOutputStream out(&output);
  ASSERT_TRUE(generator.Generate("android", "com.android.internal", &out));
  out.Flush();

  EXPECT_THAT(output, HasSubstr("package com.android.internal;"));
  EXPECT_THAT(output, HasSubstr("public static final int one=0x01020000;"));
  EXPECT_THAT(output, Not(HasSubstr("two")));
  EXPECT_THAT(output, Not(HasSubstr("com_foo$two")));
}

TEST(JavaClassGeneratorTest, StyleableAttributesWithDifferentPackageName) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("app:attr/foo", ResourceId(0x7f010000),
                    test::AttributeBuilder().Build())
          .AddValue("app:attr/bar", ResourceId(0x7f010001),
                    test::AttributeBuilder().Build())
          .AddValue("android:attr/baz", ResourceId(0x01010000),
                    test::AttributeBuilder().Build())
          .AddValue("app:styleable/MyStyleable", ResourceId(0x7f030000),
                    test::StyleableBuilder()
                        .AddItem("app:attr/foo", ResourceId(0x7f010000))
                        .AddItem("attr/bar", ResourceId(0x7f010001))
                        .AddItem("android:attr/baz", ResourceId(0x01010000))
                        .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"custom"})
          .SetCompilationPackage("custom")
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::string output;
  StringOutputStream out(&output);
  EXPECT_TRUE(generator.Generate("app", &out));
  out.Flush();

  EXPECT_THAT(output, Not(HasSubstr("public static final int baz=0x01010000;")));
  EXPECT_THAT(output, HasSubstr("public static final int foo=0x7f010000;"));
  EXPECT_THAT(output, HasSubstr("public static final int bar=0x7f010001;"));

  EXPECT_THAT(output, HasSubstr("public static final int MyStyleable_android_baz=0;"));
  EXPECT_THAT(output, HasSubstr("public static final int MyStyleable_foo=1;"));
  EXPECT_THAT(output, HasSubstr("public static final int MyStyleable_bar=2;"));

  EXPECT_THAT(output, HasSubstr("@link #MyStyleable_android_baz android:baz"));
  EXPECT_THAT(output, HasSubstr("@link #MyStyleable_foo app:foo"));
  EXPECT_THAT(output, HasSubstr("@link #MyStyleable_bar app:bar"));

  EXPECT_THAT(output, HasSubstr("@link android.R.attr#baz"));
  EXPECT_THAT(output, HasSubstr("@link app.R.attr#foo"));
  EXPECT_THAT(output, HasSubstr("@link app.R.attr#bar"));
}

TEST(JavaClassGeneratorTest, AttrPrivateIsWrittenAsAttr) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("android:attr/two", ResourceId(0x01010001))
          .AddSimple("android:^attr-private/one", ResourceId(0x01010000))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::string output;
  StringOutputStream out(&output);
  ASSERT_TRUE(generator.Generate("android", &out));
  out.Flush();

  EXPECT_THAT(output, HasSubstr("public static final class attr"));
  EXPECT_THAT(output, Not(HasSubstr("public static final class ^attr-private")));
}

TEST(JavaClassGeneratorTest, OnlyWritePublicResources) {
  StdErrDiagnostics diag;
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("android:id/one", ResourceId(0x01020000))
          .AddSimple("android:id/two", ResourceId(0x01020001))
          .AddSimple("android:id/three", ResourceId(0x01020002))
          .SetSymbolState("android:id/one", ResourceId(0x01020000), Visibility::Level::kPublic)
          .SetSymbolState("android:id/two", ResourceId(0x01020001), Visibility::Level::kPrivate)
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();

  JavaClassGeneratorOptions options;
  options.types = JavaClassGeneratorOptions::SymbolTypes::kPublic;
  {
    JavaClassGenerator generator(context.get(), table.get(), options);
    std::string output;
    StringOutputStream out(&output);
    ASSERT_TRUE(generator.Generate("android", &out));
    out.Flush();

    EXPECT_THAT(output, HasSubstr("public static final int one=0x01020000;"));
    EXPECT_THAT(output, Not(HasSubstr("two")));
    EXPECT_THAT(output, Not(HasSubstr("three")));
  }

  options.types = JavaClassGeneratorOptions::SymbolTypes::kPublicPrivate;
  {
    JavaClassGenerator generator(context.get(), table.get(), options);
    std::string output;
    StringOutputStream out(&output);
    ASSERT_TRUE(generator.Generate("android", &out));
    out.Flush();

    EXPECT_THAT(output, HasSubstr("public static final int one=0x01020000;"));
    EXPECT_THAT(output, HasSubstr("public static final int two=0x01020001;"));
    EXPECT_THAT(output, Not(HasSubstr("three")));
  }

  options.types = JavaClassGeneratorOptions::SymbolTypes::kAll;
  {
    JavaClassGenerator generator(context.get(), table.get(), options);
    std::string output;
    StringOutputStream out(&output);
    ASSERT_TRUE(generator.Generate("android", &out));
    out.Flush();

    EXPECT_THAT(output, HasSubstr("public static final int one=0x01020000;"));
    EXPECT_THAT(output, HasSubstr("public static final int two=0x01020001;"));
    EXPECT_THAT(output, HasSubstr("public static final int three=0x01020002;"));
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
          .AddValue("android:attr/bar", ResourceId(0x01010000), test::AttributeBuilder().Build())
          .AddValue("com.lib:attr/bar", ResourceId(0x02010000), test::AttributeBuilder().Build())
          .AddValue("android:styleable/foo", ResourceId(0x01030000),
                    test::StyleableBuilder()
                        .AddItem("android:attr/bar", ResourceId(0x01010000))
                        .AddItem("com.lib:attr/bar", ResourceId(0x02010000))
                        .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::string output;
  StringOutputStream out(&output);
  EXPECT_TRUE(generator.Generate("android", &out));
  out.Flush();

  EXPECT_THAT(output, HasSubstr("int foo_bar="));
  EXPECT_THAT(output, HasSubstr("int foo_com_lib_bar="));
}

TEST(JavaClassGeneratorTest, CommentsForSimpleResourcesArePresent) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("android:id/foo", ResourceId(0x01010000))
          .Build();
  test::GetValue<Id>(table.get(), "android:id/foo")
      ->SetComment(std::string("This is a comment\n@deprecated"));

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::string output;
  StringOutputStream out(&output);
  ASSERT_TRUE(generator.Generate("android", &out));
  out.Flush();

  const char* expected_text =
      R"EOF(/**
     * This is a comment
     * @deprecated
     */
    @Deprecated
    public static final int foo=0x01010000;)EOF";
  EXPECT_THAT(output, HasSubstr(expected_text));
}

TEST(JavaClassGeneratorTest, CommentsForEnumAndFlagAttributesArePresent) {}

TEST(JavaClassGeneratorTest, CommentsForStyleablesAndNestedAttributesArePresent) {
  Attribute attr;
  attr.SetComment(StringPiece("This is an attribute"));

  Styleable styleable;
  styleable.entries.push_back(Reference(test::ParseNameOrDie("android:attr/one")));
  styleable.SetComment(StringPiece("This is a styleable"));

  CloningValueTransformer cloner(nullptr);
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("android:attr/one", util::make_unique<Attribute>(attr))
          .AddValue("android:styleable/Container",
                    std::unique_ptr<Styleable>(styleable.Transform(cloner)))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGeneratorOptions options;
  options.use_final = false;
  JavaClassGenerator generator(context.get(), table.get(), options);

  std::string output;
  StringOutputStream out(&output);
  ASSERT_TRUE(generator.Generate("android", &out));
  out.Flush();

  EXPECT_THAT(output, HasSubstr("#Container_one android:one"));
  EXPECT_THAT(output, HasSubstr("@see #Container_one"));
  EXPECT_THAT(output, HasSubstr("attr name android:one"));
  EXPECT_THAT(output, HasSubstr("attr description"));
  EXPECT_THAT(output, HasSubstr(attr.GetComment()));
  EXPECT_THAT(output, HasSubstr(styleable.GetComment()));
}

TEST(JavaClassGeneratorTest, CommentsForStyleableHiddenAttributesAreNotPresent) {
  Attribute attr;
  attr.SetComment(StringPiece("This is an attribute @hide"));

  Styleable styleable;
  styleable.entries.push_back(Reference(test::ParseNameOrDie("android:attr/one")));
  styleable.SetComment(StringPiece("This is a styleable"));

  CloningValueTransformer cloner(nullptr);
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("android:attr/one", util::make_unique<Attribute>(attr))
          .AddValue("android:styleable/Container",
                    std::unique_ptr<Styleable>(styleable.Transform(cloner)))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGeneratorOptions options;
  options.use_final = false;
  JavaClassGenerator generator(context.get(), table.get(), options);

  std::string output;
  StringOutputStream out(&output);
  ASSERT_TRUE(generator.Generate("android", &out));
  out.Flush();

  EXPECT_THAT(output, Not(HasSubstr("#Container_one android:one")));
  EXPECT_THAT(output, Not(HasSubstr("@see #Container_one")));
  EXPECT_THAT(output, HasSubstr("attr name android:one"));
  EXPECT_THAT(output, HasSubstr("attr description"));
  EXPECT_THAT(output, HasSubstr(attr.GetComment()));
  EXPECT_THAT(output, HasSubstr(styleable.GetComment()));
}

TEST(JavaClassGeneratorTest, StyleableAndIndicesAreColocated) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("android:attr/layout_gravity", util::make_unique<Attribute>())
          .AddValue("android:attr/background", util::make_unique<Attribute>())
          .AddValue("android:styleable/ActionBar",
                    test::StyleableBuilder()
                        .AddItem("android:attr/background", ResourceId(0x01010000))
                        .Build())
          .AddValue("android:styleable/ActionBar.LayoutParams",
                    test::StyleableBuilder()
                        .AddItem("android:attr/layout_gravity", ResourceId(0x01010001))
                        .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();

  JavaClassGeneratorOptions options;
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::string output;
  StringOutputStream out(&output);
  ASSERT_TRUE(generator.Generate("android", &out));
  out.Flush();

  std::string::size_type actionbar_pos = output.find("int[] ActionBar");
  ASSERT_THAT(actionbar_pos, Ne(std::string::npos));

  std::string::size_type actionbar_background_pos = output.find("int ActionBar_background");
  ASSERT_THAT(actionbar_background_pos, Ne(std::string::npos));

  std::string::size_type actionbar_layout_params_pos = output.find("int[] ActionBar_LayoutParams");
  ASSERT_THAT(actionbar_layout_params_pos, Ne(std::string::npos));

  std::string::size_type actionbar_layout_params_layout_gravity_pos =
      output.find("int ActionBar_LayoutParams_layout_gravity");
  ASSERT_THAT(actionbar_layout_params_layout_gravity_pos, Ne(std::string::npos));

  EXPECT_THAT(actionbar_pos, Lt(actionbar_background_pos));
  EXPECT_THAT(actionbar_pos, Lt(actionbar_layout_params_pos));
  EXPECT_THAT(actionbar_background_pos, Lt(actionbar_layout_params_pos));
  EXPECT_THAT(actionbar_layout_params_pos, Lt(actionbar_layout_params_layout_gravity_pos));
}

TEST(JavaClassGeneratorTest, CommentsForRemovedAttributesAreNotPresentInClass) {
  Attribute attr;
  attr.SetComment(StringPiece("removed"));

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("android:attr/one", util::make_unique<Attribute>(attr))
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGeneratorOptions options;
  options.use_final = false;
  JavaClassGenerator generator(context.get(), table.get(), options);

  std::string output;
  StringOutputStream out(&output);
  ASSERT_TRUE(generator.Generate("android", &out));
  out.Flush();

  EXPECT_THAT(output, Not(HasSubstr("@attr name android:one")));
  EXPECT_THAT(output, Not(HasSubstr("@attr description")));

  // We should find @removed only in the attribute javadoc and not anywhere else
  // (i.e. the class javadoc).
  const std::string kRemoved("removed");
  ASSERT_THAT(output, HasSubstr(kRemoved));
  std::string after_first_match = output.substr(output.find(kRemoved) + kRemoved.size());
  EXPECT_THAT(after_first_match, Not(HasSubstr(kRemoved)));
}

TEST(JavaClassGeneratorTest, GenerateOnResourcesLoadedCallbackForSharedLibrary) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("android:attr/foo", ResourceId(0x00010000), util::make_unique<Attribute>())
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
  options.rewrite_callback_options = OnResourcesLoadedCallbackOptions{{"com.foo", "com.boo"}};
  JavaClassGenerator generator(context.get(), table.get(), options);

  std::string output;
  StringOutputStream out(&output);
  ASSERT_TRUE(generator.Generate("android", &out));
  out.Flush();

  EXPECT_THAT(output, HasSubstr(
          R"(  public static void onResourcesLoaded(int p) {
    com.foo.R.onResourcesLoaded(p);
    com.boo.R.onResourcesLoaded(p);
    final int packageIdBits = p << 24;
    attr.foo = (attr.foo & 0x00ffffff) | packageIdBits;
    id.foo = (id.foo & 0x00ffffff) | packageIdBits;
    style.foo = (style.foo & 0x00ffffff) | packageIdBits;
  })"));
}

TEST(JavaClassGeneratorTest, OnlyGenerateRText) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("android:attr/foo", ResourceId(0x01010000), util::make_unique<Attribute>())
          .AddValue("android:styleable/hey.dude", ResourceId(0x01020000),
                    test::StyleableBuilder()
                        .AddItem("android:attr/foo", ResourceId(0x01010000))
                        .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder().SetPackageId(0x01).SetCompilationPackage("android").Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  ASSERT_TRUE(generator.Generate("android", nullptr));
}

TEST(JavaClassGeneratorTest, SortsDynamicAttributesAfterFrameworkAttributes) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("android:attr/framework_attr", ResourceId(0x01010000),
                    test::AttributeBuilder().Build())
          .AddValue("lib:attr/dynamic_attr", ResourceId(0x00010000),
                    test::AttributeBuilder().Build())
          .AddValue("lib:styleable/MyStyleable", ResourceId(0x00030000),
                    test::StyleableBuilder()
                        .AddItem("android:attr/framework_attr", ResourceId(0x01010000))
                        .AddItem("lib:attr/dynamic_attr", ResourceId(0x00010000))
                        .Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"custom"})
          .SetCompilationPackage("custom")
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::string output;
  StringOutputStream out(&output);
  EXPECT_TRUE(generator.Generate("lib", &out));
  out.Flush();

  EXPECT_THAT(output, HasSubstr("public static final int[] MyStyleable={"));
  EXPECT_THAT(output, HasSubstr("0x01010000, lib.R.attr.dynamic_attr"));
  EXPECT_THAT(output, HasSubstr("public static final int MyStyleable_android_framework_attr=0;"));
  EXPECT_THAT(output, HasSubstr("public static final int MyStyleable_dynamic_attr=1;"));
}

TEST(JavaClassGeneratorTest, SkipMacros) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("android:macro/bar", ResourceId(0x01010000), test::AttributeBuilder().Build())
          .Build();

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
          .SetNameManglerPolicy(NameManglerPolicy{"android"})
          .Build();
  JavaClassGenerator generator(context.get(), table.get(), {});

  std::string output;
  StringOutputStream out(&output);
  EXPECT_TRUE(generator.Generate("android", &out));
  out.Flush();

  EXPECT_THAT(output, Not(HasSubstr("bar")));
}

}  // namespace aapt
