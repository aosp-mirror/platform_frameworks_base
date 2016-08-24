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

class LayerRenderer: public OpenGLRenderer {
public:
    LayerRenderer(RenderState& renderState, Layer* layer);
    virtual ~LayerRenderer();

    virtual void onViewportInitialized() override { /* do nothing */ }
    virtual void prepareDirty(int viewportWidth, int viewportHeight,
            float left, float top, float right, float bottom, bool opaque) override;
    virtual void clear(float left, float top, float right, float bottom, bool opaque) override;
    virtual bool finish() override;

    static Layer* createTextureLayer(RenderState& renderState);
    static Layer* createRenderLayer(RenderState& renderState, uint32_t width, uint32_t height);
    static bool resizeLayer(Layer* layer, uint32_t width, uint32_t height);
    static void updateTextureLayer(Layer* layer, uint32_t width, uint32_t height,
            bool isOpaque, bool forceFilter, GLenum renderTarget, const float* textureTransform);
    static void destroyLayer(Layer* layer);
    static bool copyLayer(RenderState& renderState, Layer* layer, SkBitmap* bitmap);

    static void flushLayer(RenderState& renderState, Layer* layer);

protected:
    virtual void ensureStencilBuffer() override;
    virtual bool hasLayer() const override;
    virtual Region* getRegion() const override;
    virtual GLuint getTargetFbo() const override;
    virtual bool suppressErrorChecks() const override;

private:
    void generateMesh();

    Layer* mLayer;
}; // class LayerRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_LAYER_RENDERER_H
