/*
 * Copyright (C) 2016 The Android Open Source Project
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
#include "LayerDrawable.h"
#include "VkLayer.h"

#include "GrBackendSurface.h"
#include "SkColorFilter.h"
#include "SkSurface.h"
#include "gl/GrGLTypes.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

void LayerDrawable::onDraw(SkCanvas* canvas) {
    DrawLayer(canvas->getGrContext(), canvas, mLayer.get());
}

bool LayerDrawable::DrawLayer(GrContext* context, SkCanvas* canvas, Layer* layer) {
    // transform the matrix based on the layer
    int saveCount = -1;
    if (!layer->getTransform().isIdentity()) {
        saveCount = canvas->save();
        SkMatrix transform;
        layer->getTransform().copyTo(transform);
        canvas->concat(transform);
    }

    sk_sp<SkImage> layerImage;
    if (layer->getApi() == Layer::Api::OpenGL) {
        GlLayer* glLayer = static_cast<GlLayer*>(layer);
        GrGLTextureInfo externalTexture;
        externalTexture.fTarget = glLayer->getRenderTarget();
        externalTexture.fID = glLayer->getTextureId();
        GrBackendTexture backendTexture(glLayer->getWidth(), glLayer->getHeight(),
                kRGBA_8888_GrPixelConfig, externalTexture);
        layerImage = SkImage::MakeFromTexture(context, backendTexture, kTopLeft_GrSurfaceOrigin,
                kPremul_SkAlphaType, nullptr);
    } else {
        SkASSERT(layer->getApi() == Layer::Api::Vulkan);
        VkLayer* vkLayer = static_cast<VkLayer*>(layer);
        canvas->clear(SK_ColorGREEN);
        layerImage = vkLayer->getImage();
    }

    if (layerImage) {
        SkPaint paint;
        paint.setAlpha(layer->getAlpha());
        paint.setBlendMode(layer->getMode());
        paint.setColorFilter(sk_ref_sp(layer->getColorFilter()));
        canvas->drawImage(layerImage, 0, 0, &paint);
    }
    // restore the original matrix
    if (saveCount >= 0) {
        canvas->restoreToCount(saveCount);
    }

    return layerImage;
}

}; // namespace skiapipeline
}; // namespace uirenderer
}; // namespace android
