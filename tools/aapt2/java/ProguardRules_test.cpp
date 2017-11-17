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
#include "link/Linkers.h"

#include "io/StringStream.h"
#include "test/Test.h"

using ::aapt::io::StringOutputStream;
using ::testing::HasSubstr;
using ::testing::Not;

namespace aapt {

std::string GetKeepSetString(const proguard::KeepSet& set) {
  std::string out;
  StringOutputStream sout(&out);
  proguard::WriteKeepSet(set, &sout);
  sout.Flush();
  return out;
}

TEST(ProguardRulesTest, FragmentNameRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <fragment xmlns:android="http://schemas.android.com/apk/res/android"
          android:name="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(layout.get(), &set));

  std::string actual = GetKeepSetString(set);

  EXPECT_THAT(actual, HasSubstr("com.foo.Bar"));
}

TEST(ProguardRulesTest, FragmentClassRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout =
      test::BuildXmlDom(R"(<fragment class="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(layout.get(), &set));

  std::string actual = GetKeepSetString(set);

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
  ASSERT_TRUE(proguard::CollectProguardRules(layout.get(), &set));

  std::string actual = GetKeepSetString(set);

  EXPECT_THAT(actual, HasSubstr("com.foo.Bar"));
  EXPECT_THAT(actual, HasSubstr("com.foo.Baz"));
}

TEST(ProguardRulesTest, CustomViewRulesAreEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android">
        <com.foo.Bar />
      </View>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(layout.get(), &set));

  std::string actual = GetKeepSetString(set);

  EXPECT_THAT(actual, HasSubstr("com.foo.Bar"));
}

TEST(ProguardRulesTest, IncludedLayoutRulesAreConditional) {
  std::unique_ptr<xml::XmlResource> bar_layout = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android">
        <com.foo.Bar />
      </View>)");
  bar_layout->file.name = test::ParseNameOrDie("com.foo:layout/bar");

  ResourceTable table;
  StdErrDiagnostics errDiagnostics;
  table.AddResource(bar_layout->file.name, ConfigDescription::DefaultConfig(), "",
                    util::make_unique<FileReference>(), &errDiagnostics);

  std::unique_ptr<IAaptContext> context =
      test::ContextBuilder()
          .SetCompilationPackage("com.foo")
          .AddSymbolSource(util::make_unique<ResourceTableSymbolSource>(&table))
          .Build();

  std::unique_ptr<xml::XmlResource> foo_layout = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android">
        <include layout="@layout/bar" />
      </View>)");
  foo_layout->file.name = test::ParseNameOrDie("com.foo:layout/foo");

  XmlReferenceLinker xml_linker;
  ASSERT_TRUE(xml_linker.Consume(context.get(), bar_layout.get()));
  ASSERT_TRUE(xml_linker.Consume(context.get(), foo_layout.get()));

  proguard::KeepSet set = proguard::KeepSet(true);
  ASSERT_TRUE(proguard::CollectProguardRules(bar_layout.get(), &set));
  ASSERT_TRUE(proguard::CollectProguardRules(foo_layout.get(), &set));

  std::string actual = GetKeepSetString(set);

  EXPECT_THAT(actual, HasSubstr("-if class **.R$layout"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("int foo"));
  EXPECT_THAT(actual, HasSubstr("int bar"));
  EXPECT_THAT(actual, HasSubstr("com.foo.Bar"));
}

TEST(ProguardRulesTest, AliasedLayoutRulesAreConditional) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android">
        <com.foo.Bar />
      </View>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set = proguard::KeepSet(true);
  set.AddReference({test::ParseNameOrDie("layout/bar"), {}}, layout->file.name);
  ASSERT_TRUE(proguard::CollectProguardRules(layout.get(), &set));

  std::string actual = GetKeepSetString(set);

  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("-if class **.R$layout"));
  EXPECT_THAT(actual, HasSubstr("int foo"));
  EXPECT_THAT(actual, HasSubstr("int bar"));
  EXPECT_THAT(actual, HasSubstr("com.foo.Bar"));
}

TEST(ProguardRulesTest, NonLayoutReferencesAreUnconditional) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android">
        <com.foo.Bar />
      </View>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set = proguard::KeepSet(true);
  set.AddReference({test::ParseNameOrDie("style/MyStyle"), {}}, layout->file.name);
  ASSERT_TRUE(proguard::CollectProguardRules(layout.get(), &set));

  std::string actual = GetKeepSetString(set);

  EXPECT_THAT(actual, Not(HasSubstr("-if")));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));
}

TEST(ProguardRulesTest, ViewOnClickRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:onClick="bar_method" />)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(layout.get(), &set));

  std::string actual = GetKeepSetString(set);

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
  ASSERT_TRUE(proguard::CollectProguardRules(menu.get(), &set));

  std::string actual = GetKeepSetString(set);

  EXPECT_THAT(actual, HasSubstr("on_click"));
  EXPECT_THAT(actual, HasSubstr("com.foo.Bar"));
  EXPECT_THAT(actual, HasSubstr("com.foo.Baz"));
  EXPECT_THAT(actual, Not(HasSubstr("com.foo.Bat")));
}

}  // namespace aapt
