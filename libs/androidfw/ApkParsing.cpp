/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "androidfw/ApkParsing.h"
#include <algorithm>
#include <array>
#include <stdlib.h>
#include <string_view>
#include <sys/types.h>

const std::string_view APK_LIB = "lib/";
const size_t APK_LIB_LEN = APK_LIB.size();

const std::string_view LIB_PREFIX = "/lib";
const size_t LIB_PREFIX_LEN = LIB_PREFIX.size();

const std::string_view LIB_SUFFIX = ".so";
const size_t LIB_SUFFIX_LEN = LIB_SUFFIX.size();

static const std::array<std::string_view, 2> abis = {"arm64-v8a", "x86_64"};

namespace android::util {
const char* ValidLibraryPathLastSlash(const char* fileName, bool suppress64Bit, bool debuggable) {
    // Make sure the filename is at least to the minimum library name size.
    const size_t fileNameLen = strlen(fileName);
    static const size_t minLength = APK_LIB_LEN + 2 + LIB_PREFIX_LEN + 1 + LIB_SUFFIX_LEN;
    if (fileNameLen < minLength) {
        return nullptr;
    }

    const char* lastSlash = strrchr(fileName, '/');
    if (!lastSlash) {
        return nullptr;
    }

    // Skip directories.
    if (*(lastSlash + 1) == 0) {
        return nullptr;
    }

    // Make sure the filename is safe.
    if (!isFilenameSafe(lastSlash + 1)) {
        return nullptr;
    }

    // Make sure file starts with 'lib/' prefix.
    if (strncmp(fileName, APK_LIB.data(), APK_LIB_LEN) != 0) {
        return nullptr;
    }

    // Make sure there aren't subdirectories by checking if the next / after lib/ is the last slash
    if (memchr(fileName + APK_LIB_LEN, '/', fileNameLen - APK_LIB_LEN) != lastSlash) {
        return nullptr;
    }

    if (!debuggable) {
        // Make sure the filename starts with lib and ends with ".so".
        if (strncmp(fileName + fileNameLen - LIB_SUFFIX_LEN, LIB_SUFFIX.data(), LIB_SUFFIX_LEN) != 0
            || strncmp(lastSlash, LIB_PREFIX.data(), LIB_PREFIX_LEN) != 0) {
            return nullptr;
        }
    }

    // Don't include 64 bit versions if they are suppressed
    if (suppress64Bit && std::find(abis.begin(), abis.end(), std::string_view(
        fileName + APK_LIB_LEN, lastSlash - fileName - APK_LIB_LEN)) != abis.end()) {
      return nullptr;
    }

    return lastSlash;
}

bool isFilenameSafe(const char* filename) {
    off_t offset = 0;
    for (;;) {
        switch (*(filename + offset)) {
        case 0:
            // Null.
            // If we've reached the end, all the other characters are good.
            return true;

        case 'A' ... 'Z':
        case 'a' ... 'z':
        case '0' ... '9':
        case '+':
        case ',':
        case '-':
        case '.':
        case '/':
        case '=':
        case '_':
            offset++;
            break;

        default:
            // We found something that is not good.
            return false;
        }
    }
    // Should not reach here.
}
}