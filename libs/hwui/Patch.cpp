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

#include "Patch.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

Patch::Patch(const uint32_t xCount, const uint32_t yCount) {
    // 2 triangles per patch, 3 vertices per triangle
    verticesCount = (xCount + 1) * (yCount + 1) * 2 * 3;
    vertices = new TextureVertex[verticesCount];
}

Patch::~Patch() {
    delete[] vertices;
}

///////////////////////////////////////////////////////////////////////////////
// Vertices management
///////////////////////////////////////////////////////////////////////////////

void Patch::updateVertices(const float bitmapWidth, const float bitmapHeight,
        float left, float top, float right, float bottom,
        const int32_t* xDivs, const int32_t* yDivs, const uint32_t width, const uint32_t height) {
    const uint32_t xStretchCount = (width + 1) >> 1;
    const uint32_t yStretchCount = (height + 1) >> 1;

    float stretchX = 0.0f;
    float stretchY = 0.0;

    const float meshWidth = right - left;

    if (xStretchCount > 0) {
        uint32_t stretchSize = 0;
        for (uint32_t i = 1; i < width; i += 2) {
            stretchSize += xDivs[i] - xDivs[i - 1];
        }
        const float xStretchTex = stretchSize;
        const float fixed = bitmapWidth - stretchSize;
        const float xStretch = right - left - fixed;
        stretchX = xStretch / xStretchTex;
    }

    if (yStretchCount > 0) {
        uint32_t stretchSize = 0;
        for (uint32_t i = 1; i < height; i += 2) {
            stretchSize += yDivs[i] - yDivs[i - 1];
        }
        const float yStretchTex = stretchSize;
        const float fixed = bitmapHeight - stretchSize;
        const float yStretch = bottom - top - fixed;
        stretchY = yStretch / yStretchTex;
    }

    TextureVertex* vertex = vertices;

    float previousStepY = 0.0f;

    float y1 = 0.0f;
    float v1 = 0.0f;

    for (uint32_t i = 0; i < height; i++) {
        float stepY = yDivs[i];

        float y2 = 0.0f;
        if (i & 1) {
            const float segment = stepY - previousStepY;
            y2 = y1 + segment * stretchY;
        } else {
            y2 = y1 + stepY - previousStepY;
        }
        float v2 = fmax(0.0f, stepY - 0.5f) / bitmapHeight;

        generateRow(vertex, y1, y2, v1, v2, xDivs, width, stretchX,
                right - left, bitmapWidth);

        y1 = y2;
        v1 = (stepY + 0.5f) / bitmapHeight;

        previousStepY = stepY;
    }

    generateRow(vertex, y1, bottom - top, v1, 1.0f, xDivs, width, stretchX,
            right - left, bitmapWidth);
}

inline void Patch::generateRow(TextureVertex*& vertex, float y1, float y2, float v1, float v2,
        const int32_t xDivs[], uint32_t xCount, float stretchX, float width, float bitmapWidth) {
    float previousStepX = 0.0f;

    float x1 = 0.0f;
    float u1 = 0.0f;

    // Generate the row quad by quad
    for (uint32_t i = 0; i < xCount; i++) {
        float stepX = xDivs[i];

        float x2 = 0.0f;
        if (i & 1) {
            const float segment = stepX - previousStepX;
            x2 = x1 + segment * stretchX;
        } else {
            x2 = x1 + stepX - previousStepX;
        }
        float u2 = fmax(0.0f, stepX - 0.5f) / bitmapWidth;

        generateQuad(vertex, x1, y1, x2, y2, u1, v1, u2, v2);

        x1 = x2;
        u1 = (stepX + 0.5f) / bitmapWidth;

        previousStepX = stepX;
    }

    generateQuad(vertex, x1, y1, width, y2, u1, v1, 1.0f, v2);
}

inline void Patch::generateQuad(TextureVertex*& vertex, float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2) {
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
