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

#include "TestHelpers.h"

#include <unistd.h>

#include "android-base/logging.h"

namespace android {

static std::string sTestDataPath;

void SetTestDataPath(const std::string& path) { sTestDataPath = path; }

const std::string& GetTestDataPath() {
  CHECK(!sTestDataPath.empty()) << "no test data path set.";
  return sTestDataPath;
}

::testing::AssertionResult IsStringEqual(const ResTable& table,
                                         uint32_t resource_id,
                                         const char* expected_str) {
  Res_value val;
  ssize_t block = table.getResource(resource_id, &val, MAY_NOT_BE_BAG);
  if (block < 0) {
    return ::testing::AssertionFailure() << "could not find resource";
  }

  if (val.dataType != Res_value::TYPE_STRING) {
    return ::testing::AssertionFailure() << "resource is not a string";
  }

  const ResStringPool* pool = table.getTableStringBlock(block);
  if (pool == NULL) {
    return ::testing::AssertionFailure()
           << "table has no string pool for block " << block;
  }

  const String8 actual_str = pool->string8ObjectAt(val.data);
  if (String8(expected_str) != actual_str) {
    return ::testing::AssertionFailure() << actual_str.string();
  }
  return ::testing::AssertionSuccess() << actual_str.string();
}

}  // namespace android
