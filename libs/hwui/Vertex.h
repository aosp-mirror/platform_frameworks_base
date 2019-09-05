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

#ifndef ANDROID_HWUI_VERTEX_H
#define ANDROID_HWUI_VERTEX_H

#include "Vector.h"

#include "utils/Macros.h"

namespace android {
namespace uirenderer {

/**
 * Simple structure to describe a vertex with a position and a texture.
 */
struct Vertex {
    /**
     * Fudge-factor used to disambiguate geometry pixel positioning.
     *
     * Used to offset lines and points to avoid ambiguous intersection with pixel centers (see
     * Program::set()), and used to make geometry damage rect calculation conservative (see
     * Rect::snapGeometryToPixelBoundaries())
     */
    static float GeometryFudgeFactor() { return 0.0656f; }

    float x, y;

    static inline void set(Vertex* vertex, float x, float y) {
        vertex->x = x;
        vertex->y = y;
    }

    static inline void set(Vertex* vertex, Vector2 val) { set(vertex, val.x, val.y); }

    static inline void copyWithOffset(Vertex* vertex, const Vertex& src, float x, float y) {
        set(vertex, src.x + x, src.y + y);
    }

};  // struct Vertex

REQUIRE_COMPATIBLE_LAYOUT(Vertex);

/**
 * Simple structure to describe a vertex with a position and texture UV.
 */
struct TextureVertex {
    float x, y;
    float u, v;

    static inline void set(TextureVertex* vertex, float x, float y, float u, float v) {
        *vertex = {x, y, u, v};
    }

    static inline void setUV(TextureVertex* vertex, float u, float v) {
        vertex[0].u = u;
        vertex[0].v = v;
    }
};  // struct TextureVertex

REQUIRE_COMPATIBLE_LAYOUT(TextureVertex);

}  // namespace uirenderer
}  // namespace android

#endif  // ANDROID_HWUI_VERTEX_H
