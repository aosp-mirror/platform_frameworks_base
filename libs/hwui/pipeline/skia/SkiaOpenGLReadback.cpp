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

    GrBackendTexture backendTexture(imgWidth, imgHeight, kRGBA_8888_GrPixelConfig, externalTexture);

    CopyResult copyResult = CopyResult::UnknownError;
    sk_sp<SkImage> image(SkImage::MakeFromAdoptedTexture(grContext.get(), backendTexture,
            kTopLeft_GrSurfaceOrigin));
    if (image) {
        SkMatrix textureMatrix;
        imgTransform.copyTo(textureMatrix);

        // remove the y-flip applied to the matrix
        SkMatrix yFlip = SkMatrix::MakeScale(1, -1);
        yFlip.postTranslate(0,1);
        textureMatrix.preConcat(yFlip);

        // multiply by image size, because textureMatrix maps to [0..1] range
        textureMatrix[SkMatrix::kMTransX] *= imgWidth;
        textureMatrix[SkMatrix::kMTransY] *= imgHeight;

        // swap rotation and translation part of the matrix, because we convert from
        // right-handed Cartesian to left-handed coordinate system.
        std::swap(textureMatrix[SkMatrix::kMTransX], textureMatrix[SkMatrix::kMTransY]);
        std::swap(textureMatrix[SkMatrix::kMSkewX], textureMatrix[SkMatrix::kMSkewY]);

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
