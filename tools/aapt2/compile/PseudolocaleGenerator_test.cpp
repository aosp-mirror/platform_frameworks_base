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

namespace aapt {

TEST(PseudolocaleGeneratorTest, PseudolocalizeStyledString) {
  StringPool pool;
  StyleString original_style;
  original_style.str = "Hello world!";
  original_style.spans = {Span{"b", 2, 3}, Span{"b", 6, 7}, Span{"i", 1, 10}};

  std::unique_ptr<StyledString> new_string = PseudolocalizeStyledString(
      util::make_unique<StyledString>(pool.MakeRef(original_style)).get(),
      Pseudolocalizer::Method::kNone, &pool);

  EXPECT_EQ(original_style.str, *new_string->value->str);
  ASSERT_EQ(original_style.spans.size(), new_string->value->spans.size());

  EXPECT_EQ(std::string("He").size(), new_string->value->spans[0].first_char);
  EXPECT_EQ(std::string("Hel").size(), new_string->value->spans[0].last_char);
  EXPECT_EQ(std::string("b"), *new_string->value->spans[0].name);

  EXPECT_EQ(std::string("Hello ").size(),
            new_string->value->spans[1].first_char);
  EXPECT_EQ(std::string("Hello w").size(),
            new_string->value->spans[1].last_char);
  EXPECT_EQ(std::string("b"), *new_string->value->spans[1].name);

  EXPECT_EQ(std::string("H").size(), new_string->value->spans[2].first_char);
  EXPECT_EQ(std::string("Hello worl").size(),
            new_string->value->spans[2].last_char);
  EXPECT_EQ(std::string("i"), *new_string->value->spans[2].name);

  original_style.spans.push_back(Span{"em", 0, 11u});

  new_string = PseudolocalizeStyledString(
      util::make_unique<StyledString>(pool.MakeRef(original_style)).get(),
      Pseudolocalizer::Method::kAccent, &pool);

  EXPECT_EQ(std::string("[Ĥéļļö ŵöŕļð¡ one two]"), *new_string->value->str);
  ASSERT_EQ(original_style.spans.size(), new_string->value->spans.size());

  EXPECT_EQ(std::string("[Ĥé").size(), new_string->value->spans[0].first_char);
  EXPECT_EQ(std::string("[Ĥéļ").size(), new_string->value->spans[0].last_char);

  EXPECT_EQ(std::string("[Ĥéļļö ").size(),
            new_string->value->spans[1].first_char);
  EXPECT_EQ(std::string("[Ĥéļļö ŵ").size(),
            new_string->value->spans[1].last_char);

  EXPECT_EQ(std::string("[Ĥ").size(), new_string->value->spans[2].first_char);
  EXPECT_EQ(std::string("[Ĥéļļö ŵöŕļ").size(),
            new_string->value->spans[2].last_char);

  EXPECT_EQ(std::string("[").size(), new_string->value->spans[3].first_char);
  EXPECT_EQ(std::string("[Ĥéļļö ŵöŕļð").size(),
            new_string->value->spans[3].last_char);
}

TEST(PseudolocaleGeneratorTest, PseudolocalizeOnlyDefaultConfigs) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddString("android:string/one", "one")
          .AddString("android:string/two", ResourceId{},
                     test::ParseConfigOrDie("en"), "two")
          .AddString("android:string/three", "three")
          .AddString("android:string/three", ResourceId{},
                     test::ParseConfigOrDie("en-rXA"), "three")
          .AddString("android:string/four", "four")
          .Build();

  String* val = test::GetValue<String>(table.get(), "android:string/four");
  ASSERT_NE(nullptr, val);
  val->SetTranslatable(false);

  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  PseudolocaleGenerator generator;
  ASSERT_TRUE(generator.Consume(context.get(), table.get()));

  // Normal pseudolocalization should take place.
  ASSERT_NE(nullptr,
            test::GetValueForConfig<String>(table.get(), "android:string/one",
                                            test::ParseConfigOrDie("en-rXA")));
  ASSERT_NE(nullptr,
            test::GetValueForConfig<String>(table.get(), "android:string/one",
                                            test::ParseConfigOrDie("ar-rXB")));

  // No default config for android:string/two, so no pseudlocales should exist.
  ASSERT_EQ(nullptr,
            test::GetValueForConfig<String>(table.get(), "android:string/two",
                                            test::ParseConfigOrDie("en-rXA")));
  ASSERT_EQ(nullptr,
            test::GetValueForConfig<String>(table.get(), "android:string/two",
                                            test::ParseConfigOrDie("ar-rXB")));

  // Check that we didn't override manual pseudolocalization.
  val = test::GetValueForConfig<String>(table.get(), "android:string/three",
                                        test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, val);
  EXPECT_EQ(std::string("three"), *val->value);

  ASSERT_NE(nullptr,
            test::GetValueForConfig<String>(table.get(), "android:string/three",
                                            test::ParseConfigOrDie("ar-rXB")));

  // Check that four's translateable marker was honored.
  ASSERT_EQ(nullptr,
            test::GetValueForConfig<String>(table.get(), "android:string/four",
                                            test::ParseConfigOrDie("en-rXA")));
  ASSERT_EQ(nullptr,
            test::GetValueForConfig<String>(table.get(), "android:string/four",
                                            test::ParseConfigOrDie("ar-rXB")));
}

TEST(PseudolocaleGeneratorTest, RespectUntranslateableSections) {
  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder().SetCompilationPackage("android").Build();
  std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();

  {
    StyleString original_style;
    original_style.str = "Hello world!";
    original_style.spans = {Span{"b", 2, 3}, Span{"b", 6, 7}, Span{"i", 1, 10}};

    auto styled_string =
        util::make_unique<StyledString>(table->string_pool.MakeRef(original_style));
    styled_string->untranslatable_sections.push_back(UntranslatableSection{6u, 8u});
    styled_string->untranslatable_sections.push_back(UntranslatableSection{8u, 11u});

    auto string = util::make_unique<String>(table->string_pool.MakeRef(original_style.str));
    string->untranslatable_sections.push_back(UntranslatableSection{6u, 11u});

    ASSERT_TRUE(table->AddResource(test::ParseNameOrDie("android:string/foo"), ConfigDescription{},
                                   {} /* product */, std::move(styled_string),
                                   context->GetDiagnostics()));
    ASSERT_TRUE(table->AddResource(test::ParseNameOrDie("android:string/bar"), ConfigDescription{},
                                   {} /* product */, std::move(string), context->GetDiagnostics()));
  }

  PseudolocaleGenerator generator;
  ASSERT_TRUE(generator.Consume(context.get(), table.get()));

  StyledString* new_styled_string = test::GetValueForConfig<StyledString>(
      table.get(), "android:string/foo", test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, new_styled_string);

  // "world" should be untranslated.
  EXPECT_NE(std::string::npos, new_styled_string->value->str->find("world"));

  String* new_string = test::GetValueForConfig<String>(table.get(), "android:string/bar",
                                                       test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, new_string);

  // "world" should be untranslated.
  EXPECT_NE(std::string::npos, new_string->value->find("world"));
}

}  // namespace aapt
