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

#ifndef ANDROID_HWUI_LAYER_RENDERER_H
#define ANDROID_HWUI_LAYER_RENDERER_H

#include <cutils/compiler.h>

#include "OpenGLRenderer.h"
#include "Layer.h"

#include <SkBitmap.h>

namespace android {
namespace uirenderer {

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

class LayerRenderer: public OpenGLRenderer {
public:
    ANDROID_API LayerRenderer(Layer* layer);
    virtual ~LayerRenderer();

    virtual void prepareDirty(float left, float top, float right, float bottom, bool opaque);
    virtual void finish();

    virtual bool hasLayer();
    virtual Region* getRegion();
    virtual GLint getTargetFbo();

    ANDROID_API static Layer* createTextureLayer(bool isOpaque);
    ANDROID_API static Layer* createLayer(uint32_t width, uint32_t height, bool isOpaque = false);
    ANDROID_API static bool resizeLayer(Layer* layer, uint32_t width, uint32_t height);
    ANDROID_API static void updateTextureLayer(Layer* layer, uint32_t width, uint32_t height,
            bool isOpaque, GLenum renderTarget, float* transform);
    ANDROID_API static void destroyLayer(Layer* layer);
    ANDROID_API static void destroyLayerDeferred(Layer* layer);
    ANDROID_API static bool copyLayer(Layer* layer, SkBitmap* bitmap);

private:
    void generateMesh();

    Layer* mLayer;
}; // class LayerRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_LAYER_RENDERER_H
