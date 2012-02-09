/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "BufferQueue"

#define GL_GLEXT_PROTOTYPES
#define EGL_EGLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <gui/BufferQueue.h>
#include <private/gui/ComposerService.h>
#include <surfaceflinger/ISurfaceComposer.h>

#include <utils/Log.h>

// This compile option causes SurfaceTexture to return the buffer that is currently
// attached to the GL texture from dequeueBuffer when no other buffers are
// available.  It requires the drivers (Gralloc, GL, OMX IL, and Camera) to do
// implicit cross-process synchronization to prevent the buffer from being
// written to before the buffer has (a) been detached from the GL texture and
// (b) all GL reads from the buffer have completed.
#ifdef ALLOW_DEQUEUE_CURRENT_BUFFER
#define FLAG_ALLOW_DEQUEUE_CURRENT_BUFFER    true
#warning "ALLOW_DEQUEUE_CURRENT_BUFFER enabled"
#else
#define FLAG_ALLOW_DEQUEUE_CURRENT_BUFFER    false
#endif

// Macros for including the BufferQueue name in log messages
#define ST_LOGV(x, ...) ALOGV("[%s] "x, mName.string(), ##__VA_ARGS__)
#define ST_LOGD(x, ...) ALOGD("[%s] "x, mName.string(), ##__VA_ARGS__)
#define ST_LOGI(x, ...) ALOGI("[%s] "x, mName.string(), ##__VA_ARGS__)
#define ST_LOGW(x, ...) ALOGW("[%s] "x, mName.string(), ##__VA_ARGS__)
#define ST_LOGE(x, ...) ALOGE("[%s] "x, mName.string(), ##__VA_ARGS__)

namespace android {

// Get an ID that's unique within this process.
static int32_t createProcessUniqueId() {
    static volatile int32_t globalCounter = 0;
    return android_atomic_inc(&globalCounter);
}

BufferQueue::BufferQueue( bool allowSynchronousMode ) :
    mDefaultWidth(1),
    mDefaultHeight(1),
    mPixelFormat(PIXEL_FORMAT_RGBA_8888),
    mBufferCount(MIN_ASYNC_BUFFER_SLOTS),
    mClientBufferCount(0),
    mServerBufferCount(MIN_ASYNC_BUFFER_SLOTS),
    mCurrentTexture(INVALID_BUFFER_SLOT),
    mNextTransform(0),
    mNextScalingMode(NATIVE_WINDOW_SCALING_MODE_FREEZE),
    mSynchronousMode(false),
    mAllowSynchronousMode(allowSynchronousMode),
    mConnectedApi(NO_CONNECTED_API),
    mAbandoned(false),
    mFrameCounter(0)
{
    // Choose a name using the PID and a process-unique ID.
    mName = String8::format("unnamed-%d-%d", getpid(), createProcessUniqueId());

    ST_LOGV("BufferQueue");
    sp<ISurfaceComposer> composer(ComposerService::getComposerService());
    mGraphicBufferAlloc = composer->createGraphicBufferAlloc();
    mNextCrop.makeInvalid();
}

BufferQueue::~BufferQueue() {
    ST_LOGV("~BufferQueue");
}

status_t BufferQueue::setBufferCountServerLocked(int bufferCount) {
    if (bufferCount > NUM_BUFFER_SLOTS)
        return BAD_VALUE;

    // special-case, nothing to do
    if (bufferCount == mBufferCount)
        return OK;

    if (!mClientBufferCount &&
        bufferCount >= mBufferCount) {
        // easy, we just have more buffers
        mBufferCount = bufferCount;
        mServerBufferCount = bufferCount;
        mDequeueCondition.signal();
    } else {
        // we're here because we're either
        // - reducing the number of available buffers
        // - or there is a client-buffer-count in effect

        // less than 2 buffers is never allowed
        if (bufferCount < 2)
            return BAD_VALUE;

        // when there is non client-buffer-count in effect, the client is not
        // allowed to dequeue more than one buffer at a time,
        // so the next time they dequeue a buffer, we know that they don't
        // own one. the actual resizing will happen during the next
        // dequeueBuffer.

        mServerBufferCount = bufferCount;
    }
    return OK;
}

status_t BufferQueue::setBufferCount(int bufferCount) {
    ST_LOGV("setBufferCount: count=%d", bufferCount);
    Mutex::Autolock lock(mMutex);

    if (mAbandoned) {
        ST_LOGE("setBufferCount: SurfaceTexture has been abandoned!");
        return NO_INIT;
    }
    if (bufferCount > NUM_BUFFER_SLOTS) {
        ST_LOGE("setBufferCount: bufferCount larger than slots available");
        return BAD_VALUE;
    }

    // Error out if the user has dequeued buffers
    for (int i=0 ; i<mBufferCount ; i++) {
        if (mSlots[i].mBufferState == BufferSlot::DEQUEUED) {
            ST_LOGE("setBufferCount: client owns some buffers");
            return -EINVAL;
        }
    }

    const int minBufferSlots = mSynchronousMode ?
            MIN_SYNC_BUFFER_SLOTS : MIN_ASYNC_BUFFER_SLOTS;
    if (bufferCount == 0) {
        mClientBufferCount = 0;
        bufferCount = (mServerBufferCount >= minBufferSlots) ?
                mServerBufferCount : minBufferSlots;
        return setBufferCountServerLocked(bufferCount);
    }

    if (bufferCount < minBufferSlots) {
        ST_LOGE("setBufferCount: requested buffer count (%d) is less than "
                "minimum (%d)", bufferCount, minBufferSlots);
        return BAD_VALUE;
    }

    // here we're guaranteed that the client doesn't have dequeued buffers
    // and will release all of its buffer references.
    freeAllBuffersLocked();
    mBufferCount = bufferCount;
    mClientBufferCount = bufferCount;
    mCurrentTexture = INVALID_BUFFER_SLOT;
    mQueue.clear();
    mDequeueCondition.signal();
    return OK;
}

status_t BufferQueue::requestBuffer(int slot, sp<GraphicBuffer>* buf) {
    ST_LOGV("requestBuffer: slot=%d", slot);
    Mutex::Autolock lock(mMutex);
    if (mAbandoned) {
        ST_LOGE("requestBuffer: SurfaceTexture has been abandoned!");
        return NO_INIT;
    }
    if (slot < 0 || mBufferCount <= slot) {
        ST_LOGE("requestBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, slot);
        return BAD_VALUE;
    }
    mSlots[slot].mRequestBufferCalled = true;
    *buf = mSlots[slot].mGraphicBuffer;
    return NO_ERROR;
}

status_t BufferQueue::dequeueBuffer(int *outBuf, uint32_t w, uint32_t h,
        uint32_t format, uint32_t usage) {
    ST_LOGV("dequeueBuffer: w=%d h=%d fmt=%#x usage=%#x", w, h, format, usage);

    if ((w && !h) || (!w && h)) {
        ST_LOGE("dequeueBuffer: invalid size: w=%u, h=%u", w, h);
        return BAD_VALUE;
    }

    status_t returnFlags(OK);
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLSyncKHR fence = EGL_NO_SYNC_KHR;

    { // Scope for the lock
        Mutex::Autolock lock(mMutex);

        int found = -1;
        int foundSync = -1;
        int dequeuedCount = 0;
        bool tryAgain = true;
        while (tryAgain) {
            if (mAbandoned) {
                ST_LOGE("dequeueBuffer: SurfaceTexture has been abandoned!");
                return NO_INIT;
            }

            // We need to wait for the FIFO to drain if the number of buffer
            // needs to change.
            //
            // The condition "number of buffers needs to change" is true if
            // - the client doesn't care about how many buffers there are
            // - AND the actual number of buffer is different from what was
            //   set in the last setBufferCountServer()
            //                         - OR -
            //   setBufferCountServer() was set to a value incompatible with
            //   the synchronization mode (for instance because the sync mode
            //   changed since)
            //
            // As long as this condition is true AND the FIFO is not empty, we
            // wait on mDequeueCondition.

            const int minBufferCountNeeded = mSynchronousMode ?
                    MIN_SYNC_BUFFER_SLOTS : MIN_ASYNC_BUFFER_SLOTS;

            const bool numberOfBuffersNeedsToChange = !mClientBufferCount &&
                    ((mServerBufferCount != mBufferCount) ||
                            (mServerBufferCount < minBufferCountNeeded));

            if (!mQueue.isEmpty() && numberOfBuffersNeedsToChange) {
                // wait for the FIFO to drain
                mDequeueCondition.wait(mMutex);
                // NOTE: we continue here because we need to reevaluate our
                // whole state (eg: we could be abandoned or disconnected)
                continue;
            }

            if (numberOfBuffersNeedsToChange) {
                // here we're guaranteed that mQueue is empty
                freeAllBuffersLocked();
                mBufferCount = mServerBufferCount;
                if (mBufferCount < minBufferCountNeeded)
                    mBufferCount = minBufferCountNeeded;
                mCurrentTexture = INVALID_BUFFER_SLOT;
                returnFlags |= ISurfaceTexture::RELEASE_ALL_BUFFERS;
            }

            // look for a free buffer to give to the client
            found = INVALID_BUFFER_SLOT;
            foundSync = INVALID_BUFFER_SLOT;
            dequeuedCount = 0;
            for (int i = 0; i < mBufferCount; i++) {
                const int state = mSlots[i].mBufferState;
                if (state == BufferSlot::DEQUEUED) {
                    dequeuedCount++;
                }

                // if buffer is FREE it CANNOT be current
                ALOGW_IF((state == BufferSlot::FREE) && (mCurrentTexture==i),
                        "dequeueBuffer: buffer %d is both FREE and current!",
                        i);

                if (FLAG_ALLOW_DEQUEUE_CURRENT_BUFFER) {
                    if (state == BufferSlot::FREE || i == mCurrentTexture) {
                        foundSync = i;
                        if (i != mCurrentTexture) {
                            found = i;
                            break;
                        }
                    }
                } else {
                    if (state == BufferSlot::FREE) {
                        /* We return the oldest of the free buffers to avoid
                         * stalling the producer if possible.  This is because
                         * the consumer may still have pending reads of the
                         * buffers in flight.
                         */
                        bool isOlder = mSlots[i].mFrameNumber <
                                mSlots[found].mFrameNumber;
                        if (found < 0 || isOlder) {
                            foundSync = i;
                            found = i;
                        }
                    }
                }
            }

            // clients are not allowed to dequeue more than one buffer
            // if they didn't set a buffer count.
            if (!mClientBufferCount && dequeuedCount) {
                ST_LOGE("dequeueBuffer: can't dequeue multiple buffers without "
                        "setting the buffer count");
                return -EINVAL;
            }

            // See whether a buffer has been queued since the last
            // setBufferCount so we know whether to perform the
            // MIN_UNDEQUEUED_BUFFERS check below.
            bool bufferHasBeenQueued = mCurrentTexture != INVALID_BUFFER_SLOT;
            if (bufferHasBeenQueued) {
                // make sure the client is not trying to dequeue more buffers
                // than allowed.
                const int avail = mBufferCount - (dequeuedCount+1);
                if (avail < (MIN_UNDEQUEUED_BUFFERS-int(mSynchronousMode))) {
                    ST_LOGE("dequeueBuffer: MIN_UNDEQUEUED_BUFFERS=%d exceeded "
                            "(dequeued=%d)",
                            MIN_UNDEQUEUED_BUFFERS-int(mSynchronousMode),
                            dequeuedCount);
                    return -EBUSY;
                }
            }

            // we're in synchronous mode and didn't find a buffer, we need to
            // wait for some buffers to be consumed
            tryAgain = mSynchronousMode && (foundSync == INVALID_BUFFER_SLOT);
            if (tryAgain) {
                mDequeueCondition.wait(mMutex);
            }
        }

        if (mSynchronousMode && found == INVALID_BUFFER_SLOT) {
            // foundSync guaranteed to be != INVALID_BUFFER_SLOT
            found = foundSync;
        }

        if (found == INVALID_BUFFER_SLOT) {
            // This should not happen.
            ST_LOGE("dequeueBuffer: no available buffer slots");
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
            status_t error;
            sp<GraphicBuffer> graphicBuffer(
                    mGraphicBufferAlloc->createGraphicBuffer(
                            w, h, format, usage, &error));
            if (graphicBuffer == 0) {
                ST_LOGE("dequeueBuffer: SurfaceComposer::createGraphicBuffer "
                        "failed");
                return error;
            }
            if (updateFormat) {
                mPixelFormat = format;
            }
            mSlots[buf].mGraphicBuffer = graphicBuffer;
            mSlots[buf].mRequestBufferCalled = false;
            mSlots[buf].mFence = EGL_NO_SYNC_KHR;
            if (mSlots[buf].mEglImage != EGL_NO_IMAGE_KHR) {
                eglDestroyImageKHR(mSlots[buf].mEglDisplay,
                        mSlots[buf].mEglImage);
                mSlots[buf].mEglImage = EGL_NO_IMAGE_KHR;
                mSlots[buf].mEglDisplay = EGL_NO_DISPLAY;
            }
            if (mCurrentTexture == buf) {
                // The current texture no longer references the buffer in this slot
                // since we just allocated a new buffer.
                mCurrentTexture = INVALID_BUFFER_SLOT;
            }
            returnFlags |= ISurfaceTexture::BUFFER_NEEDS_REALLOCATION;
        }

        dpy = mSlots[buf].mEglDisplay;
        fence = mSlots[buf].mFence;
        mSlots[buf].mFence = EGL_NO_SYNC_KHR;
    }

    if (fence != EGL_NO_SYNC_KHR) {
        EGLint result = eglClientWaitSyncKHR(dpy, fence, 0, 1000000000);
        // If something goes wrong, log the error, but return the buffer without
        // synchronizing access to it.  It's too late at this point to abort the
        // dequeue operation.
        if (result == EGL_FALSE) {
            ALOGE("dequeueBuffer: error waiting for fence: %#x", eglGetError());
        } else if (result == EGL_TIMEOUT_EXPIRED_KHR) {
            ALOGE("dequeueBuffer: timeout waiting for fence");
        }
        eglDestroySyncKHR(dpy, fence);
    }

    ST_LOGV("dequeueBuffer: returning slot=%d buf=%p flags=%#x", *outBuf,
            mSlots[*outBuf].mGraphicBuffer->handle, returnFlags);

    return returnFlags;
}

status_t BufferQueue::setSynchronousMode(bool enabled) {
    ST_LOGV("setSynchronousMode: enabled=%d", enabled);
    Mutex::Autolock lock(mMutex);

    if (mAbandoned) {
        ST_LOGE("setSynchronousMode: SurfaceTexture has been abandoned!");
        return NO_INIT;
    }

    status_t err = OK;
    if (!mAllowSynchronousMode && enabled)
        return err;

    if (!enabled) {
        // going to asynchronous mode, drain the queue
        err = drainQueueLocked();
        if (err != NO_ERROR)
            return err;
    }

    if (mSynchronousMode != enabled) {
        // - if we're going to asynchronous mode, the queue is guaranteed to be
        // empty here
        // - if the client set the number of buffers, we're guaranteed that
        // we have at least 3 (because we don't allow less)
        mSynchronousMode = enabled;
        mDequeueCondition.signal();
    }
    return err;
}

status_t BufferQueue::queueBuffer(int buf, int64_t timestamp,
        uint32_t* outWidth, uint32_t* outHeight, uint32_t* outTransform) {
    ST_LOGV("queueBuffer: slot=%d time=%lld", buf, timestamp);

    sp<FrameAvailableListener> listener;

    { // scope for the lock
        Mutex::Autolock lock(mMutex);
        if (mAbandoned) {
            ST_LOGE("queueBuffer: SurfaceTexture has been abandoned!");
            return NO_INIT;
        }
        if (buf < 0 || buf >= mBufferCount) {
            ST_LOGE("queueBuffer: slot index out of range [0, %d]: %d",
                    mBufferCount, buf);
            return -EINVAL;
        } else if (mSlots[buf].mBufferState != BufferSlot::DEQUEUED) {
            ST_LOGE("queueBuffer: slot %d is not owned by the client "
                    "(state=%d)", buf, mSlots[buf].mBufferState);
            return -EINVAL;
        } else if (buf == mCurrentTexture) {
            ST_LOGE("queueBuffer: slot %d is current!", buf);
            return -EINVAL;
        } else if (!mSlots[buf].mRequestBufferCalled) {
            ST_LOGE("queueBuffer: slot %d was enqueued without requesting a "
                    "buffer", buf);
            return -EINVAL;
        }

        if (mSynchronousMode) {
            // In synchronous mode we queue all buffers in a FIFO.
            mQueue.push_back(buf);

            // Synchronous mode always signals that an additional frame should
            // be consumed.
            listener = mFrameAvailableListener;
        } else {
            // In asynchronous mode we only keep the most recent buffer.
            if (mQueue.empty()) {
                mQueue.push_back(buf);

                // Asynchronous mode only signals that a frame should be
                // consumed if no previous frame was pending. If a frame were
                // pending then the consumer would have already been notified.
                listener = mFrameAvailableListener;
            } else {
                Fifo::iterator front(mQueue.begin());
                // buffer currently queued is freed
                mSlots[*front].mBufferState = BufferSlot::FREE;
                // and we record the new buffer index in the queued list
                *front = buf;
            }
        }

        mSlots[buf].mBufferState = BufferSlot::QUEUED;
        mSlots[buf].mCrop = mNextCrop;
        mSlots[buf].mTransform = mNextTransform;
        mSlots[buf].mScalingMode = mNextScalingMode;
        mSlots[buf].mTimestamp = timestamp;
        mFrameCounter++;
        mSlots[buf].mFrameNumber = mFrameCounter;

        mDequeueCondition.signal();

        *outWidth = mDefaultWidth;
        *outHeight = mDefaultHeight;
        *outTransform = 0;
    } // scope for the lock

    // call back without lock held
    if (listener != 0) {
        listener->onFrameAvailable();
    }
    return OK;
}

void BufferQueue::cancelBuffer(int buf) {
    ST_LOGV("cancelBuffer: slot=%d", buf);
    Mutex::Autolock lock(mMutex);

    if (mAbandoned) {
        ST_LOGW("cancelBuffer: BufferQueue has been abandoned!");
        return;
    }

    if (buf < 0 || buf >= mBufferCount) {
        ST_LOGE("cancelBuffer: slot index out of range [0, %d]: %d",
                mBufferCount, buf);
        return;
    } else if (mSlots[buf].mBufferState != BufferSlot::DEQUEUED) {
        ST_LOGE("cancelBuffer: slot %d is not owned by the client (state=%d)",
                buf, mSlots[buf].mBufferState);
        return;
    }
    mSlots[buf].mBufferState = BufferSlot::FREE;
    mSlots[buf].mFrameNumber = 0;
    mDequeueCondition.signal();
}

status_t BufferQueue::setCrop(const Rect& crop) {
    ST_LOGV("setCrop: crop=[%d,%d,%d,%d]", crop.left, crop.top, crop.right,
            crop.bottom);

    Mutex::Autolock lock(mMutex);
    if (mAbandoned) {
        ST_LOGE("setCrop: BufferQueue has been abandoned!");
        return NO_INIT;
    }
    mNextCrop = crop;
    return OK;
}

status_t BufferQueue::setTransform(uint32_t transform) {
    ST_LOGV("setTransform: xform=%#x", transform);
    Mutex::Autolock lock(mMutex);
    if (mAbandoned) {
        ST_LOGE("setTransform: BufferQueue has been abandoned!");
        return NO_INIT;
    }
    mNextTransform = transform;
    return OK;
}

status_t BufferQueue::setScalingMode(int mode) {
    ST_LOGV("setScalingMode: mode=%d", mode);

    switch (mode) {
        case NATIVE_WINDOW_SCALING_MODE_FREEZE:
        case NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW:
            break;
        default:
            ST_LOGE("unknown scaling mode: %d", mode);
            return BAD_VALUE;
    }

    Mutex::Autolock lock(mMutex);
    mNextScalingMode = mode;
    return OK;
}

status_t BufferQueue::connect(int api,
        uint32_t* outWidth, uint32_t* outHeight, uint32_t* outTransform) {
    ST_LOGV("connect: api=%d", api);
    Mutex::Autolock lock(mMutex);

    if (mAbandoned) {
        ST_LOGE("connect: BufferQueue has been abandoned!");
        return NO_INIT;
    }

    int err = NO_ERROR;
    switch (api) {
        case NATIVE_WINDOW_API_EGL:
        case NATIVE_WINDOW_API_CPU:
        case NATIVE_WINDOW_API_MEDIA:
        case NATIVE_WINDOW_API_CAMERA:
            if (mConnectedApi != NO_CONNECTED_API) {
                ST_LOGE("connect: already connected (cur=%d, req=%d)",
                        mConnectedApi, api);
                err = -EINVAL;
            } else {
                mConnectedApi = api;
                *outWidth = mDefaultWidth;
                *outHeight = mDefaultHeight;
                *outTransform = 0;
            }
            break;
        default:
            err = -EINVAL;
            break;
    }
    return err;
}

status_t BufferQueue::disconnect(int api) {
    ST_LOGV("disconnect: api=%d", api);
    Mutex::Autolock lock(mMutex);

    if (mAbandoned) {
        // it is not really an error to disconnect after the surface
        // has been abandoned, it should just be a no-op.
        return NO_ERROR;
    }

    int err = NO_ERROR;
    switch (api) {
        case NATIVE_WINDOW_API_EGL:
        case NATIVE_WINDOW_API_CPU:
        case NATIVE_WINDOW_API_MEDIA:
        case NATIVE_WINDOW_API_CAMERA:
            if (mConnectedApi == api) {
                drainQueueAndFreeBuffersLocked();
                mConnectedApi = NO_CONNECTED_API;
                mNextCrop.makeInvalid();
                mNextScalingMode = NATIVE_WINDOW_SCALING_MODE_FREEZE;
                mNextTransform = 0;
                mDequeueCondition.signal();
            } else {
                ST_LOGE("disconnect: connected to another api (cur=%d, req=%d)",
                        mConnectedApi, api);
                err = -EINVAL;
            }
            break;
        default:
            ST_LOGE("disconnect: unknown API %d", api);
            err = -EINVAL;
            break;
    }
    return err;
}

void BufferQueue::freeBufferLocked(int i) {
    mSlots[i].mGraphicBuffer = 0;
    mSlots[i].mBufferState = BufferSlot::FREE;
    mSlots[i].mFrameNumber = 0;
    if (mSlots[i].mEglImage != EGL_NO_IMAGE_KHR) {
        eglDestroyImageKHR(mSlots[i].mEglDisplay, mSlots[i].mEglImage);
        mSlots[i].mEglImage = EGL_NO_IMAGE_KHR;
        mSlots[i].mEglDisplay = EGL_NO_DISPLAY;
    }
}

void BufferQueue::freeAllBuffersLocked() {
    ALOGW_IF(!mQueue.isEmpty(),
            "freeAllBuffersLocked called but mQueue is not empty");
    mCurrentTexture = INVALID_BUFFER_SLOT;
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        freeBufferLocked(i);
    }
}

void BufferQueue::freeAllBuffersExceptHeadLocked() {
    ALOGW_IF(!mQueue.isEmpty(),
            "freeAllBuffersExceptCurrentLocked called but mQueue is not empty");
    int head = -1;
    if (!mQueue.empty()) {
        Fifo::iterator front(mQueue.begin());
        head = *front;
    }
    mCurrentTexture = INVALID_BUFFER_SLOT;
    for (int i = 0; i < NUM_BUFFER_SLOTS; i++) {
        if (i != head) {
            freeBufferLocked(i);
        }
    }
}

status_t BufferQueue::drainQueueLocked() {
    while (mSynchronousMode && !mQueue.isEmpty()) {
        mDequeueCondition.wait(mMutex);
        if (mAbandoned) {
            ST_LOGE("drainQueueLocked: BufferQueue has been abandoned!");
            return NO_INIT;
        }
        if (mConnectedApi == NO_CONNECTED_API) {
            ST_LOGE("drainQueueLocked: BufferQueue is not connected!");
            return NO_INIT;
        }
    }
    return NO_ERROR;
}

status_t BufferQueue::drainQueueAndFreeBuffersLocked() {
    status_t err = drainQueueLocked();
    if (err == NO_ERROR) {
        if (mSynchronousMode) {
            freeAllBuffersLocked();
        } else {
            freeAllBuffersExceptHeadLocked();
        }
    }
    return err;
}

}; // namespace android
