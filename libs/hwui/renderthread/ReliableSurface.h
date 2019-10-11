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

#include <gui/Surface.h>
#include <utils/Macros.h>
#include <utils/StrongPointer.h>

#include <memory>

namespace android::uirenderer::renderthread {

class ReliableSurface : public ANativeObjectBase<ANativeWindow, ReliableSurface, RefBase> {
    PREVENT_COPY_AND_ASSIGN(ReliableSurface);

public:
    ReliableSurface(sp<Surface>&& surface);
    ~ReliableSurface();

    void setDequeueTimeout(nsecs_t timeout) { mSurface->setDequeueTimeout(timeout); }

    int reserveNext();

    void allocateBuffers() { mSurface->allocateBuffers(); }

    int query(int what, int* value) const { return mSurface->query(what, value); }

    nsecs_t getLastDequeueStartTime() const { return mSurface->getLastDequeueStartTime(); }

    uint64_t getNextFrameNumber() const { return mSurface->getNextFrameNumber(); }

private:
    const sp<Surface> mSurface;

    mutable std::mutex mMutex;

    uint64_t mUsage = AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    PixelFormat mFormat = PIXEL_FORMAT_RGBA_8888;
    std::unique_ptr<AHardwareBuffer, void (*)(AHardwareBuffer*)> mScratchBuffer{
            nullptr, AHardwareBuffer_release};
    ANativeWindowBuffer* mReservedBuffer = nullptr;
    base::unique_fd mReservedFenceFd;
    bool mHasDequeuedBuffer = false;
    bool mInErrorState = false;

    bool isFallbackBuffer(const ANativeWindowBuffer* windowBuffer) const;
    ANativeWindowBuffer* acquireFallbackBuffer();
    void clearReservedBuffer();

    void perform(int operation, va_list args);
    int cancelBuffer(ANativeWindowBuffer* buffer, int fenceFd);
    int dequeueBuffer(ANativeWindowBuffer** buffer, int* fenceFd);
    int queueBuffer(ANativeWindowBuffer* buffer, int fenceFd);

    static Surface* getWrapped(const ANativeWindow*);

    // ANativeWindow hooks
    static int hook_cancelBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer, int fenceFd);
    static int hook_dequeueBuffer(ANativeWindow* window, ANativeWindowBuffer** buffer,
                                  int* fenceFd);
    static int hook_queueBuffer(ANativeWindow* window, ANativeWindowBuffer* buffer, int fenceFd);

    static int hook_perform(ANativeWindow* window, int operation, ...);
    static int hook_query(const ANativeWindow* window, int what, int* value);
    static int hook_setSwapInterval(ANativeWindow* window, int interval);

    static int hook_cancelBuffer_DEPRECATED(ANativeWindow* window, ANativeWindowBuffer* buffer);
    static int hook_dequeueBuffer_DEPRECATED(ANativeWindow* window, ANativeWindowBuffer** buffer);
    static int hook_lockBuffer_DEPRECATED(ANativeWindow* window, ANativeWindowBuffer* buffer);
    static int hook_queueBuffer_DEPRECATED(ANativeWindow* window, ANativeWindowBuffer* buffer);
};

};  // namespace android::uirenderer::renderthread