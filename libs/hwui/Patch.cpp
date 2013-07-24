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

#define LOG_TAG "OpenGLRenderer"

#include <cmath>

#include <utils/Log.h>

#include "Caches.h"
#include "Patch.h"
#include "Properties.h"
#include "UvMapper.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

Patch::Patch(): vertices(NULL), verticesCount(0), indexCount(0), hasEmptyQuads(false) {
}

Patch::~Patch() {
}

///////////////////////////////////////////////////////////////////////////////
// Vertices management
///////////////////////////////////////////////////////////////////////////////

uint32_t Patch::getSize() const {
    return verticesCount * sizeof(TextureVertex);
}

TextureVertex* Patch::createMesh(const float bitmapWidth, const float bitmapHeight,
        float width, float height, const Res_png_9patch* patch) {
    UvMapper mapper;
    return createMesh(bitmapWidth, bitmapHeight, width, height, mapper, patch);
}

TextureVertex* Patch::createMesh(const float bitmapWidth, const float bitmapHeight,
        float width, float height, const UvMapper& mapper, const Res_png_9patch* patch) {
    if (vertices) return vertices;

    int8_t emptyQuads = 0;
    mColors = patch->colors;

    const int8_t numColors = patch->numColors;
    if (uint8_t(numColors) < sizeof(uint32_t) * 4) {
        for (int8_t i = 0; i < numColors; i++) {
            if (mColors[i] == 0x0) {
                emptyQuads++;
            }
        }
    }

    hasEmptyQuads = emptyQuads > 0;

    uint32_t xCount = patch->numXDivs;
    uint32_t yCount = patch->numYDivs;

    uint32_t maxVertices = ((xCount + 1) * (yCount + 1) - emptyQuads) * 4;
    if (maxVertices == 0) return NULL;

    TextureVertex* tempVertices = new TextureVertex[maxVertices];
    TextureVertex* vertex = tempVertices;

    const int32_t* xDivs = patch->xDivs;
    const int32_t* yDivs = patch->yDivs;

    const uint32_t xStretchCount = (xCount + 1) >> 1;
    const uint32_t yStretchCount = (yCount + 1) >> 1;

    float stretchX = 0.0f;
    float stretchY = 0.0f;

    float rescaleX = 1.0f;
    float rescaleY = 1.0f;

    if (xStretchCount > 0) {
        uint32_t stretchSize = 0;
        for (uint32_t i = 1; i < xCount; i += 2) {
            stretchSize += xDivs[i] - xDivs[i - 1];
        }
        const float xStretchTex = stretchSize;
        const float fixed = bitmapWidth - stretchSize;
        const float xStretch = fmaxf(width - fixed, 0.0f);
        stretchX = xStretch / xStretchTex;
        rescaleX = fixed == 0.0f ? 0.0f : fminf(fmaxf(width, 0.0f) / fixed, 1.0f);
    }

    if (yStretchCount > 0) {
        uint32_t stretchSize = 0;
        for (uint32_t i = 1; i < yCount; i += 2) {
            stretchSize += yDivs[i] - yDivs[i - 1];
        }
        const float yStretchTex = stretchSize;
        const float fixed = bitmapHeight - stretchSize;
        const float yStretch = fmaxf(height - fixed, 0.0f);
        stretchY = yStretch / yStretchTex;
        rescaleY = fixed == 0.0f ? 0.0f : fminf(fmaxf(height, 0.0f) / fixed, 1.0f);
    }

    uint32_t quadCount = 0;

    float previousStepY = 0.0f;

    float y1 = 0.0f;
    float y2 = 0.0f;
    float v1 = 0.0f;

    mUvMapper = mapper;

    for (uint32_t i = 0; i < yCount; i++) {
        float stepY = yDivs[i];
        const float segment = stepY - previousStepY;

        if (i & 1) {
            y2 = y1 + floorf(segment * stretchY + 0.5f);
        } else {
            y2 = y1 + segment * rescaleY;
        }

        float vOffset = y1 == y2 ? 0.0f : 0.5 - (0.5 * segment / (y2 - y1));
        float v2 = fmax(0.0f, stepY - vOffset) / bitmapHeight;
        v1 += vOffset / bitmapHeight;

        if (stepY > 0.0f) {
            generateRow(xDivs, xCount, vertex, y1, y2, v1, v2, stretchX, rescaleX,
                    width, bitmapWidth, quadCount);
        }

        y1 = y2;
        v1 = stepY / bitmapHeight;

        previousStepY = stepY;
    }

    if (previousStepY != bitmapHeight) {
        y2 = height;
        generateRow(xDivs, xCount, vertex, y1, y2, v1, 1.0f, stretchX, rescaleX,
                width, bitmapWidth, quadCount);
    }

    if (verticesCount == maxVertices) {
        vertices = tempVertices;
    } else {
        vertices = new TextureVertex[verticesCount];
        memcpy(vertices, tempVertices, verticesCount * sizeof(TextureVertex));
        delete[] tempVertices;
    }

    return vertices;
}

void Patch::generateRow(const int32_t* xDivs, uint32_t xCount, TextureVertex*& vertex,
        float y1, float y2, float v1, float v2, float stretchX, float rescaleX,
        float width, float bitmapWidth, uint32_t& quadCount) {
    float previousStepX = 0.0f;

    float x1 = 0.0f;
    float x2 = 0.0f;
    float u1 = 0.0f;

    // Generate the row quad by quad
    for (uint32_t i = 0; i < xCount; i++) {
        float stepX = xDivs[i];
        const float segment = stepX - previousStepX;

        if (i & 1) {
            x2 = x1 + floorf(segment * stretchX + 0.5f);
        } else {
            x2 = x1 + segment * rescaleX;
        }

        float uOffset = x1 == x2 ? 0.0f : 0.5 - (0.5 * segment / (x2 - x1));
        float u2 = fmax(0.0f, stepX - uOffset) / bitmapWidth;
        u1 += uOffset / bitmapWidth;

        if (stepX > 0.0f) {
            generateQuad(vertex, x1, y1, x2, y2, u1, v1, u2, v2, quadCount);
        }

        x1 = x2;
        u1 = stepX / bitmapWidth;

        previousStepX = stepX;
    }

    if (previousStepX != bitmapWidth) {
        x2 = width;
        generateQuad(vertex, x1, y1, x2, y2, u1, v1, 1.0f, v2, quadCount);
    }
}

void Patch::generateQuad(TextureVertex*& vertex, float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2, uint32_t& quadCount) {
    const uint32_t oldQuadCount = quadCount;
    quadCount++;

    if (x1 < 0.0f) x1 = 0.0f;
    if (x2 < 0.0f) x2 = 0.0f;
    if (y1 < 0.0f) y1 = 0.0f;
    if (y2 < 0.0f) y2 = 0.0f;

    // Skip degenerate and transparent (empty) quads
    if ((mColors[oldQuadCount] == 0) || x1 >= x2 || y1 >= y2) {
#if DEBUG_PATCHES_EMPTY_VERTICES
        PATCH_LOGD("    quad %d (empty)", oldQuadCount);
        PATCH_LOGD("        left,  top    = %.2f, %.2f\t\tu1, v1 = %.8f, %.8f", x1, y1, u1, v1);
        PATCH_LOGD("        right, bottom = %.2f, %.2f\t\tu2, v2 = %.8f, %.8f", x2, y2, u2, v2);
#endif
        return;
    }

    // Record all non empty quads
    if (hasEmptyQuads) {
        Rect bounds(x1, y1, x2, y2);
        quads.add(bounds);
    }

    mUvMapper.map(u1, v1, u2, v2);

    TextureVertex::set(vertex++, x1, y1, u1, v1);
    TextureVertex::set(vertex++, x2, y1, u2, v1);
    TextureVertex::set(vertex++, x1, y2, u1, v2);
    TextureVertex::set(vertex++, x2, y2, u2, v2);

    verticesCount += 4;
    indexCount += 6;

#if DEBUG_PATCHES_VERTICES
    PATCH_LOGD("    quad %d", oldQuadCount);
    PATCH_LOGD("        left,  top    = %.2f, %.2f\t\tu1, v1 = %.8f, %.8f", x1, y1, u1, v1);
    PATCH_LOGD("        right, bottom = %.2f, %.2f\t\tu2, v2 = %.8f, %.8f", x2, y2, u2, v2);
#endif
}

}; // namespace uirenderer
}; // namespace android
