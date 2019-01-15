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

#include "optimize/MultiApkGenerator.h"

#include <string>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "LoadedApk.h"
#include "ResourceTable.h"
#include "configuration/ConfigurationParser.h"
#include "filter/Filter.h"
#include "format/Archive.h"
#include "format/binary/TableFlattener.h"
#include "process/IResourceTableConsumer.h"
#include "test/Context.h"
#include "test/Test.h"

using ::android::ConfigDescription;

namespace aapt {
namespace {

using ::aapt::configuration::Abi;
using ::aapt::configuration::AndroidSdk;
using ::aapt::configuration::OutputArtifact;
using ::aapt::test::GetValue;
using ::aapt::test::GetValueForConfig;
using ::aapt::test::ParseConfigOrDie;
using ::testing::Eq;
using ::testing::IsNull;
using ::testing::Not;
using ::testing::NotNull;
using ::testing::PrintToString;
using ::testing::Return;
using ::testing::Test;

/**
 * Subclass the MultiApkGenerator class so that we can access the protected FilterTable method to
 * directly test table filter.
 */
class MultiApkGeneratorWrapper : public MultiApkGenerator {
 public:
  MultiApkGeneratorWrapper(LoadedApk* apk, IAaptContext* context)
      : MultiApkGenerator(apk, context) {
  }

  std::unique_ptr<ResourceTable> FilterTable(IAaptContext* context,
                                             const configuration::OutputArtifact& artifact,
                                             const ResourceTable& old_table,
                                             FilterChain* filter_chain) override {
    return MultiApkGenerator::FilterTable(context, artifact, old_table, filter_chain);
  }
};

/** MultiApkGenerator test fixture. */
class MultiApkGeneratorTest : public ::testing::Test {
 public:
  std::unique_ptr<ResourceTable> BuildTable() {
    return test::ResourceTableBuilder()
        .AddFileReference(kResourceName, "res/drawable-mdpi/icon.png", mdpi_)
        .AddFileReference(kResourceName, "res/drawable-hdpi/icon.png", hdpi_)
        .AddFileReference(kResourceName, "res/drawable-xhdpi/icon.png", xhdpi_)
        .AddFileReference(kResourceName, "res/drawable-xxhdpi/icon.png", xxhdpi_)
        .AddFileReference(kResourceName, "res/drawable-v19/icon.xml", v19_)
        .AddFileReference(kResourceName, "res/drawable-v21/icon.xml", v21_)
        .AddSimple("android:string/one")
        .Build();
  }

  inline FileReference* ValueForConfig(ResourceTable* table, const ConfigDescription& config) {
    return GetValueForConfig<FileReference>(table, kResourceName, config);
  };

  void SetUp() override {
  }

 protected:
  static constexpr const char* kResourceName = "android:drawable/icon";

  ConfigDescription default_ = ParseConfigOrDie("").CopyWithoutSdkVersion();
  ConfigDescription mdpi_ = ParseConfigOrDie("mdpi").CopyWithoutSdkVersion();
  ConfigDescription hdpi_ = ParseConfigOrDie("hdpi").CopyWithoutSdkVersion();
  ConfigDescription xhdpi_ = ParseConfigOrDie("xhdpi").CopyWithoutSdkVersion();
  ConfigDescription xxhdpi_ = ParseConfigOrDie("xxhdpi").CopyWithoutSdkVersion();
  ConfigDescription xxxhdpi_ = ParseConfigOrDie("xxxhdpi").CopyWithoutSdkVersion();
  ConfigDescription v19_ = ParseConfigOrDie("v19");
  ConfigDescription v21_ = ParseConfigOrDie("v21");
};

TEST_F(MultiApkGeneratorTest, VersionFilterNewerVersion) {
  std::unique_ptr<ResourceTable> table = BuildTable();

  LoadedApk apk = {{"test.apk"}, {}, std::move(table), {}, kBinary};
  std::unique_ptr<IAaptContext> ctx = test::ContextBuilder().SetMinSdkVersion(19).Build();
  FilterChain chain;

  OutputArtifact artifact = test::ArtifactBuilder().AddDensity(xhdpi_).SetAndroidSdk(23).Build();

  MultiApkGeneratorWrapper generator{&apk, ctx.get()};
  std::unique_ptr<ResourceTable> split =
      generator.FilterTable(ctx.get(), artifact, *apk.GetResourceTable(), &chain);

  ResourceTable* new_table = split.get();
  EXPECT_THAT(ValueForConfig(new_table, mdpi_), IsNull());
  EXPECT_THAT(ValueForConfig(new_table, hdpi_), IsNull());
  EXPECT_THAT(ValueForConfig(new_table, xxhdpi_), IsNull());
  EXPECT_THAT(ValueForConfig(new_table, xxxhdpi_), IsNull());
  EXPECT_THAT(ValueForConfig(new_table, v19_), IsNull());

  // xhdpi directly matches one of the required dimensions.
  EXPECT_THAT(ValueForConfig(new_table, xhdpi_), NotNull());
  // drawable-v21 was converted to drawable.
  EXPECT_THAT(ValueForConfig(new_table, default_), NotNull());
  EXPECT_THAT(GetValue<Id>(new_table, "android:string/one"), NotNull());
}

TEST_F(MultiApkGeneratorTest, VersionFilterOlderVersion) {
  std::unique_ptr<ResourceTable> table = BuildTable();

  LoadedApk apk = {{"test.apk"}, {}, std::move(table), {}, kBinary};
  std::unique_ptr<IAaptContext> ctx = test::ContextBuilder().SetMinSdkVersion(1).Build();
  FilterChain chain;

  OutputArtifact artifact = test::ArtifactBuilder().AddDensity(xhdpi_).SetAndroidSdk(4).Build();

  MultiApkGeneratorWrapper generator{&apk, ctx.get()};;
  std::unique_ptr<ResourceTable> split =
      generator.FilterTable(ctx.get(), artifact, *apk.GetResourceTable(), &chain);

  ResourceTable* new_table = split.get();
  EXPECT_THAT(ValueForConfig(new_table, mdpi_), IsNull());
  EXPECT_THAT(ValueForConfig(new_table, hdpi_), IsNull());
  EXPECT_THAT(ValueForConfig(new_table, xxhdpi_), IsNull());
  EXPECT_THAT(ValueForConfig(new_table, xxxhdpi_), IsNull());

  EXPECT_THAT(ValueForConfig(new_table, xhdpi_), NotNull());
  EXPECT_THAT(ValueForConfig(new_table, v19_), NotNull());
  EXPECT_THAT(ValueForConfig(new_table, v21_), NotNull());
  EXPECT_THAT(GetValue<Id>(new_table, "android:string/one"), NotNull());
}

TEST_F(MultiApkGeneratorTest, VersionFilterNoVersion) {
  std::unique_ptr<ResourceTable> table = BuildTable();

  LoadedApk apk = {{"test.apk"}, {}, std::move(table), {}, kBinary};
  std::unique_ptr<IAaptContext> ctx = test::ContextBuilder().SetMinSdkVersion(1).Build();
  FilterChain chain;

  OutputArtifact artifact = test::ArtifactBuilder().AddDensity(xhdpi_).Build();

  MultiApkGeneratorWrapper generator{&apk, ctx.get()};
  std::unique_ptr<ResourceTable> split =
      generator.FilterTable(ctx.get(), artifact, *apk.GetResourceTable(), &chain);

  ResourceTable* new_table = split.get();
  EXPECT_THAT(ValueForConfig(new_table, mdpi_), IsNull());
  EXPECT_THAT(ValueForConfig(new_table, hdpi_), IsNull());
  EXPECT_THAT(ValueForConfig(new_table, xxhdpi_), IsNull());
  EXPECT_THAT(ValueForConfig(new_table, xxxhdpi_), IsNull());

  EXPECT_THAT(ValueForConfig(new_table, xhdpi_), NotNull());
  EXPECT_THAT(ValueForConfig(new_table, v19_), NotNull());
  EXPECT_THAT(ValueForConfig(new_table, v21_), NotNull());
  EXPECT_THAT(GetValue<Id>(new_table, "android:string/one"), NotNull());
}

}  // namespace
}  // namespace aapt
