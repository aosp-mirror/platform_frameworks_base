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

#include "DeviceInfo.h"
#include "Matrix.h"
#include "Properties.h"
#include <SkCanvas.h>
#include <SkSurface.h>
#include <GrBackendSurface.h>
#include <gl/GrGLInterface.h>
#include <gl/GrGLTypes.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

CopyResult SkiaOpenGLReadback::copyImageInto(EGLImageKHR eglImage, const Matrix4& imgTransform,
        int imgWidth, int imgHeight, const Rect& srcRect, SkBitmap* bitmap) {

    GLuint sourceTexId;
    glGenTextures(1, &sourceTexId);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, sourceTexId);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, eglImage);

    sk_sp<GrContext> grContext = sk_ref_sp(mRenderThread.getGrContext());
    if (Properties::getRenderPipelineType() == RenderPipelineType::SkiaVulkan) {
        sk_sp<const GrGLInterface> glInterface(GrGLCreateNativeInterface());
        LOG_ALWAYS_FATAL_IF(!glInterface.get());
        grContext.reset(GrContext::Create(GrBackend::kOpenGL_GrBackend,
                (GrBackendContext)glInterface.get()));
    } else {
        grContext->resetContext();
    }

    GrGLTextureInfo externalTexture;
    externalTexture.fTarget = GL_TEXTURE_EXTERNAL_OES;
    externalTexture.fID = sourceTexId;

    GrPixelConfig pixelConfig;
    switch (bitmap->colorType()) {
    case kRGBA_F16_SkColorType:
        pixelConfig = kRGBA_half_GrPixelConfig;
        break;
    case kN32_SkColorType:
    default:
        pixelConfig = kRGBA_8888_GrPixelConfig;
        break;
    }

    /* Ideally, we would call grContext->caps()->isConfigRenderable(...). We
     * currently can't do that since some devices (i.e. SwiftShader) supports all
     * the appropriate half float extensions, but only allow the buffer to be read
     * back as full floats.  We can relax this extension if Skia implements support
     * for reading back float buffers (skbug.com/6945).
     */
    if (pixelConfig == kRGBA_half_GrPixelConfig &&
            !DeviceInfo::get()->extensions().hasFloatTextures()) {
        ALOGW("Can't copy surface into bitmap, RGBA_F16 config is not supported");
        return CopyResult::DestinationInvalid;
    }

    GrBackendTexture backendTexture(imgWidth, imgHeight, pixelConfig, externalTexture);

    CopyResult copyResult = CopyResult::UnknownError;
    sk_sp<SkImage> image(SkImage::MakeFromAdoptedTexture(grContext.get(), backendTexture,
            kTopLeft_GrSurfaceOrigin));
    if (image) {
        // Convert imgTransform matrix from right to left handed coordinate system.
        // If we have a matrix transformation in right handed coordinate system
        //|ScaleX, SkewX,  TransX| same transform in left handed is    |ScaleX, SkewX,   TransX  |
        //|SkewY,  ScaleY, TransY|                                     |-SkewY, -ScaleY, 1-TransY|
        //|0,      0,      1     |                                     |0,      0,       1       |
        SkMatrix textureMatrix;
        textureMatrix.setIdentity();
        textureMatrix[SkMatrix::kMScaleX] = imgTransform[Matrix4::kScaleX];
        textureMatrix[SkMatrix::kMScaleY] = -imgTransform[Matrix4::kScaleY];
        textureMatrix[SkMatrix::kMSkewX] = imgTransform[Matrix4::kSkewX];
        textureMatrix[SkMatrix::kMSkewY] = -imgTransform[Matrix4::kSkewY];
        textureMatrix[SkMatrix::kMTransX] = imgTransform[Matrix4::kTranslateX];
        textureMatrix[SkMatrix::kMTransY] = 1-imgTransform[Matrix4::kTranslateY];

        // textureMatrix maps 2D texture coordinates of the form (s, t, 1) with s and t in the
        // inclusive range [0, 1] to the texture (see GLConsumer::getTransformMatrix comments).
        // Convert textureMatrix to translate in real texture dimensions. Texture width and
        // height are affected by the orientation (width and height swapped for 90/270 rotation).
        if (textureMatrix[SkMatrix::kMSkewX] >= 0.5f || textureMatrix[SkMatrix::kMSkewX] <= -0.5f) {
            textureMatrix[SkMatrix::kMTransX] *= imgHeight;
            textureMatrix[SkMatrix::kMTransY] *= imgWidth;
        } else {
            textureMatrix[SkMatrix::kMTransX] *= imgWidth;
            textureMatrix[SkMatrix::kMTransY] *= imgHeight;
        }

        // convert to Skia data structures
        SkRect skiaSrcRect = srcRect.toSkRect();
        SkMatrix textureMatrixInv;
        SkRect skiaDestRect = SkRect::MakeWH(bitmap->width(), bitmap->height());
        bool srcNotEmpty = false;
        if (textureMatrix.invert(&textureMatrixInv)) {
            if (skiaSrcRect.isEmpty()) {
                skiaSrcRect = SkRect::MakeIWH(imgWidth, imgHeight);
                srcNotEmpty = !skiaSrcRect.isEmpty();
            } else {
                // src and dest rectangles need to be converted into texture coordinates before the
                // rotation matrix is applied (because drawImageRect preconcat its matrix).
                textureMatrixInv.mapRect(&skiaSrcRect);
                srcNotEmpty = skiaSrcRect.intersect(SkRect::MakeIWH(imgWidth, imgHeight));
            }
            textureMatrixInv.mapRect(&skiaDestRect);
        }

        if (srcNotEmpty) {
            // we render in an offscreen buffer to scale and to avoid an issue b/62262733
            // with reading incorrect data from EGLImage backed SkImage (likely a driver bug)
            sk_sp<SkSurface> scaledSurface = SkSurface::MakeRenderTarget(
                    grContext.get(), SkBudgeted::kYes, bitmap->info());
            SkPaint paint;
            paint.setBlendMode(SkBlendMode::kSrc);
            scaledSurface->getCanvas()->concat(textureMatrix);
            scaledSurface->getCanvas()->drawImageRect(image, skiaSrcRect, skiaDestRect, &paint);

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
    return copyResult;
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
