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

#include "ReliableSurface.h"

#include <log/log_main.h>
#include <private/android/AHardwareBufferHelpers.h>
// TODO: this should be including apex instead.
#include <system/window.h>
#include <vndk/window.h>

namespace android::uirenderer::renderthread {

// TODO: Re-enable after addressing more of the TODO's
// With this disabled we won't have a good up-front signal that the surface is no longer valid,
// however we can at least handle that reactively post-draw. There's just not a good mechanism
// to propagate this error back to the caller
constexpr bool DISABLE_BUFFER_PREFETCH = true;

ReliableSurface::ReliableSurface(ANativeWindow* window) : mWindow(window) {
    LOG_ALWAYS_FATAL_IF(!mWindow, "Error, unable to wrap a nullptr");
    ANativeWindow_acquire(mWindow);
}

ReliableSurface::~ReliableSurface() {
    clearReservedBuffer();
    // Clear out the interceptors for proper hygiene.
    // As a concrete example, if the underlying ANativeWindow is associated with
    // an EGLSurface that is still in use, then if we don't clear out the
    // interceptors then we walk into undefined behavior.
    ANativeWindow_setCancelBufferInterceptor(mWindow, nullptr, nullptr);
    ANativeWindow_setDequeueBufferInterceptor(mWindow, nullptr, nullptr);
    ANativeWindow_setQueueBufferInterceptor(mWindow, nullptr, nullptr);
    ANativeWindow_setPerformInterceptor(mWindow, nullptr, nullptr);
    ANativeWindow_setQueryInterceptor(mWindow, nullptr, nullptr);
    ANativeWindow_release(mWindow);
}

void ReliableSurface::init() {
    int result = ANativeWindow_setCancelBufferInterceptor(mWindow, hook_cancelBuffer, this);
    LOG_ALWAYS_FATAL_IF(result != NO_ERROR, "Failed to set cancelBuffer interceptor: error = %d",
                        result);

    result = ANativeWindow_setDequeueBufferInterceptor(mWindow, hook_dequeueBuffer, this);
    LOG_ALWAYS_FATAL_IF(result != NO_ERROR, "Failed to set dequeueBuffer interceptor: error = %d",
                        result);

    result = ANativeWindow_setQueueBufferInterceptor(mWindow, hook_queueBuffer, this);
    LOG_ALWAYS_FATAL_IF(result != NO_ERROR, "Failed to set queueBuffer interceptor: error = %d",
                        result);

    result = ANativeWindow_setPerformInterceptor(mWindow, hook_perform, this);
    LOG_ALWAYS_FATAL_IF(result != NO_ERROR, "Failed to set perform interceptor: error = %d",
                        result);

    result = ANativeWindow_setQueryInterceptor(mWindow, hook_query, this);
    LOG_ALWAYS_FATAL_IF(result != NO_ERROR, "Failed to set query interceptor: error = %d",
                        result);
}

int ReliableSurface::reserveNext() {
    if constexpr (DISABLE_BUFFER_PREFETCH) {
        return OK;
    }
    {
        std::lock_guard _lock{mMutex};
        if (mReservedBuffer) {
            ALOGW("reserveNext called but there was already a buffer reserved?");
            return OK;
        }
        if (mBufferQueueState != OK) {
            return UNKNOWN_ERROR;
        }
        if (mHasDequeuedBuffer) {
            return OK;
        }
    }

    // TODO: Update this to better handle when requested dimensions have changed
    // Currently the driver does this via query + perform but that's after we've already
    // reserved a buffer. Should we do that logic instead? Or should we drop
    // the backing Surface to the ground and go full manual on the IGraphicBufferProducer instead?

    int fenceFd = -1;
    ANativeWindowBuffer* buffer = nullptr;

    // Note that this calls back into our own hooked method.
    int result = ANativeWindow_dequeueBuffer(mWindow, &buffer, &fenceFd);

    {
        std::lock_guard _lock{mMutex};
        LOG_ALWAYS_FATAL_IF(mReservedBuffer, "race condition in reserveNext");
        mReservedBuffer = buffer;
        mReservedFenceFd.reset(fenceFd);
    }

    return result;
}

void ReliableSurface::clearReservedBuffer() {
    ANativeWindowBuffer* buffer = nullptr;
    int releaseFd = -1;
    {
        std::lock_guard _lock{mMutex};
        if (mReservedBuffer) {
            ALOGW("Reserved buffer %p was never used", mReservedBuffer);
            buffer = mReservedBuffer;
            releaseFd = mReservedFenceFd.release();
        }
        mReservedBuffer = nullptr;
        mReservedFenceFd.reset();
        mHasDequeuedBuffer = false;
    }
    if (buffer) {
        // Note that clearReservedBuffer may be reentrant here, so
        // mReservedBuffer must be cleared once we reach here to avoid recursing
        // forever.
        ANativeWindow_cancelBuffer(mWindow, buffer, releaseFd);
    }
}

bool ReliableSurface::isFallbackBuffer(const ANativeWindowBuffer* windowBuffer) const {
    if (!mScratchBuffer || !windowBuffer) {
        return false;
    }
    ANativeWindowBuffer* scratchBuffer =
            AHardwareBuffer_to_ANativeWindowBuffer(mScratchBuffer.get());
    return windowBuffer == scratchBuffer;
}

ANativeWindowBuffer* ReliableSurface::acquireFallbackBuffer(int error) {
    std::lock_guard _lock{mMutex};
    mBufferQueueState = error;

    if (mScratchBuffer) {
        return AHardwareBuffer_to_ANativeWindowBuffer(mScratchBuffer.get());
    }

    AHardwareBuffer_Desc desc;
    desc.usage = mUsage;
    desc.format = mFormat;
    desc.width = 1;
    desc.height = 1;
    desc.layers = 1;
    desc.rfu0 = 0;
    desc.rfu1 = 0;
    AHardwareBuffer* newBuffer = nullptr;
    int err = AHardwareBuffer_allocate(&desc, &newBuffer);
    if (err) {
        // Allocate failed, that sucks
        ALOGW("Failed to allocate scratch buffer, error=%d", err);
        return nullptr;
    }
    mScratchBuffer.reset(newBuffer);
    return AHardwareBuffer_to_ANativeWindowBuffer(newBuffer);
}

int ReliableSurface::hook_dequeueBuffer(ANativeWindow* window,
                                        ANativeWindow_dequeueBufferFn dequeueBuffer, void* data,
                                        ANativeWindowBuffer** buffer, int* fenceFd) {
    ReliableSurface* rs = reinterpret_cast<ReliableSurface*>(data);
    {
        std::lock_guard _lock{rs->mMutex};
        if (rs->mReservedBuffer) {
            *buffer = rs->mReservedBuffer;
            *fenceFd = rs->mReservedFenceFd.release();
            rs->mReservedBuffer = nullptr;
            return OK;
        }
    }

    int result = dequeueBuffer(window, buffer, fenceFd);
    if (result != OK) {
        ALOGW("dequeueBuffer failed, error = %d; switching to fallback", result);
        *buffer = rs->acquireFallbackBuffer(result);
        *fenceFd = -1;
        return *buffer ? OK : INVALID_OPERATION;
    } else {
        std::lock_guard _lock{rs->mMutex};
        rs->mHasDequeuedBuffer = true;
    }
    return OK;
}

int ReliableSurface::hook_cancelBuffer(ANativeWindow* window,
                                       ANativeWindow_cancelBufferFn cancelBuffer, void* data,
                                       ANativeWindowBuffer* buffer, int fenceFd) {
    ReliableSurface* rs = reinterpret_cast<ReliableSurface*>(data);
    rs->clearReservedBuffer();
    if (rs->isFallbackBuffer(buffer)) {
        if (fenceFd > 0) {
            close(fenceFd);
        }
        return OK;
    }
    return cancelBuffer(window, buffer, fenceFd);
}

int ReliableSurface::hook_queueBuffer(ANativeWindow* window,
                                      ANativeWindow_queueBufferFn queueBuffer, void* data,
                                      ANativeWindowBuffer* buffer, int fenceFd) {
    ReliableSurface* rs = reinterpret_cast<ReliableSurface*>(data);
    rs->clearReservedBuffer();

    if (rs->isFallbackBuffer(buffer)) {
        if (fenceFd > 0) {
            close(fenceFd);
        }
        return OK;
    }

    return queueBuffer(window, buffer, fenceFd);
}

int ReliableSurface::hook_perform(ANativeWindow* window, ANativeWindow_performFn perform,
                                  void* data, int operation, va_list args) {
    // Drop the reserved buffer if there is one since this (probably) mutated buffer dimensions
    // TODO: Filter to things that only affect the reserved buffer
    // TODO: Can we mutate the reserved buffer in some cases?
    ReliableSurface* rs = reinterpret_cast<ReliableSurface*>(data);
    rs->clearReservedBuffer();

    va_list argsCopy;
    va_copy(argsCopy, args);
    int result = perform(window, operation, argsCopy);

    {
        std::lock_guard _lock{rs->mMutex};

        switch (operation) {
            case ANATIVEWINDOW_PERFORM_SET_USAGE:
                rs->mUsage = va_arg(args, uint32_t);
                break;
            case ANATIVEWINDOW_PERFORM_SET_USAGE64:
                rs->mUsage = va_arg(args, uint64_t);
                break;
            case ANATIVEWINDOW_PERFORM_SET_BUFFERS_GEOMETRY:
                /* width */ va_arg(args, uint32_t);
                /* height */ va_arg(args, uint32_t);
                rs->mFormat = static_cast<AHardwareBuffer_Format>(va_arg(args, int32_t));
                break;
            case ANATIVEWINDOW_PERFORM_SET_BUFFERS_FORMAT:
                rs->mFormat = static_cast<AHardwareBuffer_Format>(va_arg(args, int32_t));
                break;
            case NATIVE_WINDOW_SET_BUFFER_COUNT:
                size_t bufferCount = va_arg(args, size_t);
                if (bufferCount >= rs->mExpectedBufferCount) {
                    rs->mDidSetExtraBuffers = true;
                } else {
                    ALOGD("HOOK FAILED! Expected %zd got = %zd", rs->mExpectedBufferCount, bufferCount);
                }
                break;
        }
    }
    return result;
}

int ReliableSurface::hook_query(const ANativeWindow *window, ANativeWindow_queryFn query,
        void *data, int what, int *value) {
    ReliableSurface* rs = reinterpret_cast<ReliableSurface*>(data);
    int result = query(window, what, value);
    if (what == ANATIVEWINDOW_QUERY_MIN_UNDEQUEUED_BUFFERS && result == OK) {
        std::lock_guard _lock{rs->mMutex};
        *value += rs->mExtraBuffers;
        rs->mExpectedBufferCount = *value + 2;
    }
    return result;
}

};  // namespace android::uirenderer::renderthread
