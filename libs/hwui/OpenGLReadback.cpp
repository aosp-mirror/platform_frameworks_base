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

#include "OpenGLReadback.h"

#include "Caches.h"
#include "GlLayer.h"
#include "GlopBuilder.h"
#include "Image.h"
#include "renderstate/RenderState.h"
#include "renderthread/EglManager.h"
#include "utils/GLUtils.h"

#include <GLES2/gl2.h>
#include <gui/Surface.h>
#include <ui/Fence.h>
#include <ui/GraphicBuffer.h>

namespace android {
namespace uirenderer {

CopyResult OpenGLReadback::copySurfaceInto(Surface& surface, const Rect& srcRect,
                                           SkBitmap* bitmap) {
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

    return copyGraphicBufferInto(sourceBuffer.get(), texTransform, srcRect, bitmap);
}

CopyResult OpenGLReadback::copyGraphicBufferInto(GraphicBuffer* graphicBuffer,
                                                 Matrix4& texTransform, const Rect& srcRect,
                                                 SkBitmap* bitmap) {
    mRenderThread.eglManager().initialize();
    // TODO: Can't use Image helper since it forces GL_TEXTURE_2D usage via
    // GL_OES_EGL_image, which doesn't work since we need samplerExternalOES
    // to be able to properly sample from the buffer.

    // Create the EGLImage object that maps the GraphicBuffer
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLClientBuffer clientBuffer = (EGLClientBuffer)graphicBuffer->getNativeBuffer();
    EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};

    EGLImageKHR sourceImage = eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                                clientBuffer, attrs);

    if (sourceImage == EGL_NO_IMAGE_KHR) {
        ALOGW("eglCreateImageKHR failed (%#x)", eglGetError());
        return CopyResult::UnknownError;
    }

    uint32_t width = graphicBuffer->getWidth();
    uint32_t height = graphicBuffer->getHeight();
    CopyResult copyResult =
            copyImageInto(sourceImage, texTransform, width, height, srcRect, bitmap);

    // All we're flushing & finishing is the deletion of the texture since
    // copyImageInto already did a major flush & finish as an implicit
    // part of glReadPixels, so this shouldn't pose any major stalls.
    glFinish();
    eglDestroyImageKHR(display, sourceImage);
    return copyResult;
}

CopyResult OpenGLReadback::copyGraphicBufferInto(GraphicBuffer* graphicBuffer, SkBitmap* bitmap) {
    Rect srcRect;
    Matrix4 transform;
    transform.loadScale(1, -1, 1);
    transform.translate(0, -1);
    return copyGraphicBufferInto(graphicBuffer, transform, srcRect, bitmap);
}

static float sFlipVInit[16] = {
        1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1,
};

static const Matrix4 sFlipV(sFlipVInit);

////////////////////////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////////////////////////

inline CopyResult copyTextureInto(Caches& caches, RenderState& renderState, Texture& sourceTexture,
                                  const Matrix4& texTransform, const Rect& srcRect,
                                  SkBitmap* bitmap) {
    int destWidth = bitmap->width();
    int destHeight = bitmap->height();
    if (destWidth > caches.maxTextureSize || destHeight > caches.maxTextureSize) {
        ALOGW("Can't copy surface into bitmap, %dx%d exceeds max texture size %d", destWidth,
              destHeight, caches.maxTextureSize);
        return CopyResult::DestinationInvalid;
    }

    if (bitmap->colorType() == kRGBA_F16_SkColorType &&
        !caches.extensions().hasRenderableFloatTextures()) {
        ALOGW("Can't copy surface into bitmap, RGBA_F16 config is not supported");
        return CopyResult::DestinationInvalid;
    }

    GLuint fbo = renderState.createFramebuffer();
    if (!fbo) {
        ALOGW("Could not obtain an FBO");
        return CopyResult::UnknownError;
    }

    GLuint texture;

    GLenum format;
    GLenum internalFormat;
    GLenum type;

    switch (bitmap->colorType()) {
        case kAlpha_8_SkColorType:
            format = GL_ALPHA;
            internalFormat = GL_ALPHA;
            type = GL_UNSIGNED_BYTE;
            break;
        case kRGB_565_SkColorType:
            format = GL_RGB;
            internalFormat = GL_RGB;
            type = GL_UNSIGNED_SHORT_5_6_5;
            break;
        case kARGB_4444_SkColorType:
            format = GL_RGBA;
            internalFormat = GL_RGBA;
            type = GL_UNSIGNED_SHORT_4_4_4_4;
            break;
        case kRGBA_F16_SkColorType:
            format = GL_RGBA;
            internalFormat = GL_RGBA16F;
            type = GL_HALF_FLOAT;
            break;
        case kN32_SkColorType:
        default:
            format = GL_RGBA;
            internalFormat = GL_RGBA;
            type = GL_UNSIGNED_BYTE;
            break;
    }

    renderState.bindFramebuffer(fbo);

    // TODO: Use layerPool or something to get this maybe? But since we
    // need explicit format control we can't currently.

    // Setup the rendertarget
    glGenTextures(1, &texture);
    caches.textureState().activateTexture(0);
    caches.textureState().bindTexture(texture);
    glPixelStorei(GL_PACK_ALIGNMENT, bitmap->bytesPerPixel());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, destWidth, destHeight, 0, format, type, nullptr);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);

    {
        bool requiresFilter;
        // Draw & readback
        renderState.setViewport(destWidth, destHeight);
        renderState.scissor().setEnabled(false);
        renderState.blend().syncEnabled();
        renderState.stencil().disable();

        Matrix4 croppedTexTransform(texTransform);
        if (!srcRect.isEmpty()) {
            // We flipV to convert to 0,0 top-left for the srcRect
            // coordinates then flip back to 0,0 bottom-left for
            // GLES coordinates.
            croppedTexTransform.multiply(sFlipV);
            croppedTexTransform.translate(srcRect.left / sourceTexture.width(),
                                          srcRect.top / sourceTexture.height(), 0);
            croppedTexTransform.scale(srcRect.getWidth() / sourceTexture.width(),
                                      srcRect.getHeight() / sourceTexture.height(), 1);
            croppedTexTransform.multiply(sFlipV);
            requiresFilter = srcRect.getWidth() != (float)destWidth ||
                             srcRect.getHeight() != (float)destHeight;
        } else {
            requiresFilter = sourceTexture.width() != (uint32_t)destWidth ||
                             sourceTexture.height() != (uint32_t)destHeight;
        }
        Glop glop;
        GlopBuilder(renderState, caches, &glop)
                .setRoundRectClipState(nullptr)
                .setMeshTexturedUnitQuad(nullptr)
                .setFillExternalTexture(sourceTexture, croppedTexTransform, requiresFilter)
                .setTransform(Matrix4::identity(), TransformFlags::None)
                .setModelViewMapUnitToRect(Rect(destWidth, destHeight))
                .build();
        Matrix4 ortho;
        ortho.loadOrtho(destWidth, destHeight);
        renderState.render(glop, ortho, false);

        // TODO: We should convert to linear space when the target is RGBA16F
        glReadPixels(0, 0, bitmap->width(), bitmap->height(), format, type, bitmap->getPixels());
        bitmap->notifyPixelsChanged();
    }

    // Cleanup
    caches.textureState().deleteTexture(texture);
    renderState.deleteFramebuffer(fbo);

    GL_CHECKPOINT(MODERATE);

    return CopyResult::Success;
}

CopyResult OpenGLReadbackImpl::copyImageInto(EGLImageKHR eglImage, const Matrix4& imgTransform,
                                             int imgWidth, int imgHeight, const Rect& srcRect,
                                             SkBitmap* bitmap) {
    // If this is a 90 or 270 degree rotation we need to swap width/height
    // This is a fuzzy way of checking that.
    if (imgTransform[Matrix4::kSkewX] >= 0.5f || imgTransform[Matrix4::kSkewX] <= -0.5f) {
        std::swap(imgWidth, imgHeight);
    }

    Caches& caches = Caches::getInstance();
    GLuint sourceTexId;
    // Create a 2D texture to sample from the EGLImage
    glGenTextures(1, &sourceTexId);
    caches.textureState().bindTexture(GL_TEXTURE_EXTERNAL_OES, sourceTexId);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, eglImage);

    GLenum status = GL_NO_ERROR;
    while ((status = glGetError()) != GL_NO_ERROR) {
        ALOGW("glEGLImageTargetTexture2DOES failed (%#x)", status);
        return CopyResult::UnknownError;
    }

    Texture sourceTexture(caches);
    sourceTexture.wrap(sourceTexId, imgWidth, imgHeight, 0, 0 /* total lie */,
                       GL_TEXTURE_EXTERNAL_OES);

    CopyResult copyResult = copyTextureInto(caches, mRenderThread.renderState(), sourceTexture,
                                            imgTransform, srcRect, bitmap);
    sourceTexture.deleteTexture();
    return copyResult;
}

bool OpenGLReadbackImpl::copyLayerInto(renderthread::RenderThread& renderThread, GlLayer& layer,
                                       SkBitmap* bitmap) {
    if (!layer.isRenderable()) {
        // layer has never been updated by DeferredLayerUpdater, abort copy
        return false;
    }

    return CopyResult::Success == copyTextureInto(Caches::getInstance(), renderThread.renderState(),
                                                  layer.getTexture(), layer.getTexTransform(),
                                                  Rect(), bitmap);
}

}  // namespace uirenderer
}  // namespace android
