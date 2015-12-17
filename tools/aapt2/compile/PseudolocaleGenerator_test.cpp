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

#include "compile/PseudolocaleGenerator.h"
#include "test/Builders.h"
#include "test/Common.h"
#include "test/Context.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <gtest/gtest.h>

namespace aapt {

TEST(PseudolocaleGeneratorTest, PseudolocalizeStyledString) {
    StringPool pool;
    StyleString originalStyle;
    originalStyle.str = u"Hello world!";
    originalStyle.spans = { Span{ u"b", 2, 3 }, Span{ u"b", 6, 7 }, Span{ u"i", 1, 10 } };

    std::unique_ptr<StyledString> newString = pseudolocalizeStyledString(
            util::make_unique<StyledString>(pool.makeRef(originalStyle)).get(),
            Pseudolocalizer::Method::kNone, &pool);

    EXPECT_EQ(originalStyle.str, *newString->value->str);
    ASSERT_EQ(originalStyle.spans.size(), newString->value->spans.size());

    EXPECT_EQ(2u, newString->value->spans[0].firstChar);
    EXPECT_EQ(3u, newString->value->spans[0].lastChar);
    EXPECT_EQ(std::u16string(u"b"), *newString->value->spans[0].name);

    EXPECT_EQ(6u, newString->value->spans[1].firstChar);
    EXPECT_EQ(7u, newString->value->spans[1].lastChar);
    EXPECT_EQ(std::u16string(u"b"), *newString->value->spans[1].name);

    EXPECT_EQ(1u, newString->value->spans[2].firstChar);
    EXPECT_EQ(10u, newString->value->spans[2].lastChar);
    EXPECT_EQ(std::u16string(u"i"), *newString->value->spans[2].name);

    originalStyle.spans.push_back(Span{ u"em", 0, 11u });

    newString = pseudolocalizeStyledString(
            util::make_unique<StyledString>(pool.makeRef(originalStyle)).get(),
            Pseudolocalizer::Method::kAccent, &pool);

    EXPECT_EQ(std::u16string(u"[Ĥéļļö ŵöŕļð¡ one two]"), *newString->value->str);
    ASSERT_EQ(originalStyle.spans.size(), newString->value->spans.size());

    EXPECT_EQ(3u, newString->value->spans[0].firstChar);
    EXPECT_EQ(4u, newString->value->spans[0].lastChar);

    EXPECT_EQ(7u, newString->value->spans[1].firstChar);
    EXPECT_EQ(8u, newString->value->spans[1].lastChar);

    EXPECT_EQ(2u, newString->value->spans[2].firstChar);
    EXPECT_EQ(11u, newString->value->spans[2].lastChar);

    EXPECT_EQ(1u, newString->value->spans[3].firstChar);
    EXPECT_EQ(12u, newString->value->spans[3].lastChar);
}

TEST(PseudolocaleGeneratorTest, PseudolocalizeOnlyDefaultConfigs) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addString(u"@android:string/one", u"one")
            .addString(u"@android:string/two", ResourceId{}, test::parseConfigOrDie("en"), u"two")
            .addString(u"@android:string/three", u"three")
            .addString(u"@android:string/three", ResourceId{}, test::parseConfigOrDie("en-rXA"),
                       u"three")
            .addString(u"@android:string/four", u"four")
            .build();

    String* val = test::getValue<String>(table.get(), u"@android:string/four");
    val->setTranslateable(false);

    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    PseudolocaleGenerator generator;
    ASSERT_TRUE(generator.consume(context.get(), table.get()));

    // Normal pseudolocalization should take place.
    ASSERT_NE(nullptr, test::getValueForConfig<String>(table.get(), u"@android:string/one",
                                                       test::parseConfigOrDie("en-rXA")));
    ASSERT_NE(nullptr, test::getValueForConfig<String>(table.get(), u"@android:string/one",
                                                       test::parseConfigOrDie("ar-rXB")));

    // No default config for android:string/two, so no pseudlocales should exist.
    ASSERT_EQ(nullptr, test::getValueForConfig<String>(table.get(), u"@android:string/two",
                                                       test::parseConfigOrDie("en-rXA")));
    ASSERT_EQ(nullptr, test::getValueForConfig<String>(table.get(), u"@android:string/two",
                                                       test::parseConfigOrDie("ar-rXB")));


    // Check that we didn't override manual pseudolocalization.
    val = test::getValueForConfig<String>(table.get(), u"@android:string/three",
                                          test::parseConfigOrDie("en-rXA"));
    ASSERT_NE(nullptr, val);
    EXPECT_EQ(std::u16string(u"three"), *val->value);

    ASSERT_NE(nullptr, test::getValueForConfig<String>(table.get(), u"@android:string/three",
                                                       test::parseConfigOrDie("ar-rXB")));

    // Check that four's translateable marker was honored.
    ASSERT_EQ(nullptr, test::getValueForConfig<String>(table.get(), u"@android:string/four",
                                                       test::parseConfigOrDie("en-rXA")));
    ASSERT_EQ(nullptr, test::getValueForConfig<String>(table.get(), u"@android:string/four",
                                                       test::parseConfigOrDie("ar-rXB")));

}

} // namespace aapt

