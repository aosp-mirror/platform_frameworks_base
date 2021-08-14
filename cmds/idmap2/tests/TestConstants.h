/*
 * Copyright (C) 2020 The Android Open Source Project
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

#ifndef IDMAP2_TESTS_TESTCONSTANTS_H_
#define IDMAP2_TESTS_TESTCONSTANTS_H_

namespace android::idmap2::TestConstants {

constexpr const auto TARGET_CRC = 0x7c2d4719;
constexpr const auto TARGET_CRC_STRING = "7c2d4719";

constexpr const auto OVERLAY_CRC = 0xb71095cf;
constexpr const auto OVERLAY_CRC_STRING = "b71095cf";

constexpr const char* OVERLAY_NAME_DEFAULT = "Default";
constexpr const char* OVERLAY_NAME_ALL_POLICIES = "AllPolicies";

}  // namespace android::idmap2::TestConstants

#endif  // IDMAP2_TESTS_TESTCONSTANTS_H_
