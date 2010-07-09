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

#ifndef ANDROID_UI_VERTEX_H
#define ANDROID_UI_VERTEX_H

namespace android {
namespace uirenderer {

/**
 * Simple structure to describe a vertex with a position.
 * This is used to draw filled rectangles without a texture.
 */
struct SimpleVertex {
    float position[2];
}; // struct SimpleVertex

/**
 * Simple structure to describe a vertex with a position and a texture.
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

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_VERTEX_H
