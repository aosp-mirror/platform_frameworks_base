/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include "Layer.h"

#include "Caches.h"
#include "RenderNode.h"
#include "renderstate/RenderState.h"
#include "utils/TraceUtils.h"

#include <utils/Log.h>

#define ATRACE_LAYER_WORK(label) \
    ATRACE_FORMAT("%s HW Layer DisplayList %s %ux%u", \
            label, \
            (renderNode.get() != NULL) ? renderNode->getName() : "", \
            getWidth(), getHeight())

namespace android {
namespace uirenderer {

Layer::Layer(RenderState& renderState, uint32_t layerWidth, uint32_t layerHeight)
        : GpuMemoryTracker(GpuObjectType::Layer)
        , state(State::Uncached)
        , caches(Caches::getInstance())
        , renderState(renderState)
        , texture(caches) {
    // TODO: This is a violation of Android's typical ref counting, but it
    // preserves the old inc/dec ref locations. This should be changed...
    incStrong(nullptr);
    renderTarget = GL_TEXTURE_2D;
    texture.mWidth = layerWidth;
    texture.mHeight = layerHeight;
    renderState.registerLayer(this);
}

Layer::~Layer() {
    renderState.unregisterLayer(this);
    SkSafeUnref(colorFilter);

    if (texture.mId) {
        texture.deleteTexture();
    }

    delete[] mesh;
}

void Layer::onGlContextLost() {
    texture.deleteTexture();
}

void Layer::setColorFilter(SkColorFilter* filter) {
    SkRefCnt_SafeAssign(colorFilter, filter);
}

void Layer::bindTexture() const {
    if (texture.mId) {
        caches.textureState().bindTexture(renderTarget, texture.mId);
    }
}

void Layer::generateTexture() {
    if (!texture.mId) {
        glGenTextures(1, &texture.mId);
    }
}

void Layer::clearTexture() {
    // There's a rare possibility that Caches could have been destroyed already
    // since this method is queued up as a task.
    // Since this is a reset method, treat this as non-fatal.
    if (caches.isInitialized()) {
        caches.textureState().unbindTexture(texture.mId);
    }
    texture.mId = 0;
}

void Layer::postDecStrong() {
    renderState.postDecStrong(this);
}

}; // namespace uirenderer
}; // namespace android
