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

#include <shaders/shaders.h>
#include <utils/Color.h>
#include <utils/MathUtils.h>

#include "DeviceInfo.h"
#include "GrBackendSurface.h"
#include "SkColorFilter.h"
#include "SkRuntimeEffect.h"
#include "SkSurface.h"
#include "gl/GrGLTypes.h"
#include "math/mat4.h"
#include "system/graphics-base-v1.0.h"
#include "system/window.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

void LayerDrawable::onDraw(SkCanvas* canvas) {
    Layer* layer = mLayerUpdater->backingLayer();
    if (layer) {
        SkRect srcRect = layer->getCurrentCropRect();
        DrawLayer(canvas->recordingContext(), canvas, layer, &srcRect, nullptr, true);
    }
}

static inline SkScalar isIntegerAligned(SkScalar x) {
    return MathUtils::isZero(roundf(x) - x);
}

// Disable filtering when there is no scaling in screen coordinates and the corners have the same
// fraction (for translate) or zero fraction (for any other rect-to-rect transform).
static bool shouldFilterRect(const SkMatrix& matrix, const SkRect& srcRect, const SkRect& dstRect) {
    if (!matrix.rectStaysRect()) return true;
    SkRect dstDevRect = matrix.mapRect(dstRect);
    float dstW, dstH;
    if (MathUtils::isZero(matrix.getScaleX()) && MathUtils::isZero(matrix.getScaleY())) {
        // Has a 90 or 270 degree rotation, although total matrix may also have scale factors
        // in m10 and m01. Those scalings are automatically handled by mapRect so comparing
        // dimensions is sufficient, but swap width and height comparison.
        dstW = dstDevRect.height();
        dstH = dstDevRect.width();
    } else {
        // Handle H/V flips or 180 rotation matrices. Axes may have been mirrored, but
        // dimensions are still safe to compare directly.
        dstW = dstDevRect.width();
        dstH = dstDevRect.height();
    }
    if (!(MathUtils::areEqual(dstW, srcRect.width()) &&
          MathUtils::areEqual(dstH, srcRect.height()))) {
        return true;
    }
    // Device rect and source rect should be integer aligned to ensure there's no difference
    // in how nearest-neighbor sampling is resolved.
    return !(isIntegerAligned(srcRect.x()) &&
             isIntegerAligned(srcRect.y()) &&
             isIntegerAligned(dstDevRect.x()) &&
             isIntegerAligned(dstDevRect.y()));
}

static sk_sp<SkShader> createLinearEffectShader(sk_sp<SkShader> shader,
                                                const shaders::LinearEffect& linearEffect,
                                                float maxDisplayLuminance,
                                                float currentDisplayLuminanceNits,
                                                float maxLuminance) {
    auto shaderString = SkString(shaders::buildLinearEffectSkSL(linearEffect));
    auto [runtimeEffect, error] = SkRuntimeEffect::MakeForShader(std::move(shaderString));
    if (!runtimeEffect) {
        LOG_ALWAYS_FATAL("LinearColorFilter construction error: %s", error.c_str());
    }

    SkRuntimeShaderBuilder effectBuilder(std::move(runtimeEffect));

    effectBuilder.child("child") = std::move(shader);

    const auto uniforms = shaders::buildLinearEffectUniforms(
            linearEffect, mat4(), maxDisplayLuminance, currentDisplayLuminanceNits, maxLuminance);

    for (const auto& uniform : uniforms) {
        effectBuilder.uniform(uniform.name.c_str()).set(uniform.value.data(), uniform.value.size());
    }

    return effectBuilder.makeShader();
}

static bool isHdrDataspace(ui::Dataspace dataspace) {
    const auto transfer = dataspace & HAL_DATASPACE_TRANSFER_MASK;

    return transfer == HAL_DATASPACE_TRANSFER_ST2084 || transfer == HAL_DATASPACE_TRANSFER_HLG;
}

static void adjustCropForYUV(uint32_t format, int bufferWidth, int bufferHeight, SkRect* cropRect) {
    // Chroma channels of YUV420 images are subsampled we may need to shrink the crop region by
    // a whole texel on each side. Since skia still adds its own 0.5 inset, we apply an
    // additional 0.5 inset. See GLConsumer::computeTransformMatrix for details.
    float shrinkAmount = 0.0f;
    switch (format) {
        // Use HAL formats since some AHB formats are only available in vndk
        case HAL_PIXEL_FORMAT_YCBCR_420_888:
        case HAL_PIXEL_FORMAT_YV12:
        case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
            shrinkAmount = 0.5f;
            break;
        default:
            break;
    }

    // Shrink the crop if it has more than 1-px and differs from the buffer size.
    if (cropRect->width() > 1 && cropRect->width() < bufferWidth) {
        cropRect->inset(shrinkAmount, 0);
    }

    if (cropRect->height() > 1 && cropRect->height() < bufferHeight) {
        cropRect->inset(0, shrinkAmount);
    }
}

// TODO: Context arg probably doesn't belong here – do debug check at callsite instead.
bool LayerDrawable::DrawLayer(GrRecordingContext* context,
                              SkCanvas* canvas,
                              Layer* layer,
                              const SkRect* srcRect,
                              const SkRect* dstRect,
                              bool useLayerTransform) {
    if (context == nullptr) {
        ALOGD("Attempting to draw LayerDrawable into an unsupported surface");
        return false;
    }
    // transform the matrix based on the layer
    // SkMatrix layerTransform = layer->getTransform();
    const uint32_t windowTransform = layer->getWindowTransform();
    sk_sp<SkImage> layerImage = layer->getImage();
    const int layerWidth = layer->getWidth();
    const int layerHeight = layer->getHeight();

    if (layerImage) {
        const int imageWidth = layerImage->width();
        const int imageHeight = layerImage->height();

        if (useLayerTransform) {
            canvas->save();
            canvas->concat(layer->getTransform());
        }

        SkPaint paint;
        paint.setAlpha(layer->getAlpha());
        paint.setBlendMode(layer->getMode());
        paint.setColorFilter(layer->getColorFilter());
        const SkMatrix& totalMatrix = canvas->getTotalMatrix();
        SkRect skiaSrcRect;
        if (srcRect && !srcRect->isEmpty()) {
            skiaSrcRect = *srcRect;
            adjustCropForYUV(layer->getBufferFormat(), imageWidth, imageHeight, &skiaSrcRect);
        } else {
            skiaSrcRect = SkRect::MakeIWH(imageWidth, imageHeight);
        }
        SkRect skiaDestRect;
        if (dstRect && !dstRect->isEmpty()) {
            skiaDestRect = (windowTransform & NATIVE_WINDOW_TRANSFORM_ROT_90)
                                   ? SkRect::MakeIWH(dstRect->height(), dstRect->width())
                                   : SkRect::MakeIWH(dstRect->width(), dstRect->height());
        } else {
            skiaDestRect = (windowTransform & NATIVE_WINDOW_TRANSFORM_ROT_90)
                                   ? SkRect::MakeIWH(layerHeight, layerWidth)
                                   : SkRect::MakeIWH(layerWidth, layerHeight);
        }

        const float px = skiaDestRect.centerX();
        const float py = skiaDestRect.centerY();
        SkMatrix m;
        if (windowTransform & NATIVE_WINDOW_TRANSFORM_FLIP_H) {
            m.postScale(-1.f, 1.f, px, py);
        }
        if (windowTransform & NATIVE_WINDOW_TRANSFORM_FLIP_V) {
            m.postScale(1.f, -1.f, px, py);
        }
        if (windowTransform & NATIVE_WINDOW_TRANSFORM_ROT_90) {
            m.postRotate(90, 0, 0);
            m.postTranslate(skiaDestRect.height(), 0);
        }
        auto constraint = SkCanvas::kFast_SrcRectConstraint;
        if (srcRect && !srcRect->isEmpty()) {
            constraint = SkCanvas::kStrict_SrcRectConstraint;
        }

        canvas->save();
        canvas->concat(m);

        // If (matrix is a rect-to-rect transform)
        // and (src/dst buffers size match in screen coordinates)
        // and (src/dst corners align fractionally),
        // then use nearest neighbor, otherwise use bilerp sampling.
        // Skia TextureOp has the above logic build-in, but not NonAAFillRectOp. TextureOp works
        // only for SrcOver blending and without color filter (readback uses Src blending).
        SkSamplingOptions sampling(SkFilterMode::kNearest);
        if (layer->getForceFilter() || shouldFilterRect(totalMatrix, skiaSrcRect, skiaDestRect)) {
            sampling = SkSamplingOptions(SkFilterMode::kLinear);
        }

        const auto sourceDataspace = static_cast<ui::Dataspace>(
                ColorSpaceToADataSpace(layerImage->colorSpace(), layerImage->colorType()));
        const SkImageInfo& imageInfo = canvas->imageInfo();
        const auto destinationDataspace = static_cast<ui::Dataspace>(
                ColorSpaceToADataSpace(imageInfo.colorSpace(), imageInfo.colorType()));

        if (isHdrDataspace(sourceDataspace) || isHdrDataspace(destinationDataspace)) {
            const auto effect = shaders::LinearEffect{
                    .inputDataspace = sourceDataspace,
                    .outputDataspace = destinationDataspace,
                    .undoPremultipliedAlpha = layerImage->alphaType() == kPremul_SkAlphaType,
                    .fakeInputDataspace = destinationDataspace};
            auto shader = layerImage->makeShader(sampling,
                                                 SkMatrix::RectToRect(skiaSrcRect, skiaDestRect));
            constexpr float kMaxDisplayBrightess = 1000.f;
            constexpr float kCurrentDisplayBrightness = 500.f;
            shader = createLinearEffectShader(std::move(shader), effect, kMaxDisplayBrightess,
                                              kCurrentDisplayBrightness,
                                              layer->getMaxLuminanceNits());
            paint.setShader(shader);
            canvas->drawRect(skiaDestRect, paint);
        } else {
            canvas->drawImageRect(layerImage.get(), skiaSrcRect, skiaDestRect, sampling, &paint,
                                  constraint);
        }

        canvas->restore();
        // restore the original matrix
        if (useLayerTransform) {
            canvas->restore();
        }
    }

    return layerImage != nullptr;
}

}  // namespace skiapipeline
}  // namespace uirenderer
}  // namespace android
