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

    GrBackendTextureDesc textureDescription;
    textureDescription.fWidth = imgWidth;
    textureDescription.fHeight = imgHeight;
    textureDescription.fConfig = kRGBA_8888_GrPixelConfig;
    textureDescription.fOrigin = kTopLeft_GrSurfaceOrigin;
    textureDescription.fTextureHandle = reinterpret_cast<GrBackendObject>(&externalTexture);

    CopyResult copyResult = CopyResult::UnknownError;
    sk_sp<SkImage> image(SkImage::MakeFromAdoptedTexture(grContext.get(), textureDescription));
    if (image) {
        // convert to Skia data structures
        const SkRect bufferRect = SkRect::MakeIWH(imgWidth, imgHeight);
        SkRect skiaSrcRect = srcRect.toSkRect();
        SkMatrix textureMatrix;
        imgTransform.copyTo(textureMatrix);

        // remove the y-flip applied to the matrix so that we can scale the srcRect.
        // This flip is not needed as we specify the origin of the texture when we
        // wrap it as an SkImage.
        SkMatrix yFlip = SkMatrix::MakeScale(1, -1);
        yFlip.postTranslate(0,1);
        textureMatrix.preConcat(yFlip);

        // copy the entire src if the rect is empty
        if (skiaSrcRect.isEmpty()) {
            skiaSrcRect = bufferRect;
        }

        // since the y-flip has been removed we can simply scale & translate
        // the source rectangle
        textureMatrix.mapRect(&skiaSrcRect);

        if (skiaSrcRect.intersect(bufferRect)) {
            // we render in an offscreen buffer to scale and to avoid an issue b/62262733
            // with reading incorrect data from EGLImage backed SkImage (likely a driver bug)
            sk_sp<SkSurface> scaledSurface = SkSurface::MakeRenderTarget(
                    grContext.get(), SkBudgeted::kYes, bitmap->info());
            SkPaint paint;
            paint.setBlendMode(SkBlendMode::kSrc);
            scaledSurface->getCanvas()->drawImageRect(image, skiaSrcRect,
                    SkRect::MakeWH(bitmap->width(), bitmap->height()), &paint);
            image = scaledSurface->makeImageSnapshot();

            if (image->readPixels(bitmap->info(), bitmap->getPixels(), bitmap->rowBytes(), 0, 0)) {
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
