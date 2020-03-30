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

#include "TestHelpers.h"
#include "androidfw/PosixUtils.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "idmap2/FileUtils.h"
#include "idmap2/Idmap.h"
#include "private/android_filesystem_config.h"

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
  ASSERT_NE(result->stdout.find("0x7f010000 -> 0x7f010000 integer/int1"), std::string::npos);
  ASSERT_NE(result->stdout.find("0x7f02000c -> 0x7f020000 string/str1"), std::string::npos);
  ASSERT_NE(result->stdout.find("0x7f02000e -> 0x7f020001 string/str3"), std::string::npos);
  ASSERT_NE(result->stdout.find("0x7f02000f -> 0x7f020002 string/str4"), std::string::npos);

  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "dump",
                          "--verbose",
                          "--idmap-path", GetIdmapPath()});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;
  ASSERT_NE(result->stdout.find("00000000: 504d4449  magic"), std::string::npos);
  ASSERT_NE(result->stdout.find("00000210:     007f  target package id"), std::string::npos);

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

TEST_F(Idmap2BinaryTests, Scan) {
  SKIP_TEST_IF_CANT_EXEC_IDMAP2;

  const std::string overlay_static_no_name_apk_path =
      GetTestDataPath() + "/overlay/overlay-no-name-static.apk";
  const std::string overlay_static_1_apk_path = GetTestDataPath() + "/overlay/overlay-static-1.apk";
  const std::string overlay_static_2_apk_path = GetTestDataPath() + "/overlay/overlay-static-2.apk";
  const std::string idmap_static_no_name_path =
      Idmap::CanonicalIdmapPathFor(GetTempDirPath(), overlay_static_no_name_apk_path);
  const std::string idmap_static_1_path =
      Idmap::CanonicalIdmapPathFor(GetTempDirPath(), overlay_static_1_apk_path);
  const std::string idmap_static_2_path =
      Idmap::CanonicalIdmapPathFor(GetTempDirPath(), overlay_static_2_apk_path);

  // single input directory, recursive
  // clang-format off
  auto result = ExecuteBinary({"idmap2",
                               "scan",
                               "--input-directory", GetTestDataPath(),
                               "--recursive",
                               "--target-package-name", "test.target",
                               "--target-apk-path", GetTargetApkPath(),
                               "--output-directory", GetTempDirPath(),
                               "--override-policy", "public"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;
  std::stringstream expected;
  expected << idmap_static_no_name_path << std::endl;
  expected << idmap_static_1_path << std::endl;
  expected << idmap_static_2_path << std::endl;
  ASSERT_EQ(result->stdout, expected.str());

  auto idmap_static_no_name_raw_string = utils::ReadFile(idmap_static_no_name_path);
  auto idmap_static_no_name_raw_stream = std::istringstream(*idmap_static_no_name_raw_string);
  auto idmap_static_no_name = Idmap::FromBinaryStream(idmap_static_no_name_raw_stream);
  ASSERT_TRUE(idmap_static_no_name);
  ASSERT_IDMAP(**idmap_static_no_name, GetTargetApkPath(), overlay_static_no_name_apk_path);

  auto idmap_static_1_raw_string = utils::ReadFile(idmap_static_1_path);
  auto idmap_static_1_raw_stream = std::istringstream(*idmap_static_1_raw_string);
  auto idmap_static_1 = Idmap::FromBinaryStream(idmap_static_1_raw_stream);
  ASSERT_TRUE(idmap_static_1);
  ASSERT_IDMAP(**idmap_static_1, GetTargetApkPath(), overlay_static_1_apk_path);

  auto idmap_static_2_raw_string = utils::ReadFile(idmap_static_2_path);
  auto idmap_static_2_raw_stream = std::istringstream(*idmap_static_2_raw_string);
  auto idmap_static_2 = Idmap::FromBinaryStream(idmap_static_2_raw_stream);
  ASSERT_TRUE(idmap_static_2);
  ASSERT_IDMAP(**idmap_static_2, GetTargetApkPath(), overlay_static_2_apk_path);

  unlink(idmap_static_no_name_path.c_str());
  unlink(idmap_static_2_path.c_str());
  unlink(idmap_static_1_path.c_str());

  // multiple input directories, non-recursive
  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "scan",
                          "--input-directory", GetTestDataPath() + "/target",
                          "--input-directory", GetTestDataPath() + "/overlay",
                          "--target-package-name", "test.target",
                          "--target-apk-path", GetTargetApkPath(),
                          "--output-directory", GetTempDirPath(),
                          "--override-policy", "public"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;
  ASSERT_EQ(result->stdout, expected.str());
  unlink(idmap_static_no_name_path.c_str());
  unlink(idmap_static_2_path.c_str());
  unlink(idmap_static_1_path.c_str());

  // the same input directory given twice, but no duplicate entries
  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "scan",
                          "--input-directory", GetTestDataPath(),
                          "--input-directory", GetTestDataPath(),
                          "--recursive",
                          "--target-package-name", "test.target",
                          "--target-apk-path", GetTargetApkPath(),
                          "--output-directory", GetTempDirPath(),
                          "--override-policy", "public"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;
  ASSERT_EQ(result->stdout, expected.str());
  unlink(idmap_static_no_name_path.c_str());
  unlink(idmap_static_2_path.c_str());
  unlink(idmap_static_1_path.c_str());

  // no APKs in input-directory: ok, but no output
  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "scan",
                          "--input-directory", GetTempDirPath(),
                          "--target-package-name", "test.target",
                          "--target-apk-path", GetTargetApkPath(),
                          "--output-directory", GetTempDirPath(),
                          "--override-policy", "public"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;
  ASSERT_EQ(result->stdout, "");

  // the signature idmap failing to generate should not cause scanning to fail
  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "scan",
                          "--input-directory", GetTestDataPath(),
                          "--recursive",
                          "--target-package-name", "test.target",
                          "--target-apk-path", GetTargetApkPath(),
                          "--output-directory", GetTempDirPath(),
                          "--override-policy", "public"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;
  ASSERT_EQ(result->stdout, expected.str());
  unlink(idmap_static_no_name_path.c_str());
  unlink(idmap_static_2_path.c_str());
  unlink(idmap_static_1_path.c_str());
}

TEST_F(Idmap2BinaryTests, Lookup) {
  SKIP_TEST_IF_CANT_EXEC_IDMAP2;

  // clang-format off
  auto result = ExecuteBinary({"idmap2",
                               "create",
                               "--target-apk-path", GetTargetApkPath(),
                               "--overlay-apk-path", GetOverlayApkPath(),
                               "--idmap-path", GetIdmapPath()});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_EQ(result->status, EXIT_SUCCESS) << result->stderr;

  // clang-format off
  result = ExecuteBinary({"idmap2",
                          "lookup",
                          "--idmap-path", GetIdmapPath(),
                          "--config", "",
                          "--resid", "0x7f02000c"});  // string/str1
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
                          "--idmap-path", GetIdmapPath(),
                          "--policy", "this-does-not-exist"});
  // clang-format on
  ASSERT_THAT(result, NotNull());
  ASSERT_NE(result->status, EXIT_SUCCESS);
}

}  // namespace android::idmap2
