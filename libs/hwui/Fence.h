/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_FENCE_H
#define ANDROID_HWUI_FENCE_H

#include <EGL/egl.h>
#include <EGL/eglext.h>

namespace android {
namespace uirenderer {

/**
 * Creating a Fence instance inserts a new sync fence in the OpenGL
 * commands stream. The caller can then wait for the fence to be signaled
 * by calling the wait method.
 */
class Fence {
public:
    enum {
        /**
         * Default timeout in nano-seconds for wait()
         */
        kDefaultTimeout = 1000000000
    };

    /**
     * Inserts a new sync fence in the OpenGL commands stream.
     */
    Fence() {
        mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (mDisplay != EGL_NO_DISPLAY) {
            mFence = eglCreateSyncKHR(mDisplay, EGL_SYNC_FENCE_KHR, NULL);
        } else {
            mFence = EGL_NO_SYNC_KHR;
        }
    }

    /**
     * Destroys the fence. Any caller waiting on the fence will be
     * signaled immediately.
     */
    ~Fence() {
        if (mFence != EGL_NO_SYNC_KHR) {
            eglDestroySyncKHR(mDisplay, mFence);
        }
    }

    /**
     * Blocks the calling thread until this fence is signaled, or until
     * <timeout> nanoseconds have passed.
     *
     * Returns true if waiting for the fence was successful, false if
     * a timeout or an error occurred.
     */
    bool wait(EGLTimeKHR timeout = kDefaultTimeout) {
        EGLint waitStatus = eglClientWaitSyncKHR(mDisplay, mFence,
                EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, timeout);
        if (waitStatus == EGL_FALSE) {
            ALOGW("Failed to wait for the fence %#x", eglGetError());
        }
        return waitStatus == EGL_CONDITION_SATISFIED_KHR;
    }

private:
    EGLDisplay mDisplay;
    EGLSyncKHR mFence;

}; // class Fence

/**
 * An AutoFence creates a Fence instance and waits for the fence
 * to be signaled when the AutoFence is destroyed. This is useful
 * to automatically wait for a series of OpenGL commands to be
 * executed. For example:
 *
 * void drawAndWait() {
 *     glDrawElements();
 *     AutoFence fence;
 * }
 */
class AutoFence {
public:
    AutoFence(EGLTimeKHR timeout = Fence::kDefaultTimeout): mTimeout(timeout) {
    }

    ~AutoFence() {
        mFence.wait(mTimeout);
    }

private:
    EGLTimeKHR mTimeout;
    Fence mFence;

}; // class AutoFence

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_FENCE_H
