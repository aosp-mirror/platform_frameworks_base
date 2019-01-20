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

#include <dirent.h>
#include <set>
#include <string>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "android-base/macros.h"

#include "idmap2/FileUtils.h"

#include "TestHelpers.h"

using ::testing::NotNull;

namespace android::idmap2::utils {

TEST(FileUtilsTests, FindFilesFindEverythingNonRecursive) {
  const auto& root = GetTestDataPath();
  auto v = utils::FindFiles(root, false,
                            [](unsigned char type ATTRIBUTE_UNUSED,
                               const std::string& path ATTRIBUTE_UNUSED) -> bool { return true; });
  ASSERT_THAT(v, NotNull());
  ASSERT_EQ(v->size(), 6U);
  ASSERT_EQ(std::set<std::string>(v->begin(), v->end()),
            std::set<std::string>({root + "/.", root + "/..", root + "/overlay", root + "/target",
                                   root + "/system-overlay", root + "/system-overlay-invalid"}));
}

TEST(FileUtilsTests, FindFilesFindApkFilesRecursive) {
  const auto& root = GetTestDataPath();
  auto v = utils::FindFiles(root, true, [](unsigned char type, const std::string& path) -> bool {
    return type == DT_REG && path.size() > 4 && path.compare(path.size() - 4, 4, ".apk") == 0;
  });
  ASSERT_THAT(v, NotNull());
  ASSERT_EQ(v->size(), 6U);
  ASSERT_EQ(std::set<std::string>(v->begin(), v->end()),
            std::set<std::string>({root + "/target/target.apk", root + "/overlay/overlay.apk",
                                   root + "/overlay/overlay-static-1.apk",
                                   root + "/overlay/overlay-static-2.apk",
                                   root + "/system-overlay/system-overlay.apk",
                                   root + "/system-overlay-invalid/system-overlay-invalid.apk"}));
}

TEST(FileUtilsTests, ReadFile) {
  int pipefd[2];
  ASSERT_EQ(pipe(pipefd), 0);

  ASSERT_EQ(write(pipefd[1], "foobar", 6), 6);
  close(pipefd[1]);

  auto data = ReadFile(pipefd[0]);
  ASSERT_THAT(data, NotNull());
  ASSERT_EQ(*data, "foobar");
  close(pipefd[0]);
}

}  // namespace android::idmap2::utils
