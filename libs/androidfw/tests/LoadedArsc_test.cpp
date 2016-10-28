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

#include "androidfw/LoadedArsc.h"

#include "android-base/file.h"
#include "android-base/logging.h"
#include "android-base/macros.h"

#include "TestHelpers.h"
#include "data/basic/R.h"
#include "data/styles/R.h"

namespace app = com::android::app;
namespace basic = com::android::basic;

namespace android {

TEST(LoadedArscTest, LoadSinglePackageArsc) {
  base::ScopedLogSeverity _log(base::LogSeverity::DEBUG);
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/styles/styles.apk", "resources.arsc",
                                      &contents));

  std::unique_ptr<LoadedArsc> loaded_arsc = LoadedArsc::Load(contents.data(), contents.size());
  ASSERT_NE(nullptr, loaded_arsc);

  ResTable_config config;
  memset(&config, 0, sizeof(config));
  config.sdkVersion = 24;

  LoadedArsc::Entry entry;
  ResTable_config selected_config;
  uint32_t flags;

  ASSERT_TRUE(
      loaded_arsc->FindEntry(app::R::string::string_one, config, &entry, &selected_config, &flags));
  ASSERT_NE(nullptr, entry.entry);
}

TEST(LoadedArscTest, FindDefaultEntry) {
  base::ScopedLogSeverity _log(base::LogSeverity::DEBUG);
  std::string contents;
  ASSERT_TRUE(
      ReadFileFromZipToString(GetTestDataPath() + "/basic/basic.apk", "resources.arsc", &contents));

  std::unique_ptr<LoadedArsc> loaded_arsc = LoadedArsc::Load(contents.data(), contents.size());
  ASSERT_NE(nullptr, loaded_arsc);

  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  LoadedArsc::Entry entry;
  ResTable_config selected_config;
  uint32_t flags;

  ASSERT_TRUE(loaded_arsc->FindEntry(basic::R::string::test1, desired_config, &entry,
                                     &selected_config, &flags));
  ASSERT_NE(nullptr, entry.entry);
}

// structs with size fields (like Res_value, ResTable_entry) should be
// backwards and forwards compatible (aka checking the size field against
// sizeof(Res_value) might not be backwards compatible.
TEST(LoadedArscTest, LoadingShouldBeForwardsAndBackwardsCompatible) { ASSERT_TRUE(false); }

}  // namespace android
