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
//#define LOG_NDEBUG 0

#define GL_GLEXT_PROTOTYPES
#define EGL_EGLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <gui/SurfaceTexture.h>

#include <hardware/hardware.h>

#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/SurfaceComposerClient.h>
#include <surfaceflinger/IGraphicBufferAlloc.h>

#include <utils/Log.h>

namespace android {

// Transform matrices
static float mtxIdentity[16] = {
    1, 0, 0, 0,
    0, 1, 0, 0,
    0, 0, 1, 0,
    0, 0, 0, 1,
};
static float mtxFlipH[16] = {
    -1, 0, 0, 0,
    0, 1, 0, 0,
    0, 0, 1, 0,
    1, 0, 0, 1,
};
static float mtxFlipV[16] = {
    1, 0, 0, 0,
    0, -1, 0, 0,
    0, 0, 1, 0,
    0, 1, 0, 1,
};
static float mtxRot90[16] = {
    0, 1, 0, 0,
    -1, 0, 0, 0,
    0, 0, 1, 0,
    1, 0, 0, 1,
};
static float mtxRot180[16] = {
    -1, 0, 0, 0,
    0, -1, 0, 0,
    0, 0, 1, 0,
    1, 1, 0, 1,
};
static float mtxRot270[16] = {
    0, -1, 0, 0,
    1, 0, 0, 0,
    0, 0, 1, 0,
    0, 1, 0, 1,
};

static void mtxMul(float out[16], const float a[16], const float b[16]);

SurfaceTexture::SurfaceTexture(GLuint tex) :
    mDefaultWidth(1),
    mDefaultHeight(1),
    mPixelFormat(PIXEL_FORMAT_RGBA_8888),
    mBufferCount(MIN_BUFFER_SLOTS),
    mCurrentTexture(INVALID_BUFFER_SLOT),
    mCurrentTextureTarget(GL_TEXTURE_EXTERNAL_OES),
    mCurrentTransform(0),
    mCurrentTimestamp(0),
    mNextTransform(0),
    mTexName(tex),
    mSynchronousMode(false) {
    LOGV("SurfaceTexture::SurfaceTexture");
    sp<ISurfaceComposer> composer(ComposerService::getComposerService());
    mGraphicBufferAlloc = composer->createGraphicBufferAlloc();
    mNextCrop.makeInvalid();
}

SurfaceTexture::~SurfaceTexture() {
    LOGV("SurfaceTexture::~SurfaceTexture");
    freeAllBuffers();
}

status_t SurfaceTexture::setBufferCount(int bufferCount) {
    LOGV("SurfaceTexture::setBufferCount");
    Mutex::Autolock lock(mMutex);

    const int minBufferSlots = mSynchronousMode ?
            MIN_BUFFER_SLOTS-1 : MIN_BUFFER_SLOTS;

    if (bufferCount < minBufferSlots) {
        return BAD_VALUE;
    }

    freeAllBuffers();
    mBufferCount = bufferCount;
    mCurrentTexture = INVALID_BUFFER_SLOT;
    mQueue.clear();
    mQueue.reserve(mSynchronousMode ? mBufferCount : 1);
    mDequeueCondition.signal();
    return OK;
}

status_t SurfaceTexture::setDefaultBufferSize(uint32_t w, uint32_t h)
{
    Mutex::Autolock lock(mMutex);
    if ((w != mDefaultWidth) || (h != mDefaultHeight)) {
        mDefaultWidth = w;
        mDefaultHeight = h;
    }
    return OK;
}

sp<GraphicBuffer> SurfaceTexture::requestBuffer(int buf) {
    LOGV("SurfaceTexture::requestBuffer");
    Mutex::Autolock lock(mMutex);
    if (buf < 0 || mBufferCount <= buf) {
        LOGE("requestBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, buf);
        return 0;
    }
    mSlots[buf].mRequestBufferCalled = true;
    return mSlots[buf].mGraphicBuffer;
}

status_t SurfaceTexture::dequeueBuffer(int *outBuf, uint32_t w, uint32_t h,
        uint32_t format, uint32_t usage) {
    LOGV("SurfaceTexture::dequeueBuffer");

    if ((w && !h) || (!w & h)) {
        LOGE("dequeueBuffer: invalid size: w=%u, h=%u", w, h);
        return BAD_VALUE;
    }

    Mutex::Autolock lock(mMutex);
    int found, foundSync;
    int dequeuedCount = 0;
    bool tryAgain = true;
    while (tryAgain) {
        found = INVALID_BUFFER_SLOT;
        foundSync = INVALID_BUFFER_SLOT;
        dequeuedCount = 0;
        for (int i = 0; i < mBufferCount; i++) {
            const int state = mSlots[i].mBufferState;
            if (state == BufferSlot::DEQUEUED) {
                dequeuedCount++;
            }
            if (state == BufferSlot::FREE || i == mCurrentTexture) {
                foundSync = i;
                if (i != mCurrentTexture) {
                    found = i;
                    break;
                }
            }
        }
        // we're in synchronous mode and didn't find a buffer, we need to wait
        tryAgain = mSynchronousMode && (foundSync == INVALID_BUFFER_SLOT);
        if (tryAgain) {
            mDequeueCondition.wait(mMutex);
        }
    }

    if (mSynchronousMode) {
        // we're dequeuing more buffers than allowed in synchronous mode
        if ((mBufferCount - (dequeuedCount+1)) < MIN_UNDEQUEUED_BUFFERS-1)
            return -EBUSY;

        if (found == INVALID_BUFFER_SLOT) {
            // foundSync guaranteed to be != INVALID_BUFFER_SLOT
            found = foundSync;
        }
    }

    if (found == INVALID_BUFFER_SLOT) {
        return -EBUSY;
    }

    const int buf = found;
    *outBuf = found;

    const bool useDefaultSize = !w && !h;
    if (useDefaultSize) {
        // use the default size
        w = mDefaultWidth;
        h = mDefaultHeight;
    }

    const bool updateFormat = (format != 0);
    if (!updateFormat) {
        // keep the current (or default) format
        format = mPixelFormat;
    }

    // buffer is now in DEQUEUED (but can also be current at the same time,
    // if we're in synchronous mode)
    mSlots[buf].mBufferState = BufferSlot::DEQUEUED;

    const sp<GraphicBuffer>& buffer(mSlots[buf].mGraphicBuffer);
    if ((buffer == NULL) ||
        (uint32_t(buffer->width)  != w) ||
        (uint32_t(buffer->height) != h) ||
        (uint32_t(buffer->format) != format) ||
        ((uint32_t(buffer->usage) & usage) != usage))
    {
        usage |= GraphicBuffer::USAGE_HW_TEXTURE;
        sp<GraphicBuffer> graphicBuffer(
                mGraphicBufferAlloc->createGraphicBuffer(w, h, format, usage));
        if (graphicBuffer == 0) {
            LOGE("dequeueBuffer: SurfaceComposer::createGraphicBuffer failed");
            return NO_MEMORY;
        }
        if (updateFormat) {
            mPixelFormat = format;
        }
        mSlots[buf].mGraphicBuffer = graphicBuffer;
        mSlots[buf].mRequestBufferCalled = false;
        if (mSlots[buf].mEglImage != EGL_NO_IMAGE_KHR) {
            eglDestroyImageKHR(mSlots[buf].mEglDisplay, mSlots[buf].mEglImage);
            mSlots[buf].mEglImage = EGL_NO_IMAGE_KHR;
            mSlots[buf].mEglDisplay = EGL_NO_DISPLAY;
        }
        return ISurfaceTexture::BUFFER_NEEDS_REALLOCATION;
    }
    return OK;
}

status_t SurfaceTexture::setSynchronousMode(bool enabled) {
    Mutex::Autolock lock(mMutex);
    if (mSynchronousMode != enabled) {
        mSynchronousMode = enabled;
        freeAllBuffers();
        mCurrentTexture = INVALID_BUFFER_SLOT;
        mQueue.clear();
        mQueue.reserve(mSynchronousMode ? mBufferCount : 1);
        mDequeueCondition.signal();
    }
    return NO_ERROR;
}

status_t SurfaceTexture::queueBuffer(int buf, int64_t timestamp) {
    LOGV("SurfaceTexture::queueBuffer");
    Mutex::Autolock lock(mMutex);
    if (buf < 0 || buf >= mBufferCount) {
        LOGE("queueBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, buf);
        return -EINVAL;
    } else if (mSlots[buf].mBufferState != BufferSlot::DEQUEUED) {
        LOGE("queueBuffer: slot %d is not owned by the client (state=%d)",
                buf, mSlots[buf].mBufferState);
        return -EINVAL;
    } else if (!mSlots[buf].mRequestBufferCalled) {
        LOGE("queueBuffer: slot %d was enqueued without requesting a buffer",
                buf);
        return -EINVAL;
    }

    if (mSynchronousMode) {
        // in synchronous mode we queue all buffers in a FIFO
        mQueue.push_back(buf);
    } else {
        // in asynchronous mode we only keep the most recent buffer
        if (mQueue.empty()) {
            mQueue.push_back(buf);
        } else {
            Fifo::iterator front(mQueue.begin());
            // buffer currently queued is freed
            mSlots[*front].mBufferState = BufferSlot::FREE;
            // and we record the new buffer index in the queued list
            *front = buf;
        }
    }

    mSlots[buf].mBufferState = BufferSlot::QUEUED;
    mSlots[buf].mLastQueuedCrop = mNextCrop;
    mSlots[buf].mLastQueuedTransform = mNextTransform;
    mSlots[buf].mLastQueuedTimestamp = timestamp;

    if (mFrameAvailableListener != 0) {
        mFrameAvailableListener->onFrameAvailable();
    }
    mDequeueCondition.signal();
    return OK;
}

void SurfaceTexture::cancelBuffer(int buf) {
    LOGV("SurfaceTexture::cancelBuffer");
    Mutex::Autolock lock(mMutex);
    if (buf < 0 || buf >= mBufferCount) {
        LOGE("cancelBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, buf);
        return;
    } else if (mSlots[buf].mBufferState != BufferSlot::DEQUEUED) {
        LOGE("cancelBuffer: slot %d is not owned by the client (state=%d)",
                buf, mSlots[buf].mBufferState);
        return;
    }
    mSlots[buf].mBufferState = BufferSlot::FREE;
    mDequeueCondition.signal();
}

status_t SurfaceTexture::setCrop(const Rect& crop) {
    LOGV("SurfaceTexture::setCrop");
    Mutex::Autolock lock(mMutex);
    mNextCrop = crop;
    return OK;
}

status_t SurfaceTexture::setTransform(uint32_t transform) {
    LOGV("SurfaceTexture::setTransform");
    Mutex::Autolock lock(mMutex);
    mNextTransform = transform;
    return OK;
}

status_t SurfaceTexture::updateTexImage() {
    LOGV("SurfaceTexture::updateTexImage");
    Mutex::Autolock lock(mMutex);

    int buf = mCurrentTexture;
    if (!mQueue.empty()) {
        // in asynchronous mode the list is guaranteed to be one buffer deep,
        // while in synchronous mode we use the oldest buffer
        Fifo::iterator front(mQueue.begin());
        buf = *front;
        mQueue.erase(front);
    }

    // Initially both mCurrentTexture and buf are INVALID_BUFFER_SLOT,
    // so this check will fail until a buffer gets queued.
    if (mCurrentTexture != buf) {
        // Update the GL texture object.
        EGLImageKHR image = mSlots[buf].mEglImage;
        if (image == EGL_NO_IMAGE_KHR) {
            EGLDisplay dpy = eglGetCurrentDisplay();
            image = createImage(dpy, mSlots[buf].mGraphicBuffer);
            mSlots[buf].mEglImage = image;
            mSlots[buf].mEglDisplay = dpy;
            if (image == EGL_NO_IMAGE_KHR) {
                // NOTE: if dpy was invalid, createImage() is guaranteed to
                // fail. so we'd end up here.
                return -EINVAL;
            }
        }

        GLint error;
        while ((error = glGetError()) != GL_NO_ERROR) {
            LOGE("GL error cleared before updating SurfaceTexture: %#04x", error);
        }

        GLenum target = getTextureTarget(mSlots[buf].mGraphicBuffer->format);
        if (target != mCurrentTextureTarget) {
            glDeleteTextures(1, &mTexName);
        }
        glBindTexture(target, mTexName);
        glEGLImageTargetTexture2DOES(target, (GLeglImageOES)image);

        bool failed = false;
        while ((error = glGetError()) != GL_NO_ERROR) {
            LOGE("error binding external texture image %p (slot %d): %#04x",
                    image, buf, error);
            failed = true;
        }
        if (failed) {
            return -EINVAL;
        }

        if (mCurrentTexture != INVALID_BUFFER_SLOT) {
            // the current buffer becomes FREE if it was still in the queued
            // state. If it has already been given to the client
            // (synchronous mode), then it stays in DEQUEUED state.
            if (mSlots[mCurrentTexture].mBufferState == BufferSlot::QUEUED)
                mSlots[mCurrentTexture].mBufferState = BufferSlot::FREE;
        }

        // Update the SurfaceTexture state.
        mCurrentTexture = buf;
        mCurrentTextureTarget = target;
        mCurrentTextureBuf = mSlots[buf].mGraphicBuffer;
        mCurrentCrop = mSlots[buf].mLastQueuedCrop;
        mCurrentTransform = mSlots[buf].mLastQueuedTransform;
        mCurrentTimestamp = mSlots[buf].mLastQueuedTimestamp;
        mDequeueCondition.signal();
    } else {
        // We always bind the texture even if we don't update its contents.
        glBindTexture(mCurrentTextureTarget, mTexName);
    }
    return OK;
}

bool SurfaceTexture::isExternalFormat(uint32_t format)
{
    switch (format) {
    // supported YUV formats
    case HAL_PIXEL_FORMAT_YV12:
    // Legacy/deprecated YUV formats
    case HAL_PIXEL_FORMAT_YCbCr_422_SP:
    case HAL_PIXEL_FORMAT_YCrCb_420_SP:
    case HAL_PIXEL_FORMAT_YCbCr_422_I:
        return true;
    }

    // Any OEM format needs to be considered
    if (format>=0x100 && format<=0x1FF)
        return true;

    return false;
}

GLenum SurfaceTexture::getTextureTarget(uint32_t format)
{
    GLenum target = GL_TEXTURE_2D;
#if defined(GL_OES_EGL_image_external)
    if (isExternalFormat(format)) {
        target = GL_TEXTURE_EXTERNAL_OES;
    }
#endif
    return target;
}

GLenum SurfaceTexture::getCurrentTextureTarget() const {
    Mutex::Autolock lock(mMutex);
    return mCurrentTextureTarget;
}

void SurfaceTexture::getTransformMatrix(float mtx[16]) {
    LOGV("SurfaceTexture::getTransformMatrix");
    Mutex::Autolock lock(mMutex);

    float xform[16];
    for (int i = 0; i < 16; i++) {
        xform[i] = mtxIdentity[i];
    }
    if (mCurrentTransform & NATIVE_WINDOW_TRANSFORM_FLIP_H) {
        float result[16];
        mtxMul(result, xform, mtxFlipH);
        for (int i = 0; i < 16; i++) {
            xform[i] = result[i];
        }
    }
    if (mCurrentTransform & NATIVE_WINDOW_TRANSFORM_FLIP_V) {
        float result[16];
        mtxMul(result, xform, mtxFlipV);
        for (int i = 0; i < 16; i++) {
            xform[i] = result[i];
        }
    }
    if (mCurrentTransform & NATIVE_WINDOW_TRANSFORM_ROT_90) {
        float result[16];
        mtxMul(result, xform, mtxRot90);
        for (int i = 0; i < 16; i++) {
            xform[i] = result[i];
        }
    }

    sp<GraphicBuffer>& buf(mSlots[mCurrentTexture].mGraphicBuffer);
    float tx, ty, sx, sy;
    if (!mCurrentCrop.isEmpty()) {
        // In order to prevent bilinear sampling at the of the crop rectangle we
        // may need to shrink it by 2 texels in each direction.  Normally this
        // would just need to take 1/2 a texel off each end, but because the
        // chroma channels will likely be subsampled we need to chop off a whole
        // texel.  This will cause artifacts if someone does nearest sampling
        // with 1:1 pixel:texel ratio, but it's impossible to simultaneously
        // accomodate the bilinear and nearest sampling uses.
        //
        // If nearest sampling turns out to be a desirable usage of these
        // textures then we could add the ability to switch a SurfaceTexture to
        // nearest-mode.  Preferably, however, the image producers (video
        // decoder, camera, etc.) would simply not use a crop rectangle (or at
        // least not tell the framework about it) so that the GPU can do the
        // correct edge behavior.
        int xshrink = 0, yshrink = 0;
        if (mCurrentCrop.left > 0) {
            tx = float(mCurrentCrop.left + 1) / float(buf->getWidth());
            xshrink++;
        } else {
            tx = 0.0f;
        }
        if (mCurrentCrop.right < int32_t(buf->getWidth())) {
            xshrink++;
        }
        if (mCurrentCrop.bottom < int32_t(buf->getHeight())) {
            ty = (float(buf->getHeight() - mCurrentCrop.bottom) + 1.0f) /
                    float(buf->getHeight());
            yshrink++;
        } else {
            ty = 0.0f;
        }
        if (mCurrentCrop.top > 0) {
            yshrink++;
        }
        sx = float(mCurrentCrop.width() - xshrink) / float(buf->getWidth());
        sy = float(mCurrentCrop.height() - yshrink) / float(buf->getHeight());
    } else {
        tx = 0.0f;
        ty = 0.0f;
        sx = 1.0f;
        sy = 1.0f;
    }
    float crop[16] = {
        sx, 0, 0, 0,
        0, sy, 0, 0,
        0, 0, 1, 0,
        tx, ty, 0, 1,
    };

    float mtxBeforeFlipV[16];
    mtxMul(mtxBeforeFlipV, crop, xform);

    // SurfaceFlinger expects the top of its window textures to be at a Y
    // coordinate of 0, so SurfaceTexture must behave the same way.  We don't
    // want to expose this to applications, however, so we must add an
    // additional vertical flip to the transform after all the other transforms.
    mtxMul(mtx, mtxFlipV, mtxBeforeFlipV);
}

nsecs_t SurfaceTexture::getTimestamp() {
    LOGV("SurfaceTexture::getTimestamp");
    Mutex::Autolock lock(mMutex);
    return mCurrentTimestamp;
}

void SurfaceTexture::setFrameAvailableListener(
        const sp<FrameAvailableListener>& l) {
    LOGV("SurfaceTexture::setFrameAvailableListener");
    Mutex::Autolock lock(mMutex);
    mFrameAvailableListener = l;
}

sp<IBinder> SurfaceTexture::getAllocator() {
    LOGV("SurfaceTexture::getAllocator");
    return mGraphicBufferAlloc->asBinder();
}

void SurfaceTexture::freeAllBuffers() {
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        mSlots[i].mGraphicBuffer = 0;
        mSlots[i].mBufferState = BufferSlot::FREE;
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
    if (image == EGL_NO_IMAGE_KHR) {
        EGLint error = eglGetError();
        LOGE("error creating EGLImage: %#x", error);
    }
    return image;
}

sp<GraphicBuffer> SurfaceTexture::getCurrentBuffer() const {
    Mutex::Autolock lock(mMutex);
    return mCurrentTextureBuf;
}

Rect SurfaceTexture::getCurrentCrop() const {
    Mutex::Autolock lock(mMutex);
    return mCurrentCrop;
}

uint32_t SurfaceTexture::getCurrentTransform() const {
    Mutex::Autolock lock(mMutex);
    return mCurrentTransform;
}

int SurfaceTexture::query(int what, int* outValue)
{
    Mutex::Autolock lock(mMutex);
    int value;
    switch (what) {
    case NATIVE_WINDOW_WIDTH:
        value = mDefaultWidth;
        if (!mDefaultWidth && !mDefaultHeight && mCurrentTextureBuf!=0)
            value = mCurrentTextureBuf->width;
        break;
    case NATIVE_WINDOW_HEIGHT:
        value = mDefaultHeight;
        if (!mDefaultWidth && !mDefaultHeight && mCurrentTextureBuf!=0)
            value = mCurrentTextureBuf->height;
        break;
    case NATIVE_WINDOW_FORMAT:
        value = mPixelFormat;
        break;
    case NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS:
        value = mSynchronousMode ?
                (MIN_UNDEQUEUED_BUFFERS-1) : MIN_UNDEQUEUED_BUFFERS;
        break;
    default:
        return BAD_VALUE;
    }
    outValue[0] = value;
    return NO_ERROR;
}

static void mtxMul(float out[16], const float a[16], const float b[16]) {
    out[0] = a[0]*b[0] + a[4]*b[1] + a[8]*b[2] + a[12]*b[3];
    out[1] = a[1]*b[0] + a[5]*b[1] + a[9]*b[2] + a[13]*b[3];
    out[2] = a[2]*b[0] + a[6]*b[1] + a[10]*b[2] + a[14]*b[3];
    out[3] = a[3]*b[0] + a[7]*b[1] + a[11]*b[2] + a[15]*b[3];

    out[4] = a[0]*b[4] + a[4]*b[5] + a[8]*b[6] + a[12]*b[7];
    out[5] = a[1]*b[4] + a[5]*b[5] + a[9]*b[6] + a[13]*b[7];
    out[6] = a[2]*b[4] + a[6]*b[5] + a[10]*b[6] + a[14]*b[7];
    out[7] = a[3]*b[4] + a[7]*b[5] + a[11]*b[6] + a[15]*b[7];

    out[8] = a[0]*b[8] + a[4]*b[9] + a[8]*b[10] + a[12]*b[11];
    out[9] = a[1]*b[8] + a[5]*b[9] + a[9]*b[10] + a[13]*b[11];
    out[10] = a[2]*b[8] + a[6]*b[9] + a[10]*b[10] + a[14]*b[11];
    out[11] = a[3]*b[8] + a[7]*b[9] + a[11]*b[10] + a[15]*b[11];

    out[12] = a[0]*b[12] + a[4]*b[13] + a[8]*b[14] + a[12]*b[15];
    out[13] = a[1]*b[12] + a[5]*b[13] + a[9]*b[14] + a[13]*b[15];
    out[14] = a[2]*b[12] + a[6]*b[13] + a[10]*b[14] + a[14]*b[15];
    out[15] = a[3]*b[12] + a[7]*b[13] + a[11]*b[14] + a[15]*b[15];
}

}; // namespace android
