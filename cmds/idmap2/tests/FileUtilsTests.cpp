/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <string>

#include "TestHelpers.h"
#include "android-base/stringprintf.h"
#include "gtest/gtest.h"
#include "idmap2/FileUtils.h"
#include "private/android_filesystem_config.h"

namespace android::idmap2::utils {

#ifdef __ANDROID__
TEST(FileUtilsTests, UidHasWriteAccessToPath) {
  constexpr const char* tmp_path = "/data/local/tmp/test@idmap";
  const std::string cache_path(base::StringPrintf("%s/test@idmap", kIdmapCacheDir.data()));
  const std::string sneaky_cache_path(
      base::StringPrintf("/data/../%s/test@idmap", kIdmapCacheDir.data()));

  ASSERT_TRUE(UidHasWriteAccessToPath(AID_ROOT, tmp_path));
  ASSERT_TRUE(UidHasWriteAccessToPath(AID_ROOT, cache_path));
  ASSERT_TRUE(UidHasWriteAccessToPath(AID_ROOT, sneaky_cache_path));

  ASSERT_TRUE(UidHasWriteAccessToPath(AID_SYSTEM, tmp_path));
  ASSERT_TRUE(UidHasWriteAccessToPath(AID_SYSTEM, cache_path));
  ASSERT_TRUE(UidHasWriteAccessToPath(AID_SYSTEM, sneaky_cache_path));

  constexpr const uid_t AID_SOME_APP = AID_SYSTEM + 1;
  ASSERT_TRUE(UidHasWriteAccessToPath(AID_SOME_APP, tmp_path));
  ASSERT_FALSE(UidHasWriteAccessToPath(AID_SOME_APP, cache_path));
  ASSERT_FALSE(UidHasWriteAccessToPath(AID_SOME_APP, sneaky_cache_path));
}
#endif

}  // namespace android::idmap2::utils
