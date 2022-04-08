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

#ifndef ANDROIDFW_TEST_COMMON_HELPERS_H
#define ANDROIDFW_TEST_COMMON_HELPERS_H

#include <ostream>
#include <string>

#include "androidfw/ResourceTypes.h"
#include "utils/String16.h"
#include "utils/String8.h"

namespace android {

void InitializeTest(int* argc, char** argv);

enum { MAY_NOT_BE_BAG = false };

void SetTestDataPath(const std::string& path);

const std::string& GetTestDataPath();

std::string GetStringFromPool(const ResStringPool* pool, uint32_t idx);

static inline bool operator==(const ResTable_config& a, const ResTable_config& b) {
  return a.compare(b) == 0;
}

static inline ::std::ostream& operator<<(::std::ostream& out, const String8& str) {
  return out << str.string();
}

static inline ::std::ostream& operator<<(::std::ostream& out, const ResTable_config& c) {
  return out << c.toString();
}

}  // namespace android

#endif  // ANDROIDFW_TEST_COMMON_HELPERS_H
