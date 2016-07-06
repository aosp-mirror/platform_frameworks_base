/*
 * Copyright (C) 2011 The Android Open Source Project
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

#pragma once

#include <cutils/compiler.h>

#include "Layer.h"

#include <SkBitmap.h>

namespace android {
namespace uirenderer {

class RenderState;

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_LAYER_RENDERER
    #define LAYER_RENDERER_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define LAYER_RENDERER_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Renderer
///////////////////////////////////////////////////////////////////////////////

class LayerRenderer {
public:
    static Layer* createTextureLayer(RenderState& renderState);
    static void updateTextureLayer(Layer* layer, uint32_t width, uint32_t height,
            bool isOpaque, bool forceFilter, GLenum renderTarget, const float* textureTransform);
}; // class LayerRenderer

}; // namespace uirenderer
}; // namespace android
