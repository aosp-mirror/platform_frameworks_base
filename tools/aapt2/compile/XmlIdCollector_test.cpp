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

#include "compile/XmlIdCollector.h"
#include "test/Builders.h"
#include "test/Context.h"

#include <algorithm>
#include <gtest/gtest.h>

namespace aapt {

TEST(XmlIdCollectorTest, CollectsIds) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();

    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDom(R"EOF(
            <View xmlns:android="http://schemas.android.com/apk/res/android"
                  android:id="@+id/foo"
                  text="@+id/bar">
              <SubView android:id="@+id/car"
                       class="@+id/bar"/>
            </View>)EOF");

    XmlIdCollector collector;
    ASSERT_TRUE(collector.consume(context.get(), doc.get()));

    EXPECT_EQ(1, std::count(doc->file.exportedSymbols.begin(), doc->file.exportedSymbols.end(),
                             SourcedResourceName{ test::parseNameOrDie(u"@id/foo"), 3u }));

    EXPECT_EQ(1, std::count(doc->file.exportedSymbols.begin(), doc->file.exportedSymbols.end(),
                             SourcedResourceName{ test::parseNameOrDie(u"@id/bar"), 3u }));

    EXPECT_EQ(1, std::count(doc->file.exportedSymbols.begin(), doc->file.exportedSymbols.end(),
                             SourcedResourceName{ test::parseNameOrDie(u"@id/car"), 6u }));
}

TEST(XmlIdCollectorTest, DontCollectNonIds) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();

    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDom("<View foo=\"@+string/foo\"/>");

    XmlIdCollector collector;
    ASSERT_TRUE(collector.consume(context.get(), doc.get()));

    EXPECT_TRUE(doc->file.exportedSymbols.empty());
}

} // namespace aapt
