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

#include "LayerRenderer.h"
#include "Matrix.h"
#include "Properties.h"
#include "Rect.h"
#include "renderstate/RenderState.h"
#include "utils/GLUtils.h"
#include "utils/TraceUtils.h"

#include <ui/Rect.h>

#include <private/hwui/DrawGlInfo.h>

namespace android {
namespace uirenderer {

Layer* LayerRenderer::createTextureLayer(RenderState& renderState) {
    LAYER_RENDERER_LOGD("Creating new texture layer");

    Layer* layer = new Layer(renderState, 0, 0);
    layer->layer.set(0.0f, 0.0f, 0.0f, 0.0f);
    layer->texCoords.set(0.0f, 1.0f, 1.0f, 0.0f);
    layer->setRenderTarget(GL_NONE); // see ::updateTextureLayer()

    Caches::getInstance().textureState().activateTexture(0);
    layer->generateTexture();

    return layer;
}

void LayerRenderer::updateTextureLayer(Layer* layer, uint32_t width, uint32_t height,
    bool isOpaque, bool forceFilter, GLenum renderTarget, const float* textureTransform) {
    layer->setBlend(!isOpaque);
    layer->setForceFilter(forceFilter);
    layer->setSize(width, height);
    layer->layer.set(0.0f, 0.0f, width, height);
    layer->getTexTransform().load(textureTransform);

    if (renderTarget != layer->getRenderTarget()) {
        layer->setRenderTarget(renderTarget);
        layer->bindTexture();
        layer->setFilter(GL_NEAREST, false, true);
        layer->setWrap(GL_CLAMP_TO_EDGE, false, true);
    }
}

}; // namespace uirenderer
}; // namespace android
