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
#include "test/Test.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>

namespace aapt {

TEST(PseudolocaleGeneratorTest, PseudolocalizeStyledString) {
    StringPool pool;
    StyleString originalStyle;
    originalStyle.str = "Hello world!";
    originalStyle.spans = { Span{ "b", 2, 3 }, Span{ "b", 6, 7 }, Span{ "i", 1, 10 } };

    std::unique_ptr<StyledString> newString = pseudolocalizeStyledString(
            util::make_unique<StyledString>(pool.makeRef(originalStyle)).get(),
            Pseudolocalizer::Method::kNone, &pool);

    EXPECT_EQ(originalStyle.str, *newString->value->str);
    ASSERT_EQ(originalStyle.spans.size(), newString->value->spans.size());

    EXPECT_EQ(std::string("He").size(), newString->value->spans[0].firstChar);
    EXPECT_EQ(std::string("Hel").size(), newString->value->spans[0].lastChar);
    EXPECT_EQ(std::string("b"), *newString->value->spans[0].name);

    EXPECT_EQ(std::string("Hello ").size(), newString->value->spans[1].firstChar);
    EXPECT_EQ(std::string("Hello w").size(), newString->value->spans[1].lastChar);
    EXPECT_EQ(std::string("b"), *newString->value->spans[1].name);

    EXPECT_EQ(std::string("H").size(), newString->value->spans[2].firstChar);
    EXPECT_EQ(std::string("Hello worl").size(), newString->value->spans[2].lastChar);
    EXPECT_EQ(std::string("i"), *newString->value->spans[2].name);

    originalStyle.spans.push_back(Span{ "em", 0, 11u });

    newString = pseudolocalizeStyledString(
            util::make_unique<StyledString>(pool.makeRef(originalStyle)).get(),
            Pseudolocalizer::Method::kAccent, &pool);

    EXPECT_EQ(std::string("[Ĥéļļö ŵöŕļð¡ one two]"), *newString->value->str);
    ASSERT_EQ(originalStyle.spans.size(), newString->value->spans.size());

    EXPECT_EQ(std::string("[Ĥé").size(), newString->value->spans[0].firstChar);
    EXPECT_EQ(std::string("[Ĥéļ").size(), newString->value->spans[0].lastChar);

    EXPECT_EQ(std::string("[Ĥéļļö ").size(), newString->value->spans[1].firstChar);
    EXPECT_EQ(std::string("[Ĥéļļö ŵ").size(), newString->value->spans[1].lastChar);

    EXPECT_EQ(std::string("[Ĥ").size(), newString->value->spans[2].firstChar);
    EXPECT_EQ(std::string("[Ĥéļļö ŵöŕļ").size(), newString->value->spans[2].lastChar);

    EXPECT_EQ(std::string("[").size(), newString->value->spans[3].firstChar);
    EXPECT_EQ(std::string("[Ĥéļļö ŵöŕļð").size(), newString->value->spans[3].lastChar);
}

TEST(PseudolocaleGeneratorTest, PseudolocalizeOnlyDefaultConfigs) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addString("@android:string/one", "one")
            .addString("@android:string/two", ResourceId{}, test::parseConfigOrDie("en"), "two")
            .addString("@android:string/three", "three")
            .addString("@android:string/three", ResourceId{}, test::parseConfigOrDie("en-rXA"),
                       "three")
            .addString("@android:string/four", "four")
            .build();

    String* val = test::getValue<String>(table.get(), "@android:string/four");
    val->setTranslateable(false);

    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    PseudolocaleGenerator generator;
    ASSERT_TRUE(generator.consume(context.get(), table.get()));

    // Normal pseudolocalization should take place.
    ASSERT_NE(nullptr, test::getValueForConfig<String>(table.get(), "@android:string/one",
                                                       test::parseConfigOrDie("en-rXA")));
    ASSERT_NE(nullptr, test::getValueForConfig<String>(table.get(), "@android:string/one",
                                                       test::parseConfigOrDie("ar-rXB")));

    // No default config for android:string/two, so no pseudlocales should exist.
    ASSERT_EQ(nullptr, test::getValueForConfig<String>(table.get(), "@android:string/two",
                                                       test::parseConfigOrDie("en-rXA")));
    ASSERT_EQ(nullptr, test::getValueForConfig<String>(table.get(), "@android:string/two",
                                                       test::parseConfigOrDie("ar-rXB")));


    // Check that we didn't override manual pseudolocalization.
    val = test::getValueForConfig<String>(table.get(), "@android:string/three",
                                          test::parseConfigOrDie("en-rXA"));
    ASSERT_NE(nullptr, val);
    EXPECT_EQ(std::string("three"), *val->value);

    ASSERT_NE(nullptr, test::getValueForConfig<String>(table.get(), "@android:string/three",
                                                       test::parseConfigOrDie("ar-rXB")));

    // Check that four's translateable marker was honored.
    ASSERT_EQ(nullptr, test::getValueForConfig<String>(table.get(), "@android:string/four",
                                                       test::parseConfigOrDie("en-rXA")));
    ASSERT_EQ(nullptr, test::getValueForConfig<String>(table.get(), "@android:string/four",
                                                       test::parseConfigOrDie("ar-rXB")));

}

} // namespace aapt

