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

using ::android::ConfigDescription;

namespace aapt {

TEST(ProductFilterTest, SelectTwoProducts) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  const ConfigDescription land = test::ParseConfigOrDie("land");
  const ConfigDescription port = test::ParseConfigOrDie("port");

  ResourceTable table;
  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"), land, "",
      test::ValueBuilder<Id>().SetSource(Source("land/default.xml")).Build(),
      context->GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"), land, "tablet",
      test::ValueBuilder<Id>().SetSource(Source("land/tablet.xml")).Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"), port, "",
      test::ValueBuilder<Id>().SetSource(Source("port/default.xml")).Build(),
      context->GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"), port, "tablet",
      test::ValueBuilder<Id>().SetSource(Source("port/tablet.xml")).Build(),
      context->GetDiagnostics()));

  ProductFilter filter({"tablet"});
  ASSERT_TRUE(filter.Consume(context.get(), &table));

  EXPECT_EQ(nullptr, test::GetValueForConfigAndProduct<Id>(
                         &table, "android:string/one", land, ""));
  EXPECT_NE(nullptr, test::GetValueForConfigAndProduct<Id>(
                         &table, "android:string/one", land, "tablet"));
  EXPECT_EQ(nullptr, test::GetValueForConfigAndProduct<Id>(
                         &table, "android:string/one", port, ""));
  EXPECT_NE(nullptr, test::GetValueForConfigAndProduct<Id>(
                         &table, "android:string/one", port, "tablet"));
}

TEST(ProductFilterTest, SelectDefaultProduct) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  ResourceTable table;
  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"),
      ConfigDescription::DefaultConfig(), "",
      test::ValueBuilder<Id>().SetSource(Source("default.xml")).Build(),
      context->GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"),
      ConfigDescription::DefaultConfig(), "tablet",
      test::ValueBuilder<Id>().SetSource(Source("tablet.xml")).Build(),
      context->GetDiagnostics()));

  ProductFilter filter(std::unordered_set<std::string>{});
  ASSERT_TRUE(filter.Consume(context.get(), &table));

  EXPECT_NE(nullptr, test::GetValueForConfigAndProduct<Id>(
                         &table, "android:string/one",
                         ConfigDescription::DefaultConfig(), ""));
  EXPECT_EQ(nullptr, test::GetValueForConfigAndProduct<Id>(
                         &table, "android:string/one",
                         ConfigDescription::DefaultConfig(), "tablet"));
}

TEST(ProductFilterTest, FailOnAmbiguousProduct) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  ResourceTable table;
  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"),
      ConfigDescription::DefaultConfig(), "",
      test::ValueBuilder<Id>().SetSource(Source("default.xml")).Build(),
      context->GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"),
      ConfigDescription::DefaultConfig(), "tablet",
      test::ValueBuilder<Id>().SetSource(Source("tablet.xml")).Build(),
      context->GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"),
      ConfigDescription::DefaultConfig(), "no-sdcard",
      test::ValueBuilder<Id>().SetSource(Source("no-sdcard.xml")).Build(),
      context->GetDiagnostics()));

  ProductFilter filter({"tablet", "no-sdcard"});
  ASSERT_FALSE(filter.Consume(context.get(), &table));
}

TEST(ProductFilterTest, FailOnMultipleDefaults) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  ResourceTable table;
  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"),
      ConfigDescription::DefaultConfig(), "",
      test::ValueBuilder<Id>().SetSource(Source(".xml")).Build(),
      context->GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(
      test::ParseNameOrDie("android:string/one"),
      ConfigDescription::DefaultConfig(), "default",
      test::ValueBuilder<Id>().SetSource(Source("default.xml")).Build(),
      context->GetDiagnostics()));

  ProductFilter filter(std::unordered_set<std::string>{});
  ASSERT_FALSE(filter.Consume(context.get(), &table));
}

}  // namespace aapt
