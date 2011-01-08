/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "SurfaceTexture"

#define GL_GLEXT_PROTOTYPES
#define EGL_EGLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <gui/SurfaceTexture.h>

#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <utils/Log.h>

namespace android {

SurfaceTexture::SurfaceTexture(GLuint tex) :
    mBufferCount(MIN_BUFFER_SLOTS), mCurrentTexture(INVALID_BUFFER_SLOT),
    mLastQueued(INVALID_BUFFER_SLOT), mTexName(tex) {
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        mSlots[i].mEglImage = EGL_NO_IMAGE_KHR;
        mSlots[i].mEglDisplay = EGL_NO_DISPLAY;
        mSlots[i].mOwnedByClient = false;
    }
}

SurfaceTexture::~SurfaceTexture() {
    freeAllBuffers();
}

status_t SurfaceTexture::setBufferCount(int bufferCount) {
    Mutex::Autolock lock(mMutex);
    freeAllBuffers();
    mBufferCount = bufferCount;
    return OK;
}

sp<GraphicBuffer> SurfaceTexture::requestBuffer(int buf,
        uint32_t w, uint32_t h, uint32_t format, uint32_t usage) {
    Mutex::Autolock lock(mMutex);
    if (buf < 0 || mBufferCount <= buf) {
        LOGE("requestBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, buf);
        return 0;
    }
    usage |= GraphicBuffer::USAGE_HW_TEXTURE;
    sp<ISurfaceComposer> composer(ComposerService::getComposerService());
    sp<GraphicBuffer> graphicBuffer(composer->createGraphicBuffer(w, h,
            format, usage));
    if (graphicBuffer == 0) {
        LOGE("requestBuffer: SurfaceComposer::createGraphicBuffer failed");
    } else {
        mSlots[buf].mGraphicBuffer = graphicBuffer;
        if (mSlots[buf].mEglImage != EGL_NO_IMAGE_KHR) {
            eglDestroyImageKHR(mSlots[buf].mEglDisplay, mSlots[buf].mEglImage);
            mSlots[buf].mEglImage = EGL_NO_IMAGE_KHR;
            mSlots[buf].mEglDisplay = EGL_NO_DISPLAY;
        }
    }
    return graphicBuffer;
}

status_t SurfaceTexture::dequeueBuffer(int *buf) {
    Mutex::Autolock lock(mMutex);
    int found = INVALID_BUFFER_SLOT;
    for (int i = 0; i < mBufferCount; i++) {
        if (!mSlots[i].mOwnedByClient && i != mCurrentTexture) {
            mSlots[i].mOwnedByClient = true;
            found = i;
            break;
        }
    }
    if (found == INVALID_BUFFER_SLOT) {
        return -EBUSY;
    }
    *buf = found;
    return OK;
}

status_t SurfaceTexture::queueBuffer(int buf) {
    Mutex::Autolock lock(mMutex);
    if (buf < 0 || mBufferCount <= buf) {
        LOGE("queueBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, buf);
        return -EINVAL;
    } else if (!mSlots[buf].mOwnedByClient) {
        LOGE("queueBuffer: slot %d is not owned by the client", buf);
        return -EINVAL;
    } else if (mSlots[buf].mGraphicBuffer == 0) {
        LOGE("queueBuffer: slot %d was enqueued without requesting a buffer",
                buf);
        return -EINVAL;
    }
    mSlots[buf].mOwnedByClient = false;
    mLastQueued = buf;
    return OK;
}

void SurfaceTexture::cancelBuffer(int buf) {
    Mutex::Autolock lock(mMutex);
    if (buf < 0 || mBufferCount <= buf) {
        LOGE("cancelBuffer: slot index out of range [0, %d]: %d", mBufferCount,
                buf);
        return;
    } else if (!mSlots[buf].mOwnedByClient) {
        LOGE("cancelBuffer: slot %d is not owned by the client", buf);
        return;
    }
    mSlots[buf].mOwnedByClient = false;
}

status_t SurfaceTexture::setCrop(const Rect& reg) {
    Mutex::Autolock lock(mMutex);
    // XXX: How should we handle crops?
    return OK;
}

status_t SurfaceTexture::setTransform(uint32_t transform) {
    Mutex::Autolock lock(mMutex);
    // XXX: How should we handle transforms?
    return OK;
}

status_t SurfaceTexture::updateTexImage() {
    Mutex::Autolock lock(mMutex);

    // We always bind the texture even if we don't update its contents.
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, mTexName);

    // Initially both mCurrentTexture and mLastQueued are INVALID_BUFFER_SLOT,
    // so this check will fail until a buffer gets queued.
    if (mCurrentTexture != mLastQueued) {
        // XXX: Figure out the right target.
        mCurrentTexture = mLastQueued;
        EGLImageKHR image = mSlots[mCurrentTexture].mEglImage;
        if (image == EGL_NO_IMAGE_KHR) {
            EGLDisplay dpy = eglGetCurrentDisplay();
            sp<GraphicBuffer> graphicBuffer = mSlots[mCurrentTexture].mGraphicBuffer;
            image = createImage(dpy, graphicBuffer);
            mSlots[mCurrentTexture].mEglImage = image;
            mSlots[mCurrentTexture].mEglDisplay = dpy;
        }
        glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES)image);
        GLint error = glGetError();
        if (error != GL_NO_ERROR) {
            LOGE("error binding external texture image %p (slot %d): %#04x",
                    image, mCurrentTexture, error);
            return -EINVAL;
        }
    }
    return OK;
}

void SurfaceTexture::freeAllBuffers() {
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        mSlots[i].mGraphicBuffer = 0;
        mSlots[i].mOwnedByClient = false;
        if (mSlots[i].mEglImage != EGL_NO_IMAGE_KHR) {
            eglDestroyImageKHR(mSlots[i].mEglDisplay, mSlots[i].mEglImage);
            mSlots[i].mEglImage = EGL_NO_IMAGE_KHR;
            mSlots[i].mEglDisplay = EGL_NO_DISPLAY;
        }
    }
}

EGLImageKHR SurfaceTexture::createImage(EGLDisplay dpy,
        const sp<GraphicBuffer>& graphicBuffer) {
    EGLClientBuffer cbuf = (EGLClientBuffer)graphicBuffer->getNativeBuffer();
    EGLint attrs[] = {
        EGL_IMAGE_PRESERVED_KHR,    EGL_TRUE,
        EGL_NONE,
    };
    EGLImageKHR image = eglCreateImageKHR(dpy, EGL_NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID, cbuf, attrs);
    EGLint error = eglGetError();
    if (error != EGL_SUCCESS) {
        LOGE("error creating EGLImage: %#x", error);
    } else if (image == EGL_NO_IMAGE_KHR) {
        LOGE("no error reported, but no image was returned by "
                "eglCreateImageKHR");
    }
    return image;
}

}; // namespace android
