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

#include <androidfw/ResourceTypes.h>

#include "Rect.h"
#include "UvMapper.h"
#include "Vertex.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// 9-patch structures
///////////////////////////////////////////////////////////////////////////////

class Patch {
public:
    Patch();
    ~Patch();

    /**
     * Returns the size of this patch's mesh in bytes.
     */
    uint32_t getSize() const;

    TextureVertex* vertices;
    uint32_t verticesCount;
    uint32_t indexCount;
    bool hasEmptyQuads;
    Vector<Rect> quads;

    GLintptr offset;
    GLintptr textureOffset;

    TextureVertex* createMesh(const float bitmapWidth, const float bitmapHeight,
            float width, float height, const Res_png_9patch* patch);
    TextureVertex* createMesh(const float bitmapWidth, const float bitmapHeight,
            float width, float height, const UvMapper& mapper, const Res_png_9patch* patch);

private:
    void generateRow(const int32_t* xDivs, uint32_t xCount, TextureVertex*& vertex,
            float y1, float y2, float v1, float v2, float stretchX, float rescaleX,
            float width, float bitmapWidth, uint32_t& quadCount);
    void generateQuad(TextureVertex*& vertex, float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2, uint32_t& quadCount);

    const uint32_t* mColors;
    UvMapper mUvMapper;
}; // struct Patch

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATCH_H
