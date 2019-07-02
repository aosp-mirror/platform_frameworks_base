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

#include "Command.h"

#include "test/Test.h"

using ::testing::Eq;

namespace aapt {

class TestCommand : public Command {
 public:
  explicit TestCommand() : Command("command") {}
  int Action(const std::vector<std::string>& args) override {
    args_ = args;
    return 0;
  }

  std::vector<std::string> args_;
};

#ifdef _WIN32
TEST(CommandTest, LongFullyQualifiedPathWindows) {
  TestCommand command;
  std::string required_flag;
  command.AddRequiredFlag("--rflag", "", &required_flag, Command::kPath);
  Maybe<std::string> optional_flag;
  command.AddOptionalFlag("--oflag", "", &optional_flag, Command::kPath);
  std::vector<std::string> required_flag_list;
  command.AddRequiredFlagList("--rlflag", "", &required_flag_list, Command::kPath);
  std::vector<std::string> optional_flag_list;
  command.AddOptionalFlagList("--olflag", "", &optional_flag_list, Command::kPath);
  std::string non_path_flag;
  command.AddRequiredFlag("--nflag", "", &non_path_flag);

  const std::string kLongPath =
      "C:\\Users\\jedo\\_bazel_jedo\\vcmdctjv\\execroot\\__main__\\_tmp"
      "\\6767b4778f8798efc0f784ee74fa70ee\\tests\\testApksAr8c7560a9a65"
      "\\1346ee7c014a089fb55d8c46cf3d9\\project\\baseModule\\build"
      "\\intermediates\\processed_res\\minified\\processMinifiedResources"
      "\\1346ee7c014a089fb55d8c46cf3d9\\project\\baseModule\\build"
      "\\intermediates\\processed_res\\minified\\processMinifiedResources"
      "\\out\\resources-minified.ap_";

  const std::string kExpected =
      "\\\\?\\C:\\Users\\jedo\\_bazel_jedo\\vcmdctjv\\execroot\\__main__\\_tmp"
      "\\6767b4778f8798efc0f784ee74fa70ee\\tests\\testApksAr8c7560a9a65"
      "\\1346ee7c014a089fb55d8c46cf3d9\\project\\baseModule\\build"
      "\\intermediates\\processed_res\\minified\\processMinifiedResources"
      "\\1346ee7c014a089fb55d8c46cf3d9\\project\\baseModule\\build"
      "\\intermediates\\processed_res\\minified\\processMinifiedResources"
      "\\out\\resources-minified.ap_";


  ASSERT_THAT(command.Execute({"--rflag", kLongPath,
                               "--oflag", kLongPath,
                               "--rlflag", kLongPath,
                               "--rlflag", kLongPath,
                               "--olflag", kLongPath,
                               "--olflag", kLongPath,
                               "--nflag", kLongPath,
                               kLongPath, kLongPath}, &std::cerr), Eq(0));

  ASSERT_THAT(required_flag, Eq(kExpected));
  ASSERT_THAT(optional_flag, Eq(kExpected));
  ASSERT_THAT(required_flag_list.size(), Eq(2));
  ASSERT_THAT(required_flag_list[0], Eq(kExpected));
  ASSERT_THAT(required_flag_list[1], Eq(kExpected));
  ASSERT_THAT(optional_flag_list.size(), Eq(2));
  ASSERT_THAT(optional_flag_list[0], Eq(kExpected));
  ASSERT_THAT(optional_flag_list[1], Eq(kExpected));

  // File arguments should also be converted to use the long path prefix
  ASSERT_THAT(command.args_.size(), Eq(2));
  ASSERT_THAT(command.args_[0], Eq(kExpected));
  ASSERT_THAT(command.args_[1], Eq(kExpected));

  // Do not convert flags that are not marged as paths
  ASSERT_THAT(non_path_flag, Eq(kLongPath));
}
#endif

}  // namespace aapt