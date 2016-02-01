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

#ifndef _LIBS_UTILS_LOCALE_DATA_H
#define _LIBS_UTILS_LOCALE_DATA_H

#include <stddef.h>
#include <stdint.h>

namespace android {

int localeDataCompareRegions(
        const char* left_region, const char* right_region,
        const char* requested_language, const char* requested_script,
        const char* requested_region);

void localeDataComputeScript(char out[4], const char* language, const char* region);

bool localeDataIsCloseToUsEnglish(const char* region);

} // namespace android

#endif // _LIBS_UTILS_LOCALE_DATA_H
