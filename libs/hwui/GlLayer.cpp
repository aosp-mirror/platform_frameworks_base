/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "GlLayer.h"

#include "Caches.h"
#include "RenderNode.h"
#include "renderstate/RenderState.h"
#include "utils/TraceUtils.h"

#include <utils/Log.h>

#define ATRACE_LAYER_WORK(label)                                                       \
    ATRACE_FORMAT("%s HW Layer DisplayList %s %ux%u", label,                           \
                  (renderNode.get() != NULL) ? renderNode->getName() : "", getWidth(), \
                  getHeight())

namespace android {
namespace uirenderer {

GlLayer::GlLayer(RenderState& renderState, uint32_t layerWidth, uint32_t layerHeight,
                 sk_sp<SkColorFilter> colorFilter, int alpha, SkBlendMode mode, bool blend)
        : Layer(renderState, Api::OpenGL, colorFilter, alpha, mode)
        , caches(Caches::getInstance())
        , texture(caches) {
    texture.mWidth = layerWidth;
    texture.mHeight = layerHeight;
    texture.blend = blend;
}

GlLayer::~GlLayer() {
    // There's a rare possibility that Caches could have been destroyed already
    // since this method is queued up as a task.
    // Since this is a reset method, treat this as non-fatal.
    if (caches.isInitialized() && texture.mId) {
        texture.deleteTexture();
    }
}

void GlLayer::onGlContextLost() {
    texture.deleteTexture();
}

void GlLayer::setRenderTarget(GLenum renderTarget) {
    if (renderTarget != getRenderTarget()) {
        // new render target: bind with new target, and update filter/wrap
        texture.mTarget = renderTarget;
        if (texture.mId) {
            caches.textureState().bindTexture(texture.target(), texture.mId);
        }
        texture.setFilter(GL_NEAREST, false, true);
        texture.setWrap(GL_CLAMP_TO_EDGE, false, true);
    }
}

void GlLayer::generateTexture() {
    if (!texture.mId) {
        glGenTextures(1, &texture.mId);
    }
}

};  // namespace uirenderer
};  // namespace android
