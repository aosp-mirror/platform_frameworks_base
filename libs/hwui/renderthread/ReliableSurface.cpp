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

// TODO: Make surface less protected
// This exists because perform is a varargs, and ANativeWindow has no va_list perform.
// So wrapping/chaining that is hard. Telling the compiler to ignore protected is easy, so we do
// that instead
struct SurfaceExposer : Surface {
    // Make warnings happy
    SurfaceExposer() = delete;

    using Surface::setBufferCount;
    using Surface::setSwapInterval;
    using Surface::dequeueBuffer;
    using Surface::queueBuffer;
    using Surface::cancelBuffer;
    using Surface::lockBuffer_DEPRECATED;
    using Surface::perform;
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
    {
        std::lock_guard _lock{mMutex};
        if (mReservedBuffer) {
            ALOGW("reserveNext called but there was already a buffer reserved?");
            return OK;
        }
        if (mInErrorState) {
            return UNKNOWN_ERROR;
        }
    }

    int fenceFd = -1;
    ANativeWindowBuffer* buffer = nullptr;
    int result = callProtected(mSurface, dequeueBuffer, &buffer, &fenceFd);

    {
        std::lock_guard _lock{mMutex};
        LOG_ALWAYS_FATAL_IF(mReservedBuffer, "race condition in reserveNext");
        mReservedBuffer = buffer;
        mReservedFenceFd.reset(fenceFd);
        if (result != OK) {
            ALOGW("reserveNext failed, error %d", result);
        }
    }

    return result;
}

void ReliableSurface::clearReservedBuffer() {
    std::lock_guard _lock{mMutex};
    if (mReservedBuffer) {
        ALOGW("Reserved buffer %p was never used", mReservedBuffer);
    }
    mReservedBuffer = nullptr;
    mReservedFenceFd.reset();
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
        *buffer = acquireFallbackBuffer();
        *fenceFd = -1;
        return *buffer ? OK : INVALID_OPERATION;
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

ANativeWindowBuffer* ReliableSurface::acquireFallbackBuffer() {
    std::lock_guard _lock{mMutex};
    mInErrorState = true;

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
    va_list args;
    va_start(args, operation);
    int result = callProtected(getWrapped(window), perform, operation, args);
    va_end(args);

    switch (operation) {
        case NATIVE_WINDOW_SET_BUFFERS_FORMAT:
        case NATIVE_WINDOW_SET_USAGE:
        case NATIVE_WINDOW_SET_USAGE64:
            va_start(args, operation);
            getSelf(window)->perform(operation, args);
            va_end(args);
            break;
        default:
            break;
    }

    return result;
}

};  // namespace android::uirenderer::renderthread