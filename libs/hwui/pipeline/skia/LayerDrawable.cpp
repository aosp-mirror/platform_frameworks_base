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

#include "LayerDrawable.h"
#include "SkColorFilter.h"
#include "gl/GrGLTypes.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

void LayerDrawable::onDraw(SkCanvas* canvas) {
    // transform the matrix based on the layer
    int saveCount = -1;
    if (!mLayer->getTransform().isIdentity()) {
        saveCount = canvas->save();
        SkMatrix transform;
        mLayer->getTransform().copyTo(transform);
        canvas->concat(transform);
    }
    GrGLTextureInfo externalTexture;
    externalTexture.fTarget = mLayer->getRenderTarget();
    externalTexture.fID = mLayer->getTextureId();
    GrContext* context = canvas->getGrContext();
    GrBackendTextureDesc textureDescription;
    textureDescription.fWidth = mLayer->getWidth();
    textureDescription.fHeight = mLayer->getHeight();
    textureDescription.fConfig = kRGBA_8888_GrPixelConfig;
    textureDescription.fOrigin = kTopLeft_GrSurfaceOrigin;
    textureDescription.fTextureHandle = reinterpret_cast<GrBackendObject>(&externalTexture);
    sk_sp<SkImage> layerImage = SkImage::MakeFromTexture(context, textureDescription);
    if (layerImage) {
        SkPaint paint;
        paint.setAlpha(mLayer->getAlpha());
        paint.setBlendMode(mLayer->getMode());
        paint.setColorFilter(sk_ref_sp(mLayer->getColorFilter()));
        canvas->drawImage(layerImage, 0, 0, &paint);
    }
    // restore the original matrix
    if (saveCount >= 0) {
        canvas->restoreToCount(saveCount);
    }
}

}; // namespace skiapipeline
}; // namespace uirenderer
}; // namespace android
