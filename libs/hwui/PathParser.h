/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef ANDROID_HWUI_PATHPARSER_H
#define ANDROID_HWUI_PATHPARSER_H

#include "VectorDrawable.h"
#include "utils/VectorDrawableUtils.h"

#include <android/log.h>
#include <cutils/compiler.h>

#include <string>

namespace android {
namespace uirenderer {

class PathParser {
public:
    struct ParseResult {
        bool failureOccurred = false;
        std::string failureMessage;
    };
    /**
     * Parse the string literal and create a Skia Path. Return true on success.
     */
    static void parseAsciiStringForSkPath(SkPath* outPath, ParseResult* result,
                                          const char* pathStr, size_t strLength);
    static void getPathDataFromAsciiString(PathData* outData, ParseResult* result,
                                           const char* pathStr, size_t strLength);
    static void dump(const PathData& data);
    static void validateVerbAndPoints(char verb, size_t points, ParseResult* result);
};

}      // namespace uirenderer
}      // namespace android
#endif  // ANDROID_HWUI_PATHPARSER_H
