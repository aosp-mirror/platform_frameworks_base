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

#include "link/Linkers.h"
#include "test/Test.h"

namespace aapt {

template <typename T>
using uptr = std::unique_ptr<T>;

static uptr<ResourceTable> buildTableWithConfigs(const StringPiece& name,
                                                 std::initializer_list<std::string> list) {
    test::ResourceTableBuilder builder;
    for (const std::string& item : list) {
        builder.addSimple(name, test::parseConfigOrDie(item));
    }
    return builder.build();
}

TEST(VersionCollapserTest, CollapseVersions) {
    uptr<IAaptContext> context = test::ContextBuilder().setMinSdkVersion(7).build();

    const StringPiece resName = "@android:string/foo";

    uptr<ResourceTable> table =
            buildTableWithConfigs(resName,
                                  { "land-v4", "land-v5", "sw600dp", "land-v6",
                                          "land-v14", "land-v21" });

    VersionCollapser collapser;
    ASSERT_TRUE(collapser.consume(context.get(), table.get()));

    // These should be removed.
    EXPECT_EQ(nullptr,
              test::getValueForConfig<Id>(table.get(), resName, test::parseConfigOrDie("land-v4")));
    EXPECT_EQ(nullptr,
              test::getValueForConfig<Id>(table.get(), resName, test::parseConfigOrDie("land-v5")));
    // This one should be removed because it was renamed to 'land', with the version dropped.
    EXPECT_EQ(nullptr,
              test::getValueForConfig<Id>(table.get(), resName, test::parseConfigOrDie("land-v6")));

    // These should remain.
    EXPECT_NE(nullptr,
              test::getValueForConfig<Id>(table.get(), resName, test::parseConfigOrDie("sw600dp")));

    // 'land' should be present because it was renamed from 'land-v6'.
    EXPECT_NE(nullptr,
              test::getValueForConfig<Id>(table.get(), resName, test::parseConfigOrDie("land")));
    EXPECT_NE(nullptr,
              test::getValueForConfig<Id>(table.get(), resName, test::parseConfigOrDie("land-v14")));
    EXPECT_NE(nullptr,
              test::getValueForConfig<Id>(table.get(), resName, test::parseConfigOrDie("land-v21")));
}

TEST(VersionCollapserTest, CollapseVersionsWhenMinSdkIsHighest) {
    uptr<IAaptContext> context = test::ContextBuilder().setMinSdkVersion(21).build();

    const StringPiece resName = "@android:string/foo";

    uptr<ResourceTable> table =
                buildTableWithConfigs(resName,
                                      { "land-v4", "land-v5", "sw600dp", "land-v6",
                                              "land-v14", "land-v21", "land-v22" });
    VersionCollapser collapser;
    ASSERT_TRUE(collapser.consume(context.get(), table.get()));

    // These should all be removed.
    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(table.get(), resName,
                                                   test::parseConfigOrDie("land-v4")));
    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(table.get(), resName,
                                                   test::parseConfigOrDie("land-v5")));
    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(table.get(), resName,
                                                   test::parseConfigOrDie("land-v6")));
    EXPECT_EQ(nullptr, test::getValueForConfig<Id>(table.get(), resName,
                                                   test::parseConfigOrDie("land-v14")));

    // These should remain.
    EXPECT_NE(nullptr, test::getValueForConfig<Id>(
            table.get(), resName, test::parseConfigOrDie("sw600dp").copyWithoutSdkVersion()));

    // land-v21 should have been converted to land.
    EXPECT_NE(nullptr, test::getValueForConfig<Id>(table.get(), resName,
                                                   test::parseConfigOrDie("land")));
    // land-v22 should remain as-is.
    EXPECT_NE(nullptr, test::getValueForConfig<Id>(table.get(), resName,
                                                   test::parseConfigOrDie("land-v22")));
}

} // namespace aapt
