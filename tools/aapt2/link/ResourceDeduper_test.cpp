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

#include "ResourceTable.h"
#include "link/Linkers.h"
#include "test/Test.h"

namespace aapt {

TEST(ResourceDeduperTest, SameValuesAreDeduped) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    const ConfigDescription defaultConfig = {};
    const ConfigDescription enConfig = test::parseConfigOrDie("en");
    const ConfigDescription enV21Config = test::parseConfigOrDie("en-v21");
    // Chosen because this configuration is compatible with en.
    const ConfigDescription landConfig = test::parseConfigOrDie("land");

    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addString("android:string/dedupe", ResourceId{}, defaultConfig, "dedupe")
            .addString("android:string/dedupe", ResourceId{}, enConfig, "dedupe")
            .addString("android:string/dedupe", ResourceId{}, landConfig, "dedupe")
            .addString("android:string/dedupe2", ResourceId{}, defaultConfig, "dedupe")
            .addString("android:string/dedupe2", ResourceId{}, enConfig, "dedupe")
            .addString("android:string/dedupe2", ResourceId{}, enV21Config, "keep")
            .addString("android:string/dedupe2", ResourceId{}, landConfig, "dedupe")
            .build();

    ASSERT_TRUE(ResourceDeduper().consume(context.get(), table.get()));
    EXPECT_EQ(
            nullptr,
            test::getValueForConfig<String>(table.get(), "android:string/dedupe", enConfig));
    EXPECT_EQ(
            nullptr,
            test::getValueForConfig<String>(table.get(), "android:string/dedupe", landConfig));
    EXPECT_EQ(
            nullptr,
            test::getValueForConfig<String>(table.get(), "android:string/dedupe2", enConfig));
    EXPECT_NE(
            nullptr,
            test::getValueForConfig<String>(table.get(), "android:string/dedupe2", enV21Config));
}

TEST(ResourceDeduperTest, DifferentValuesAreKept) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    const ConfigDescription defaultConfig = {};
    const ConfigDescription enConfig = test::parseConfigOrDie("en");
    const ConfigDescription enV21Config = test::parseConfigOrDie("en-v21");
    // Chosen because this configuration is compatible with en.
    const ConfigDescription landConfig = test::parseConfigOrDie("land");

    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addString("android:string/keep", ResourceId{}, defaultConfig, "keep")
            .addString("android:string/keep", ResourceId{}, enConfig, "keep")
            .addString("android:string/keep", ResourceId{}, enV21Config, "keep2")
            .addString("android:string/keep", ResourceId{}, landConfig, "keep2")
            .build();

    ASSERT_TRUE(ResourceDeduper().consume(context.get(), table.get()));
    EXPECT_NE(
            nullptr,
            test::getValueForConfig<String>(table.get(), "android:string/keep", enConfig));
    EXPECT_NE(
            nullptr,
            test::getValueForConfig<String>(table.get(), "android:string/keep", enV21Config));
    EXPECT_NE(
            nullptr,
            test::getValueForConfig<String>(table.get(), "android:string/keep", landConfig));
}

}  // namespace aapt
