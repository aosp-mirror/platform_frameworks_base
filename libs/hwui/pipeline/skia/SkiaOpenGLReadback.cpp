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

#include "SkiaOpenGLReadback.h"

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GrBackendSurface.h>
#include <SkCanvas.h>
#include <SkSurface.h>
#include <gl/GrGLInterface.h>
#include <gl/GrGLTypes.h>
#include "DeviceInfo.h"
#include "Matrix.h"
#include "Properties.h"

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

CopyResult SkiaOpenGLReadback::copyImageInto(EGLImageKHR eglImage, const Matrix4& imgTransform,
                                             int imgWidth, int imgHeight, const Rect& srcRect,
                                             SkBitmap* bitmap) {
    GLuint sourceTexId;
    glGenTextures(1, &sourceTexId);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, sourceTexId);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, eglImage);

    sk_sp<GrContext> grContext = sk_ref_sp(mRenderThread.getGrContext());
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaVulkan) {
        sk_sp<const GrGLInterface> glInterface(GrGLCreateNativeInterface());
        LOG_ALWAYS_FATAL_IF(!glInterface.get());
        grContext = GrContext::MakeGL(std::move(glInterface));
    } else {
        grContext->resetContext();
    }

    if (bitmap->colorType() == kRGBA_F16_SkColorType &&
            !grContext->colorTypeSupportedAsSurface(bitmap->colorType())) {
        ALOGW("Can't copy surface into bitmap, RGBA_F16 config is not supported");
        return CopyResult::DestinationInvalid;
    }

    GrGLTextureInfo externalTexture;
    externalTexture.fTarget = GL_TEXTURE_EXTERNAL_OES;
    externalTexture.fID = sourceTexId;
    switch (bitmap->colorType()) {
        case kRGBA_F16_SkColorType:
            externalTexture.fFormat = GL_RGBA16F;
            break;
        case kN32_SkColorType:
        default:
            externalTexture.fFormat = GL_RGBA8;
            break;
    }

    GrBackendTexture backendTexture(imgWidth, imgHeight, GrMipMapped::kNo, externalTexture);

    CopyResult copyResult = CopyResult::UnknownError;
    sk_sp<SkImage> image(SkImage::MakeFromAdoptedTexture(grContext.get(), backendTexture,
                                                         kTopLeft_GrSurfaceOrigin,
                                                         bitmap->colorType()));
    if (image) {
        int displayedWidth = imgWidth, displayedHeight = imgHeight;
        // If this is a 90 or 270 degree rotation we need to swap width/height to get the device
        // size.
        if (imgTransform[Matrix4::kSkewX] >= 0.5f || imgTransform[Matrix4::kSkewX] <= -0.5f) {
            std::swap(displayedWidth, displayedHeight);
        }
        SkRect skiaDestRect = SkRect::MakeWH(bitmap->width(), bitmap->height());
        SkRect skiaSrcRect = srcRect.toSkRect();
        if (skiaSrcRect.isEmpty()) {
            skiaSrcRect = SkRect::MakeIWH(displayedWidth, displayedHeight);
        }
        bool srcNotEmpty = skiaSrcRect.intersect(SkRect::MakeIWH(displayedWidth, displayedHeight));

        if (srcNotEmpty) {
            SkMatrix textureMatrixInv;
            imgTransform.copyTo(textureMatrixInv);
            // TODO: after skia bug https://bugs.chromium.org/p/skia/issues/detail?id=7075 is fixed
            // use bottom left origin and remove flipV and invert transformations.
            SkMatrix flipV;
            flipV.setAll(1, 0, 0, 0, -1, 1, 0, 0, 1);
            textureMatrixInv.preConcat(flipV);
            textureMatrixInv.preScale(1.0f / displayedWidth, 1.0f / displayedHeight);
            textureMatrixInv.postScale(imgWidth, imgHeight);
            SkMatrix textureMatrix;
            if (!textureMatrixInv.invert(&textureMatrix)) {
                textureMatrix = textureMatrixInv;
            }

            textureMatrixInv.mapRect(&skiaSrcRect);
            textureMatrixInv.mapRect(&skiaDestRect);

            // we render in an offscreen buffer to scale and to avoid an issue b/62262733
            // with reading incorrect data from EGLImage backed SkImage (likely a driver bug)
            sk_sp<SkSurface> scaledSurface =
                    SkSurface::MakeRenderTarget(grContext.get(), SkBudgeted::kYes, bitmap->info());
            SkPaint paint;
            paint.setBlendMode(SkBlendMode::kSrc);
            // Apply a filter, which is matching OpenGL pipeline readback behaviour. Filter usage
            // is codified by tests using golden images like DecodeAccuracyTest.
            if (skiaSrcRect.width() != bitmap->width() ||
                skiaSrcRect.height() != bitmap->height()) {
                // TODO: apply filter always, but check if tests will be fine
                paint.setFilterQuality(kLow_SkFilterQuality);
            }
            scaledSurface->getCanvas()->concat(textureMatrix);
            scaledSurface->getCanvas()->drawImageRect(image, skiaSrcRect, skiaDestRect, &paint,
                                                      SkCanvas::kFast_SrcRectConstraint);

            image = scaledSurface->makeImageSnapshot();

            if (image->readPixels(bitmap->info(), bitmap->getPixels(), bitmap->rowBytes(), 0, 0)) {
                bitmap->notifyPixelsChanged();
                copyResult = CopyResult::Success;
            }
        }
    }

    // make sure that we have deleted the texture (in the SkImage) before we
    // destroy the EGLImage that it was created from
    image.reset();
    glFinish();

    return copyResult;
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
