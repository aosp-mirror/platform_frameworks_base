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

namespace android {
namespace uirenderer {

/**
 * Simple structure to describe a vertex with a position and a texture.
 */
struct Vertex {
    float position[2];

    static inline void set(Vertex* vertex, float x, float y) {
        vertex[0].position[0] = x;
        vertex[0].position[1] = y;
    }
}; // struct Vertex

/**
 * Simple structure to describe a vertex with a position and texture UV.
 */
struct TextureVertex {
    float position[2];
    float texture[2];

    static inline void set(TextureVertex* vertex, float x, float y, float u, float v) {
        vertex[0].position[0] = x;
        vertex[0].position[1] = y;
        vertex[0].texture[0] = u;
        vertex[0].texture[1] = v;
    }

    static inline void setUV(TextureVertex* vertex, float u, float v) {
        vertex[0].texture[0] = u;
        vertex[0].texture[1] = v;
    }
}; // struct TextureVertex

/**
 * Simple structure to describe a vertex with a position, texture UV and ARGB color.
 */
struct ColorTextureVertex : TextureVertex {
    float color[4];

    static inline void set(ColorTextureVertex* vertex, float x, float y,
            float u, float v, int color) {
        TextureVertex::set(vertex, x, y, u, v);

        const float a = ((color >> 24) & 0xff) / 255.0f;
        vertex[0].color[0] = a * ((color >> 16) & 0xff) / 255.0f;
        vertex[0].color[1] = a * ((color >>  8) & 0xff) / 255.0f;
        vertex[0].color[2] = a * ((color      ) & 0xff) / 255.0f;
        vertex[0].color[3] = a;
    }
}; // struct ColorTextureVertex

/**
 * Simple structure to describe a vertex with a position and an alpha value.
 */
struct AlphaVertex : Vertex {
    float alpha;

    static inline void set(AlphaVertex* vertex, float x, float y, float alpha) {
        Vertex::set(vertex, x, y);
        vertex[0].alpha = alpha;
    }

    static inline void setColor(AlphaVertex* vertex, float alpha) {
        vertex[0].alpha = alpha;
    }
}; // struct AlphaVertex

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_VERTEX_H
