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

#include "Readback.h"

#include "Caches.h"
#include "Image.h"
#include "GlopBuilder.h"
#include "renderstate/RenderState.h"
#include "renderthread/EglManager.h"
#include "utils/GLUtils.h"

#include <GLES2/gl2.h>
#include <ui/Fence.h>
#include <ui/GraphicBuffer.h>

namespace android {
namespace uirenderer {

CopyResult Readback::copySurfaceInto(renderthread::RenderThread& renderThread,
        Surface& surface, SkBitmap* bitmap) {
    // TODO: Clean this up and unify it with LayerRenderer::copyLayer,
    // of which most of this is copied from.
    renderThread.eglManager().initialize();

    Caches& caches = Caches::getInstance();
    RenderState& renderState = renderThread.renderState();
    int destWidth = bitmap->width();
    int destHeight = bitmap->height();
    if (destWidth > caches.maxTextureSize
                || destHeight > caches.maxTextureSize) {
        ALOGW("Can't copy surface into bitmap, %dx%d exceeds max texture size %d",
                destWidth, destHeight, caches.maxTextureSize);
        return CopyResult::DestinationInvalid;
    }
    GLuint fbo = renderState.createFramebuffer();
    if (!fbo) {
        ALOGW("Could not obtain an FBO");
        return CopyResult::UnknownError;
    }

    SkAutoLockPixels alp(*bitmap);

    GLuint texture;

    GLenum format;
    GLenum type;

    switch (bitmap->colorType()) {
        case kAlpha_8_SkColorType:
            format = GL_ALPHA;
            type = GL_UNSIGNED_BYTE;
            break;
        case kRGB_565_SkColorType:
            format = GL_RGB;
            type = GL_UNSIGNED_SHORT_5_6_5;
            break;
        case kARGB_4444_SkColorType:
            format = GL_RGBA;
            type = GL_UNSIGNED_SHORT_4_4_4_4;
            break;
        case kN32_SkColorType:
        default:
            format = GL_RGBA;
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
    glTexImage2D(GL_TEXTURE_2D, 0, format, destWidth, destHeight,
            0, format, type, nullptr);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D, texture, 0);

    // Setup the source
    sp<GraphicBuffer> sourceBuffer;
    sp<Fence> sourceFence;
    Matrix4 texTransform;
    status_t err = surface.getLastQueuedBuffer(&sourceBuffer, &sourceFence,
            texTransform.data);
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

    // TODO: Can't use Image helper since it forces GL_TEXTURE_2D usage via
    // GL_OES_EGL_image, which doesn't work since we need samplerExternalOES
    // to be able to properly sample from the buffer.

    // Create the EGLImage object that maps the GraphicBuffer
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLClientBuffer clientBuffer = (EGLClientBuffer) sourceBuffer->getNativeBuffer();
    EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };

    EGLImageKHR sourceImage = eglCreateImageKHR(display, EGL_NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attrs);

    if (sourceImage == EGL_NO_IMAGE_KHR) {
        ALOGW("Error creating image (%#x)", eglGetError());
        return CopyResult::UnknownError;
    }
    GLuint sourceTexId;
    // Create a 2D texture to sample from the EGLImage
    glGenTextures(1, &sourceTexId);
    Caches::getInstance().textureState().bindTexture(GL_TEXTURE_EXTERNAL_OES, sourceTexId);
    glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, sourceImage);

    GLenum status = GL_NO_ERROR;
    while ((status = glGetError()) != GL_NO_ERROR) {
        ALOGW("Error creating image (%#x)", status);
        return CopyResult::UnknownError;
    }

    Texture sourceTexture(caches);
    sourceTexture.wrap(sourceTexId,
            sourceBuffer->getWidth(), sourceBuffer->getHeight(), 0 /* total lie */);

    {
        // Draw & readback
        renderState.setViewport(destWidth, destHeight);
        renderState.scissor().setEnabled(false);
        renderState.blend().syncEnabled();
        renderState.stencil().disable();

        Rect destRect(destWidth, destHeight);
        Glop glop;
        GlopBuilder(renderState, caches, &glop)
                .setRoundRectClipState(nullptr)
                .setMeshTexturedUnitQuad(nullptr)
                .setFillExternalTexture(sourceTexture, texTransform)
                .setTransform(Matrix4::identity(), TransformFlags::None)
                .setModelViewMapUnitToRect(destRect)
                .build();
        Matrix4 ortho;
        ortho.loadOrtho(destWidth, destHeight);
        renderState.render(glop, ortho);

        glReadPixels(0, 0, bitmap->width(), bitmap->height(), format,
                type, bitmap->getPixels());
    }

    // Cleanup
    caches.textureState().deleteTexture(texture);
    renderState.deleteFramebuffer(fbo);

    GL_CHECKPOINT(MODERATE);

    return CopyResult::Success;
}

} // namespace uirenderer
} // namespace android
