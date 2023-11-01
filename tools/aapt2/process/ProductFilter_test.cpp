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

#include "process/ProductFilter.h"

#include "test/Test.h"

using ::android::ConfigDescription;

namespace aapt {

TEST(ProductFilterTest, SelectTwoProducts) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  const ConfigDescription land = test::ParseConfigOrDie("land");
  const ConfigDescription port = test::ParseConfigOrDie("port");

  ResourceTable table;
  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("land/default.xml")).Build(),
                    land)
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("land/tablet.xml")).Build(),
                    land, "tablet")
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("port/default.xml")).Build(),
                    port)
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("port/tablet.xml")).Build(),
                    port, "tablet")
          .Build(),
      context->GetDiagnostics()));

  ProductFilter filter({"tablet"}, /* remove_default_config_values = */ false);
  ASSERT_TRUE(filter.Consume(context.get(), &table));

  EXPECT_EQ(nullptr, test::GetValueForConfigAndProduct<Id>(&table, "android:string/one", land, ""));
  EXPECT_NE(nullptr,
            test::GetValueForConfigAndProduct<Id>(&table, "android:string/one", land, "tablet"));
  EXPECT_EQ(nullptr, test::GetValueForConfigAndProduct<Id>(&table, "android:string/one", port, ""));
  EXPECT_NE(nullptr,
            test::GetValueForConfigAndProduct<Id>(&table, "android:string/one", port, "tablet"));
}

TEST(ProductFilterTest, SelectDefaultProduct) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  ResourceTable table;
  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("default.xml")).Build())
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("tablet.xml")).Build(), {},
                    "tablet")
          .Build(),
      context->GetDiagnostics()));
  ;

  ProductFilter filter(std::unordered_set<std::string>{},
                       /* remove_default_config_values = */ false);
  ASSERT_TRUE(filter.Consume(context.get(), &table));

  EXPECT_NE(nullptr, test::GetValueForConfigAndProduct<Id>(&table, "android:string/one",
                                                           ConfigDescription::DefaultConfig(), ""));
  EXPECT_EQ(nullptr,
            test::GetValueForConfigAndProduct<Id>(&table, "android:string/one",
                                                  ConfigDescription::DefaultConfig(), "tablet"));
}

TEST(ProductFilterTest, FailOnAmbiguousProduct) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  ResourceTable table;
  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("default.xml")).Build())
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("tablet.xml")).Build(), {},
                    "tablet")
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("no-sdcard.xml")).Build(),
                    {}, "no-sdcard")
          .Build(),
      context->GetDiagnostics()));

  ProductFilter filter({"tablet", "no-sdcard"}, /* remove_default_config_values = */ false);
  ASSERT_FALSE(filter.Consume(context.get(), &table));
}

TEST(ProductFilterTest, FailOnMultipleDefaults) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  ResourceTable table;
  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source(".xml")).Build())
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("default.xml")).Build(), {},
                    "default")
          .Build(),
      context->GetDiagnostics()));

  ProductFilter filter(std::unordered_set<std::string>{},
                       /* remove_default_config_values = */ false);
  ASSERT_FALSE(filter.Consume(context.get(), &table));
}

TEST(ProductFilterTest, RemoveDefaultConfigValues) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  const ConfigDescription land = test::ParseConfigOrDie("land");
  const ConfigDescription port = test::ParseConfigOrDie("port");

  ResourceTable table;
  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("land/default.xml")).Build(),
                    land)
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("land/tablet.xml")).Build(),
                    land, "tablet")
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/two"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("land/default.xml")).Build(),
                    land)
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("port/default.xml")).Build(),
                    port)
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/one"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("port/tablet.xml")).Build(),
                    port, "tablet")
          .Build(),
      context->GetDiagnostics()));

  ASSERT_TRUE(table.AddResource(
      NewResourceBuilder(test::ParseNameOrDie("android:string/two"))
          .SetValue(test::ValueBuilder<Id>().SetSource(android::Source("port/default.xml")).Build(),
                    port)
          .Build(),
      context->GetDiagnostics()));

  ProductFilter filter({"tablet"}, /* remove_default_config_values = */ true);
  ASSERT_TRUE(filter.Consume(context.get(), &table));

  EXPECT_NE(nullptr, test::GetValueForConfigAndProduct<Id>(&table, "android:string/one", land, ""));
  EXPECT_EQ(nullptr, test::GetValueForConfigAndProduct<Id>(&table, "android:string/two", land, ""));
  EXPECT_NE(nullptr, test::GetValueForConfigAndProduct<Id>(&table, "android:string/one", port, ""));
  EXPECT_EQ(nullptr, test::GetValueForConfigAndProduct<Id>(&table, "android:string/two", port, ""));
}

}  // namespace aapt
