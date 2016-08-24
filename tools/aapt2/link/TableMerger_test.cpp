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

#include "filter/ConfigFilter.h"
#include "io/FileSystem.h"
#include "link/TableMerger.h"
#include "test/Builders.h"
#include "test/Context.h"

#include <gtest/gtest.h>

namespace aapt {

struct TableMergerTest : public ::testing::Test {
    std::unique_ptr<IAaptContext> mContext;

    void SetUp() override {
        mContext = test::ContextBuilder()
                // We are compiling this package.
                .setCompilationPackage(u"com.app.a")

                // Merge all packages that have this package ID.
                .setPackageId(0x7f)

                // Mangle all packages that do not have this package name.
                .setNameManglerPolicy(NameManglerPolicy{ u"com.app.a", { u"com.app.b" } })

                .build();
    }
};

TEST_F(TableMergerTest, SimpleMerge) {
    std::unique_ptr<ResourceTable> tableA = test::ResourceTableBuilder()
            .setPackageId(u"com.app.a", 0x7f)
            .addReference(u"@com.app.a:id/foo", u"@com.app.a:id/bar")
            .addReference(u"@com.app.a:id/bar", u"@com.app.b:id/foo")
            .addValue(u"@com.app.a:styleable/view", test::StyleableBuilder()
                    .addItem(u"@com.app.b:id/foo")
                    .build())
            .build();

    std::unique_ptr<ResourceTable> tableB = test::ResourceTableBuilder()
            .setPackageId(u"com.app.b", 0x7f)
            .addSimple(u"@com.app.b:id/foo")
            .build();

    ResourceTable finalTable;
    TableMerger merger(mContext.get(), &finalTable, TableMergerOptions{});
    io::FileCollection collection;

    ASSERT_TRUE(merger.merge({}, tableA.get()));
    ASSERT_TRUE(merger.mergeAndMangle({}, u"com.app.b", tableB.get(), &collection));

    EXPECT_TRUE(merger.getMergedPackages().count(u"com.app.b") != 0);

    // Entries from com.app.a should not be mangled.
    AAPT_EXPECT_TRUE(finalTable.findResource(test::parseNameOrDie(u"@com.app.a:id/foo")));
    AAPT_EXPECT_TRUE(finalTable.findResource(test::parseNameOrDie(u"@com.app.a:id/bar")));
    AAPT_EXPECT_TRUE(finalTable.findResource(test::parseNameOrDie(u"@com.app.a:styleable/view")));

    // The unmangled name should not be present.
    AAPT_EXPECT_FALSE(finalTable.findResource(test::parseNameOrDie(u"@com.app.b:id/foo")));

    // Look for the mangled name.
    AAPT_EXPECT_TRUE(finalTable.findResource(test::parseNameOrDie(u"@com.app.a:id/com.app.b$foo")));
}

TEST_F(TableMergerTest, MergeFile) {
    ResourceTable finalTable;
    TableMergerOptions options;
    options.autoAddOverlay = false;
    TableMerger merger(mContext.get(), &finalTable, options);

    ResourceFile fileDesc;
    fileDesc.config = test::parseConfigOrDie("hdpi-v4");
    fileDesc.name = test::parseNameOrDie(u"@layout/main");
    fileDesc.source = Source("res/layout-hdpi/main.xml");
    test::TestFile testFile("path/to/res/layout-hdpi/main.xml.flat");

    ASSERT_TRUE(merger.mergeFile(fileDesc, &testFile));

    FileReference* file = test::getValueForConfig<FileReference>(&finalTable,
                                                                 u"@com.app.a:layout/main",
                                                                 test::parseConfigOrDie("hdpi-v4"));
    ASSERT_NE(nullptr, file);
    EXPECT_EQ(std::u16string(u"res/layout-hdpi-v4/main.xml"), *file->path);
}

TEST_F(TableMergerTest, MergeFileOverlay) {
    ResourceTable finalTable;
    TableMergerOptions tableMergerOptions;
    tableMergerOptions.autoAddOverlay = false;
    TableMerger merger(mContext.get(), &finalTable, tableMergerOptions);

    ResourceFile fileDesc;
    fileDesc.name = test::parseNameOrDie(u"@xml/foo");
    test::TestFile fileA("path/to/fileA.xml.flat");
    test::TestFile fileB("path/to/fileB.xml.flat");

    ASSERT_TRUE(merger.mergeFile(fileDesc, &fileA));
    ASSERT_TRUE(merger.mergeFileOverlay(fileDesc, &fileB));
}

TEST_F(TableMergerTest, MergeFileReferences) {
    std::unique_ptr<ResourceTable> tableA = test::ResourceTableBuilder()
            .setPackageId(u"com.app.a", 0x7f)
            .addFileReference(u"@com.app.a:xml/file", u"res/xml/file.xml")
            .build();
    std::unique_ptr<ResourceTable> tableB = test::ResourceTableBuilder()
            .setPackageId(u"com.app.b", 0x7f)
            .addFileReference(u"@com.app.b:xml/file", u"res/xml/file.xml")
            .build();

    ResourceTable finalTable;
    TableMerger merger(mContext.get(), &finalTable, TableMergerOptions{});
    io::FileCollection collection;
    collection.insertFile("res/xml/file.xml");

    ASSERT_TRUE(merger.merge({}, tableA.get()));
    ASSERT_TRUE(merger.mergeAndMangle({}, u"com.app.b", tableB.get(), &collection));

    FileReference* f = test::getValue<FileReference>(&finalTable, u"@com.app.a:xml/file");
    ASSERT_NE(f, nullptr);
    EXPECT_EQ(std::u16string(u"res/xml/file.xml"), *f->path);

    f = test::getValue<FileReference>(&finalTable, u"@com.app.a:xml/com.app.b$file");
    ASSERT_NE(f, nullptr);
    EXPECT_EQ(std::u16string(u"res/xml/com.app.b$file.xml"), *f->path);
}

TEST_F(TableMergerTest, OverrideResourceWithOverlay) {
    std::unique_ptr<ResourceTable> base = test::ResourceTableBuilder()
            .setPackageId(u"", 0x00)
            .addValue(u"@bool/foo", ResourceUtils::tryParseBool(u"true"))
            .build();
    std::unique_ptr<ResourceTable> overlay = test::ResourceTableBuilder()
            .setPackageId(u"", 0x00)
            .addValue(u"@bool/foo", ResourceUtils::tryParseBool(u"false"))
            .build();

    ResourceTable finalTable;
    TableMergerOptions tableMergerOptions;
    tableMergerOptions.autoAddOverlay = false;
    TableMerger merger(mContext.get(), &finalTable, tableMergerOptions);

    ASSERT_TRUE(merger.merge({}, base.get()));
    ASSERT_TRUE(merger.mergeOverlay({}, overlay.get()));

    BinaryPrimitive* foo = test::getValue<BinaryPrimitive>(&finalTable, u"@com.app.a:bool/foo");
    ASSERT_NE(nullptr, foo);
    EXPECT_EQ(0x0u, foo->value.data);
}

TEST_F(TableMergerTest, MergeAddResourceFromOverlay) {
    std::unique_ptr<ResourceTable> tableA = test::ResourceTableBuilder()
            .setPackageId(u"", 0x7f)
            .setSymbolState(u"@bool/foo", {}, SymbolState::kUndefined)
            .build();
    std::unique_ptr<ResourceTable> tableB = test::ResourceTableBuilder()
            .setPackageId(u"", 0x7f)
            .addValue(u"@bool/foo", ResourceUtils::tryParseBool(u"true"))
            .build();

    ResourceTable finalTable;
    TableMerger merger(mContext.get(), &finalTable, TableMergerOptions{});

    ASSERT_TRUE(merger.merge({}, tableA.get()));
    ASSERT_TRUE(merger.mergeOverlay({}, tableB.get()));
}

TEST_F(TableMergerTest, MergeAddResourceFromOverlayWithAutoAddOverlay) {
    std::unique_ptr<ResourceTable> tableA = test::ResourceTableBuilder()
            .setPackageId(u"", 0x7f)
            .build();
    std::unique_ptr<ResourceTable> tableB = test::ResourceTableBuilder()
            .setPackageId(u"", 0x7f)
            .addValue(u"@bool/foo", ResourceUtils::tryParseBool(u"true"))
            .build();

    ResourceTable finalTable;
    TableMergerOptions options;
    options.autoAddOverlay = true;
    TableMerger merger(mContext.get(), &finalTable, options);

    ASSERT_TRUE(merger.merge({}, tableA.get()));
    ASSERT_TRUE(merger.mergeOverlay({}, tableB.get()));
}

TEST_F(TableMergerTest, FailToMergeNewResourceWithoutAutoAddOverlay) {
    std::unique_ptr<ResourceTable> tableA = test::ResourceTableBuilder()
            .setPackageId(u"", 0x7f)
            .build();
    std::unique_ptr<ResourceTable> tableB = test::ResourceTableBuilder()
            .setPackageId(u"", 0x7f)
            .addValue(u"@bool/foo", ResourceUtils::tryParseBool(u"true"))
            .build();

    ResourceTable finalTable;
    TableMergerOptions options;
    options.autoAddOverlay = false;
    TableMerger merger(mContext.get(), &finalTable, options);

    ASSERT_TRUE(merger.merge({}, tableA.get()));
    ASSERT_FALSE(merger.mergeOverlay({}, tableB.get()));
}

} // namespace aapt
