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

#include "EglReadback.h"

#include "renderthread/EglManager.h"

#include <gui/Surface.h>
#include <ui/Fence.h>
#include <ui/GraphicBuffer.h>

namespace android {
namespace uirenderer {

CopyResult EglReadback::copySurfaceInto(Surface& surface, const Rect& srcRect,
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

CopyResult EglReadback::copyGraphicBufferInto(GraphicBuffer* graphicBuffer,
                                                 Matrix4& texTransform, const Rect& srcRect,
                                                 SkBitmap* bitmap) {
    mRenderThread.requireGlContext();
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

    eglDestroyImageKHR(display, sourceImage);
    return copyResult;
}

CopyResult EglReadback::copyGraphicBufferInto(GraphicBuffer* graphicBuffer, SkBitmap* bitmap) {
    Rect srcRect;
    Matrix4 transform;
    transform.loadScale(1, -1, 1);
    transform.translate(0, -1);
    return copyGraphicBufferInto(graphicBuffer, transform, srcRect, bitmap);
}

}  // namespace uirenderer
}  // namespace android
