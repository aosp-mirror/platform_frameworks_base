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

#ifndef ANDROID_HWUI_VPATH_H
#define ANDROID_HWUI_VPATH_H

#include <cutils/compiler.h>
#include "SkPath.h"
#include <vector>

namespace android {
namespace uirenderer {

struct ANDROID_API PathData {
    // TODO: Try using FatVector instead of std::vector and do a micro benchmark on the performance
    // difference.
    std::vector<char> verbs;
    std::vector<size_t> verbSizes;
    std::vector<float> points;
    bool operator== (const PathData& data) const {
        return verbs == data.verbs && verbSizes == data.verbSizes && points == data.points;
    }

};

class VectorDrawablePath {
public:
    VectorDrawablePath(const PathData& nodes);
    VectorDrawablePath(const VectorDrawablePath& path);
    VectorDrawablePath(const char* path, size_t strLength);
    bool canMorph(const PathData& path);
    bool canMorph(const VectorDrawablePath& path);

private:
    PathData mData;
    SkPath mSkPath;
};

} // namespace uirenderer
} // namespace android

#endif // ANDROID_HWUI_VPATH_H
