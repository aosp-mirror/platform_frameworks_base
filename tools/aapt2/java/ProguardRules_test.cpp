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
using ::android::ConfigDescription;
using ::testing::HasSubstr;
using ::testing::Not;

namespace aapt {

std::string GetKeepSetString(const proguard::KeepSet& set, bool minimal_rules) {
  std::string out;
  StringOutputStream sout(&out);
  proguard::WriteKeepSet(set, &sout, minimal_rules, false);
  sout.Flush();
  return out;
}

TEST(ProguardRulesTest, ManifestRuleDefaultConstructorOnly) {
  std::unique_ptr<xml::XmlResource> manifest = test::BuildXmlDom(R"(
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
        <application
            android:appComponentFactory="com.foo.BarAppComponentFactory"
            android:backupAgent="com.foo.BarBackupAgent"
            android:name="com.foo.BarApplication"
            android:zygotePreloadName="com.foo.BarZygotePreload"
            >
          <activity android:name="com.foo.BarActivity"/>
          <service android:name="com.foo.BarService"/>
          <receiver android:name="com.foo.BarReceiver"/>
          <provider android:name="com.foo.BarProvider"/>
        </application>
        <instrumentation android:name="com.foo.BarInstrumentation"/>
      </manifest>)");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRulesForManifest(manifest.get(), &set, false));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarAppComponentFactory { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarBackupAgent { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarApplication { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarActivity { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarService { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarReceiver { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarProvider { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarInstrumentation { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarZygotePreload { <init>(); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarAppComponentFactory { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarBackupAgent { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarApplication { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarActivity { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarService { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarReceiver { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarProvider { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarInstrumentation { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.BarZygotePreload { <init>(); }"));
}

TEST(ProguardRulesTest, FragmentNameRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <fragment xmlns:android="http://schemas.android.com/apk/res/android"
          android:name="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), layout.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(); }"));
}

TEST(ProguardRulesTest, FragmentClassRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout =
      test::BuildXmlDom(R"(<fragment class="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), layout.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(); }"));
}

TEST(ProguardRulesTest, FragmentNameAndClassRulesAreEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <fragment xmlns:android="http://schemas.android.com/apk/res/android"
          android:name="com.foo.Baz"
          class="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), layout.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Baz { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Baz { <init>(); }"));
}

TEST(ProguardRulesTest, FragmentContainerViewNameRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <androidx.fragment.app.FragmentContainerView
          xmlns:android="http://schemas.android.com/apk/res/android"
          android:name="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), layout.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(); }"));
}

TEST(ProguardRulesTest, FragmentContainerViewClassRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout =
      test::BuildXmlDom(R"(<androidx.fragment.app.FragmentContainerView class="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), layout.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(); }"));
}

TEST(ProguardRulesTest, FragmentContainerViewNameAndClassRulesAreEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <androidx.fragment.app.FragmentContainerView
          xmlns:android="http://schemas.android.com/apk/res/android"
          android:name="com.foo.Baz"
          class="com.foo.Bar"/>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), layout.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Baz { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Baz { <init>(); }"));
}

TEST(ProguardRulesTest, NavigationFragmentNameAndClassRulesAreEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder()
      .SetCompilationPackage("com.base").Build();
  std::unique_ptr<xml::XmlResource> navigation = test::BuildXmlDom(R"(
      <navigation
          xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:app="http://schemas.android.com/apk/res-auto">
          <custom android:id="@id/foo"
              android:name="com.package.Foo"/>
          <fragment android:id="@id/bar"
              android:name="com.package.Bar">
              <nested android:id="@id/nested"
                  android:name=".Nested"/>
          </fragment>
      </navigation>
  )");

  navigation->file.name = test::ParseNameOrDie("navigation/graph.xml");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), navigation.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.package.Foo { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.package.Bar { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.base.Nested { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr("-keep class com.package.Foo { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.package.Bar { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.base.Nested { <init>(...); }"));
}

TEST(ProguardRulesTest, CustomViewRulesAreEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android">
        <com.foo.Bar />
      </View>)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), layout.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr(
      "-keep class com.foo.Bar { <init>(android.content.Context, android.util.AttributeSet); }"));
}

TEST(ProguardRulesTest, IncludedLayoutRulesAreConditional) {
  std::unique_ptr<xml::XmlResource> bar_layout = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android">
        <com.foo.Bar />
      </View>)");
  bar_layout->file.name = test::ParseNameOrDie("com.foo:layout/bar");

  ResourceTable table;
  StdErrDiagnostics errDiagnostics;
  table.AddResource(NewResourceBuilder(bar_layout->file.name)
                        .SetValue(util::make_unique<FileReference>())
                        .Build(),
                    &errDiagnostics);

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
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), bar_layout.get(), &set));
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), foo_layout.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-if class **.R$layout"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("int foo"));
  EXPECT_THAT(actual, HasSubstr("int bar"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr("-if class **.R$layout"));
  EXPECT_THAT(actual, HasSubstr(
    "-keep class com.foo.Bar { <init>(android.content.Context, android.util.AttributeSet); }"));
  EXPECT_THAT(actual, HasSubstr("int foo"));
  EXPECT_THAT(actual, HasSubstr("int bar"));
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
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), layout.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr(
      "-keep class com.foo.Bar { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("-if class **.R$layout"));
  EXPECT_THAT(actual, HasSubstr("int foo"));
  EXPECT_THAT(actual, HasSubstr("int bar"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr(
    "-keep class com.foo.Bar { <init>(android.content.Context, android.util.AttributeSet); }"));
  EXPECT_THAT(actual, HasSubstr("-if class **.R$layout"));
  EXPECT_THAT(actual, HasSubstr("int foo"));
  EXPECT_THAT(actual, HasSubstr("int bar"));
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
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), layout.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, Not(HasSubstr("-if")));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, Not(HasSubstr("-if")));
  EXPECT_THAT(actual, HasSubstr(
    "-keep class com.foo.Bar { <init>(android.content.Context, android.util.AttributeSet); }"));
}

TEST(ProguardRulesTest, ViewOnClickRuleIsEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> layout = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:onClick="bar_method" />)");
  layout->file.name = test::ParseNameOrDie("layout/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), layout.get(), &set));

  std::string actual = GetKeepSetString(set,  /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr(
      "-keepclassmembers class * { *** bar_method(android.view.View); }"));

  actual = GetKeepSetString(set,  /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr(
    "-keepclassmembers class * { *** bar_method(android.view.View); }"));
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
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), menu.get(), &set));

  std::string actual = GetKeepSetString(set,  /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr(
    "-keepclassmembers class * { *** on_click(android.view.MenuItem); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Baz { <init>(...); }"));
  EXPECT_THAT(actual, Not(HasSubstr("com.foo.Bat")));

  actual = GetKeepSetString(set,  /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr(
    "-keepclassmembers class * { *** on_click(android.view.MenuItem); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(android.content.Context); }"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Baz { <init>(android.content.Context); }"));
  EXPECT_THAT(actual, Not(HasSubstr("com.foo.Bat")));
}

TEST(ProguardRulesTest, MenuRulesAreEmittedForActionClasses) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> menu = test::BuildXmlDom(R"(
      <menu xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto">
        <item android:id="@+id/my_item"
            app:actionViewClass="com.foo.Bar"
            app:actionProviderClass="com.foo.Baz" />
      </menu>)");
  menu->file.name = test::ParseNameOrDie("menu/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), menu.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar"));
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Baz"));
}

TEST(ProguardRulesTest, TransitionPathMotionRulesAreEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> transition = test::BuildXmlDom(R"(
      <changeBounds>
        <pathMotion class="com.foo.Bar"/>
      </changeBounds>)");
  transition->file.name = test::ParseNameOrDie("transition/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), transition.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr(
    "-keep class com.foo.Bar { <init>(android.content.Context, android.util.AttributeSet); }"));
}

TEST(ProguardRulesTest, TransitionRulesAreEmitted) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<xml::XmlResource> transitionSet = test::BuildXmlDom(R"(
      <transitionSet>
        <transition class="com.foo.Bar"/>
      </transitionSet>)");
  transitionSet->file.name = test::ParseNameOrDie("transition/foo");

  proguard::KeepSet set;
  ASSERT_TRUE(proguard::CollectProguardRules(context.get(), transitionSet.get(), &set));

  std::string actual = GetKeepSetString(set, /** minimal_rules */ false);
  EXPECT_THAT(actual, HasSubstr("-keep class com.foo.Bar { <init>(...); }"));

  actual = GetKeepSetString(set, /** minimal_rules */ true);
  EXPECT_THAT(actual, HasSubstr(
    "-keep class com.foo.Bar { <init>(android.content.Context, android.util.AttributeSet); }"));
}

TEST(ProguardRulesTest, UsageLocationComparator) {
  proguard::UsageLocation location1 = {{"pkg", ResourceType::kAttr, "x"}};
  proguard::UsageLocation location2 = {{"pkg", ResourceType::kAttr, "y"}};

  EXPECT_EQ(location1 < location2, true);
  EXPECT_EQ(location2 < location1, false);
}

}  // namespace aapt
