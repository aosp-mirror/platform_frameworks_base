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

#include <libgen.h>

#include <iostream>
#include <memory>
#include <string>

#include "android-base/file.h"
#include "android-base/strings.h"
#include "gtest/gtest.h"

#include "TestHelpers.h"

// Extract the directory of the current executable path.
static std::string GetExecutableDir() {
  const std::string path = android::base::GetExecutablePath();
  std::unique_ptr<char, decltype(&std::free)> mutable_path = {
      strdup(path.c_str()), std::free};
  std::string executable_dir = dirname(mutable_path.get());
  return executable_dir;
}

int main(int argc, char** argv) {
  ::testing::InitGoogleTest(&argc, argv);

  // Set the default test data path to be the executable path directory.
  android::SetTestDataPath(GetExecutableDir());

  const char* command = argv[0];
  ++argv;
  --argc;

  while (argc > 0) {
    const std::string arg = *argv;
    if (android::base::StartsWith(arg, "--testdata=")) {
      android::SetTestDataPath(arg.substr(strlen("--testdata=")));
    } else if (arg == "-h" || arg == "--help") {
      std::cerr
          << "\nAdditional options specific to this test:\n"
             "  --testdata=[PATH]\n"
             "      Specify the location of test data used within the tests.\n";
      return 1;
    } else {
      std::cerr << command << ": Unrecognized argument '" << *argv << "'.\n";
      return 1;
    }

    --argc;
    ++argv;
  }

  std::cerr << "using --testdata=" << android::GetTestDataPath() << "\n";
  return RUN_ALL_TESTS();
}
