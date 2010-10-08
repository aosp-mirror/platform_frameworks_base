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

#ifndef ANDROID_UI_LAYER_H
#define ANDROID_UI_LAYER_H

#include <sys/types.h>

#include <GLES2/gl2.h>

#include <SkXfermode.h>

#include "Rect.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Layers
///////////////////////////////////////////////////////////////////////////////

/**
 * A layer has dimensions and is backed by an OpenGL texture or FBO.
 */
struct Layer {
    Layer(const uint32_t layerWidth, const uint32_t layerHeight):
            width(layerWidth), height(layerHeight) {
    }

    /**
     * Bounds of the layer.
     */
    Rect layer;
    /**
     * Texture coordinates of the layer.
     */
    Rect texCoords;

    /**
     * Name of the FBO used to render the layer. If the name is 0
     * this layer is not backed by an FBO, but a simple texture.
     */
    GLuint fbo;

    /**
     * Opacity of the layer.
     */
    int alpha;
    /**
     * Blending mode of the layer.
     */
    SkXfermode::Mode mode;
    /**
     * Indicates whether this layer should be blended.
     */
    bool blend;

    /**
     * Indicates whether this layer has been used already.
     */
    bool empty;

    /**
     * Name of the texture used to render the layer.
     */
    GLuint texture;
    /**
     * Width of the layer texture.
     */
    uint32_t width;
    /**
     * Height of the layer texture.
     */
    uint32_t height;
}; // struct Layer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_LAYER_H
