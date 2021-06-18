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

#include <android-base/unique_fd.h>
#include <system/window.h>
#include <apex/window.h>
#include <utils/Errors.h>
#include <utils/Macros.h>
#include <utils/NdkUtils.h>
#include <utils/StrongPointer.h>

#include <memory>
#include <mutex>

namespace android::uirenderer::renderthread {

class ReliableSurface {
    PREVENT_COPY_AND_ASSIGN(ReliableSurface);

public:
    ReliableSurface(ANativeWindow* window);
    ~ReliableSurface();

    // Performs initialization that is not safe to do in the constructor.
    // For instance, registering ANativeWindow interceptors with ReliableSurface
    // passed as the data pointer is not safe.
    void init();

    ANativeWindow* getNativeWindow() { return mWindow; }

    int reserveNext();

    int getAndClearError() {
        int ret = mBufferQueueState;
        mBufferQueueState = OK;
        return ret;
    }

    bool didSetExtraBuffers() const {
        std::lock_guard _lock{mMutex};
        return mDidSetExtraBuffers;
    }

private:
    ANativeWindow* mWindow;

    mutable std::mutex mMutex;

    uint64_t mUsage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    AHardwareBuffer_Format mFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    UniqueAHardwareBuffer mScratchBuffer;
    ANativeWindowBuffer* mReservedBuffer = nullptr;
    base::unique_fd mReservedFenceFd;
    bool mHasDequeuedBuffer = false;
    int mBufferQueueState = OK;
    size_t mExpectedBufferCount = 0;
    bool mDidSetExtraBuffers = false;

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
    static int hook_query(const ANativeWindow* window, ANativeWindow_queryFn query, void* data,
            int what, int* value);
};

};  // namespace android::uirenderer::renderthread
