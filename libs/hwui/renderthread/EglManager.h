/*
 * Copyright (C) 2014 The Android Open Source Project
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
#ifndef EGLMANAGER_H
#define EGLMANAGER_H

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <SkImageInfo.h>
#include <SkRect.h>
#include <cutils/compiler.h>
#include <utils/StrongPointer.h>

#include "IRenderPipeline.h"
#include "utils/Result.h"

namespace android {
namespace uirenderer {
namespace renderthread {

class Frame;
class RenderThread;

// This class contains the shared global EGL objects, such as EGLDisplay
// and EGLConfig, which are re-used by CanvasContext
class EglManager {
public:
    explicit EglManager();

    ~EglManager();

    static const char* eglErrorString();

    void initialize();

    bool hasEglContext();

    Result<EGLSurface, EGLint> createSurface(EGLNativeWindowType window, ColorMode colorMode,
                                             sk_sp<SkColorSpace> colorSpace);
    void destroySurface(EGLSurface surface);

    void destroy();

    bool isCurrent(EGLSurface surface) { return mCurrentSurface == surface; }
    // Returns true if the current surface changed, false if it was already current
    bool makeCurrent(EGLSurface surface, EGLint* errOut = nullptr, bool force = false);
    Frame beginFrame(EGLSurface surface);
    void damageFrame(const Frame& frame, const SkRect& dirty);
    // If this returns true it is mandatory that swapBuffers is called
    // if damageFrame is called without subsequent calls to damageFrame().
    // See EGL_KHR_partial_update for more information
    bool damageRequiresSwap();
    bool swapBuffers(const Frame& frame, const SkRect& screenDirty);

    // Returns true iff the surface is now preserving buffers.
    bool setPreserveBuffer(EGLSurface surface, bool preserve);

    void fence();

    EGLDisplay eglDisplay() const { return mEglDisplay; }

    // Inserts a wait on fence command into the OpenGL ES command stream. If EGL extension
    // support is missing, block the CPU on the fence.
    status_t fenceWait(int fence);

    // Creates a fence that is signaled, when all the pending GL commands are flushed.
    // Depending on installed extensions, the result is either Android native fence or EGL fence.
    status_t createReleaseFence(bool useFenceSync, EGLSyncKHR* eglFence, int* nativeFence);

private:
    enum class SwapBehavior {
        Discard,
        Preserved,
        BufferAge,
    };

    static EGLConfig load8BitsConfig(EGLDisplay display, SwapBehavior swapBehavior);
    static EGLConfig loadFP16Config(EGLDisplay display, SwapBehavior swapBehavior);
    static EGLConfig load1010102Config(EGLDisplay display, SwapBehavior swapBehavior);
    static EGLConfig loadA8Config(EGLDisplay display, SwapBehavior swapBehavior);

    void initExtensions();
    void createPBufferSurface();
    void loadConfigs();
    void createContext();
    EGLint queryBufferAge(EGLSurface surface);

    EGLDisplay mEglDisplay;
    EGLConfig mEglConfig;
    EGLConfig mEglConfigF16;
    EGLConfig mEglConfig1010102;
    EGLConfig mEglConfigA8;
    EGLContext mEglContext;
    EGLSurface mPBufferSurface;
    EGLSurface mCurrentSurface;
    bool mHasWideColorGamutSupport;
    SwapBehavior mSwapBehavior = SwapBehavior::Discard;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* EGLMANAGER_H */
