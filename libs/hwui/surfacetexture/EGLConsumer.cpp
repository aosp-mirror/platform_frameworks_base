/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <inttypes.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <cutils/compiler.h>
#include <gui/BufferItem.h>
#include <gui/BufferQueue.h>
#include <private/gui/SyncFeatures.h>
#include "EGLConsumer.h"
#include "SurfaceTexture.h"

#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/Trace.h>

#define PROT_CONTENT_EXT_STR "EGL_EXT_protected_content"
#define EGL_PROTECTED_CONTENT_EXT 0x32C0

namespace android {

// Macros for including the SurfaceTexture name in log messages
#define EGC_LOGV(x, ...) ALOGV("[%s] " x, st.mName.string(), ##__VA_ARGS__)
#define EGC_LOGD(x, ...) ALOGD("[%s] " x, st.mName.string(), ##__VA_ARGS__)
#define EGC_LOGW(x, ...) ALOGW("[%s] " x, st.mName.string(), ##__VA_ARGS__)
#define EGC_LOGE(x, ...) ALOGE("[%s] " x, st.mName.string(), ##__VA_ARGS__)

static const struct {
    uint32_t width, height;
    char const* bits;
} kDebugData = {15, 12,
                "_______________"
                "_______________"
                "_____XX_XX_____"
                "__X_X_____X_X__"
                "__X_XXXXXXX_X__"
                "__XXXXXXXXXXX__"
                "___XX_XXX_XX___"
                "____XXXXXXX____"
                "_____X___X_____"
                "____X_____X____"
                "_______________"
                "_______________"};

Mutex EGLConsumer::sStaticInitLock;
sp<GraphicBuffer> EGLConsumer::sReleasedTexImageBuffer;

static bool hasEglProtectedContentImpl() {
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    const char* exts = eglQueryString(dpy, EGL_EXTENSIONS);
    size_t cropExtLen = strlen(PROT_CONTENT_EXT_STR);
    size_t extsLen = strlen(exts);
    bool equal = !strcmp(PROT_CONTENT_EXT_STR, exts);
    bool atStart = !strncmp(PROT_CONTENT_EXT_STR " ", exts, cropExtLen + 1);
    bool atEnd = (cropExtLen + 1) < extsLen &&
                 !strcmp(" " PROT_CONTENT_EXT_STR, exts + extsLen - (cropExtLen + 1));
    bool inMiddle = strstr(exts, " " PROT_CONTENT_EXT_STR " ");
    return equal || atStart || atEnd || inMiddle;
}

static bool hasEglProtectedContent() {
    // Only compute whether the extension is present once the first time this
    // function is called.
    static bool hasIt = hasEglProtectedContentImpl();
    return hasIt;
}

EGLConsumer::EGLConsumer() : mEglDisplay(EGL_NO_DISPLAY), mEglContext(EGL_NO_CONTEXT) {}

status_t EGLConsumer::updateTexImage(SurfaceTexture& st) {
    // Make sure the EGL state is the same as in previous calls.
    status_t err = checkAndUpdateEglStateLocked(st);
    if (err != NO_ERROR) {
        return err;
    }

    BufferItem item;

    // Acquire the next buffer.
    // In asynchronous mode the list is guaranteed to be one buffer
    // deep, while in synchronous mode we use the oldest buffer.
    err = st.acquireBufferLocked(&item, 0);
    if (err != NO_ERROR) {
        if (err == BufferQueue::NO_BUFFER_AVAILABLE) {
            // We always bind the texture even if we don't update its contents.
            EGC_LOGV("updateTexImage: no buffers were available");
            glBindTexture(st.mTexTarget, st.mTexName);
            err = NO_ERROR;
        } else {
            EGC_LOGE("updateTexImage: acquire failed: %s (%d)", strerror(-err), err);
        }
        return err;
    }

    // Release the previous buffer.
    err = updateAndReleaseLocked(item, nullptr, st);
    if (err != NO_ERROR) {
        // We always bind the texture.
        glBindTexture(st.mTexTarget, st.mTexName);
        return err;
    }

    // Bind the new buffer to the GL texture, and wait until it's ready.
    return bindTextureImageLocked(st);
}

status_t EGLConsumer::releaseTexImage(SurfaceTexture& st) {
    // Make sure the EGL state is the same as in previous calls.
    status_t err = NO_ERROR;

    // if we're detached, no need to validate EGL's state -- we won't use it.
    if (st.mOpMode == SurfaceTexture::OpMode::attachedToGL) {
        err = checkAndUpdateEglStateLocked(st, true);
        if (err != NO_ERROR) {
            return err;
        }
    }

    // Update the EGLConsumer state.
    int buf = st.mCurrentTexture;
    if (buf != BufferQueue::INVALID_BUFFER_SLOT) {
        EGC_LOGV("releaseTexImage: (slot=%d, mOpMode=%d)", buf, (int)st.mOpMode);

        // if we're detached, we just use the fence that was created in detachFromContext()
        // so... basically, nothing more to do here.
        if (st.mOpMode == SurfaceTexture::OpMode::attachedToGL) {
            // Do whatever sync ops we need to do before releasing the slot.
            err = syncForReleaseLocked(mEglDisplay, st);
            if (err != NO_ERROR) {
                EGC_LOGE("syncForReleaseLocked failed (slot=%d), err=%d", buf, err);
                return err;
            }
        }

        err = st.releaseBufferLocked(buf, st.mSlots[buf].mGraphicBuffer, mEglDisplay,
                                     EGL_NO_SYNC_KHR);
        if (err < NO_ERROR) {
            EGC_LOGE("releaseTexImage: failed to release buffer: %s (%d)", strerror(-err), err);
            return err;
        }

        if (mReleasedTexImage == nullptr) {
            mReleasedTexImage = new EglImage(getDebugTexImageBuffer());
        }

        st.mCurrentTexture = BufferQueue::INVALID_BUFFER_SLOT;
        mCurrentTextureImage = mReleasedTexImage;
        st.mCurrentCrop.makeInvalid();
        st.mCurrentTransform = 0;
        st.mCurrentTimestamp = 0;
        st.mCurrentDataSpace = HAL_DATASPACE_UNKNOWN;
        st.mCurrentFence = Fence::NO_FENCE;
        st.mCurrentFenceTime = FenceTime::NO_FENCE;

        // detached, don't touch the texture (and we may not even have an
        // EGLDisplay here.
        if (st.mOpMode == SurfaceTexture::OpMode::attachedToGL) {
            // This binds a dummy buffer (mReleasedTexImage).
            status_t result = bindTextureImageLocked(st);
            if (result != NO_ERROR) {
                return result;
            }
        }
    }

    return NO_ERROR;
}

sp<GraphicBuffer> EGLConsumer::getDebugTexImageBuffer() {
    Mutex::Autolock _l(sStaticInitLock);
    if (CC_UNLIKELY(sReleasedTexImageBuffer == nullptr)) {
        // The first time, create the debug texture in case the application
        // continues to use it.
        sp<GraphicBuffer> buffer = new GraphicBuffer(
                kDebugData.width, kDebugData.height, PIXEL_FORMAT_RGBA_8888,
                GraphicBuffer::USAGE_SW_WRITE_RARELY, "[EGLConsumer debug texture]");
        uint32_t* bits;
        buffer->lock(GraphicBuffer::USAGE_SW_WRITE_RARELY, reinterpret_cast<void**>(&bits));
        uint32_t stride = buffer->getStride();
        uint32_t height = buffer->getHeight();
        memset(bits, 0, stride * height * 4);
        for (uint32_t y = 0; y < kDebugData.height; y++) {
            for (uint32_t x = 0; x < kDebugData.width; x++) {
                bits[x] = (kDebugData.bits[y + kDebugData.width + x] == 'X') ? 0xFF000000
                                                                             : 0xFFFFFFFF;
            }
            bits += stride;
        }
        buffer->unlock();
        sReleasedTexImageBuffer = buffer;
    }
    return sReleasedTexImageBuffer;
}

void EGLConsumer::onAcquireBufferLocked(BufferItem* item, SurfaceTexture& st) {
    // If item->mGraphicBuffer is not null, this buffer has not been acquired
    // before, so any prior EglImage created is using a stale buffer. This
    // replaces any old EglImage with a new one (using the new buffer).
    int slot = item->mSlot;
    if (item->mGraphicBuffer != nullptr || mEglSlots[slot].mEglImage.get() == nullptr) {
        mEglSlots[slot].mEglImage = new EglImage(st.mSlots[slot].mGraphicBuffer);
    }
}

void EGLConsumer::onReleaseBufferLocked(int buf) {
    mEglSlots[buf].mEglFence = EGL_NO_SYNC_KHR;
}

status_t EGLConsumer::updateAndReleaseLocked(const BufferItem& item, PendingRelease* pendingRelease,
                                             SurfaceTexture& st) {
    status_t err = NO_ERROR;

    int slot = item.mSlot;

    if (st.mOpMode != SurfaceTexture::OpMode::attachedToGL) {
        EGC_LOGE(
                "updateAndRelease: EGLConsumer is not attached to an OpenGL "
                "ES context");
        st.releaseBufferLocked(slot, st.mSlots[slot].mGraphicBuffer, mEglDisplay, EGL_NO_SYNC_KHR);
        return INVALID_OPERATION;
    }

    // Confirm state.
    err = checkAndUpdateEglStateLocked(st);
    if (err != NO_ERROR) {
        st.releaseBufferLocked(slot, st.mSlots[slot].mGraphicBuffer, mEglDisplay, EGL_NO_SYNC_KHR);
        return err;
    }

    // Ensure we have a valid EglImageKHR for the slot, creating an EglImage
    // if nessessary, for the gralloc buffer currently in the slot in
    // ConsumerBase.
    // We may have to do this even when item.mGraphicBuffer == NULL (which
    // means the buffer was previously acquired).
    err = mEglSlots[slot].mEglImage->createIfNeeded(mEglDisplay);
    if (err != NO_ERROR) {
        EGC_LOGW("updateAndRelease: unable to createImage on display=%p slot=%d", mEglDisplay,
                 slot);
        st.releaseBufferLocked(slot, st.mSlots[slot].mGraphicBuffer, mEglDisplay, EGL_NO_SYNC_KHR);
        return UNKNOWN_ERROR;
    }

    // Do whatever sync ops we need to do before releasing the old slot.
    if (slot != st.mCurrentTexture) {
        err = syncForReleaseLocked(mEglDisplay, st);
        if (err != NO_ERROR) {
            // Release the buffer we just acquired.  It's not safe to
            // release the old buffer, so instead we just drop the new frame.
            // As we are still under lock since acquireBuffer, it is safe to
            // release by slot.
            st.releaseBufferLocked(slot, st.mSlots[slot].mGraphicBuffer, mEglDisplay,
                                   EGL_NO_SYNC_KHR);
            return err;
        }
    }

    EGC_LOGV(
            "updateAndRelease: (slot=%d buf=%p) -> (slot=%d buf=%p)", st.mCurrentTexture,
            mCurrentTextureImage != nullptr ? mCurrentTextureImage->graphicBufferHandle() : nullptr,
            slot, st.mSlots[slot].mGraphicBuffer->handle);

    // Hang onto the pointer so that it isn't freed in the call to
    // releaseBufferLocked() if we're in shared buffer mode and both buffers are
    // the same.
    sp<EglImage> nextTextureImage = mEglSlots[slot].mEglImage;

    // release old buffer
    if (st.mCurrentTexture != BufferQueue::INVALID_BUFFER_SLOT) {
        if (pendingRelease == nullptr) {
            status_t status = st.releaseBufferLocked(
                    st.mCurrentTexture, mCurrentTextureImage->graphicBuffer(), mEglDisplay,
                    mEglSlots[st.mCurrentTexture].mEglFence);
            if (status < NO_ERROR) {
                EGC_LOGE("updateAndRelease: failed to release buffer: %s (%d)", strerror(-status),
                         status);
                err = status;
                // keep going, with error raised [?]
            }
        } else {
            pendingRelease->currentTexture = st.mCurrentTexture;
            pendingRelease->graphicBuffer = mCurrentTextureImage->graphicBuffer();
            pendingRelease->display = mEglDisplay;
            pendingRelease->fence = mEglSlots[st.mCurrentTexture].mEglFence;
            pendingRelease->isPending = true;
        }
    }

    // Update the EGLConsumer state.
    st.mCurrentTexture = slot;
    mCurrentTextureImage = nextTextureImage;
    st.mCurrentCrop = item.mCrop;
    st.mCurrentTransform = item.mTransform;
    st.mCurrentScalingMode = item.mScalingMode;
    st.mCurrentTimestamp = item.mTimestamp;
    st.mCurrentDataSpace = item.mDataSpace;
    st.mCurrentFence = item.mFence;
    st.mCurrentFenceTime = item.mFenceTime;
    st.mCurrentFrameNumber = item.mFrameNumber;

    st.computeCurrentTransformMatrixLocked();

    return err;
}

status_t EGLConsumer::bindTextureImageLocked(SurfaceTexture& st) {
    if (mEglDisplay == EGL_NO_DISPLAY) {
        ALOGE("bindTextureImage: invalid display");
        return INVALID_OPERATION;
    }

    GLenum error;
    while ((error = glGetError()) != GL_NO_ERROR) {
        EGC_LOGW("bindTextureImage: clearing GL error: %#04x", error);
    }

    glBindTexture(st.mTexTarget, st.mTexName);
    if (st.mCurrentTexture == BufferQueue::INVALID_BUFFER_SLOT && mCurrentTextureImage == nullptr) {
        EGC_LOGE("bindTextureImage: no currently-bound texture");
        return NO_INIT;
    }

    status_t err = mCurrentTextureImage->createIfNeeded(mEglDisplay);
    if (err != NO_ERROR) {
        EGC_LOGW("bindTextureImage: can't create image on display=%p slot=%d", mEglDisplay,
                 st.mCurrentTexture);
        return UNKNOWN_ERROR;
    }
    mCurrentTextureImage->bindToTextureTarget(st.mTexTarget);

    // In the rare case that the display is terminated and then initialized
    // again, we can't detect that the display changed (it didn't), but the
    // image is invalid. In this case, repeat the exact same steps while
    // forcing the creation of a new image.
    if ((error = glGetError()) != GL_NO_ERROR) {
        glBindTexture(st.mTexTarget, st.mTexName);
        status_t result = mCurrentTextureImage->createIfNeeded(mEglDisplay, true);
        if (result != NO_ERROR) {
            EGC_LOGW("bindTextureImage: can't create image on display=%p slot=%d", mEglDisplay,
                     st.mCurrentTexture);
            return UNKNOWN_ERROR;
        }
        mCurrentTextureImage->bindToTextureTarget(st.mTexTarget);
        if ((error = glGetError()) != GL_NO_ERROR) {
            EGC_LOGE("bindTextureImage: error binding external image: %#04x", error);
            return UNKNOWN_ERROR;
        }
    }

    // Wait for the new buffer to be ready.
    return doGLFenceWaitLocked(st);
}

status_t EGLConsumer::checkAndUpdateEglStateLocked(SurfaceTexture& st, bool contextCheck) {
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLContext ctx = eglGetCurrentContext();

    if (!contextCheck) {
        // if this is the first time we're called, mEglDisplay/mEglContext have
        // never been set, so don't error out (below).
        if (mEglDisplay == EGL_NO_DISPLAY) {
            mEglDisplay = dpy;
        }
        if (mEglContext == EGL_NO_CONTEXT) {
            mEglContext = ctx;
        }
    }

    if (mEglDisplay != dpy || dpy == EGL_NO_DISPLAY) {
        EGC_LOGE("checkAndUpdateEglState: invalid current EGLDisplay");
        return INVALID_OPERATION;
    }

    if (mEglContext != ctx || ctx == EGL_NO_CONTEXT) {
        EGC_LOGE("checkAndUpdateEglState: invalid current EGLContext");
        return INVALID_OPERATION;
    }

    mEglDisplay = dpy;
    mEglContext = ctx;
    return NO_ERROR;
}

status_t EGLConsumer::detachFromContext(SurfaceTexture& st) {
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLContext ctx = eglGetCurrentContext();

    if (mEglDisplay != dpy && mEglDisplay != EGL_NO_DISPLAY) {
        EGC_LOGE("detachFromContext: invalid current EGLDisplay");
        return INVALID_OPERATION;
    }

    if (mEglContext != ctx && mEglContext != EGL_NO_CONTEXT) {
        EGC_LOGE("detachFromContext: invalid current EGLContext");
        return INVALID_OPERATION;
    }

    if (dpy != EGL_NO_DISPLAY && ctx != EGL_NO_CONTEXT) {
        status_t err = syncForReleaseLocked(dpy, st);
        if (err != OK) {
            return err;
        }

        glDeleteTextures(1, &st.mTexName);
    }

    mEglDisplay = EGL_NO_DISPLAY;
    mEglContext = EGL_NO_CONTEXT;

    return OK;
}

status_t EGLConsumer::attachToContext(uint32_t tex, SurfaceTexture& st) {
    // Initialize mCurrentTextureImage if there is a current buffer from past attached state.
    int slot = st.mCurrentTexture;
    if (slot != BufferItem::INVALID_BUFFER_SLOT) {
        if (!mEglSlots[slot].mEglImage.get()) {
            mEglSlots[slot].mEglImage = new EglImage(st.mSlots[slot].mGraphicBuffer);
        }
        mCurrentTextureImage = mEglSlots[slot].mEglImage;
    }

    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLContext ctx = eglGetCurrentContext();

    if (dpy == EGL_NO_DISPLAY) {
        EGC_LOGE("attachToContext: invalid current EGLDisplay");
        return INVALID_OPERATION;
    }

    if (ctx == EGL_NO_CONTEXT) {
        EGC_LOGE("attachToContext: invalid current EGLContext");
        return INVALID_OPERATION;
    }

    // We need to bind the texture regardless of whether there's a current
    // buffer.
    glBindTexture(st.mTexTarget, GLuint(tex));

    mEglDisplay = dpy;
    mEglContext = ctx;
    st.mTexName = tex;
    st.mOpMode = SurfaceTexture::OpMode::attachedToGL;

    if (mCurrentTextureImage != nullptr) {
        // This may wait for a buffer a second time. This is likely required if
        // this is a different context, since otherwise the wait could be skipped
        // by bouncing through another context. For the same context the extra
        // wait is redundant.
        status_t err = bindTextureImageLocked(st);
        if (err != NO_ERROR) {
            return err;
        }
    }

    return OK;
}

status_t EGLConsumer::syncForReleaseLocked(EGLDisplay dpy, SurfaceTexture& st) {
    EGC_LOGV("syncForReleaseLocked");

    if (st.mCurrentTexture != BufferQueue::INVALID_BUFFER_SLOT) {
        if (SyncFeatures::getInstance().useNativeFenceSync()) {
            EGLSyncKHR sync = eglCreateSyncKHR(dpy, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
            if (sync == EGL_NO_SYNC_KHR) {
                EGC_LOGE("syncForReleaseLocked: error creating EGL fence: %#x", eglGetError());
                return UNKNOWN_ERROR;
            }
            glFlush();
            int fenceFd = eglDupNativeFenceFDANDROID(dpy, sync);
            eglDestroySyncKHR(dpy, sync);
            if (fenceFd == EGL_NO_NATIVE_FENCE_FD_ANDROID) {
                EGC_LOGE(
                        "syncForReleaseLocked: error dup'ing native fence "
                        "fd: %#x",
                        eglGetError());
                return UNKNOWN_ERROR;
            }
            sp<Fence> fence(new Fence(fenceFd));
            status_t err = st.addReleaseFenceLocked(st.mCurrentTexture,
                                                    mCurrentTextureImage->graphicBuffer(), fence);
            if (err != OK) {
                EGC_LOGE(
                        "syncForReleaseLocked: error adding release fence: "
                        "%s (%d)",
                        strerror(-err), err);
                return err;
            }
        } else if (st.mUseFenceSync && SyncFeatures::getInstance().useFenceSync()) {
            EGLSyncKHR fence = mEglSlots[st.mCurrentTexture].mEglFence;
            if (fence != EGL_NO_SYNC_KHR) {
                // There is already a fence for the current slot.  We need to
                // wait on that before replacing it with another fence to
                // ensure that all outstanding buffer accesses have completed
                // before the producer accesses it.
                EGLint result = eglClientWaitSyncKHR(dpy, fence, 0, 1000000000);
                if (result == EGL_FALSE) {
                    EGC_LOGE(
                            "syncForReleaseLocked: error waiting for previous "
                            "fence: %#x",
                            eglGetError());
                    return UNKNOWN_ERROR;
                } else if (result == EGL_TIMEOUT_EXPIRED_KHR) {
                    EGC_LOGE(
                            "syncForReleaseLocked: timeout waiting for previous "
                            "fence");
                    return TIMED_OUT;
                }
                eglDestroySyncKHR(dpy, fence);
            }

            // Create a fence for the outstanding accesses in the current
            // OpenGL ES context.
            fence = eglCreateSyncKHR(dpy, EGL_SYNC_FENCE_KHR, nullptr);
            if (fence == EGL_NO_SYNC_KHR) {
                EGC_LOGE("syncForReleaseLocked: error creating fence: %#x", eglGetError());
                return UNKNOWN_ERROR;
            }
            glFlush();
            mEglSlots[st.mCurrentTexture].mEglFence = fence;
        }
    }

    return OK;
}

status_t EGLConsumer::doGLFenceWaitLocked(SurfaceTexture& st) const {
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLContext ctx = eglGetCurrentContext();

    if (mEglDisplay != dpy || mEglDisplay == EGL_NO_DISPLAY) {
        EGC_LOGE("doGLFenceWait: invalid current EGLDisplay");
        return INVALID_OPERATION;
    }

    if (mEglContext != ctx || mEglContext == EGL_NO_CONTEXT) {
        EGC_LOGE("doGLFenceWait: invalid current EGLContext");
        return INVALID_OPERATION;
    }

    if (st.mCurrentFence->isValid()) {
        if (SyncFeatures::getInstance().useWaitSync() &&
            SyncFeatures::getInstance().useNativeFenceSync()) {
            // Create an EGLSyncKHR from the current fence.
            int fenceFd = st.mCurrentFence->dup();
            if (fenceFd == -1) {
                EGC_LOGE("doGLFenceWait: error dup'ing fence fd: %d", errno);
                return -errno;
            }
            EGLint attribs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fenceFd, EGL_NONE};
            EGLSyncKHR sync = eglCreateSyncKHR(dpy, EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
            if (sync == EGL_NO_SYNC_KHR) {
                close(fenceFd);
                EGC_LOGE("doGLFenceWait: error creating EGL fence: %#x", eglGetError());
                return UNKNOWN_ERROR;
            }

            // XXX: The spec draft is inconsistent as to whether this should
            // return an EGLint or void.  Ignore the return value for now, as
            // it's not strictly needed.
            eglWaitSyncKHR(dpy, sync, 0);
            EGLint eglErr = eglGetError();
            eglDestroySyncKHR(dpy, sync);
            if (eglErr != EGL_SUCCESS) {
                EGC_LOGE("doGLFenceWait: error waiting for EGL fence: %#x", eglErr);
                return UNKNOWN_ERROR;
            }
        } else {
            status_t err = st.mCurrentFence->waitForever("EGLConsumer::doGLFenceWaitLocked");
            if (err != NO_ERROR) {
                EGC_LOGE("doGLFenceWait: error waiting for fence: %d", err);
                return err;
            }
        }
    }

    return NO_ERROR;
}

void EGLConsumer::onFreeBufferLocked(int slotIndex) {
    mEglSlots[slotIndex].mEglImage.clear();
}

void EGLConsumer::onAbandonLocked() {
    mCurrentTextureImage.clear();
}

EGLConsumer::EglImage::EglImage(sp<GraphicBuffer> graphicBuffer)
        : mGraphicBuffer(graphicBuffer), mEglImage(EGL_NO_IMAGE_KHR), mEglDisplay(EGL_NO_DISPLAY) {}

EGLConsumer::EglImage::~EglImage() {
    if (mEglImage != EGL_NO_IMAGE_KHR) {
        if (!eglDestroyImageKHR(mEglDisplay, mEglImage)) {
            ALOGE("~EglImage: eglDestroyImageKHR failed");
        }
        eglTerminate(mEglDisplay);
    }
}

status_t EGLConsumer::EglImage::createIfNeeded(EGLDisplay eglDisplay, bool forceCreation) {
    // If there's an image and it's no longer valid, destroy it.
    bool haveImage = mEglImage != EGL_NO_IMAGE_KHR;
    bool displayInvalid = mEglDisplay != eglDisplay;
    if (haveImage && (displayInvalid || forceCreation)) {
        if (!eglDestroyImageKHR(mEglDisplay, mEglImage)) {
            ALOGE("createIfNeeded: eglDestroyImageKHR failed");
        }
        eglTerminate(mEglDisplay);
        mEglImage = EGL_NO_IMAGE_KHR;
        mEglDisplay = EGL_NO_DISPLAY;
    }

    // If there's no image, create one.
    if (mEglImage == EGL_NO_IMAGE_KHR) {
        mEglDisplay = eglDisplay;
        mEglImage = createImage(mEglDisplay, mGraphicBuffer);
    }

    // Fail if we can't create a valid image.
    if (mEglImage == EGL_NO_IMAGE_KHR) {
        mEglDisplay = EGL_NO_DISPLAY;
        const sp<GraphicBuffer>& buffer = mGraphicBuffer;
        ALOGE("Failed to create image. size=%ux%u st=%u usage=%#" PRIx64 " fmt=%d",
              buffer->getWidth(), buffer->getHeight(), buffer->getStride(), buffer->getUsage(),
              buffer->getPixelFormat());
        return UNKNOWN_ERROR;
    }

    return OK;
}

void EGLConsumer::EglImage::bindToTextureTarget(uint32_t texTarget) {
    glEGLImageTargetTexture2DOES(texTarget, static_cast<GLeglImageOES>(mEglImage));
}

EGLImageKHR EGLConsumer::EglImage::createImage(EGLDisplay dpy,
                                               const sp<GraphicBuffer>& graphicBuffer) {
    EGLClientBuffer cbuf = static_cast<EGLClientBuffer>(graphicBuffer->getNativeBuffer());
    const bool createProtectedImage =
            (graphicBuffer->getUsage() & GRALLOC_USAGE_PROTECTED) && hasEglProtectedContent();
    EGLint attrs[] = {
            EGL_IMAGE_PRESERVED_KHR,
            EGL_TRUE,
            createProtectedImage ? EGL_PROTECTED_CONTENT_EXT : EGL_NONE,
            createProtectedImage ? EGL_TRUE : EGL_NONE,
            EGL_NONE,
    };
    eglInitialize(dpy, nullptr, nullptr);
    EGLImageKHR image =
            eglCreateImageKHR(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cbuf, attrs);
    if (image == EGL_NO_IMAGE_KHR) {
        EGLint error = eglGetError();
        ALOGE("error creating EGLImage: %#x", error);
        eglTerminate(dpy);
    }
    return image;
}

}  // namespace android
