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

#include "split/TableSplitter.h"

#include "test/Test.h"

using ::android::ConfigDescription;

namespace aapt {

TEST(TableSplitterTest, NoSplitPreferredDensity) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:drawable/icon",
                            "res/drawable-mdpi/icon.png",
                            test::ParseConfigOrDie("mdpi"))
          .AddFileReference("android:drawable/icon",
                            "res/drawable-hdpi/icon.png",
                            test::ParseConfigOrDie("hdpi"))
          .AddFileReference("android:drawable/icon",
                            "res/drawable-xhdpi/icon.png",
                            test::ParseConfigOrDie("xhdpi"))
          .AddFileReference("android:drawable/icon",
                            "res/drawable-xxhdpi/icon.png",
                            test::ParseConfigOrDie("xxhdpi"))
          .AddSimple("android:string/one")
          .Build();

  TableSplitterOptions options;
  options.preferred_densities.push_back(ConfigDescription::DENSITY_XHIGH);
  TableSplitter splitter({}, options);
  splitter.SplitTable(table.get());

  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/icon",
                         test::ParseConfigOrDie("mdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/icon",
                         test::ParseConfigOrDie("hdpi")));
  EXPECT_NE(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/icon",
                         test::ParseConfigOrDie("xhdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/icon",
                         test::ParseConfigOrDie("xxhdpi")));
  EXPECT_NE(nullptr, test::GetValue<Id>(table.get(), "android:string/one"));
}

TEST(TableSplitterTest, NoSplitMultiplePreferredDensities) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:drawable/icon",
                            "res/drawable-mdpi/icon.png",
                            test::ParseConfigOrDie("mdpi"))
          .AddFileReference("android:drawable/icon",
                            "res/drawable-hdpi/icon.png",
                            test::ParseConfigOrDie("hdpi"))
          .AddFileReference("android:drawable/icon",
                            "res/drawable-xhdpi/icon.png",
                            test::ParseConfigOrDie("xhdpi"))
          .AddFileReference("android:drawable/icon",
                            "res/drawable-xxhdpi/icon.png",
                            test::ParseConfigOrDie("xxhdpi"))
          .AddSimple("android:string/one")
          .Build();

  TableSplitterOptions options;
  options.preferred_densities.push_back(ConfigDescription::DENSITY_LOW);
  options.preferred_densities.push_back(ConfigDescription::DENSITY_XXXHIGH);
  TableSplitter splitter({}, options);
  splitter.SplitTable(table.get());

  // Densities remaining:
  // "mdpi" is the closest available density for the requested "ldpi" density.
  EXPECT_NE(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/icon",
                         test::ParseConfigOrDie("mdpi")));
  // "xxhdpi" is the closest available density for the requested "xxxhdpi" density.
  EXPECT_NE(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/icon",
                         test::ParseConfigOrDie("xxhdpi")));
  EXPECT_NE(nullptr, test::GetValue<Id>(table.get(), "android:string/one"));

  // Removed densities:
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/icon",
                         test::ParseConfigOrDie("hdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/icon",
                         test::ParseConfigOrDie("xhdpi")));
}


TEST(TableSplitterTest, SplitTableByDensity) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:drawable/foo", "res/drawable-mdpi/foo.png",
                            test::ParseConfigOrDie("mdpi"))
          .AddFileReference("android:drawable/foo", "res/drawable-hdpi/foo.png",
                            test::ParseConfigOrDie("hdpi"))
          .AddFileReference("android:drawable/foo",
                            "res/drawable-xhdpi/foo.png",
                            test::ParseConfigOrDie("xhdpi"))
          .AddFileReference("android:drawable/foo",
                            "res/drawable-xxhdpi/foo.png",
                            test::ParseConfigOrDie("xxhdpi"))
          .Build();

  std::vector<SplitConstraints> constraints;
  constraints.push_back(SplitConstraints{{test::ParseConfigOrDie("mdpi")}});
  constraints.push_back(SplitConstraints{{test::ParseConfigOrDie("hdpi")}});
  constraints.push_back(SplitConstraints{{test::ParseConfigOrDie("xhdpi")}});

  TableSplitter splitter(constraints, TableSplitterOptions{});
  splitter.SplitTable(table.get());

  ASSERT_EQ(3u, splitter.splits().size());

  ResourceTable* split_one = splitter.splits()[0].get();
  ResourceTable* split_two = splitter.splits()[1].get();
  ResourceTable* split_three = splitter.splits()[2].get();

  // Just xxhdpi should be in the base.
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/foo",
                         test::ParseConfigOrDie("mdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/foo",
                         test::ParseConfigOrDie("hdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/foo",
                         test::ParseConfigOrDie("xhdpi")));
  EXPECT_NE(nullptr, test::GetValueForConfig<FileReference>(
                         table.get(), "android:drawable/foo",
                         test::ParseConfigOrDie("xxhdpi")));

  // Each split should have one and only one drawable.
  EXPECT_NE(nullptr, test::GetValueForConfig<FileReference>(
                         split_one, "android:drawable/foo",
                         test::ParseConfigOrDie("mdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         split_one, "android:drawable/foo",
                         test::ParseConfigOrDie("hdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         split_one, "android:drawable/foo",
                         test::ParseConfigOrDie("xhdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         split_one, "android:drawable/foo",
                         test::ParseConfigOrDie("xxhdpi")));

  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         split_two, "android:drawable/foo",
                         test::ParseConfigOrDie("mdpi")));
  EXPECT_NE(nullptr, test::GetValueForConfig<FileReference>(
                         split_two, "android:drawable/foo",
                         test::ParseConfigOrDie("hdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         split_two, "android:drawable/foo",
                         test::ParseConfigOrDie("xhdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         split_two, "android:drawable/foo",
                         test::ParseConfigOrDie("xxhdpi")));

  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         split_three, "android:drawable/foo",
                         test::ParseConfigOrDie("mdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         split_three, "android:drawable/foo",
                         test::ParseConfigOrDie("hdpi")));
  EXPECT_NE(nullptr, test::GetValueForConfig<FileReference>(
                         split_three, "android:drawable/foo",
                         test::ParseConfigOrDie("xhdpi")));
  EXPECT_EQ(nullptr, test::GetValueForConfig<FileReference>(
                         split_three, "android:drawable/foo",
                         test::ParseConfigOrDie("xxhdpi")));
}

TEST(TableSplitterTest, SplitTableByConfigAndDensity) {
  ResourceTable table;

  const ResourceName foo = test::ParseNameOrDie("android:string/foo");
  ASSERT_TRUE(table.AddResource(foo, test::ParseConfigOrDie("land-hdpi"), {},
                                util::make_unique<Id>(),
                                test::GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(foo, test::ParseConfigOrDie("land-xhdpi"), {},
                                util::make_unique<Id>(),
                                test::GetDiagnostics()));
  ASSERT_TRUE(table.AddResource(foo, test::ParseConfigOrDie("land-xxhdpi"), {},
                                util::make_unique<Id>(),
                                test::GetDiagnostics()));

  std::vector<SplitConstraints> constraints;
  constraints.push_back(
      SplitConstraints{{test::ParseConfigOrDie("land-mdpi")}});
  constraints.push_back(
      SplitConstraints{{test::ParseConfigOrDie("land-xhdpi")}});

  TableSplitter splitter(constraints, TableSplitterOptions{});
  splitter.SplitTable(&table);

  ASSERT_EQ(2u, splitter.splits().size());

  ResourceTable* split_one = splitter.splits()[0].get();
  ResourceTable* split_two = splitter.splits()[1].get();

  // All but the xxhdpi resource should be gone, since there were closer matches
  // in land-xhdpi.
  EXPECT_EQ(nullptr,
            test::GetValueForConfig<Id>(&table, "android:string/foo",
                                        test::ParseConfigOrDie("land-hdpi")));
  EXPECT_EQ(nullptr,
            test::GetValueForConfig<Id>(&table, "android:string/foo",
                                        test::ParseConfigOrDie("land-xhdpi")));
  EXPECT_NE(nullptr,
            test::GetValueForConfig<Id>(&table, "android:string/foo",
                                        test::ParseConfigOrDie("land-xxhdpi")));

  EXPECT_NE(nullptr,
            test::GetValueForConfig<Id>(split_one, "android:string/foo",
                                        test::ParseConfigOrDie("land-hdpi")));
  EXPECT_EQ(nullptr,
            test::GetValueForConfig<Id>(split_one, "android:string/foo",
                                        test::ParseConfigOrDie("land-xhdpi")));
  EXPECT_EQ(nullptr,
            test::GetValueForConfig<Id>(split_one, "android:string/foo",
                                        test::ParseConfigOrDie("land-xxhdpi")));

  EXPECT_EQ(nullptr,
            test::GetValueForConfig<Id>(split_two, "android:string/foo",
                                        test::ParseConfigOrDie("land-hdpi")));
  EXPECT_NE(nullptr,
            test::GetValueForConfig<Id>(split_two, "android:string/foo",
                                        test::ParseConfigOrDie("land-xhdpi")));
  EXPECT_EQ(nullptr,
            test::GetValueForConfig<Id>(split_two, "android:string/foo",
                                        test::ParseConfigOrDie("land-xxhdpi")));
}

}  // namespace aapt
