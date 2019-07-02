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

#ifndef ANDROID_OS_DEBUG_H
#define ANDROID_OS_DEBUG_H

#include <memory>
#include <stdio.h>
#include <meminfo/meminfo.h>
#include <android-base/stringprintf.h>
#include <android-base/unique_fd.h>

namespace android {

inline void safeFclose(FILE* fp) {
  if (fp) fclose(fp);
}

using UniqueFile = std::unique_ptr<FILE, decltype(&safeFclose)>;

inline UniqueFile MakeUniqueFile(const char* path, const char* mode) {
    return UniqueFile(fopen(path, mode), safeFclose);
}

}  // namespace android

#endif  // ANDROID_OS_HW_BLOB_H
