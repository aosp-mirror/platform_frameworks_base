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

#pragma once

#include <apex/window.h>
#include <gui/Surface.h>
#include <utils/Macros.h>
#include <utils/StrongPointer.h>

#include <memory>

namespace android::uirenderer::renderthread {

class ReliableSurface {
    PREVENT_COPY_AND_ASSIGN(ReliableSurface);

public:
    ReliableSurface(sp<Surface>&& surface);
    ~ReliableSurface();

    // Performs initialization that is not safe to do in the constructor.
    // For instance, registering ANativeWindow interceptors with ReliableSurface
    // passed as the data pointer is not safe.
    void init();

    ANativeWindow* getNativeWindow() { return mSurface.get(); }

    int reserveNext();

    int query(int what, int* value) const { return mSurface->query(what, value); }

    uint64_t getNextFrameNumber() const { return mSurface->getNextFrameNumber(); }

    int getAndClearError() {
        int ret = mBufferQueueState;
        mBufferQueueState = OK;
        return ret;
    }

    status_t getFrameTimestamps(uint64_t frameNumber,
            nsecs_t* outRequestedPresentTime, nsecs_t* outAcquireTime,
            nsecs_t* outLatchTime, nsecs_t* outFirstRefreshStartTime,
            nsecs_t* outLastRefreshStartTime, nsecs_t* outGlCompositionDoneTime,
            nsecs_t* outDisplayPresentTime, nsecs_t* outDequeueReadyTime,
            nsecs_t* outReleaseTime) {
        return mSurface->getFrameTimestamps(frameNumber, outRequestedPresentTime, outAcquireTime,
            outLatchTime, outFirstRefreshStartTime, outLastRefreshStartTime,
            outGlCompositionDoneTime, outDisplayPresentTime, outDequeueReadyTime, outReleaseTime);
    }

    void enableFrameTimestamps(bool enable) {
        return mSurface->enableFrameTimestamps(enable);
    }

private:
    sp<Surface> mSurface;

    mutable std::mutex mMutex;

    uint64_t mUsage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    PixelFormat mFormat = PIXEL_FORMAT_RGBA_8888;
    std::unique_ptr<AHardwareBuffer, void (*)(AHardwareBuffer*)> mScratchBuffer{
            nullptr, AHardwareBuffer_release};
    ANativeWindowBuffer* mReservedBuffer = nullptr;
    base::unique_fd mReservedFenceFd;
    bool mHasDequeuedBuffer = false;
    int mBufferQueueState = OK;

    bool isFallbackBuffer(const ANativeWindowBuffer* windowBuffer) const;
    ANativeWindowBuffer* acquireFallbackBuffer(int error);
    void clearReservedBuffer();

    // ANativeWindow hooks. When an ANativeWindow_* method is called on the
    // underlying ANativeWindow, these methods will intercept the original call.
    // For example, an EGL driver would call into these hooks instead of the
    // original methods.
    static int hook_cancelBuffer(ANativeWindow* window, ANativeWindow_cancelBufferFn cancelBuffer,
                                 void* data, ANativeWindowBuffer* buffer, int fenceFd);
    static int hook_dequeueBuffer(ANativeWindow* window,
                                  ANativeWindow_dequeueBufferFn dequeueBuffer, void* data,
                                  ANativeWindowBuffer** buffer, int* fenceFd);
    static int hook_queueBuffer(ANativeWindow* window, ANativeWindow_queueBufferFn queueBuffer,
                                void* data, ANativeWindowBuffer* buffer, int fenceFd);

    static int hook_perform(ANativeWindow* window, ANativeWindow_performFn perform, void* data,
                            int operation, va_list args);
};

};  // namespace android::uirenderer::renderthread
