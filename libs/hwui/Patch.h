/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_UI_PATCH_H
#define ANDROID_UI_PATCH_H

#include <sys/types.h>

#include <SkBitmap.h>

#include "Vertex.h"

namespace android {
namespace uirenderer {

/**
 * Description of a patch.
 */
struct PatchDescription {
    PatchDescription(): xCount(0), yCount(0) { }
    PatchDescription(const uint32_t xCount, const uint32_t yCount):
            xCount(xCount), yCount(yCount) { }
    PatchDescription(const PatchDescription& description):
            xCount(description.xCount), yCount(description.yCount) { }

    uint32_t xCount;
    uint32_t yCount;

    bool operator<(const PatchDescription& rhs) const {
        if (xCount == rhs.xCount) {
            return yCount < rhs.yCount;
        }
        return xCount < rhs.xCount;
    }

    bool operator==(const PatchDescription& rhs) const {
        return xCount == rhs.xCount && yCount == rhs.yCount;
    }
}; // struct PatchDescription

/**
 * An OpenGL patch. This contains an array of vertices and an array of
 * indices to render the vertices.
 */
struct Patch {
    Patch(const uint32_t xCount, const uint32_t yCount);
    ~Patch();

    void updateVertices(const SkBitmap* bitmap, float left, float top, float right, float bottom,
            const int32_t* xDivs,  const int32_t* yDivs,
            const uint32_t width, const uint32_t height);
    void dump();

    uint32_t xCount;
    uint32_t yCount;

    uint16_t* indices;
    uint32_t indicesCount;

    TextureVertex* vertices;
    uint32_t verticesCount;

private:
    static inline void generateVertices(TextureVertex* vertex, float y, float v,
            const int32_t xDivs[], uint32_t xCount, float xStretch, float xStretchTex,
            float width, float widthTex);
}; // struct Patch

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_PATCH_H
