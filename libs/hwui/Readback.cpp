/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "Readback.h"

#include <sync/sync.h>
#include <system/window.h>

#include "DeferredLayerUpdater.h"
#include "Properties.h"
#include "hwui/Bitmap.h"
#include "pipeline/skia/LayerDrawable.h"
#include "renderthread/EglManager.h"
#include "renderthread/VulkanManager.h"
#include "utils/Color.h"
#include "utils/MathUtils.h"
#include "utils/NdkUtils.h"
#include "utils/TraceUtils.h"

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {

CopyResult Readback::copySurfaceInto(ANativeWindow* window, const Rect& srcRect, SkBitmap* bitmap) {
    ATRACE_CALL();
    // Setup the source
    AHardwareBuffer* rawSourceBuffer;
    int rawSourceFence;
    Matrix4 texTransform;
    status_t err = ANativeWindow_getLastQueuedBuffer(window, &rawSourceBuffer, &rawSourceFence,
                                                     texTransform.data);
    base::unique_fd sourceFence(rawSourceFence);
    texTransform.invalidateType();
    if (err != NO_ERROR) {
        ALOGW("Failed to get last queued buffer, error = %d", err);
        return CopyResult::UnknownError;
    }
    if (rawSourceBuffer == nullptr) {
        ALOGW("Surface doesn't have any previously queued frames, nothing to readback from");
        return CopyResult::SourceEmpty;
    }

    UniqueAHardwareBuffer sourceBuffer{rawSourceBuffer};
    AHardwareBuffer_Desc description;
    AHardwareBuffer_describe(sourceBuffer.get(), &description);
    if (description.usage & AHARDWAREBUFFER_USAGE_PROTECTED_CONTENT) {
        ALOGW("Surface is protected, unable to copy from it");
        return CopyResult::SourceInvalid;
    }

    if (sourceFence != -1 && sync_wait(sourceFence.get(), 500 /* ms */) != NO_ERROR) {
        ALOGE("Timeout (500ms) exceeded waiting for buffer fence, abandoning readback attempt");
        return CopyResult::Timeout;
    }

    sk_sp<SkColorSpace> colorSpace = DataSpaceToColorSpace(
            static_cast<android_dataspace>(ANativeWindow_getBuffersDataSpace(window)));
    sk_sp<SkImage> image =
            SkImage::MakeFromAHardwareBuffer(sourceBuffer.get(), kPremul_SkAlphaType, colorSpace);
    return copyImageInto(image, texTransform, srcRect, bitmap);
}

CopyResult Readback::copyHWBitmapInto(Bitmap* hwBitmap, SkBitmap* bitmap) {
    LOG_ALWAYS_FATAL_IF(!hwBitmap->isHardware());

    Rect srcRect;
    Matrix4 transform;
    transform.loadScale(1, -1, 1);
    transform.translate(0, -1);

    return copyImageInto(hwBitmap->makeImage(), transform, srcRect, bitmap);
}

CopyResult Readback::copyLayerInto(DeferredLayerUpdater* deferredLayer, SkBitmap* bitmap) {
    ATRACE_CALL();
    if (!mRenderThread.getGrContext()) {
        return CopyResult::UnknownError;
    }

    // acquire most recent buffer for drawing
    deferredLayer->updateTexImage();
    deferredLayer->apply();
    const SkRect dstRect = SkRect::MakeIWH(bitmap->width(), bitmap->height());
    CopyResult copyResult = CopyResult::UnknownError;
    Layer* layer = deferredLayer->backingLayer();
    if (layer) {
        if (copyLayerInto(layer, nullptr, &dstRect, bitmap)) {
            copyResult = CopyResult::Success;
        }
    }
    return copyResult;
}

CopyResult Readback::copyImageInto(const sk_sp<SkImage>& image, Matrix4& texTransform,
                                   const Rect& srcRect, SkBitmap* bitmap) {
    ATRACE_CALL();
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        mRenderThread.requireGlContext();
    } else {
        mRenderThread.requireVkContext();
    }
    if (!image.get()) {
        return CopyResult::UnknownError;
    }
    int imgWidth = image->width();
    int imgHeight = image->height();
    sk_sp<GrContext> grContext = sk_ref_sp(mRenderThread.getGrContext());

    if (bitmap->colorType() == kRGBA_F16_SkColorType &&
        !grContext->colorTypeSupportedAsSurface(bitmap->colorType())) {
        ALOGW("Can't copy surface into bitmap, RGBA_F16 config is not supported");
        return CopyResult::DestinationInvalid;
    }

    CopyResult copyResult = CopyResult::UnknownError;

    int displayedWidth = imgWidth, displayedHeight = imgHeight;
    // If this is a 90 or 270 degree rotation we need to swap width/height to get the device
    // size.
    if (texTransform[Matrix4::kSkewX] >= 0.5f || texTransform[Matrix4::kSkewX] <= -0.5f) {
        std::swap(displayedWidth, displayedHeight);
    }
    SkRect skiaDestRect = SkRect::MakeWH(bitmap->width(), bitmap->height());
    SkRect skiaSrcRect = srcRect.toSkRect();
    if (skiaSrcRect.isEmpty()) {
        skiaSrcRect = SkRect::MakeIWH(displayedWidth, displayedHeight);
    }
    bool srcNotEmpty = skiaSrcRect.intersect(SkRect::MakeIWH(displayedWidth, displayedHeight));
    if (!srcNotEmpty) {
        return copyResult;
    }

    Layer layer(mRenderThread.renderState(), nullptr, 255, SkBlendMode::kSrc);
    layer.setSize(displayedWidth, displayedHeight);
    texTransform.copyTo(layer.getTexTransform());
    layer.setImage(image);
    // Scaling filter is not explicitly set here, because it is done inside copyLayerInfo
    // after checking the necessity based on the src/dest rect size and the transformation.
    if (copyLayerInto(&layer, &skiaSrcRect, &skiaDestRect, bitmap)) {
        copyResult = CopyResult::Success;
    }

    return copyResult;
}

bool Readback::copyLayerInto(Layer* layer, const SkRect* srcRect, const SkRect* dstRect,
                             SkBitmap* bitmap) {
    /* This intermediate surface is present to work around a bug in SwiftShader that
     * prevents us from reading the contents of the layer's texture directly. The
     * workaround involves first rendering that texture into an intermediate buffer and
     * then reading from the intermediate buffer into the bitmap.
     * Another reason to render in an offscreen buffer is to scale and to avoid an issue b/62262733
     * with reading incorrect data from EGLImage backed SkImage (likely a driver bug).
     */
    sk_sp<SkSurface> tmpSurface = SkSurface::MakeRenderTarget(mRenderThread.getGrContext(),
                                                              SkBudgeted::kYes, bitmap->info(), 0,
                                                              kTopLeft_GrSurfaceOrigin, nullptr);

    // if we can't generate a GPU surface that matches the destination bitmap (e.g. 565) then we
    // attempt to do the intermediate rendering step in 8888
    if (!tmpSurface.get()) {
        SkImageInfo tmpInfo = bitmap->info().makeColorType(SkColorType::kN32_SkColorType);
        tmpSurface = SkSurface::MakeRenderTarget(mRenderThread.getGrContext(), SkBudgeted::kYes,
                                                 tmpInfo, 0, kTopLeft_GrSurfaceOrigin, nullptr);
        if (!tmpSurface.get()) {
            ALOGW("Unable to generate GPU buffer in a format compatible with the provided bitmap");
            return false;
        }
    }

    if (!skiapipeline::LayerDrawable::DrawLayer(mRenderThread.getGrContext(),
                                                tmpSurface->getCanvas(), layer, srcRect, dstRect,
                                                false)) {
        ALOGW("Unable to draw content from GPU into the provided bitmap");
        return false;
    }

    if (!tmpSurface->readPixels(*bitmap, 0, 0)) {
        // if we fail to readback from the GPU directly (e.g. 565) then we attempt to read into
        // 8888 and then convert that into the destination format before giving up.
        SkBitmap tmpBitmap;
        SkImageInfo tmpInfo = bitmap->info().makeColorType(SkColorType::kN32_SkColorType);
        if (bitmap->info().colorType() == SkColorType::kN32_SkColorType ||
                !tmpBitmap.tryAllocPixels(tmpInfo) ||
                !tmpSurface->readPixels(tmpBitmap, 0, 0) ||
                !tmpBitmap.readPixels(bitmap->info(), bitmap->getPixels(),
                                      bitmap->rowBytes(), 0, 0)) {
            ALOGW("Unable to convert content into the provided bitmap");
            return false;
        }
    }

    bitmap->notifyPixelsChanged();
    return true;
}

} /* namespace uirenderer */
} /* namespace android */
