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

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

Patch::Patch(const uint32_t xCount, const uint32_t yCount, const int8_t emptyQuads):
        mXCount(xCount), mYCount(yCount) {
    // 2 triangles per patch, 3 vertices per triangle
    verticesCount = ((xCount + 1) * (yCount + 1) - emptyQuads) * 2 * 3;
    mVertices = new TextureVertex[verticesCount];
    hasEmptyQuads = emptyQuads > 0;
    mUploaded = false;

    mColorKey = 0;
    mXDivs = new int32_t[mXCount];
    mYDivs = new int32_t[mYCount];

    PATCH_LOGD("    patch: xCount = %d, yCount = %d, emptyQuads = %d, vertices = %d",
            xCount, yCount, emptyQuads, verticesCount);

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
    if (hasEmptyQuads) quads.clear();

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
    float v1 = 0.0f;

    for (uint32_t i = 0; i < mYCount; i++) {
        float stepY = mYDivs[i];

        float y2 = 0.0f;
        if (i & 1) {
            const float segment = stepY - previousStepY;
            y2 = y1 + segment * stretchY;
        } else {
            y2 = y1 + stepY - previousStepY;
        }
        float v2 = fmax(0.0f, stepY - 0.5f) / bitmapHeight;

        generateRow(vertex, y1, y2, v1, v2, stretchX, right - left, bitmapWidth, quadCount);

        y1 = y2;
        v1 = (stepY + 0.5f) / bitmapHeight;

        previousStepY = stepY;
    }

    generateRow(vertex, y1, bottom - top, v1, 1.0f, stretchX, right - left,
            bitmapWidth, quadCount);

    Caches::getInstance().bindMeshBuffer(meshBuffer);
    if (!mUploaded) {
        glBufferData(GL_ARRAY_BUFFER, sizeof(TextureVertex) * verticesCount,
                mVertices, GL_DYNAMIC_DRAW);
        mUploaded = true;
    } else {
        glBufferSubData(GL_ARRAY_BUFFER, 0,
                sizeof(TextureVertex) * verticesCount, mVertices);
    }
}

void Patch::generateRow(TextureVertex*& vertex, float y1, float y2, float v1, float v2,
        float stretchX, float width, float bitmapWidth, uint32_t& quadCount) {
    float previousStepX = 0.0f;

    float x1 = 0.0f;
    float u1 = 0.0f;

    // Generate the row quad by quad
    for (uint32_t i = 0; i < mXCount; i++) {
        float stepX = mXDivs[i];

        float x2 = 0.0f;
        if (i & 1) {
            const float segment = stepX - previousStepX;
            x2 = x1 + segment * stretchX;
        } else {
            x2 = x1 + stepX - previousStepX;
        }
        float u2 = fmax(0.0f, stepX - 0.5f) / bitmapWidth;

        generateQuad(vertex, x1, y1, x2, y2, u1, v1, u2, v2, quadCount);

        x1 = x2;
        u1 = (stepX + 0.5f) / bitmapWidth;

        previousStepX = stepX;
    }

    generateQuad(vertex, x1, y1, width, y2, u1, v1, 1.0f, v2, quadCount);
}

void Patch::generateQuad(TextureVertex*& vertex, float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2, uint32_t& quadCount) {
    uint32_t oldQuadCount = quadCount;

    // Degenerate quads are an artifact of our implementation and should not
    // be taken into account when checking for transparent quads
    if (x2 - x1 > 0.999f && y2 - y1 > 0.999f) {
        quadCount++;
    }

    if (((mColorKey >> oldQuadCount) & 0x1) == 1) {
        return;
    }

    if (hasEmptyQuads) {
        Rect bounds(x1, y1, x2, y2);
        quads.add(bounds);
    }

    // Left triangle
    TextureVertex::set(vertex++, x1, y1, u1, v1);
    TextureVertex::set(vertex++, x2, y1, u2, v1);
    TextureVertex::set(vertex++, x1, y2, u1, v2);

    // Right triangle
    TextureVertex::set(vertex++, x1, y2, u1, v2);
    TextureVertex::set(vertex++, x2, y1, u2, v1);
    TextureVertex::set(vertex++, x2, y2, u2, v2);
}

}; // namespace uirenderer
}; // namespace android
