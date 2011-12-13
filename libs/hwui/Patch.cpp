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

#include "Patch.h"
#include "Caches.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

Patch::Patch(const uint32_t xCount, const uint32_t yCount, const int8_t emptyQuads):
        mXCount(xCount), mYCount(yCount), mEmptyQuads(emptyQuads) {
    // Initialized with the maximum number of vertices we will need
    // 2 triangles per patch, 3 vertices per triangle
    uint32_t maxVertices = ((xCount + 1) * (yCount + 1) - emptyQuads) * 2 * 3;
    mVertices = new TextureVertex[maxVertices];
    mUploaded = false;

    verticesCount = 0;
    hasEmptyQuads = emptyQuads > 0;

    mColorKey = 0;
    mXDivs = new int32_t[mXCount];
    mYDivs = new int32_t[mYCount];

    PATCH_LOGD("    patch: xCount = %d, yCount = %d, emptyQuads = %d, max vertices = %d",
            xCount, yCount, emptyQuads, maxVertices);

    glGenBuffers(1, &meshBuffer);
}

Patch::~Patch() {
    delete[] mVertices;
    delete[] mXDivs;
    delete[] mYDivs;
    glDeleteBuffers(1, &meshBuffer);
}

///////////////////////////////////////////////////////////////////////////////
// Patch management
///////////////////////////////////////////////////////////////////////////////

void Patch::copy(const int32_t* xDivs, const int32_t* yDivs) {
    memcpy(mXDivs, xDivs, mXCount * sizeof(int32_t));
    memcpy(mYDivs, yDivs, mYCount * sizeof(int32_t));
}

void Patch::copy(const int32_t* yDivs) {
    memcpy(mYDivs, yDivs, mYCount * sizeof(int32_t));
}

void Patch::updateColorKey(const uint32_t colorKey) {
    mColorKey = colorKey;
}

bool Patch::matches(const int32_t* xDivs, const int32_t* yDivs, const uint32_t colorKey) {
    if (mColorKey != colorKey) {
        updateColorKey(colorKey);
        copy(xDivs, yDivs);
        return false;
    }

    for (uint32_t i = 0; i < mXCount; i++) {
        if (mXDivs[i] != xDivs[i]) {
            // The Y divs may or may not match, copy everything
            copy(xDivs, yDivs);
            return false;
        }
    }

    for (uint32_t i = 0; i < mYCount; i++) {
        if (mYDivs[i] != yDivs[i]) {
            // We know all the X divs match, copy only Y divs
            copy(yDivs);
            return false;
        }
    }

    return true;
}

///////////////////////////////////////////////////////////////////////////////
// Vertices management
///////////////////////////////////////////////////////////////////////////////

void Patch::updateVertices(const float bitmapWidth, const float bitmapHeight,
        float left, float top, float right, float bottom) {
#if RENDER_LAYERS_AS_REGIONS
    if (hasEmptyQuads) quads.clear();
#endif

    // Reset the vertices count here, we will count exactly how many
    // vertices we actually need when generating the quads
    verticesCount = 0;

    const uint32_t xStretchCount = (mXCount + 1) >> 1;
    const uint32_t yStretchCount = (mYCount + 1) >> 1;

    float stretchX = 0.0f;
    float stretchY = 0.0;

    const float meshWidth = right - left;

    if (xStretchCount > 0) {
        uint32_t stretchSize = 0;
        for (uint32_t i = 1; i < mXCount; i += 2) {
            stretchSize += mXDivs[i] - mXDivs[i - 1];
        }
        const float xStretchTex = stretchSize;
        const float fixed = bitmapWidth - stretchSize;
        const float xStretch = right - left - fixed;
        stretchX = xStretch / xStretchTex;
    }

    if (yStretchCount > 0) {
        uint32_t stretchSize = 0;
        for (uint32_t i = 1; i < mYCount; i += 2) {
            stretchSize += mYDivs[i] - mYDivs[i - 1];
        }
        const float yStretchTex = stretchSize;
        const float fixed = bitmapHeight - stretchSize;
        const float yStretch = bottom - top - fixed;
        stretchY = yStretch / yStretchTex;
    }

    TextureVertex* vertex = mVertices;
    uint32_t quadCount = 0;

    float previousStepY = 0.0f;

    float y1 = 0.0f;
    float y2 = 0.0f;
    float v1 = 0.0f;

    for (uint32_t i = 0; i < mYCount; i++) {
        float stepY = mYDivs[i];
        const float segment = stepY - previousStepY;

        if (i & 1) {
            y2 = y1 + floorf(segment * stretchY + 0.5f);
        } else {
            y2 = y1 + segment;
        }

        float vOffset = y1 == y2 ? 0.0f : 0.5 - (0.5 * segment / (y2 - y1));
        float v2 = fmax(0.0f, stepY - vOffset) / bitmapHeight;
        v1 += vOffset / bitmapHeight;

        if (stepY > 0.0f) {
#if DEBUG_EXPLODE_PATCHES
            y1 += i * EXPLODE_GAP;
            y2 += i * EXPLODE_GAP;
#endif
            generateRow(vertex, y1, y2, v1, v2, stretchX, right - left,
                    bitmapWidth, quadCount);
#if DEBUG_EXPLODE_PATCHES
            y2 -= i * EXPLODE_GAP;
#endif
        }

        y1 = y2;
        v1 = stepY / bitmapHeight;

        previousStepY = stepY;
    }

    if (previousStepY != bitmapHeight) {
        y2 = bottom - top;
#if DEBUG_EXPLODE_PATCHES
        y1 += mYCount * EXPLODE_GAP;
        y2 += mYCount * EXPLODE_GAP;
#endif
        generateRow(vertex, y1, y2, v1, 1.0f, stretchX, right - left, bitmapWidth, quadCount);
    }

    if (verticesCount > 0) {
        Caches& caches = Caches::getInstance();
        caches.bindMeshBuffer(meshBuffer);
        if (!mUploaded) {
            glBufferData(GL_ARRAY_BUFFER, sizeof(TextureVertex) * verticesCount,
                    mVertices, GL_DYNAMIC_DRAW);
            mUploaded = true;
        } else {
            glBufferSubData(GL_ARRAY_BUFFER, 0,
                    sizeof(TextureVertex) * verticesCount, mVertices);
        }
        caches.resetVertexPointers();
    }

    PATCH_LOGD("    patch: new vertices count = %d", verticesCount);
}

void Patch::generateRow(TextureVertex*& vertex, float y1, float y2, float v1, float v2,
        float stretchX, float width, float bitmapWidth, uint32_t& quadCount) {
    float previousStepX = 0.0f;

    float x1 = 0.0f;
    float x2 = 0.0f;
    float u1 = 0.0f;

    // Generate the row quad by quad
    for (uint32_t i = 0; i < mXCount; i++) {
        float stepX = mXDivs[i];
        const float segment = stepX - previousStepX;

        if (i & 1) {
            x2 = x1 + floorf(segment * stretchX + 0.5f);
        } else {
            x2 = x1 + segment;
        }

        float uOffset = x1 == x2 ? 0.0f : 0.5 - (0.5 * segment / (x2 - x1));
        float u2 = fmax(0.0f, stepX - uOffset) / bitmapWidth;
        u1 += uOffset / bitmapWidth;

        if (stepX > 0.0f) {
#if DEBUG_EXPLODE_PATCHES
            x1 += i * EXPLODE_GAP;
            x2 += i * EXPLODE_GAP;
#endif
            generateQuad(vertex, x1, y1, x2, y2, u1, v1, u2, v2, quadCount);
#if DEBUG_EXPLODE_PATCHES
            x2 -= i * EXPLODE_GAP;
#endif
        }

        x1 = x2;
        u1 = stepX / bitmapWidth;

        previousStepX = stepX;
    }

    if (previousStepX != bitmapWidth) {
        x2 = width;
#if DEBUG_EXPLODE_PATCHES
        x1 += mXCount * EXPLODE_GAP;
        x2 += mXCount * EXPLODE_GAP;
#endif
        generateQuad(vertex, x1, y1, x2, y2, u1, v1, 1.0f, v2, quadCount);
    }
}

void Patch::generateQuad(TextureVertex*& vertex, float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2, uint32_t& quadCount) {
    const uint32_t oldQuadCount = quadCount;
    quadCount++;

    // Skip degenerate and transparent (empty) quads
    if ((mColorKey >> oldQuadCount) & 0x1) {
#if DEBUG_PATCHES_EMPTY_VERTICES
        PATCH_LOGD("    quad %d (empty)", oldQuadCount);
        PATCH_LOGD("        left,  top    = %.2f, %.2f\t\tu1, v1 = %.4f, %.4f", x1, y1, u1, v1);
        PATCH_LOGD("        right, bottom = %.2f, %.2f\t\tu2, v2 = %.4f, %.4f", x2, y2, u2, v2);
#endif
        return;
    }

#if RENDER_LAYERS_AS_REGIONS
    // Record all non empty quads
    if (hasEmptyQuads) {
        Rect bounds(x1, y1, x2, y2);
        quads.add(bounds);
    }
#endif

    // Left triangle
    TextureVertex::set(vertex++, x1, y1, u1, v1);
    TextureVertex::set(vertex++, x2, y1, u2, v1);
    TextureVertex::set(vertex++, x1, y2, u1, v2);

    // Right triangle
    TextureVertex::set(vertex++, x1, y2, u1, v2);
    TextureVertex::set(vertex++, x2, y1, u2, v1);
    TextureVertex::set(vertex++, x2, y2, u2, v2);

    // A quad is made of 2 triangles, 6 vertices
    verticesCount += 6;

#if DEBUG_PATCHES_VERTICES
    PATCH_LOGD("    quad %d", oldQuadCount);
    PATCH_LOGD("        left,  top    = %.2f, %.2f\t\tu1, v1 = %.4f, %.4f", x1, y1, u1, v1);
    PATCH_LOGD("        right, bottom = %.2f, %.2f\t\tu2, v2 = %.4f, %.4f", x2, y2, u2, v2);
#endif
}

}; // namespace uirenderer
}; // namespace android
