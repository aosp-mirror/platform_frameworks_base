/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "java/ProguardRules.h"

#include "test/Test.h"

using ::testing::HasSubstr;
using ::testing::Not;

namespace aapt {

TEST(ProguardRulesTest, FragmentNameRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <fragment xmlns:android="http://schemas.android.com/apk/res/android"
          android:name="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules({}, layout.get(), &set));

  std::stringstream out;
  ASSERT_TRUE(proguard::WriteKeepSet(&out, set));

  std::string actual = out.str();
  EXPECT_THAT(actual, HasSubstr("com.foo.Bar"));
}

TEST(ProguardRulesTest, FragmentClassRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout =
      test::BuildXmlDom(R"(<fragment class="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules({}, layout.get(), &set));

  std::stringstream out;
  ASSERT_TRUE(proguard::WriteKeepSet(&out, set));

  std::string actual = out.str();
  EXPECT_THAT(actual, HasSubstr("com.foo.Bar"));
}

TEST(ProguardRulesTest, FragmentNameAndClassRulesAreEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <fragment xmlns:android="http://schemas.android.com/apk/res/android"
          android:name="com.foo.Baz"
          class="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules({}, layout.get(), &set));

  std::stringstream out;
  ASSERT_TRUE(proguard::WriteKeepSet(&out, set));

  std::string actual = out.str();
  EXPECT_THAT(actual, HasSubstr("com.foo.Bar"));
  EXPECT_THAT(actual, HasSubstr("com.foo.Baz"));
}

TEST(ProguardRulesTest, ViewOnClickRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:onClick="bar_method" />)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules({}, layout.get(), &set));

  std::stringstream out;
  ASSERT_TRUE(proguard::WriteKeepSet(&out, set));

  std::string actual = out.str();
  EXPECT_THAT(actual, HasSubstr("bar_method"));
}

TEST(ProguardRulesTest, MenuRulesAreEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> menu = test::BuildXmlDom(R"(
      <menu xmlns:android="http://schemas.android.com/apk/res/android">
        <item android:onClick="on_click"
            android:actionViewClass="com.foo.Bar"
            android:actionProviderClass="com.foo.Baz"
            android:name="com.foo.Bat" />
      </menu>)");
  menu->file.name = test::ParseNameOrDie("menu/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules({}, menu.get(), &set));

  std::stringstream out;
  ASSERT_TRUE(proguard::WriteKeepSet(&out, set));

  std::string actual = out.str();
  EXPECT_THAT(actual, HasSubstr("on_click"));
  EXPECT_THAT(actual, HasSubstr("com.foo.Bar"));
  EXPECT_THAT(actual, HasSubstr("com.foo.Baz"));
  EXPECT_THAT(actual, Not(HasSubstr("com.foo.Bat")));
}

}  // namespace aapt
