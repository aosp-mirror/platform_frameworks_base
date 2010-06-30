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

#ifndef ANDROID_UI_TEXTURE_H
#define ANDROID_UI_TEXTURE_H

#include <GLES2/gl2.h>

namespace android {
namespace uirenderer {

/**
 * Represents an OpenGL texture.
 */
struct Texture {
    /**
     * Name of the texture.
     */
    GLuint id;
    /**
     * Indicates whether the texture requires blending.
     */
    bool blend;
    /**
     * Width of the backing bitmap.
     */
    unsigned int width;
    /**
     * Height of the backing bitmap.
     */
    unsigned int height;
}; // struct Texture

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_TEXTURE_H
