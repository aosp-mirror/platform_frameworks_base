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

#include "Vertex.h"
#include "utils/Compare.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// 9-patch structures
///////////////////////////////////////////////////////////////////////////////

/**
 * Description of a patch.
 */
struct PatchDescription {
    PatchDescription(): bitmapWidth(0), bitmapHeight(0), pixelWidth(0), pixelHeight(0),
            xCount(0), yCount(0), emptyCount(0), colorKey(0) { }
    PatchDescription(const float bitmapWidth, const float bitmapHeight,
            const float pixelWidth, const float pixelHeight,
            const uint32_t xCount, const uint32_t yCount,
            const int8_t emptyCount, const uint32_t colorKey):
            bitmapWidth(bitmapWidth), bitmapHeight(bitmapHeight),
            pixelWidth(pixelWidth), pixelHeight(pixelHeight),
            xCount(xCount), yCount(yCount),
            emptyCount(emptyCount), colorKey(colorKey) { }
    PatchDescription(const PatchDescription& description):
            bitmapWidth(description.bitmapWidth), bitmapHeight(description.bitmapHeight),
            pixelWidth(description.pixelWidth), pixelHeight(description.pixelHeight),
            xCount(description.xCount), yCount(description.yCount),
            emptyCount(description.emptyCount), colorKey(description.colorKey) { }

    float bitmapWidth;
    float bitmapHeight;
    float pixelWidth;
    float pixelHeight;
    uint32_t xCount;
    uint32_t yCount;
    int8_t emptyCount;
    uint32_t colorKey;

    bool operator<(const PatchDescription& rhs) const {
        compare(bitmapWidth) {
            compare(bitmapHeight) {
                compare(pixelWidth) {
                    compare(pixelHeight) {
                        compareI(xCount) {
                            compareI(yCount) {
                                compareI(emptyCount) {
                                    compareI(colorKey) return false;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}; // struct PatchDescription

/**
 * An OpenGL patch. This contains an array of vertices and an array of
 * indices to render the vertices.
 */
struct Patch {
    Patch(const uint32_t xCount, const uint32_t yCount, const int8_t emptyQuads = 0);
    ~Patch();

    void updateVertices(const float bitmapWidth, const float bitmapHeight,
            float left, float top, float right, float bottom,
            const int32_t* xDivs, const int32_t* yDivs,
            const uint32_t width, const uint32_t height,
            const uint32_t colorKey = 0);

    TextureVertex* vertices;
    uint32_t verticesCount;

private:
    static inline void generateRow(TextureVertex*& vertex, float y1, float y2,
            float v1, float v2, const int32_t xDivs[], uint32_t xCount,
            float stretchX, float width, float bitmapWidth,
            uint32_t& quadCount, const uint32_t colorKey);
    static inline void generateQuad(TextureVertex*& vertex,
            float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2,
            uint32_t& quadCount, const uint32_t colorKey);
}; // struct Patch

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_PATCH_H
