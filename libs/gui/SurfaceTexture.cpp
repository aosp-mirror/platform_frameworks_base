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

#include <hardware/hardware.h>

#include <gui/IGraphicBufferAlloc.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceTexture.h>

#include <private/gui/ComposerService.h>

#include <utils/Log.h>
#include <utils/String8.h>

// This compile option makes SurfaceTexture use the EGL_KHR_fence_sync extension
// to synchronize access to the buffers.  It will cause dequeueBuffer to stall,
// waiting for the GL reads for the buffer being dequeued to complete before
// allowing the buffer to be dequeued.
#ifdef USE_FENCE_SYNC
#ifdef ALLOW_DEQUEUE_CURRENT_BUFFER
#error "USE_FENCE_SYNC and ALLOW_DEQUEUE_CURRENT_BUFFER are incompatible"
#endif
#endif

// Macros for including the SurfaceTexture name in log messages
#define ST_LOGV(x, ...) ALOGV("[%s] "x, mName.string(), ##__VA_ARGS__)
#define ST_LOGD(x, ...) ALOGD("[%s] "x, mName.string(), ##__VA_ARGS__)
#define ST_LOGI(x, ...) ALOGI("[%s] "x, mName.string(), ##__VA_ARGS__)
#define ST_LOGW(x, ...) ALOGW("[%s] "x, mName.string(), ##__VA_ARGS__)
#define ST_LOGE(x, ...) ALOGE("[%s] "x, mName.string(), ##__VA_ARGS__)

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


SurfaceTexture::SurfaceTexture(GLuint tex, bool allowSynchronousMode,
        GLenum texTarget, bool useFenceSync) :
    BufferQueue(allowSynchronousMode),
    mCurrentTransform(0),
    mCurrentTimestamp(0),
    mTexName(tex),
#ifdef USE_FENCE_SYNC
    mUseFenceSync(useFenceSync),
#else
    mUseFenceSync(false),
#endif
    mTexTarget(texTarget)
{

    ST_LOGV("SurfaceTexture");
    memcpy(mCurrentTransformMatrix, mtxIdentity,
            sizeof(mCurrentTransformMatrix));
}

SurfaceTexture::~SurfaceTexture() {
    ST_LOGV("~SurfaceTexture");
    freeAllBuffersLocked();
}

status_t SurfaceTexture::setBufferCountServer(int bufferCount) {
    Mutex::Autolock lock(mMutex);
    return setBufferCountServerLocked(bufferCount);
}


status_t SurfaceTexture::setDefaultBufferSize(uint32_t w, uint32_t h)
{
    ST_LOGV("setDefaultBufferSize: w=%d, h=%d", w, h);
    if (!w || !h) {
        ST_LOGE("setDefaultBufferSize: dimensions cannot be 0 (w=%d, h=%d)",
                w, h);
        return BAD_VALUE;
    }

    Mutex::Autolock lock(mMutex);
    mDefaultWidth = w;
    mDefaultHeight = h;
    return OK;
}

status_t SurfaceTexture::updateTexImage() {
    ST_LOGV("updateTexImage");
    Mutex::Autolock lock(mMutex);

    if (mAbandoned) {
        ST_LOGE("calling updateTexImage() on an abandoned SurfaceTexture");
        return NO_INIT;
    }

    // In asynchronous mode the list is guaranteed to be one buffer
    // deep, while in synchronous mode we use the oldest buffer.
    if (!mQueue.empty()) {
        Fifo::iterator front(mQueue.begin());
        int buf = *front;

        // Update the GL texture object.
        EGLImageKHR image = mSlots[buf].mEglImage;
        EGLDisplay dpy = eglGetCurrentDisplay();
        if (image == EGL_NO_IMAGE_KHR) {
            if (mSlots[buf].mGraphicBuffer == 0) {
                ST_LOGE("buffer at slot %d is null", buf);
                return BAD_VALUE;
            }
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
            ST_LOGW("updateTexImage: clearing GL error: %#04x", error);
        }

        glBindTexture(mTexTarget, mTexName);
        glEGLImageTargetTexture2DOES(mTexTarget, (GLeglImageOES)image);

        bool failed = false;
        while ((error = glGetError()) != GL_NO_ERROR) {
            ST_LOGE("error binding external texture image %p (slot %d): %#04x",
                    image, buf, error);
            failed = true;
        }
        if (failed) {
            return -EINVAL;
        }

        if (mCurrentTexture != INVALID_BUFFER_SLOT) {
            if (mUseFenceSync) {
                EGLSyncKHR fence = eglCreateSyncKHR(dpy, EGL_SYNC_FENCE_KHR,
                        NULL);
                if (fence == EGL_NO_SYNC_KHR) {
                    ALOGE("updateTexImage: error creating fence: %#x",
                            eglGetError());
                    return -EINVAL;
                }
                glFlush();
                mSlots[mCurrentTexture].mFence = fence;
            }
        }

        ST_LOGV("updateTexImage: (slot=%d buf=%p) -> (slot=%d buf=%p)",
                mCurrentTexture,
                mCurrentTextureBuf != NULL ? mCurrentTextureBuf->handle : 0,
                buf, mSlots[buf].mGraphicBuffer->handle);

        if (mCurrentTexture != INVALID_BUFFER_SLOT) {
            // The current buffer becomes FREE if it was still in the queued
            // state. If it has already been given to the client
            // (synchronous mode), then it stays in DEQUEUED state.
            if (mSlots[mCurrentTexture].mBufferState == BufferSlot::QUEUED) {
                mSlots[mCurrentTexture].mBufferState = BufferSlot::FREE;
            }
        }

        // Update the SurfaceTexture state.
        mCurrentTexture = buf;
        mCurrentTextureBuf = mSlots[buf].mGraphicBuffer;
        mCurrentCrop = mSlots[buf].mCrop;
        mCurrentTransform = mSlots[buf].mTransform;
        mCurrentScalingMode = mSlots[buf].mScalingMode;
        mCurrentTimestamp = mSlots[buf].mTimestamp;
        computeCurrentTransformMatrix();

        // Now that we've passed the point at which failures can happen,
        // it's safe to remove the buffer from the front of the queue.
        mQueue.erase(front);
        mDequeueCondition.signal();
    } else {
        // We always bind the texture even if we don't update its contents.
        glBindTexture(mTexTarget, mTexName);
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

GLenum SurfaceTexture::getCurrentTextureTarget() const {
    return mTexTarget;
}

void SurfaceTexture::getTransformMatrix(float mtx[16]) {
    Mutex::Autolock lock(mMutex);
    memcpy(mtx, mCurrentTransformMatrix, sizeof(mCurrentTransformMatrix));
}

void SurfaceTexture::computeCurrentTransformMatrix() {
    ST_LOGV("computeCurrentTransformMatrix");

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
    mtxMul(mCurrentTransformMatrix, mtxFlipV, mtxBeforeFlipV);
}

nsecs_t SurfaceTexture::getTimestamp() {
    ST_LOGV("getTimestamp");
    Mutex::Autolock lock(mMutex);
    return mCurrentTimestamp;
}

void SurfaceTexture::setFrameAvailableListener(
        const sp<FrameAvailableListener>& listener) {
    ST_LOGV("setFrameAvailableListener");
    Mutex::Autolock lock(mMutex);
    mFrameAvailableListener = listener;
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
        ST_LOGE("error creating EGLImage: %#x", error);
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

uint32_t SurfaceTexture::getCurrentScalingMode() const {
    Mutex::Autolock lock(mMutex);
    return mCurrentScalingMode;
}

bool SurfaceTexture::isSynchronousMode() const {
    Mutex::Autolock lock(mMutex);
    return mSynchronousMode;
}



void SurfaceTexture::abandon() {
    Mutex::Autolock lock(mMutex);
    mQueue.clear();
    mAbandoned = true;
    mCurrentTextureBuf.clear();
    freeAllBuffersLocked();
    mDequeueCondition.signal();
}

void SurfaceTexture::setName(const String8& name) {
    mName = name;
}

void SurfaceTexture::dump(String8& result) const
{
    char buffer[1024];
    dump(result, "", buffer, 1024);
}

void SurfaceTexture::dump(String8& result, const char* prefix,
        char* buffer, size_t SIZE) const
{
    Mutex::Autolock _l(mMutex);
    snprintf(buffer, SIZE,
            "%smBufferCount=%d, mSynchronousMode=%d, default-size=[%dx%d], "
            "mPixelFormat=%d, mTexName=%d\n",
            prefix, mBufferCount, mSynchronousMode, mDefaultWidth,
            mDefaultHeight, mPixelFormat, mTexName);
    result.append(buffer);

    String8 fifo;
    int fifoSize = 0;
    Fifo::const_iterator i(mQueue.begin());
    while (i != mQueue.end()) {
        snprintf(buffer, SIZE, "%02d ", *i++);
        fifoSize++;
        fifo.append(buffer);
    }

    snprintf(buffer, SIZE,
            "%scurrent: {crop=[%d,%d,%d,%d], transform=0x%02x, current=%d}\n"
            "%snext   : {crop=[%d,%d,%d,%d], transform=0x%02x, FIFO(%d)={%s}}\n"
            ,
            prefix, mCurrentCrop.left,
            mCurrentCrop.top, mCurrentCrop.right, mCurrentCrop.bottom,
            mCurrentTransform, mCurrentTexture,
            prefix, mNextCrop.left, mNextCrop.top, mNextCrop.right,
            mNextCrop.bottom, mNextTransform, fifoSize, fifo.string()
    );
    result.append(buffer);

    struct {
        const char * operator()(int state) const {
            switch (state) {
                case BufferSlot::DEQUEUED: return "DEQUEUED";
                case BufferSlot::QUEUED: return "QUEUED";
                case BufferSlot::FREE: return "FREE";
                default: return "Unknown";
            }
        }
    } stateName;

    for (int i=0 ; i<mBufferCount ; i++) {
        const BufferSlot& slot(mSlots[i]);
        snprintf(buffer, SIZE,
                "%s%s[%02d] "
                "state=%-8s, crop=[%d,%d,%d,%d], "
                "transform=0x%02x, timestamp=%lld",
                prefix, (i==mCurrentTexture)?">":" ", i,
                stateName(slot.mBufferState),
                slot.mCrop.left, slot.mCrop.top, slot.mCrop.right,
                slot.mCrop.bottom, slot.mTransform, slot.mTimestamp
        );
        result.append(buffer);

        const sp<GraphicBuffer>& buf(slot.mGraphicBuffer);
        if (buf != NULL) {
            snprintf(buffer, SIZE,
                    ", %p [%4ux%4u:%4u,%3X]",
                    buf->handle, buf->width, buf->height, buf->stride,
                    buf->format);
            result.append(buffer);
        }
        result.append("\n");
    }
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
