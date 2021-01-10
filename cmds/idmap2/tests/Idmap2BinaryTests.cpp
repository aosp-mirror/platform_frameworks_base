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

/*
 * The tests in this file operate on a higher level than the tests in the other
 * files. Here, all tests execute the idmap2 binary and only depend on
 * libidmap2 to verify the output of idmap2.
 */
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstdlib>
#include <cstring>  // strerror
#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "R.h"
#include "TestConstants.h"
#include "TestHelpers.h"
#include "androidfw/PosixUtils.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"
#include "private/android_filesystem_config.h"

using ::android::base::StringPrintf;
using ::android::util::ExecuteBinary;
using ::testing::NotNull;

namespace android::idmap2 {

class Idmap2BinaryTests : public Idmap2Tests {};

namespace {

void AssertIdmap(const Idmap& idmap, const std::string& target_apk_path,
                 const std::string& overlay_apk_path) {
  // check that the idmap file looks reasonable (IdmapTests is responsible for
  // more in-depth verification)
  ASSERT_EQ(idmap.GetHeader()->GetMagic(), kIdmapMagic);
  ASSERT_EQ(idmap.GetHeader()->GetVersion(), kIdmapCurrentVersion);
  ASSERT_EQ(idmap.GetHeader()->GetTargetPath(), target_apk_path);
  ASSERT_EQ(idmap.GetHeader()->GetOverlayPath(), overlay_apk_path);
  ASSERT_EQ(idmap.GetData().size(), 1U);
}

#define ASSERT_IDMAP(idmap_ref, target_apk_path, overlay_apk_path)                      \
  do {                                                                                  \
    ASSERT_NO_FATAL_FAILURE(AssertIdmap(idmap_ref, target_apk_path, overlay_apk_path)); \
  } while (0)

#ifdef __ANDROID__
#define SKIP_TEST_IF_CANT_EXEC_IDMAP2           \
  do {                                          \
    const uid_t uid = getuid();                 \
    if (uid != AID_ROOT && uid != AID_SYSTEM) { \
      GTEST_SKIP();                             \
    }                                           \
  } while (0)
#else
#define SKIP_TEST_IF_CANT_EXEC_IDMAP2
#endif

}  // namespace

TEST_F(Idmap2BinaryTests, Create) {
  SKIP_TEST_IF_CANT_EXEC_IDMAP2;

  // clang-format off
  auto result = ExecuteBinary({"idmap2",
                               "create",
                               "--target-apk-path", GetTargetApkPath(),
                               "--overlay-apk-path", GetOverlayApkPath(),
                               "--overlay-name", TestConstants::OVERLAY_NAME_DEFAULT,
                               "--idmap-path", GetIdmapPath()});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;

  struct stat st;
  ASSERT_EQ(stat(GetIdmapPath().c_str(), &st), 0);

  std::ifstream fin(GetIdmapPath());
  const auto idmap = Idmap::FromBinaryStream(fin);
  fin.close();

  ASSERT_TRUE(idmap);
  ASSERT_IDMAP(**idmap, GetTargetApkPath(), GetOverlayApkPath());

  unlink(GetIdmapPath().c_str());
}

TEST_F(Idmap2BinaryTests, Dump) {
  SKIP_TEST_IF_CANT_EXEC_IDMAP2;

  // clang-format off
  auto result = ExecuteBinary({"idmap2",
                               "create",
                               "--target-apk-path", GetTargetApkPath(),
                               "--overlay-apk-path", GetOverlayApkPath(),
                               "--overlay-name", TestConstants::OVERLAY_NAME_DEFAULT,
                               "--idmap-path", GetIdmapPath()});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;

  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "dump",
                          "--idmap-path", GetIdmapPath()});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;

  ASSERT_NE(result->stdout.find(StringPrintf("0x%08x -> 0x%08x", R::target::integer::int1,
                                             R::overlay::integer::int1)),
            std::string::npos)
      << result->stdout;
  ASSERT_NE(result->stdout.find(StringPrintf("0x%08x -> 0x%08x", R::target::string::str1,
                                             R::overlay::string::str1)),
            std::string::npos)
      << result->stdout;
  ASSERT_NE(result->stdout.find(StringPrintf("0x%08x -> 0x%08x", R::target::string::str3,
                                             R::overlay::string::str3)),
            std::string::npos)
      << result->stdout;
  ASSERT_NE(result->stdout.find(StringPrintf("0x%08x -> 0x%08x", R::target::string::str4,
                                             R::overlay::string::str4)),
            std::string::npos)
      << result->stdout;

  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "dump",
                          "--verbose",
                          "--idmap-path", GetIdmapPath()});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;
  ASSERT_NE(result->stdout.find("00000000: 504d4449  magic"), std::string::npos);

  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "dump",
                          "--verbose",
                          "--idmap-path", GetTestDataPath() + "/DOES-NOT-EXIST"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_NE(result->status, EXIT_SUCCESS);

  unlink(GetIdmapPath().c_str());
}

TEST_F(Idmap2BinaryTests, Lookup) {
  SKIP_TEST_IF_CANT_EXEC_IDMAP2;

  // clang-format off
  auto result = ExecuteBinary({"idmap2",
                               "create",
                               "--target-apk-path", GetTargetApkPath(),
                               "--overlay-apk-path", GetOverlayApkPath(),
                               "--overlay-name", TestConstants::OVERLAY_NAME_DEFAULT,
                               "--idmap-path", GetIdmapPath()});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;

  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "lookup",
                          "--idmap-path", GetIdmapPath(),
                          "--config", "",
                          "--resid", StringPrintf("0x%08x", R::target::string::str1)});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;
  ASSERT_NE(result->stdout.find("overlay-1"), std::string::npos);
  ASSERT_EQ(result->stdout.find("overlay-1-sv"), std::string::npos);

  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "lookup",
                          "--idmap-path", GetIdmapPath(),
                          "--config", "",
                          "--resid", "test.target:string/str1"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;
  ASSERT_NE(result->stdout.find("overlay-1"), std::string::npos);
  ASSERT_EQ(result->stdout.find("overlay-1-sv"), std::string::npos);

  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "lookup",
                          "--idmap-path", GetIdmapPath(),
                          "--config", "sv",
                          "--resid", "test.target:string/str1"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;
  ASSERT_NE(result->stdout.find("overlay-1-sv"), std::string::npos);

  unlink(GetIdmapPath().c_str());
}

TEST_F(Idmap2BinaryTests, InvalidCommandLineOptions) {
  SKIP_TEST_IF_CANT_EXEC_IDMAP2;

  const std::string invalid_target_apk_path = GetTestDataPath() + "/DOES-NOT-EXIST";

  // missing mandatory options
  // clang-format off
  auto result = ExecuteBinary({"idmap2",
                               "create"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_NE(result->status, EXIT_SUCCESS);

  // missing argument to option
  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "create",
                          "--target-apk-path", GetTargetApkPath(),
                          "--overlay-apk-path", GetOverlayApkPath(),
                          "--overlay-name", TestConstants::OVERLAY_NAME_DEFAULT,
                          "--idmap-path"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_NE(result->status, EXIT_SUCCESS);

  // invalid target apk path
  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "create",
                          "--target-apk-path", invalid_target_apk_path,
                          "--overlay-apk-path", GetOverlayApkPath(),
                          "--overlay-name", TestConstants::OVERLAY_NAME_DEFAULT,
                          "--idmap-path", GetIdmapPath()});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_NE(result->status, EXIT_SUCCESS);

  // unknown policy
  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "create",
                          "--target-apk-path", GetTargetApkPath(),
                          "--overlay-apk-path", GetOverlayApkPath(),
                          "--overlay-name", TestConstants::OVERLAY_NAME_DEFAULT,
                          "--idmap-path", GetIdmapPath(),
                          "--policy", "this-does-not-exist"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_NE(result->status, EXIT_SUCCESS);
}

}  // namespace android::idmap2
