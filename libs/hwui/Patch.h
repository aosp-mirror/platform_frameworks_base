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

#include <cstring>

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
    Patch(const uint32_t xCount, const uint32_t yCount): xCount(xCount + 2), yCount(yCount + 2) {
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

    ~Patch() {
        delete indices;
        delete vertices;
    }

    void dump() {
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

    uint32_t xCount;
    uint32_t yCount;

    uint16_t* indices;
    uint32_t indicesCount;

    TextureVertex* vertices;
    uint32_t verticesCount;
}; // struct Patch

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_PATCH_H
