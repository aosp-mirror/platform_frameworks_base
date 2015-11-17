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

#include "VectorDrawablePath.h"

#include "PathParser.h"
#include "utils/VectorDrawableUtils.h"

#include <math.h>
#include <utils/Log.h>

namespace android {
namespace uirenderer {


VectorDrawablePath::VectorDrawablePath(const char* pathStr, size_t strLength) {
    PathParser::ParseResult result;
    PathParser::getPathDataFromString(&mData, &result, pathStr, strLength);
    if (!result.failureOccurred) {
        VectorDrawableUtils::verbsToPath(&mSkPath, mData);
    }
}

VectorDrawablePath::VectorDrawablePath(const PathData& data) {
    mData = data;
    // Now we need to construct a path
    VectorDrawableUtils::verbsToPath(&mSkPath, data);
}

VectorDrawablePath::VectorDrawablePath(const VectorDrawablePath& path) {
    mData = path.mData;
    VectorDrawableUtils::verbsToPath(&mSkPath, mData);
}


bool VectorDrawablePath::canMorph(const PathData& morphTo) {
    return VectorDrawableUtils::canMorph(mData, morphTo);
}

bool VectorDrawablePath::canMorph(const VectorDrawablePath& path) {
    return canMorph(path.mData);
}


}; // namespace uirenderer
}; // namespace android
