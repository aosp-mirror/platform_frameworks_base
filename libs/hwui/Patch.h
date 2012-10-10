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

#ifndef ANDROID_HWUI_PATCH_H
#define ANDROID_HWUI_PATCH_H

#include <sys/types.h>

#include <GLES2/gl2.h>

#include <utils/Vector.h>

#include "Rect.h"
#include "Vertex.h"
#include "utils/Compare.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define EXPLODE_GAP 4

///////////////////////////////////////////////////////////////////////////////
// 9-patch structures
///////////////////////////////////////////////////////////////////////////////

/**
 * An OpenGL patch. This contains an array of vertices and an array of
 * indices to render the vertices.
 */
struct Patch {
    Patch(const uint32_t xCount, const uint32_t yCount, const int8_t emptyQuads = 0);
    ~Patch();

    void updateVertices(const float bitmapWidth, const float bitmapHeight,
            float left, float top, float right, float bottom);

    void updateColorKey(const uint32_t colorKey);
    void copy(const int32_t* xDivs, const int32_t* yDivs);
    bool matches(const int32_t* xDivs, const int32_t* yDivs, const uint32_t colorKey);

    GLuint meshBuffer;
    uint32_t verticesCount;
    bool hasEmptyQuads;
    Vector<Rect> quads;

private:
    TextureVertex* mVertices;
    bool mUploaded;

    int32_t* mXDivs;
    int32_t* mYDivs;
    uint32_t mColorKey;

    uint32_t mXCount;
    uint32_t mYCount;
    int8_t mEmptyQuads;

    void copy(const int32_t* yDivs);

    void generateRow(TextureVertex*& vertex, float y1, float y2,
            float v1, float v2, float stretchX, float rescaleX,
            float width, float bitmapWidth, uint32_t& quadCount);
    void generateQuad(TextureVertex*& vertex,
            float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2,
            uint32_t& quadCount);
}; // struct Patch

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATCH_H
