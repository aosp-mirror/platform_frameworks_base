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

#include <private/android/AHardwareBufferHelpers.h>

namespace android::uirenderer::renderthread {

// TODO: Re-enable after addressing more of the TODO's
// With this disabled we won't have a good up-front signal that the surface is no longer valid,
// however we can at least handle that reactively post-draw. There's just not a good mechanism
// to propagate this error back to the caller
constexpr bool DISABLE_BUFFER_PREFETCH = true;

// TODO: Make surface less protected
// This exists because perform is a varargs, and ANativeWindow has no va_list perform.
// So wrapping/chaining that is hard. Telling the compiler to ignore protected is easy, so we do
// that instead
struct SurfaceExposer : Surface {
    // Make warnings happy
    SurfaceExposer() = delete;

    using Surface::cancelBuffer;
    using Surface::dequeueBuffer;
    using Surface::lockBuffer_DEPRECATED;
    using Surface::perform;
    using Surface::queueBuffer;
    using Surface::setBufferCount;
    using Surface::setSwapInterval;
};

#define callProtected(surface, func, ...) ((*surface).*&SurfaceExposer::func)(__VA_ARGS__)

ReliableSurface::ReliableSurface(sp<Surface>&& surface) : mSurface(std::move(surface)) {
    LOG_ALWAYS_FATAL_IF(!mSurface, "Error, unable to wrap a nullptr");

    ANativeWindow::setSwapInterval = hook_setSwapInterval;
    ANativeWindow::dequeueBuffer = hook_dequeueBuffer;
    ANativeWindow::cancelBuffer = hook_cancelBuffer;
    ANativeWindow::queueBuffer = hook_queueBuffer;
    ANativeWindow::query = hook_query;
    ANativeWindow::perform = hook_perform;

    ANativeWindow::dequeueBuffer_DEPRECATED = hook_dequeueBuffer_DEPRECATED;
    ANativeWindow::cancelBuffer_DEPRECATED = hook_cancelBuffer_DEPRECATED;
    ANativeWindow::lockBuffer_DEPRECATED = hook_lockBuffer_DEPRECATED;
    ANativeWindow::queueBuffer_DEPRECATED = hook_queueBuffer_DEPRECATED;
}

ReliableSurface::~ReliableSurface() {
    clearReservedBuffer();
}

void ReliableSurface::perform(int operation, va_list args) {
    std::lock_guard _lock{mMutex};

    switch (operation) {
        case NATIVE_WINDOW_SET_USAGE:
            mUsage = va_arg(args, uint32_t);
            break;
        case NATIVE_WINDOW_SET_USAGE64:
            mUsage = va_arg(args, uint64_t);
            break;
        case NATIVE_WINDOW_SET_BUFFERS_GEOMETRY:
            /* width */ va_arg(args, uint32_t);
            /* height */ va_arg(args, uint32_t);
            mFormat = va_arg(args, PixelFormat);
            break;
        case NATIVE_WINDOW_SET_BUFFERS_FORMAT:
            mFormat = va_arg(args, PixelFormat);
            break;
    }
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
    int result = callProtected(mSurface, dequeueBuffer, &buffer, &fenceFd);

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
        callProtected(mSurface, cancelBuffer, buffer, releaseFd);
    }
}

int ReliableSurface::cancelBuffer(ANativeWindowBuffer* buffer, int fenceFd) {
    clearReservedBuffer();
    if (isFallbackBuffer(buffer)) {
        if (fenceFd > 0) {
            close(fenceFd);
        }
        return OK;
    }
    int result = callProtected(mSurface, cancelBuffer, buffer, fenceFd);
    return result;
}

int ReliableSurface::dequeueBuffer(ANativeWindowBuffer** buffer, int* fenceFd) {
    {
        std::lock_guard _lock{mMutex};
        if (mReservedBuffer) {
            *buffer = mReservedBuffer;
            *fenceFd = mReservedFenceFd.release();
            mReservedBuffer = nullptr;
            return OK;
        }
    }


    int result = callProtected(mSurface, dequeueBuffer, buffer, fenceFd);
    if (result != OK) {
        ALOGW("dequeueBuffer failed, error = %d; switching to fallback", result);
        *buffer = acquireFallbackBuffer(result);
        *fenceFd = -1;
        return *buffer ? OK : INVALID_OPERATION;
    } else {
        std::lock_guard _lock{mMutex};
        mHasDequeuedBuffer = true;
    }
    return OK;
}

int ReliableSurface::queueBuffer(ANativeWindowBuffer* buffer, int fenceFd) {
    clearReservedBuffer();

    if (isFallbackBuffer(buffer)) {
        if (fenceFd > 0) {
            close(fenceFd);
        }
        return OK;
    }

    int result = callProtected(mSurface, queueBuffer, buffer, fenceFd);
    return result;
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

Surface* ReliableSurface::getWrapped(const ANativeWindow* window) {
    return getSelf(window)->mSurface.get();
}

int ReliableSurface::hook_setSwapInterval(ANativeWindow* window, int interval) {
    return callProtected(getWrapped(window), setSwapInterval, interval);
}

int ReliableSurface::hook_dequeueBuffer(ANativeWindow* window, ANativeWindowBuffer** buffer,
                                        int* fenceFd) {
    return getSelf(window)->dequeueBuffer(buffer, fenceFd);
}

int ReliableSurface::hook_cancelBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer,
                                       int fenceFd) {
    return getSelf(window)->cancelBuffer(buffer, fenceFd);
}

int ReliableSurface::hook_queueBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer,
                                      int fenceFd) {
    return getSelf(window)->queueBuffer(buffer, fenceFd);
}

int ReliableSurface::hook_dequeueBuffer_DEPRECATED(ANativeWindow* window,
                                                   ANativeWindowBuffer** buffer) {
    ANativeWindowBuffer* buf;
    int fenceFd = -1;
    int result = window->dequeueBuffer(window, &buf, &fenceFd);
    if (result != OK) {
        return result;
    }
    sp<Fence> fence(new Fence(fenceFd));
    int waitResult = fence->waitForever("dequeueBuffer_DEPRECATED");
    if (waitResult != OK) {
        ALOGE("dequeueBuffer_DEPRECATED: Fence::wait returned an error: %d", waitResult);
        window->cancelBuffer(window, buf, -1);
        return waitResult;
    }
    *buffer = buf;
    return result;
}

int ReliableSurface::hook_cancelBuffer_DEPRECATED(ANativeWindow* window,
                                                  ANativeWindowBuffer* buffer) {
    return window->cancelBuffer(window, buffer, -1);
}

int ReliableSurface::hook_lockBuffer_DEPRECATED(ANativeWindow* window,
                                                ANativeWindowBuffer* buffer) {
    // This method is a no-op in Surface as well
    return OK;
}

int ReliableSurface::hook_queueBuffer_DEPRECATED(ANativeWindow* window,
                                                 ANativeWindowBuffer* buffer) {
    return window->queueBuffer(window, buffer, -1);
}

int ReliableSurface::hook_query(const ANativeWindow* window, int what, int* value) {
    return getWrapped(window)->query(what, value);
}

int ReliableSurface::hook_perform(ANativeWindow* window, int operation, ...) {
    // Drop the reserved buffer if there is one since this (probably) mutated buffer dimensions
    // TODO: Filter to things that only affect the reserved buffer
    // TODO: Can we mutate the reserved buffer in some cases?
    getSelf(window)->clearReservedBuffer();
    va_list args;
    va_start(args, operation);
    int result = callProtected(getWrapped(window), perform, operation, args);
    va_end(args);

    va_start(args, operation);
    getSelf(window)->perform(operation, args);
    va_end(args);

    return result;
}

};  // namespace android::uirenderer::renderthread
