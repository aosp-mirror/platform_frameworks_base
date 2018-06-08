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

#ifndef ANDROIDFW_TEST_TESTHELPERS_H
#define ANDROIDFW_TEST_TESTHELPERS_H

#include <string>

#include "androidfw/ResourceTypes.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "CommonHelpers.h"

namespace android {

::testing::AssertionResult ReadFileFromZipToString(const std::string& zip_path,
                                                   const std::string& file,
                                                   std::string* out_contents);

::testing::AssertionResult IsStringEqual(const ResTable& table, uint32_t resource_id,
                                         const char* expected_str);

}  // namespace android

#endif  // ANDROIDFW_TEST_TESTHELPERS_H
