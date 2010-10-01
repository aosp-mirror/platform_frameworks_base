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

/**
 * Dimensions of a layer.
 */
struct LayerSize {
    LayerSize(): width(0), height(0), id(0) { }
    LayerSize(const uint32_t width, const uint32_t height): width(width), height(height), id(0) { }
    LayerSize(const LayerSize& size): width(size.width), height(size.height), id(size.id) { }

    uint32_t width;
    uint32_t height;

    // Incremental id used by the layer cache to store multiple
    // LayerSize with the same dimensions
    uint32_t id;

    bool operator<(const LayerSize& rhs) const {
        if (id != 0 && rhs.id != 0 && id != rhs.id) {
            return id < rhs.id;
        }
        if (width == rhs.width) {
            return height < rhs.height;
        }
        return width < rhs.width;
    }

    bool operator==(const LayerSize& rhs) const {
        return id == rhs.id && width == rhs.width && height == rhs.height;
    }
}; // struct LayerSize

/**
 * A layer has dimensions and is backed by an OpenGL texture.
 */
struct Layer {
    /**
     * Coordinates of the layer.
     */
    Rect layer;
    /**
     * Name of the texture used to render the layer.
     */
    GLuint texture;
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
}; // struct Layer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_LAYER_H
