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

#include <cstring>

#include <utils/Log.h>

#include "Patch.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

Patch::Patch(const uint32_t xCount, const uint32_t yCount):
        xCount(xCount + 2), yCount(yCount + 2) {
    verticesCount = (xCount + 2) * (yCount + 2);
    vertices = new TextureVertex[verticesCount];

    // 2 triangles per patch, 3 vertices per triangle
    indicesCount = (xCount + 1) * (yCount + 1) * 2 * 3;
    indices = new uint16_t[indicesCount];

    const uint32_t xNum = xCount + 1;
    const uint32_t yNum = yCount + 1;

    uint16_t* startIndices = indices;
    uint32_t n = 0;
    for (uint32_t y = 0; y < yNum; y++) {
        for (uint32_t x = 0; x < xNum; x++) {
            *startIndices++ = n;
            *startIndices++ = n + 1;
            *startIndices++ = n + xNum + 2;

            *startIndices++ = n;
            *startIndices++ = n + xNum + 2;
            *startIndices++ = n + xNum + 1;

            n += 1;
        }
        n += 1;
    }
}

Patch::~Patch() {
    delete indices;
    delete vertices;
}

///////////////////////////////////////////////////////////////////////////////
// Vertices management
///////////////////////////////////////////////////////////////////////////////

void Patch::updateVertices(const SkBitmap* bitmap, float left, float top, float right,
        float bottom, const int32_t* xDivs,  const int32_t* yDivs, const uint32_t width,
        const uint32_t height) {
    const uint32_t xStretchCount = (width + 1) >> 1;
    const uint32_t yStretchCount = (height + 1) >> 1;

    float xStretch = 0;
    float yStretch = 0;
    float xStretchTex = 0;
    float yStretchTex = 0;

    const float meshWidth = right - left;

    const float bitmapWidth = float(bitmap->width());
    const float bitmapHeight = float(bitmap->height());

    if (xStretchCount > 0) {
        uint32_t stretchSize = 0;
        for (uint32_t i = 1; i < width; i += 2) {
            stretchSize += xDivs[i] - xDivs[i - 1];
        }
        xStretchTex = (stretchSize / bitmapWidth) / xStretchCount;
        const float fixed = bitmapWidth - stretchSize;
        xStretch = (right - left - fixed) / xStretchCount;
    }

    if (yStretchCount > 0) {
        uint32_t stretchSize = 0;
        for (uint32_t i = 1; i < height; i += 2) {
            stretchSize += yDivs[i] - yDivs[i - 1];
        }
        yStretchTex = (stretchSize / bitmapHeight) / yStretchCount;
        const float fixed = bitmapHeight - stretchSize;
        yStretch = (bottom - top - fixed) / yStretchCount;
    }

    float vy = 0.0f;
    float ty = 0.0f;
    TextureVertex* vertex = vertices;

    generateVertices(vertex, 0.0f, 0.0f, xDivs, width, xStretch, xStretchTex,
            meshWidth, bitmapWidth);
    vertex += width + 2;

    for (uint32_t y = 0; y < height; y++) {
        if (y & 1) {
            vy += yStretch;
            ty += yStretchTex;
        } else {
            const float step = float(yDivs[y]);
            vy += step;
            ty += step / bitmapHeight;
        }
        generateVertices(vertex, vy, ty, xDivs, width, xStretch, xStretchTex,
                meshWidth, bitmapWidth);
        vertex += width + 2;
    }

    generateVertices(vertex, bottom - top, 1.0f, xDivs, width, xStretch, xStretchTex,
            meshWidth, bitmapWidth);
}

inline void Patch::generateVertices(TextureVertex* vertex, float y, float v,
        const int32_t xDivs[], uint32_t xCount, float xStretch, float xStretchTex,
        float width, float widthTex) {
    float vx = 0.0f;
    float tx = 0.0f;

    TextureVertex::set(vertex, vx, y, tx, v);
    vertex++;

    for (uint32_t x = 0; x < xCount; x++) {
        if (x & 1) {
            vx += xStretch;
            tx += xStretchTex;
        } else {
            const float step = float(xDivs[x]);
            vx += step;
            tx += step / widthTex;
        }

        TextureVertex::set(vertex, vx, y, tx, v);
        vertex++;
    }

    TextureVertex::set(vertex, width, y, 1.0f, v);
    vertex++;
}

///////////////////////////////////////////////////////////////////////////////
// Debug tools
///////////////////////////////////////////////////////////////////////////////

void Patch::dump() {
    LOGD("Vertices [");
    for (uint32_t y = 0; y < yCount; y++) {
        char buffer[512];
        buffer[0] = '\0';
        uint32_t offset = 0;
        for (uint32_t x = 0; x < xCount; x++) {
            TextureVertex* v = &vertices[y * xCount + x];
            offset += sprintf(&buffer[offset], " (%.4f,%.4f)-(%.4f,%.4f)",
                    v->position[0], v->position[1], v->texture[0], v->texture[1]);
        }
        LOGD("  [%s ]", buffer);
    }
    LOGD("]\nIndices [ ");
    char buffer[4096];
    buffer[0] = '\0';
    uint32_t offset = 0;
    for (uint32_t i = 0; i < indicesCount; i++) {
        offset += sprintf(&buffer[offset], "%d ", indices[i]);
    }
    LOGD("  %s\n]", buffer);
}

}; // namespace uirenderer
}; // namespace android
