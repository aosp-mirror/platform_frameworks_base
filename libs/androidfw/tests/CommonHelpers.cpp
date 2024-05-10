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

#include "CommonHelpers.h"

#include <iostream>

#include "android-base/file.h"
#include "android-base/logging.h"
#include "android-base/strings.h"

namespace android {

static std::string sTestDataPath;

void InitializeTest(int* argc, char** argv) {
  // Set the default test data path to be the executable path directory + data.
  SetTestDataPath(base::GetExecutableDirectory() + "/tests/data");

  for (int i = 1; i < *argc; i++) {
    const std::string arg = argv[i];
    if (base::StartsWith(arg, "--testdata=")) {
      SetTestDataPath(arg.substr(strlen("--testdata=")));
      for (int j = i; j != *argc; j++) {
        argv[j] = argv[j + 1];
      }
      --(*argc);
      --i;
    } else if (arg == "-h" || arg == "--help") {
      std::cerr << "\nAdditional options specific to this test:\n"
                   "  --testdata=[PATH]\n"
                   "      Specify the location of test data used within the tests.\n";
      exit(1);
    }
  }
}

void SetTestDataPath(const std::string& path) {
  sTestDataPath = path;
}

const std::string& GetTestDataPath() {
  CHECK(!sTestDataPath.empty()) << "no test data path set.";
  return sTestDataPath;
}

std::string GetStringFromPool(const ResStringPool* pool, uint32_t idx) {
  auto str = pool->string8ObjectAt(idx);
  CHECK(str.has_value()) << "failed to find string entry";
  return std::string(str->c_str(), str->length());
}

}  // namespace android
