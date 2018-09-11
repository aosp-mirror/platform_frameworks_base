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

#include "pipeline/skia/LayerDrawable.h"
#include "renderthread/EglManager.h"
#include "renderthread/VulkanManager.h"

#include <SkToSRGBColorFilter.h>
#include <gui/Surface.h>
#include <ui/Fence.h>
#include <ui/GraphicBuffer.h>
#include "DeferredLayerUpdater.h"
#include "Properties.h"
#include "hwui/Bitmap.h"
#include "utils/Color.h"
#include "utils/MathUtils.h"

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {

CopyResult Readback::copySurfaceInto(Surface& surface, const Rect& srcRect, SkBitmap* bitmap) {
    ATRACE_CALL();
    // Setup the source
    sp<GraphicBuffer> sourceBuffer;
    sp<Fence> sourceFence;
    Matrix4 texTransform;
    status_t err = surface.getLastQueuedBuffer(&sourceBuffer, &sourceFence, texTransform.data);
    texTransform.invalidateType();
    if (err != NO_ERROR) {
        ALOGW("Failed to get last queued buffer, error = %d", err);
        return CopyResult::UnknownError;
    }
    if (!sourceBuffer.get()) {
        ALOGW("Surface doesn't have any previously queued frames, nothing to readback from");
        return CopyResult::SourceEmpty;
    }
    if (sourceBuffer->getUsage() & GRALLOC_USAGE_PROTECTED) {
        ALOGW("Surface is protected, unable to copy from it");
        return CopyResult::SourceInvalid;
    }
    err = sourceFence->wait(500 /* ms */);
    if (err != NO_ERROR) {
        ALOGE("Timeout (500ms) exceeded waiting for buffer fence, abandoning readback attempt");
        return CopyResult::Timeout;
    }
    if (!sourceBuffer.get()) {
        return CopyResult::UnknownError;
    }

    sk_sp<SkColorSpace> colorSpace =
            DataSpaceToColorSpace(static_cast<android_dataspace>(surface.getBuffersDataSpace()));
    sk_sp<SkColorFilter> colorSpaceFilter;
    if (colorSpace && !colorSpace->isSRGB()) {
        colorSpaceFilter = SkToSRGBColorFilter::Make(colorSpace);
    }
    sk_sp<SkImage> image = SkImage::MakeFromAHardwareBuffer(
            reinterpret_cast<AHardwareBuffer*>(sourceBuffer.get()), kPremul_SkAlphaType);
    return copyImageInto(image, colorSpaceFilter, texTransform, srcRect, bitmap);
}

CopyResult Readback::copyHWBitmapInto(Bitmap* hwBitmap, SkBitmap* bitmap) {
    LOG_ALWAYS_FATAL_IF(!hwBitmap->isHardware());

    Rect srcRect;
    Matrix4 transform;
    transform.loadScale(1, -1, 1);
    transform.translate(0, -1);

    // TODO: Try to take and reuse the image inside HW bitmap with "hwBitmap->makeImage".
    // TODO: When this was attempted, it resulted in instability.
    sk_sp<SkColorFilter> colorSpaceFilter;
    sk_sp<SkColorSpace> colorSpace = hwBitmap->info().refColorSpace();
    if (colorSpace && !colorSpace->isSRGB()) {
        colorSpaceFilter = SkToSRGBColorFilter::Make(colorSpace);
    }
    sk_sp<SkImage> image = SkImage::MakeFromAHardwareBuffer(
            reinterpret_cast<AHardwareBuffer*>(hwBitmap->graphicBuffer()), kPremul_SkAlphaType);

    // HW Bitmap currently can only attach to a GraphicBuffer with PIXEL_FORMAT_RGBA_8888 format
    // and SRGB color space. ImageDecoder can create a new HW Bitmap with non-SRGB color space: for
    // example see android.graphics.cts.BitmapColorSpaceTest#testEncodeP3hardware test.
    return copyImageInto(image, colorSpaceFilter, transform, srcRect, bitmap);
}

CopyResult Readback::copyLayerInto(DeferredLayerUpdater* deferredLayer, SkBitmap* bitmap) {
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

CopyResult Readback::copyImageInto(const sk_sp<SkImage>& image,
                                   sk_sp<SkColorFilter>& colorSpaceFilter, Matrix4& texTransform,
                                   const Rect& srcRect, SkBitmap* bitmap) {
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaGL) {
        mRenderThread.requireGlContext();
    } else {
        mRenderThread.vulkanManager().initialize();
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

    // See Readback::copyLayerInto for an overview of color space conversion.
    // HW Bitmap are allowed to be in a non-SRGB color space (for example coming from ImageDecoder).
    // For Surface and HW Bitmap readback flows we pass colorSpaceFilter, which does the conversion.
    // TextureView readback is using Layer::setDataSpace, which creates a SkColorFilter internally.
    Layer layer(mRenderThread.renderState(), colorSpaceFilter, 255, SkBlendMode::kSrc);
    bool disableFilter = MathUtils::areEqual(skiaSrcRect.width(), skiaDestRect.width()) &&
                         MathUtils::areEqual(skiaSrcRect.height(), skiaDestRect.height());
    layer.setForceFilter(!disableFilter);
    layer.setSize(displayedWidth, displayedHeight);
    texTransform.copyTo(layer.getTexTransform());
    layer.setImage(image);
    if (copyLayerInto(&layer, &skiaSrcRect, &skiaDestRect, bitmap)) {
        copyResult = CopyResult::Success;
    }

    return copyResult;
}

bool Readback::copyLayerInto(Layer* layer, const SkRect* srcRect, const SkRect* dstRect,
                             SkBitmap* bitmap) {
    /*
     * In the past only TextureView readback was setting the temporary surface color space to null.
     * Now all 3 readback flows are drawing into a SkSurface with null color space.
     * At readback there are 3 options to convert the source image color space to the destination
     * color space requested in "bitmap->info().colorSpace()":
     * 1. Set color space for temporary surface render target to null (disables color management),
     *    colorspace tag from source SkImage is ignored by Skia,
     *    convert SkImage to SRGB at draw time with SkColorFilter/SkToSRGBColorFilter,
     *    do a readback from temporary SkSurface to a temporary SRGB SkBitmap "bitmap2",
     *    read back from SRGB "bitmap2" into non-SRGB "bitmap" which will do a CPU color conversion.
     *
     * 2. Set color space for temporary surface render target to SRGB (not nullptr),
     *    colorspace tag on the source SkImage is used by Skia to enable conversion,
     *    convert SkImage to SRGB at draw time with drawImage (no filters),
     *    do a readback from temporary SkSurface, which will do a color conversion from SRGB to
     *    bitmap->info().colorSpace() on the CPU.
     *
     * 3. Set color space for temporary surface render target to bitmap->info().colorSpace(),
     *    colorspace tag on the source SkImage is used by Skia to enable conversion,
     *    convert SkImage to bitmap->info().colorSpace() at draw time with drawImage (no filters),
     *    do a readback from SkSurface, which will not do any color conversion, because
     *    surface was created with the same color space as the "bitmap".
     *
     * Option 1 is used for all readback flows.
     * Options 2 and 3 are new, because skia added support for non-SRGB render targets without
     * linear blending.
     * TODO: evaluate if options 2 or 3 for color space conversion are better.
     */

    // drop the colorSpace from the temporary surface.
    SkImageInfo surfaceInfo = bitmap->info().makeColorSpace(nullptr);

    /* This intermediate surface is present to work around a bug in SwiftShader that
     * prevents us from reading the contents of the layer's texture directly. The
     * workaround involves first rendering that texture into an intermediate buffer and
     * then reading from the intermediate buffer into the bitmap.
     * Another reason to render in an offscreen buffer is to scale and to avoid an issue b/62262733
     * with reading incorrect data from EGLImage backed SkImage (likely a driver bug).
     */
    sk_sp<SkSurface> tmpSurface = SkSurface::MakeRenderTarget(mRenderThread.getGrContext(),
                                                              SkBudgeted::kYes, surfaceInfo);

    if (!tmpSurface.get()) {
        surfaceInfo = surfaceInfo.makeColorType(SkColorType::kN32_SkColorType);
        tmpSurface = SkSurface::MakeRenderTarget(mRenderThread.getGrContext(), SkBudgeted::kYes,
                                                 surfaceInfo);
        if (!tmpSurface.get()) {
            ALOGW("Unable to readback GPU contents into the provided bitmap");
            return false;
        }
    }

    if (skiapipeline::LayerDrawable::DrawLayer(mRenderThread.getGrContext(),
                                               tmpSurface->getCanvas(), layer, srcRect, dstRect,
                                               false)) {
        // If bitmap->info().colorSpace() is non-SRGB, convert the data from SRGB to non-SRGB on
        // CPU. We can't just pass bitmap->info() to SkSurface::readPixels, because "tmpSurface" has
        // disabled color conversion.
        SkColorSpace* destColorSpace = bitmap->info().colorSpace();
        SkBitmap tempSRGBBitmap;
        SkBitmap tmpN32Bitmap;
        SkBitmap* bitmapInSRGB;
        if (destColorSpace && !destColorSpace->isSRGB()) {
            tempSRGBBitmap.allocPixels(bitmap->info().makeColorSpace(SkColorSpace::MakeSRGB()));
            bitmapInSRGB = &tempSRGBBitmap;  // Need to convert latter from SRGB to non-SRGB.
        } else {
            bitmapInSRGB = bitmap;  // No need for color conversion - write directly into output.
        }
        bool success = false;

        // TODO: does any of the readbacks below clamp F16 exSRGB?
        // Readback into a SRGB SkBitmap.
        if (tmpSurface->readPixels(bitmapInSRGB->info(), bitmapInSRGB->getPixels(),
                                   bitmapInSRGB->rowBytes(), 0, 0)) {
            success = true;
        } else {
            // if we fail to readback from the GPU directly (e.g. 565) then we attempt to read into
            // 8888 and then convert that into the destination format before giving up.
            SkImageInfo bitmapInfo =
                    SkImageInfo::MakeN32(bitmap->width(), bitmap->height(), bitmap->alphaType(),
                                         SkColorSpace::MakeSRGB());
            if (tmpN32Bitmap.tryAllocPixels(bitmapInfo) &&
                tmpSurface->readPixels(bitmapInfo, tmpN32Bitmap.getPixels(),
                                       tmpN32Bitmap.rowBytes(), 0, 0)) {
                success = true;
                bitmapInSRGB = &tmpN32Bitmap;
            }
        }

        if (success) {
            if (bitmapInSRGB != bitmap) {
                // Convert from SRGB to non-SRGB color space if needed. Convert from N32 to
                // destination bitmap color format if needed.
                if (!bitmapInSRGB->readPixels(bitmap->info(), bitmap->getPixels(),
                                              bitmap->rowBytes(), 0, 0)) {
                    return false;
                }
            }
            bitmap->notifyPixelsChanged();
            return true;
        }
    }

    return false;
}

} /* namespace uirenderer */
} /* namespace android */
