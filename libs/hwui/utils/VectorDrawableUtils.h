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

#ifndef ANDROID_HWUI_VECTORDRAWABLE_UTILS_H
#define ANDROID_HWUI_VECTORDRAWABLE_UTILS_H

#include "VectorDrawable.h"

#include <cutils/compiler.h>
#include <vector>
#include "SkPath.h"

namespace android {
namespace uirenderer {

class VectorDrawableUtils {
public:
    static bool canMorph(const PathData& morphFrom, const PathData& morphTo);
    static bool interpolatePathData(PathData* outData, const PathData& morphFrom,
                                                const PathData& morphTo, float fraction);
    static void verbsToPath(SkPath* outPath, const PathData& data);
    static void interpolatePaths(PathData* outPathData, const PathData& from, const PathData& to,
                                 float fraction);
};
}  // namespace uirenderer
}  // namespace android
#endif /* ANDROID_HWUI_VECTORDRAWABLE_UTILS_H*/
