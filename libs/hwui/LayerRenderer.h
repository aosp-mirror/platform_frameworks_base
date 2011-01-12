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

#include "OpenGLRenderer.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_LAYER_RENDERER
    #define LAYER_RENDERER_LOGD(...) LOGD(__VA_ARGS__)
#else
    #define LAYER_RENDERER_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Renderer
///////////////////////////////////////////////////////////////////////////////

class LayerRenderer: public OpenGLRenderer {
public:
    LayerRenderer(GLuint fbo): mFbo(fbo) {
    }

    ~LayerRenderer() {
    }

    void prepare(bool opaque);
    void finish();

    static GLuint createLayer(uint32_t width, uint32_t height,
            uint32_t* layerWidth, uint32_t* layerHeight, GLuint* texture);
    static void resizeLayer(GLuint fbo, GLuint texture, uint32_t width, uint32_t height,
            uint32_t* layerWidth, uint32_t* layerHeight);
    static void destroyLayer(GLuint fbo, GLuint texture);
    static void destroyLayerDeferred(GLuint fbo, GLuint texture);

private:
    GLuint mFbo;
    GLuint mPreviousFbo;

}; // class LayerRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_LAYER_RENDERER_H
