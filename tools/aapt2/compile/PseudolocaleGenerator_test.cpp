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

using ::android::ConfigDescription;

namespace aapt {

TEST(PseudolocaleGeneratorTest, PseudolocalizeStyledString) {
  android::StringPool pool;
  android::StyleString original_style;
  original_style.str = "Hello world!";
  original_style.spans = {android::Span{"i", 1, 10}, android::Span{"b", 2, 3},
                          android::Span{"b", 6, 7}};

  std::unique_ptr<StyledString> new_string = PseudolocalizeStyledString(
      util::make_unique<StyledString>(pool.MakeRef(original_style)).get(),
      Pseudolocalizer::Method::kNone, &pool);

  EXPECT_EQ(original_style.str, new_string->value->value);
  ASSERT_EQ(original_style.spans.size(), new_string->value->spans.size());

  EXPECT_EQ(std::string("i"), *new_string->value->spans[0].name);
  EXPECT_EQ(std::u16string(u"H").size(), new_string->value->spans[0].first_char);
  EXPECT_EQ(std::u16string(u"Hello worl").size(), new_string->value->spans[0].last_char);

  EXPECT_EQ(std::string("b"), *new_string->value->spans[1].name);
  EXPECT_EQ(std::u16string(u"He").size(), new_string->value->spans[1].first_char);
  EXPECT_EQ(std::u16string(u"Hel").size(), new_string->value->spans[1].last_char);

  EXPECT_EQ(std::string("b"), *new_string->value->spans[2].name);
  EXPECT_EQ(std::u16string(u"Hello ").size(), new_string->value->spans[2].first_char);
  EXPECT_EQ(std::u16string(u"Hello w").size(), new_string->value->spans[2].last_char);

  original_style.spans.insert(original_style.spans.begin(), android::Span{"em", 0, 11u});

  new_string = PseudolocalizeStyledString(
      util::make_unique<StyledString>(pool.MakeRef(original_style)).get(),
      Pseudolocalizer::Method::kAccent, &pool);

  EXPECT_EQ(std::string("[Ĥéļļö ŵöŕļð¡ one two]"), new_string->value->value);
  ASSERT_EQ(original_style.spans.size(), new_string->value->spans.size());

  EXPECT_EQ(std::u16string(u"[").size(), new_string->value->spans[0].first_char);
  EXPECT_EQ(std::u16string(u"[Ĥéļļö ŵöŕļð").size(), new_string->value->spans[0].last_char);

  EXPECT_EQ(std::u16string(u"[Ĥ").size(), new_string->value->spans[1].first_char);
  EXPECT_EQ(std::u16string(u"[Ĥéļļö ŵöŕļ").size(), new_string->value->spans[1].last_char);

  EXPECT_EQ(std::u16string(u"[Ĥé").size(), new_string->value->spans[2].first_char);
  EXPECT_EQ(std::u16string(u"[Ĥéļ").size(), new_string->value->spans[2].last_char);

  EXPECT_EQ(std::u16string(u"[Ĥéļļö ").size(), new_string->value->spans[3].first_char);
  EXPECT_EQ(std::u16string(u"[Ĥéļļö ŵ").size(), new_string->value->spans[3].last_char);
}

TEST(PseudolocaleGeneratorTest, PseudolocalizeAdjacentNestedTags) {
  android::StringPool pool;
  android::StyleString original_style;
  original_style.str = "bold";
  original_style.spans = {android::Span{"b", 0, 3}, android::Span{"i", 0, 3}};

  std::unique_ptr<StyledString> new_string = PseudolocalizeStyledString(
      util::make_unique<StyledString>(pool.MakeRef(original_style)).get(),
      Pseudolocalizer::Method::kAccent, &pool);
  ASSERT_NE(nullptr, new_string);
  ASSERT_EQ(2u, new_string->value->spans.size());
  EXPECT_EQ(std::string("[ɓöļð one]"), new_string->value->value);

  EXPECT_EQ(std::string("b"), *new_string->value->spans[0].name);
  EXPECT_EQ(std::u16string(u"[").size(), new_string->value->spans[0].first_char);
  EXPECT_EQ(std::u16string(u"[ɓöļ").size(), new_string->value->spans[0].last_char);

  EXPECT_EQ(std::string("i"), *new_string->value->spans[1].name);
  EXPECT_EQ(std::u16string(u"[").size(), new_string->value->spans[1].first_char);
  EXPECT_EQ(std::u16string(u"[ɓöļ").size(), new_string->value->spans[1].last_char);
}

TEST(PseudolocaleGeneratorTest, PseudolocalizeAdjacentTagsUnsorted) {
  android::StringPool pool;
  android::StyleString original_style;
  original_style.str = "bold";
  original_style.spans = {android::Span{"i", 2, 3}, android::Span{"b", 0, 1}};

  std::unique_ptr<StyledString> new_string = PseudolocalizeStyledString(
      util::make_unique<StyledString>(pool.MakeRef(original_style)).get(),
      Pseudolocalizer::Method::kAccent, &pool);
  ASSERT_NE(nullptr, new_string);
  ASSERT_EQ(2u, new_string->value->spans.size());
  EXPECT_EQ(std::string("[ɓöļð one]"), new_string->value->value);

  EXPECT_EQ(std::string("b"), *new_string->value->spans[0].name);
  EXPECT_EQ(std::u16string(u"[").size(), new_string->value->spans[0].first_char);
  EXPECT_EQ(std::u16string(u"[ɓ").size(), new_string->value->spans[0].last_char);

  EXPECT_EQ(std::string("i"), *new_string->value->spans[1].name);
  EXPECT_EQ(std::u16string(u"[ɓö").size(), new_string->value->spans[1].first_char);
  EXPECT_EQ(std::u16string(u"[ɓöļ").size(), new_string->value->spans[1].last_char);
}

TEST(PseudolocaleGeneratorTest, PseudolocalizeNestedAndAdjacentTags) {
  android::StringPool pool;
  android::StyleString original_style;
  original_style.str = "This sentence is not what you think it is at all.";
  original_style.spans = {android::Span{"b", 16u, 19u}, android::Span{"em", 29u, 47u},
                          android::Span{"i", 38u, 40u}, android::Span{"b", 44u, 47u}};

  std::unique_ptr<StyledString> new_string = PseudolocalizeStyledString(
      util::make_unique<StyledString>(pool.MakeRef(original_style)).get(),
      Pseudolocalizer::Method::kAccent, &pool);
  ASSERT_NE(nullptr, new_string);
  ASSERT_EQ(4u, new_string->value->spans.size());
  EXPECT_EQ(std::string(
                "[Ţĥîš šéñţéñçé îš ñöţ ŵĥåţ ýöû ţĥîñķ îţ îš åţ åļļ. one two three four five six]"),
            new_string->value->value);

  EXPECT_EQ(std::string("b"), *new_string->value->spans[0].name);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šéñţéñçé îš").size(), new_string->value->spans[0].first_char);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šéñţéñçé îš ñö").size(), new_string->value->spans[0].last_char);

  EXPECT_EQ(std::string("em"), *new_string->value->spans[1].name);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šéñţéñçé îš ñöţ ŵĥåţ ýöû").size(),
            new_string->value->spans[1].first_char);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šéñţéñçé îš ñöţ ŵĥåţ ýöû ţĥîñķ îţ îš åţ åļ").size(),
            new_string->value->spans[1].last_char);

  EXPECT_EQ(std::string("i"), *new_string->value->spans[2].name);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šéñţéñçé îš ñöţ ŵĥåţ ýöû ţĥîñķ îţ").size(),
            new_string->value->spans[2].first_char);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šéñţéñçé îš ñöţ ŵĥåţ ýöû ţĥîñķ îţ î").size(),
            new_string->value->spans[2].last_char);

  EXPECT_EQ(std::string("b"), *new_string->value->spans[3].name);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šéñţéñçé îš ñöţ ŵĥåţ ýöû ţĥîñķ îţ îš åţ").size(),
            new_string->value->spans[3].first_char);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šéñţéñçé îš ñöţ ŵĥåţ ýöû ţĥîñķ îţ îš åţ åļ").size(),
            new_string->value->spans[3].last_char);
}

TEST(PseudolocaleGeneratorTest, PseudolocalizePartsOfString) {
  android::StringPool pool;
  android::StyleString original_style;
  original_style.str = "This should NOT be pseudolocalized.";
  original_style.spans = {android::Span{"em", 4u, 14u}, android::Span{"i", 18u, 33u}};
  std::unique_ptr<StyledString> original_string =
      util::make_unique<StyledString>(pool.MakeRef(original_style));
  original_string->untranslatable_sections = {UntranslatableSection{11u, 15u}};

  std::unique_ptr<StyledString> new_string =
      PseudolocalizeStyledString(original_string.get(), Pseudolocalizer::Method::kAccent, &pool);
  ASSERT_NE(nullptr, new_string);
  ASSERT_EQ(2u, new_string->value->spans.size());
  EXPECT_EQ(std::string("[Ţĥîš šĥöûļð NOT ɓé þšéûðöļöçåļîžéð. one two three four]"),
            new_string->value->value);

  EXPECT_EQ(std::string("em"), *new_string->value->spans[0].name);
  EXPECT_EQ(std::u16string(u"[Ţĥîš").size(), new_string->value->spans[0].first_char);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šĥöûļð NO").size(), new_string->value->spans[0].last_char);

  EXPECT_EQ(std::string("i"), *new_string->value->spans[1].name);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šĥöûļð NOT ɓé").size(), new_string->value->spans[1].first_char);
  EXPECT_EQ(std::u16string(u"[Ţĥîš šĥöûļð NOT ɓé þšéûðöļöçåļîžé").size(),
            new_string->value->spans[1].last_char);
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
  PseudolocaleGenerator generator(std::string("f,m,n"), std::string("1.0"));
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

TEST(PseudolocaleGeneratorTest, PluralsArePseudolocalized) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder().Build();
  std::unique_ptr<Plural> plural = util::make_unique<Plural>();
  plural->values = {util::make_unique<String>(table->string_pool.MakeRef("zero")),
                    util::make_unique<String>(table->string_pool.MakeRef("one"))};
  ASSERT_TRUE(table->AddResource(NewResourceBuilder(test::ParseNameOrDie("com.pkg:plurals/foo"))
                                     .SetValue(std::move(plural))
                                     .Build(),
                                 context->GetDiagnostics()));
  std::unique_ptr<Plural> expected = util::make_unique<Plural>();
  expected->values = {util::make_unique<String>(table->string_pool.MakeRef("[žéŕö one]")),
                      util::make_unique<String>(table->string_pool.MakeRef("[öñé one]"))};

  PseudolocaleGenerator generator(std::string("f,m,n"), std::string("1.0"));
  ASSERT_TRUE(generator.Consume(context.get(), table.get()));

  const auto* actual = test::GetValueForConfig<Plural>(table.get(), "com.pkg:plurals/foo",
                                                       test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, actual);
  EXPECT_TRUE(actual->Equals(expected.get()));
}

TEST(PseudolocaleGeneratorTest, RespectUntranslateableSections) {
  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder().SetCompilationPackage("android").Build();
  std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();

  {
    android::StyleString original_style;
    original_style.str = "Hello world!";
    original_style.spans = {android::Span{"i", 1, 10}, android::Span{"b", 2, 3},
                            android::Span{"b", 6, 7}};

    auto styled_string =
        util::make_unique<StyledString>(table->string_pool.MakeRef(original_style));
    styled_string->untranslatable_sections.push_back(UntranslatableSection{6u, 8u});
    styled_string->untranslatable_sections.push_back(UntranslatableSection{8u, 11u});

    auto string = util::make_unique<String>(table->string_pool.MakeRef(original_style.str));
    string->untranslatable_sections.push_back(UntranslatableSection{6u, 11u});

    ASSERT_TRUE(table->AddResource(NewResourceBuilder(test::ParseNameOrDie("android:string/foo"))
                                       .SetValue(std::move(styled_string))
                                       .Build(),
                                   context->GetDiagnostics()));
    ASSERT_TRUE(table->AddResource(NewResourceBuilder(test::ParseNameOrDie("android:string/bar"))
                                       .SetValue(std::move(string))
                                       .Build(),
                                   context->GetDiagnostics()));
  }

  PseudolocaleGenerator generator(std::string("f,m,n"), std::string("1.0"));
  ASSERT_TRUE(generator.Consume(context.get(), table.get()));

  StyledString* new_styled_string = test::GetValueForConfig<StyledString>(
      table.get(), "android:string/foo", test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, new_styled_string);

  // "world" should be untranslated.
  EXPECT_NE(std::string::npos, new_styled_string->value->value.find("world"));

  String* new_string = test::GetValueForConfig<String>(table.get(), "android:string/bar",
                                                       test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, new_string);

  // "world" should be untranslated.
  EXPECT_NE(std::string::npos, new_string->value->find("world"));
}

TEST(PseudolocaleGeneratorTest, PseudolocalizeGrammaticalGenderForString) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder().AddString("android:string/foo", "foo").Build();

  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  PseudolocaleGenerator generator(std::string("f,m,n"), std::string("1.0"));
  ASSERT_TRUE(generator.Consume(context.get(), table.get()));

  String* locale = test::GetValueForConfig<String>(table.get(), "android:string/foo",
                                                   test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, locale);

  // Grammatical gendered string
  auto config_feminine = test::ParseConfigOrDie("en-rXA-feminine");
  config_feminine.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  String* feminine =
      test::GetValueForConfig<String>(table.get(), "android:string/foo", config_feminine);
  ASSERT_NE(nullptr, feminine);
  EXPECT_EQ(std::string("(F)") + *locale->value, *feminine->value);

  auto config_masculine = test::ParseConfigOrDie("en-rXA-masculine");
  config_masculine.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  String* masculine =
      test::GetValueForConfig<String>(table.get(), "android:string/foo", config_masculine);
  ASSERT_NE(nullptr, masculine);
  EXPECT_EQ(std::string("(M)") + *locale->value, *masculine->value);

  auto config_neuter = test::ParseConfigOrDie("en-rXA-neuter");
  config_neuter.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  String* neuter =
      test::GetValueForConfig<String>(table.get(), "android:string/foo", config_neuter);
  ASSERT_NE(nullptr, neuter);
  EXPECT_EQ(std::string("(N)") + *locale->value, *neuter->value);
}

TEST(PseudolocaleGeneratorTest, PseudolocalizeGrammaticalGenderForPlural) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder().Build();
  std::unique_ptr<Plural> plural = util::make_unique<Plural>();
  plural->values = {util::make_unique<String>(table->string_pool.MakeRef("zero")),
                    util::make_unique<String>(table->string_pool.MakeRef("one"))};
  ASSERT_TRUE(table->AddResource(NewResourceBuilder(test::ParseNameOrDie("com.pkg:plurals/foo"))
                                     .SetValue(std::move(plural))
                                     .Build(),
                                 context->GetDiagnostics()));
  PseudolocaleGenerator generator(std::string("f,m,n"), std::string("1.0"));
  ASSERT_TRUE(generator.Consume(context.get(), table.get()));

  Plural* actual = test::GetValueForConfig<Plural>(table.get(), "com.pkg:plurals/foo",
                                                   test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, actual);

  // Grammatical gendered Plural
  auto config_feminine = test::ParseConfigOrDie("en-rXA-feminine");
  config_feminine.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  Plural* actual_feminine =
      test::GetValueForConfig<Plural>(table.get(), "com.pkg:plurals/foo", config_feminine);
  for (size_t i = 0; i < actual->values.size(); i++) {
    if (actual->values[i]) {
      String* locale = ValueCast<String>(actual->values[i].get());
      String* feminine = ValueCast<String>(actual_feminine->values[i].get());
      EXPECT_EQ(std::string("(F)") + *locale->value, *feminine->value);
    }
  }

  auto config_masculine = test::ParseConfigOrDie("en-rXA-masculine");
  config_masculine.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  Plural* actual_masculine =
      test::GetValueForConfig<Plural>(table.get(), "com.pkg:plurals/foo", config_masculine);
  ASSERT_NE(nullptr, actual_masculine);
  for (size_t i = 0; i < actual->values.size(); i++) {
    if (actual->values[i]) {
      String* locale = ValueCast<String>(actual->values[i].get());
      String* masculine = ValueCast<String>(actual_masculine->values[i].get());
      EXPECT_EQ(std::string("(M)") + *locale->value, *masculine->value);
    }
  }

  auto config_neuter = test::ParseConfigOrDie("en-rXA-neuter");
  config_neuter.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  Plural* actual_neuter =
      test::GetValueForConfig<Plural>(table.get(), "com.pkg:plurals/foo", config_neuter);
  for (size_t i = 0; i < actual->values.size(); i++) {
    if (actual->values[i]) {
      String* locale = ValueCast<String>(actual->values[i].get());
      String* neuter = ValueCast<String>(actual_neuter->values[i].get());
      EXPECT_EQ(std::string("(N)") + *locale->value, *neuter->value);
    }
  }
}

TEST(PseudolocaleGeneratorTest, PseudolocalizeGrammaticalGenderForStyledString) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder().Build();
  android::StyleString original_style;
  original_style.str = "Hello world!";
  original_style.spans = {android::Span{"i", 1, 10}};

  std::unique_ptr<StyledString> original =
      util::make_unique<StyledString>(table->string_pool.MakeRef(original_style));
  ASSERT_TRUE(table->AddResource(NewResourceBuilder(test::ParseNameOrDie("android:string/foo"))
                                     .SetValue(std::move(original))
                                     .Build(),
                                 context->GetDiagnostics()));
  PseudolocaleGenerator generator(std::string("f,m,n"), std::string("1.0"));
  ASSERT_TRUE(generator.Consume(context.get(), table.get()));

  StyledString* locale = test::GetValueForConfig<StyledString>(table.get(), "android:string/foo",
                                                               test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, locale);
  EXPECT_EQ(1, locale->value->spans.size());
  EXPECT_EQ(std::string("i"), *locale->value->spans[0].name);

  // Grammatical gendered StyledString
  auto config_feminine = test::ParseConfigOrDie("en-rXA-feminine");
  config_feminine.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  StyledString* feminine =
      test::GetValueForConfig<StyledString>(table.get(), "android:string/foo", config_feminine);
  ASSERT_NE(nullptr, feminine);
  EXPECT_EQ(1, feminine->value->spans.size());
  EXPECT_EQ(std::string("i"), *feminine->value->spans[0].name);
  EXPECT_EQ(std::string("(F)") + locale->value->value, feminine->value->value);

  auto config_masculine = test::ParseConfigOrDie("en-rXA-masculine");
  config_masculine.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  StyledString* masculine =
      test::GetValueForConfig<StyledString>(table.get(), "android:string/foo", config_masculine);
  ASSERT_NE(nullptr, masculine);
  EXPECT_EQ(1, masculine->value->spans.size());
  EXPECT_EQ(std::string("i"), *masculine->value->spans[0].name);
  EXPECT_EQ(std::string("(M)") + locale->value->value, masculine->value->value);

  auto config_neuter = test::ParseConfigOrDie("en-rXA-neuter");
  config_neuter.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  StyledString* neuter =
      test::GetValueForConfig<StyledString>(table.get(), "android:string/foo", config_neuter);
  ASSERT_NE(nullptr, neuter);
  EXPECT_EQ(1, neuter->value->spans.size());
  EXPECT_EQ(std::string("i"), *neuter->value->spans[0].name);
  EXPECT_EQ(std::string("(N)") + locale->value->value, neuter->value->value);
}

TEST(PseudolocaleGeneratorTest, GrammaticalGenderForCertainValues) {
  // single gender value
  std::unique_ptr<ResourceTable> table_0 =
      test::ResourceTableBuilder().AddString("android:string/foo", "foo").Build();

  std::unique_ptr<IAaptContext> context_0 = test::ContextBuilder().Build();
  PseudolocaleGenerator generator_0(std::string("f"), std::string("1.0"));
  ASSERT_TRUE(generator_0.Consume(context_0.get(), table_0.get()));

  String* locale_0 = test::GetValueForConfig<String>(table_0.get(), "android:string/foo",
                                                     test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, locale_0);

  auto config_feminine = test::ParseConfigOrDie("en-rXA-feminine");
  config_feminine.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  String* feminine_0 =
      test::GetValueForConfig<String>(table_0.get(), "android:string/foo", config_feminine);
  ASSERT_NE(nullptr, feminine_0);
  EXPECT_EQ(std::string("(F)") + *locale_0->value, *feminine_0->value);

  auto config_masculine = test::ParseConfigOrDie("en-rXA-masculine");
  config_masculine.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  String* masculine_0 =
      test::GetValueForConfig<String>(table_0.get(), "android:string/foo", config_masculine);
  EXPECT_EQ(nullptr, masculine_0);

  auto config_neuter = test::ParseConfigOrDie("en-rXA-neuter");
  config_neuter.sdkVersion = android::ResTable_config::SDKVERSION_ANY;
  String* neuter_0 =
      test::GetValueForConfig<String>(table_0.get(), "android:string/foo", config_neuter);
  EXPECT_EQ(nullptr, neuter_0);

  // multiple gender values
  std::unique_ptr<ResourceTable> table_1 =
      test::ResourceTableBuilder().AddString("android:string/foo", "foo").Build();

  std::unique_ptr<IAaptContext> context_1 = test::ContextBuilder().Build();
  PseudolocaleGenerator generator_1(std::string("f,n"), std::string("1.0"));
  ASSERT_TRUE(generator_1.Consume(context_1.get(), table_1.get()));

  String* locale_1 = test::GetValueForConfig<String>(table_1.get(), "android:string/foo",
                                                     test::ParseConfigOrDie("en-rXA"));
  ASSERT_NE(nullptr, locale_1);

  String* feminine_1 =
      test::GetValueForConfig<String>(table_1.get(), "android:string/foo", config_feminine);
  ASSERT_NE(nullptr, feminine_1);
  EXPECT_EQ(std::string("(F)") + *locale_1->value, *feminine_1->value);

  String* masculine_1 =
      test::GetValueForConfig<String>(table_1.get(), "android:string/foo", config_masculine);
  EXPECT_EQ(nullptr, masculine_1);

  String* neuter_1 =
      test::GetValueForConfig<String>(table_1.get(), "android:string/foo", config_neuter);
  ASSERT_NE(nullptr, neuter_1);
  EXPECT_EQ(std::string("(N)") + *locale_1->value, *neuter_1->value);

  // invalid gender value
  std::unique_ptr<ResourceTable> table_2 =
      test::ResourceTableBuilder().AddString("android:string/foo", "foo").Build();

  std::unique_ptr<IAaptContext> context_2 = test::ContextBuilder().Build();
  PseudolocaleGenerator generator_2(std::string("invald,"), std::string("1.0"));
  ASSERT_FALSE(generator_2.Consume(context_2.get(), table_2.get()));
}

}  // namespace aapt
