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
#include "flatten/Archive.h"
#include "flatten/TableFlattener.h"
#include "process/IResourceTableConsumer.h"
#include "test/Context.h"
#include "test/Test.h"

namespace aapt {
namespace {

using ::aapt::configuration::Abi;
using ::aapt::configuration::Artifact;
using ::aapt::configuration::PostProcessingConfiguration;

using ::testing::Eq;
using ::testing::Return;
using ::testing::_;

class MockApk : public LoadedApk {
 public:
  MockApk(std::unique_ptr<ResourceTable> table) : LoadedApk({"test.apk"}, {}, std::move(table)){};
  MOCK_METHOD5(WriteToArchive, bool(IAaptContext*, ResourceTable*, const TableFlattenerOptions&,
                                    FilterChain*, IArchiveWriter*));
};

TEST(MultiApkGeneratorTest, FromBaseApk) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddFileReference("android:drawable/icon", "res/drawable-mdpi/icon.png",
                            test::ParseConfigOrDie("mdpi"))
          .AddFileReference("android:drawable/icon", "res/drawable-hdpi/icon.png",
                            test::ParseConfigOrDie("hdpi"))
          .AddFileReference("android:drawable/icon", "res/drawable-xhdpi/icon.png",
                            test::ParseConfigOrDie("xhdpi"))
          .AddFileReference("android:drawable/icon", "res/drawable-xxhdpi/icon.png",
                            test::ParseConfigOrDie("xxhdpi"))
          .AddSimple("android:string/one")
          .Build();

  MockApk apk{std::move(table)};

  EXPECT_CALL(apk, WriteToArchive(_, _, _, _, _)).Times(0);

  test::Context ctx;
  PostProcessingConfiguration empty_config;
  TableFlattenerOptions table_flattener_options;

  MultiApkGenerator generator{&apk, &ctx};
  EXPECT_TRUE(generator.FromBaseApk("out", empty_config, table_flattener_options));

  Artifact x64 = test::ArtifactBuilder()
                     .SetName("${basename}.x64.apk")
                     .SetAbiGroup("x64")
                     .SetLocaleGroup("en")
                     .SetDensityGroup("xhdpi")
                     .Build();

  Artifact intel = test::ArtifactBuilder()
                       .SetName("${basename}.intel.apk")
                       .SetAbiGroup("intel")
                       .SetLocaleGroup("europe")
                       .SetDensityGroup("large")
                       .Build();

  auto config = test::PostProcessingConfigurationBuilder()
                    .SetLocaleGroup("en", {"en"})
                    .SetLocaleGroup("europe", {"en", "fr", "de", "es"})
                    .SetAbiGroup("x64", {Abi::kX86_64})
                    .SetAbiGroup("intel", {Abi::kX86_64, Abi::kX86})
                    .SetDensityGroup("xhdpi", {"xhdpi"})
                    .SetDensityGroup("large", {"xhdpi", "xxhdpi", "xxxhdpi"})
                    .AddArtifact(x64)
                    .AddArtifact(intel)
                    .Build();

  // Called once for each artifact.
  EXPECT_CALL(apk, WriteToArchive(Eq(&ctx), _, _, _, _)).Times(2).WillRepeatedly(Return(true));
  EXPECT_TRUE(generator.FromBaseApk("out", config, table_flattener_options));
}

}  // namespace
}  // namespace aapt
