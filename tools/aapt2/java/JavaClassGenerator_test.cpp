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
#include "test/Test.h"
#include "util/Util.h"

#include <sstream>
#include <string>

namespace aapt {

TEST(JavaClassGeneratorTest, FailWhenEntryIsJavaKeyword) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"android", 0x01)
            .addSimple(u"@android:id/class", ResourceId(0x01020000))
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .setNameManglerPolicy(NameManglerPolicy{ u"android" })
            .build();
    JavaClassGenerator generator(context.get(), table.get(), {});

    std::stringstream out;
    EXPECT_FALSE(generator.generate(u"android", &out));
}

TEST(JavaClassGeneratorTest, TransformInvalidJavaIdentifierCharacter) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"android", 0x01)
            .addSimple(u"@android:id/hey-man", ResourceId(0x01020000))
            .addValue(u"@android:attr/cool.attr", ResourceId(0x01010000),
                      test::AttributeBuilder(false).build())
            .addValue(u"@android:styleable/hey.dude", ResourceId(0x01030000),
                      test::StyleableBuilder()
                              .addItem(u"@android:attr/cool.attr", ResourceId(0x01010000))
                              .build())
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .setNameManglerPolicy(NameManglerPolicy{ u"android" })
            .build();
    JavaClassGenerator generator(context.get(), table.get(), {});

    std::stringstream out;
    EXPECT_TRUE(generator.generate(u"android", &out));

    std::string output = out.str();

    EXPECT_NE(std::string::npos,
              output.find("public static final int hey_man=0x01020000;"));

    EXPECT_NE(std::string::npos,
              output.find("public static final int[] hey_dude={"));

    EXPECT_NE(std::string::npos,
              output.find("public static final int hey_dude_cool_attr=0;"));
}

TEST(JavaClassGeneratorTest, CorrectPackageNameIsUsed) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"android", 0x01)
            .addSimple(u"@android:id/one", ResourceId(0x01020000))
            .addSimple(u"@android:id/com.foo$two", ResourceId(0x01020001))
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .setNameManglerPolicy(NameManglerPolicy{ u"android" })
            .build();
    JavaClassGenerator generator(context.get(), table.get(), {});
    std::stringstream out;
    ASSERT_TRUE(generator.generate(u"android", u"com.android.internal", &out));

    std::string output = out.str();
    EXPECT_NE(std::string::npos, output.find("package com.android.internal;"));
    EXPECT_NE(std::string::npos, output.find("public static final int one=0x01020000;"));
    EXPECT_EQ(std::string::npos, output.find("two"));
    EXPECT_EQ(std::string::npos, output.find("com_foo$two"));
}

TEST(JavaClassGeneratorTest, AttrPrivateIsWrittenAsAttr) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"android", 0x01)
            .addSimple(u"@android:attr/two", ResourceId(0x01010001))
            .addSimple(u"@android:^attr-private/one", ResourceId(0x01010000))
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .setNameManglerPolicy(NameManglerPolicy{ u"android" })
            .build();
    JavaClassGenerator generator(context.get(), table.get(), {});
    std::stringstream out;
    ASSERT_TRUE(generator.generate(u"android", &out));

    std::string output = out.str();
    EXPECT_NE(std::string::npos, output.find("public static final class attr"));
    EXPECT_EQ(std::string::npos, output.find("public static final class ^attr-private"));
}

TEST(JavaClassGeneratorTest, OnlyWritePublicResources) {
    StdErrDiagnostics diag;
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"android", 0x01)
            .addSimple(u"@android:id/one", ResourceId(0x01020000))
            .addSimple(u"@android:id/two", ResourceId(0x01020001))
            .addSimple(u"@android:id/three", ResourceId(0x01020002))
            .setSymbolState(u"@android:id/one", ResourceId(0x01020000), SymbolState::kPublic)
            .setSymbolState(u"@android:id/two", ResourceId(0x01020001), SymbolState::kPrivate)
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .setNameManglerPolicy(NameManglerPolicy{ u"android" })
            .build();

    JavaClassGeneratorOptions options;
    options.types = JavaClassGeneratorOptions::SymbolTypes::kPublic;
    {
        JavaClassGenerator generator(context.get(), table.get(), options);
        std::stringstream out;
        ASSERT_TRUE(generator.generate(u"android", &out));
        std::string output = out.str();
        EXPECT_NE(std::string::npos, output.find("public static final int one=0x01020000;"));
        EXPECT_EQ(std::string::npos, output.find("two"));
        EXPECT_EQ(std::string::npos, output.find("three"));
    }

    options.types = JavaClassGeneratorOptions::SymbolTypes::kPublicPrivate;
    {
        JavaClassGenerator generator(context.get(), table.get(), options);
        std::stringstream out;
        ASSERT_TRUE(generator.generate(u"android", &out));
        std::string output = out.str();
        EXPECT_NE(std::string::npos, output.find("public static final int one=0x01020000;"));
        EXPECT_NE(std::string::npos, output.find("public static final int two=0x01020001;"));
        EXPECT_EQ(std::string::npos, output.find("three"));
    }

    options.types = JavaClassGeneratorOptions::SymbolTypes::kAll;
    {
        JavaClassGenerator generator(context.get(), table.get(), options);
        std::stringstream out;
        ASSERT_TRUE(generator.generate(u"android", &out));
        std::string output = out.str();
        EXPECT_NE(std::string::npos, output.find("public static final int one=0x01020000;"));
        EXPECT_NE(std::string::npos, output.find("public static final int two=0x01020001;"));
        EXPECT_NE(std::string::npos, output.find("public static final int three=0x01020002;"));
    }
}

/*
 * TODO(adamlesinski): Re-enable this once we get merging working again.
 * TEST(JavaClassGeneratorTest, EmitPackageMangledSymbols) {
    ASSERT_TRUE(addResource(ResourceName{ {}, ResourceType::kId, u"foo" },
                            ResourceId{ 0x01, 0x02, 0x0000 }));
    ResourceTable table;
    table.setPackage(u"com.lib");
    ASSERT_TRUE(table.addResource(ResourceName{ {}, ResourceType::kId, u"test" }, {},
                                  Source{ "lib.xml", 33 }, util::make_unique<Id>()));
    ASSERT_TRUE(mTable->merge(std::move(table)));

    Linker linker(mTable,
                  std::make_shared<MockResolver>(mTable, std::map<ResourceName, ResourceId>()),
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
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
                .setPackageId(u"android", 0x01)
                .setPackageId(u"com.lib", 0x02)
                .addValue(u"@android:attr/bar", ResourceId(0x01010000),
                          test::AttributeBuilder(false).build())
                .addValue(u"@com.lib:attr/bar", ResourceId(0x02010000),
                           test::AttributeBuilder(false).build())
                .addValue(u"@android:styleable/foo", ResourceId(0x01030000),
                          test::StyleableBuilder()
                                  .addItem(u"@android:attr/bar", ResourceId(0x01010000))
                                  .addItem(u"@com.lib:attr/bar", ResourceId(0x02010000))
                                  .build())
                .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .setNameManglerPolicy(NameManglerPolicy{ u"android" })
            .build();
    JavaClassGenerator generator(context.get(), table.get(), {});

    std::stringstream out;
    EXPECT_TRUE(generator.generate(u"android", &out));

    std::string output = out.str();
    EXPECT_NE(std::string::npos, output.find("int foo_bar="));
    EXPECT_NE(std::string::npos, output.find("int foo_com_lib_bar="));
}

TEST(JavaClassGeneratorTest, CommentsForSimpleResourcesArePresent) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"android", 0x01)
            .addSimple(u"@android:id/foo", ResourceId(0x01010000))
            .build();
    test::getValue<Id>(table.get(), u"@android:id/foo")
            ->setComment(std::u16string(u"This is a comment\n@deprecated"));

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .setNameManglerPolicy(NameManglerPolicy{ u"android" })
            .build();
    JavaClassGenerator generator(context.get(), table.get(), {});
    std::stringstream out;
    ASSERT_TRUE(generator.generate(u"android", &out));
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

TEST(JavaClassGeneratorTest, CommentsForEnumAndFlagAttributesArePresent) {

}

TEST(JavaClassGeneratorTest, CommentsForStyleablesAndNestedAttributesArePresent) {
    Attribute attr(false);
    attr.setComment(StringPiece16(u"This is an attribute"));

    Styleable styleable;
    styleable.entries.push_back(Reference(test::parseNameOrDie(u"@android:attr/one")));
    styleable.setComment(StringPiece16(u"This is a styleable"));

    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"android", 0x01)
            .addValue(u"@android:attr/one", util::make_unique<Attribute>(attr))
            .addValue(u"@android:styleable/Container",
                      std::unique_ptr<Styleable>(styleable.clone(nullptr)))
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .setNameManglerPolicy(NameManglerPolicy{ u"android" })
            .build();
    JavaClassGeneratorOptions options;
    options.useFinal = false;
    JavaClassGenerator generator(context.get(), table.get(), options);
    std::stringstream out;
    ASSERT_TRUE(generator.generate(u"android", &out));
    std::string actual = out.str();

    EXPECT_NE(std::string::npos, actual.find("@attr name android:one"));
    EXPECT_NE(std::string::npos, actual.find("@attr description"));
    EXPECT_NE(std::string::npos, actual.find(util::utf16ToUtf8(attr.getComment())));
    EXPECT_NE(std::string::npos, actual.find(util::utf16ToUtf8(styleable.getComment())));
}

TEST(JavaClassGeneratorTest, CommentsForRemovedAttributesAreNotPresentInClass) {
    Attribute attr(false);
    attr.setComment(StringPiece16(u"@removed"));


    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"android", 0x01)
            .addValue(u"@android:attr/one", util::make_unique<Attribute>(attr))
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .setNameManglerPolicy(NameManglerPolicy{ u"android" })
            .build();
    JavaClassGeneratorOptions options;
    options.useFinal = false;
    JavaClassGenerator generator(context.get(), table.get(), options);
    std::stringstream out;
    ASSERT_TRUE(generator.generate(u"android", &out));
    std::string actual = out.str();

    std::cout << actual << std::endl;

    EXPECT_EQ(std::string::npos, actual.find("@attr name android:one"));
    EXPECT_EQ(std::string::npos, actual.find("@attr description"));

    // We should find @removed only in the attribute javadoc and not anywhere else (i.e. the class
    // javadoc).
    const size_t pos = actual.find("@removed");
    EXPECT_NE(std::string::npos, pos);
    EXPECT_EQ(std::string::npos, actual.find("@removed", pos + 1));
}

} // namespace aapt
