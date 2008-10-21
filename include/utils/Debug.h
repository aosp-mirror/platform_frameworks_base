/*
 * Copyright (C) 2005 The Android Open Source Project
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

//
// Debugging tools.  These should be able to be stripped
// in release builds.
//
#ifndef ANDROID_DEBUG_H
#define ANDROID_DEBUG_H

#include <stdint.h>
#include <sys/types.h>

namespace android {

template<bool> struct CompileTimeAssert;
template<> struct CompileTimeAssert<true> {};

const char* stringForIndent(int32_t indentLevel);

typedef void (*debugPrintFunc)(void* cookie, const char* txt);

void printTypeCode(uint32_t typeCode,
    debugPrintFunc func = 0, void* cookie = 0);
void printHexData(int32_t indent, const void *buf, size_t length,
    size_t bytesPerLine=16, int32_t singleLineBytesCutoff=16,
    size_t alignment=0, bool cArrayStyle=false,
    debugPrintFunc func = 0, void* cookie = 0);

}; // namespace android

#endif // ANDROID_DEBUG_H
