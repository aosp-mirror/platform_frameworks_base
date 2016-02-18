/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "split/TableSplitter.h"
#include "test/Builders.h"
#include "test/Common.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(TableSplitterTest, NoSplitPreferredDensity) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addFileReference(u"@android:drawable/icon", u"res/drawable-mdpi/icon.png",
                              test::parseConfigOrDie("mdpi"))
            .addFileReference(u"@android:drawable/icon", u"res/drawable-hdpi/icon.png",
                              test::parseConfigOrDie("hdpi"))
            .addFileReference(u"@android:drawable/icon", u"res/drawable-xhdpi/icon.png",
                              test::parseConfigOrDie("xhdpi"))
            .addFileReference(u"@android:drawable/icon", u"res/drawable-xxhdpi/icon.png",
                              test::parseConfigOrDie("xxhdpi"))
            .addSimple(u"@android:string/one", {})
            .build();

    TableSplitterOptions options;
    options.preferredDensity = ConfigDescription::DENSITY_XHIGH;
    TableSplitter splitter({}, options);
    splitter.splitTable(table.get());

    EXPECT_EQ(nullptr, test::getValueForConfig<FileReference>(table.get(),
                                                              u"@android:drawable/icon",
                                                              test::parseConfigOrDie("mdpi")));
    EXPECT_EQ(nullptr, test::getValueForConfig<FileReference>(table.get(),
                                                              u"@android:drawable/icon",
                                                              test::parseConfigOrDie("hdpi")));
    EXPECT_NE(nullptr, test::getValueForConfig<FileReference>(table.get(),
                                                              u"@android:drawable/icon",
                                                              test::parseConfigOrDie("xhdpi")));
    EXPECT_EQ(nullptr, test::getValueForConfig<FileReference>(table.get(),
                                                              u"@android:drawable/icon",
                                                              test::parseConfigOrDie("xxhdpi")));
    EXPECT_NE(nullptr, test::getValue<Id>(table.get(), u"@android:string/one"));
}

TEST(TableSplitterTest, SplitTableByConfigAndDensity) {
    ResourceTable table;

    const ResourceName foo = test::parseNameOrDie(u"@android:string/foo");
    ASSERT_TRUE(table.addResource(foo, test::parseConfigOrDie("land-hdpi"), {},
                                  util::make_unique<Id>(),
                                  test::getDiagnostics()));
    ASSERT_TRUE(table.addResource(foo, test::parseConfigOrDie("land-xhdpi"), {},
                                  util::make_unique<Id>(),
                                  test::getDiagnostics()));
    ASSERT_TRUE(table.addResource(foo, test::parseConfigOrDie("land-xxhdpi"), {},
                                  util::make_unique<Id>(),
                                  test::getDiagnostics()));

    std::vector<SplitConstraints> constraints;
    constraints.push_back(SplitConstraints{ { test::parseConfigOrDie("land-mdpi") } });
    constraints.push_back(SplitConstraints{ { test::parseConfigOrDie("land-xhdpi") } });

    TableSplitter splitter(constraints, TableSplitterOptions{});
    splitter.splitTable(&table);

    ASSERT_EQ(2u, splitter.getSplits().size());

    ResourceTable* splitOne = splitter.getSplits()[0].get();
    ResourceTable* splitTwo = splitter.getSplits()[1].get();

    // Since a split was defined, all densities should be gone from base.
    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(&table, u"@android:string/foo",
                                                   test::parseConfigOrDie("land-hdpi")));
    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(&table, u"@android:string/foo",
                                                   test::parseConfigOrDie("land-xhdpi")));
    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(&table, u"@android:string/foo",
                                                   test::parseConfigOrDie("land-xxhdpi")));

    EXPECT_NE(nullptr, test::getValueForConfig<Id>(splitOne, u"@android:string/foo",
                                                   test::parseConfigOrDie("land-hdpi")));
    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(splitOne, u"@android:string/foo",
                                                   test::parseConfigOrDie("land-xhdpi")));
    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(splitOne, u"@android:string/foo",
                                                   test::parseConfigOrDie("land-xxhdpi")));

    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(splitTwo, u"@android:string/foo",
                                                   test::parseConfigOrDie("land-hdpi")));
    EXPECT_NE(nullptr, test::getValueForConfig<Id>(splitTwo, u"@android:string/foo",
                                                   test::parseConfigOrDie("land-xhdpi")));
    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(splitTwo, u"@android:string/foo",
                                                   test::parseConfigOrDie("land-xxhdpi")));
}

} // namespace aapt
