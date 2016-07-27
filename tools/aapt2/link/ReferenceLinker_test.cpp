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

using android::ResTable_map;

namespace aapt {

TEST(ReferenceLinkerTest, LinkSimpleReferences) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId("com.app.test", 0x7f)
            .addReference("com.app.test:string/foo", ResourceId(0x7f020000),
                          "com.app.test:string/bar")

            // Test use of local reference (w/o package name).
            .addReference("com.app.test:string/bar", ResourceId(0x7f020001), "string/baz")

            .addReference("com.app.test:string/baz", ResourceId(0x7f020002),
                          "android:string/ok")
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .setCompilationPackage("com.app.test")
            .setPackageId(0x7f)
            .setNameManglerPolicy(NameManglerPolicy{ "com.app.test" })
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .addSymbolSource(test::StaticSymbolSourceBuilder()
                                     .addPublicSymbol("android:string/ok", ResourceId(0x01040034))
                                     .build())
            .build();

    ReferenceLinker linker;
    ASSERT_TRUE(linker.consume(context.get(), table.get()));

    Reference* ref = test::getValue<Reference>(table.get(), "com.app.test:string/foo");
    ASSERT_NE(ref, nullptr);
    AAPT_ASSERT_TRUE(ref->id);
    EXPECT_EQ(ref->id.value(), ResourceId(0x7f020001));

    ref = test::getValue<Reference>(table.get(), "com.app.test:string/bar");
    ASSERT_NE(ref, nullptr);
    AAPT_ASSERT_TRUE(ref->id);
    EXPECT_EQ(ref->id.value(), ResourceId(0x7f020002));

    ref = test::getValue<Reference>(table.get(), "com.app.test:string/baz");
    ASSERT_NE(ref, nullptr);
    AAPT_ASSERT_TRUE(ref->id);
    EXPECT_EQ(ref->id.value(), ResourceId(0x01040034));
}

TEST(ReferenceLinkerTest, LinkStyleAttributes) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId("com.app.test", 0x7f)
            .addValue("com.app.test:style/Theme", test::StyleBuilder()
                    .setParent("android:style/Theme.Material")
                    .addItem("android:attr/foo", ResourceUtils::tryParseColor("#ff00ff"))
                    .addItem("android:attr/bar", {} /* placeholder */)
                    .build())
            .build();

    {
        // We need to fill in the value for the attribute android:attr/bar after we build the
        // table, because we need access to the string pool.
        Style* style = test::getValue<Style>(table.get(), "com.app.test:style/Theme");
        ASSERT_NE(style, nullptr);
        style->entries.back().value = util::make_unique<RawString>(
                table->stringPool.makeRef("one|two"));
    }

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .setCompilationPackage("com.app.test")
            .setPackageId(0x7f)
            .setNameManglerPolicy(NameManglerPolicy{ "com.app.test" })
            .addSymbolSource(test::StaticSymbolSourceBuilder()
                                     .addPublicSymbol("android:style/Theme.Material",
                                                      ResourceId(0x01060000))
                                     .addPublicSymbol("android:attr/foo", ResourceId(0x01010001),
                                                      test::AttributeBuilder()
                                                              .setTypeMask(ResTable_map::TYPE_COLOR)
                                                              .build())
                                     .addPublicSymbol("android:attr/bar", ResourceId(0x01010002),
                                                      test::AttributeBuilder()
                                                              .setTypeMask(ResTable_map::TYPE_FLAGS)
                                                              .addItem("one", 0x01)
                                                              .addItem("two", 0x02)
                                                              .build())
                                     .build())
            .build();

    ReferenceLinker linker;
    ASSERT_TRUE(linker.consume(context.get(), table.get()));

    Style* style = test::getValue<Style>(table.get(), "com.app.test:style/Theme");
    ASSERT_NE(style, nullptr);
    AAPT_ASSERT_TRUE(style->parent);
    AAPT_ASSERT_TRUE(style->parent.value().id);
    EXPECT_EQ(style->parent.value().id.value(), ResourceId(0x01060000));

    ASSERT_EQ(2u, style->entries.size());

    AAPT_ASSERT_TRUE(style->entries[0].key.id);
    EXPECT_EQ(style->entries[0].key.id.value(), ResourceId(0x01010001));
    ASSERT_NE(valueCast<BinaryPrimitive>(style->entries[0].value.get()), nullptr);

    AAPT_ASSERT_TRUE(style->entries[1].key.id);
    EXPECT_EQ(style->entries[1].key.id.value(), ResourceId(0x01010002));
    ASSERT_NE(valueCast<BinaryPrimitive>(style->entries[1].value.get()), nullptr);
}

TEST(ReferenceLinkerTest, LinkMangledReferencesAndAttributes) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .setCompilationPackage("com.app.test")
            .setPackageId(0x7f)
            .setNameManglerPolicy(NameManglerPolicy{ "com.app.test", { "com.android.support" } })
            .addSymbolSource(test::StaticSymbolSourceBuilder()
                                     .addPublicSymbol("com.app.test:attr/com.android.support$foo",
                                                      ResourceId(0x7f010000),
                                                      test::AttributeBuilder()
                                                              .setTypeMask(ResTable_map::TYPE_COLOR)
                                                              .build())
                                     .build())
            .build();

    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId("com.app.test", 0x7f)
            .addValue("com.app.test:style/Theme", ResourceId(0x7f020000),
                      test::StyleBuilder().addItem("com.android.support:attr/foo",
                                                   ResourceUtils::tryParseColor("#ff0000"))
                                          .build())
            .build();

    ReferenceLinker linker;
    ASSERT_TRUE(linker.consume(context.get(), table.get()));

    Style* style = test::getValue<Style>(table.get(), "com.app.test:style/Theme");
    ASSERT_NE(style, nullptr);
    ASSERT_EQ(1u, style->entries.size());
    AAPT_ASSERT_TRUE(style->entries.front().key.id);
    EXPECT_EQ(style->entries.front().key.id.value(), ResourceId(0x7f010000));
}

TEST(ReferenceLinkerTest, FailToLinkPrivateSymbols) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId("com.app.test", 0x7f)
            .addReference("com.app.test:string/foo", ResourceId(0x7f020000),
                          "android:string/hidden")
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .setCompilationPackage("com.app.test")
            .setPackageId(0x7f)
            .setNameManglerPolicy(NameManglerPolicy{ "com.app.test" })
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .addSymbolSource(test::StaticSymbolSourceBuilder()
                                     .addSymbol("android:string/hidden", ResourceId(0x01040034))
                                     .build())
            .build();

    ReferenceLinker linker;
    ASSERT_FALSE(linker.consume(context.get(), table.get()));
}

TEST(ReferenceLinkerTest, FailToLinkPrivateMangledSymbols) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId("com.app.test", 0x7f)
            .addReference("com.app.test:string/foo", ResourceId(0x7f020000),
                          "com.app.lib:string/hidden")
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .setCompilationPackage("com.app.test")
            .setPackageId(0x7f)
            .setNameManglerPolicy(NameManglerPolicy{ "com.app.test", { "com.app.lib" } })
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .addSymbolSource(test::StaticSymbolSourceBuilder()
                                     .addSymbol("com.app.test:string/com.app.lib$hidden",
                                                ResourceId(0x7f040034))
                                     .build())

            .build();

    ReferenceLinker linker;
    ASSERT_FALSE(linker.consume(context.get(), table.get()));
}

TEST(ReferenceLinkerTest, FailToLinkPrivateStyleAttributes) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId("com.app.test", 0x7f)
            .addValue("com.app.test:style/Theme", test::StyleBuilder()
                    .addItem("android:attr/hidden", ResourceUtils::tryParseColor("#ff00ff"))
                    .build())
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .setCompilationPackage("com.app.test")
            .setPackageId(0x7f)
            .setNameManglerPolicy(NameManglerPolicy{ "com.app.test" })
            .addSymbolSource(util::make_unique<ResourceTableSymbolSource>(table.get()))
            .addSymbolSource(test::StaticSymbolSourceBuilder()
                                     .addSymbol("android:attr/hidden", ResourceId(0x01010001),
                                                test::AttributeBuilder()
                                                        .setTypeMask(
                                                                android::ResTable_map::TYPE_COLOR)
                                                        .build())
                                     .build())
            .build();

    ReferenceLinker linker;
    ASSERT_FALSE(linker.consume(context.get(), table.get()));
}

} // namespace aapt
