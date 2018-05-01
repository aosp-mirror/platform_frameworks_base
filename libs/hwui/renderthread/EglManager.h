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
#include <SkRect.h>
#include <cutils/compiler.h>
#include <ui/GraphicBuffer.h>
#include <utils/StrongPointer.h>

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

    EGLSurface createSurface(EGLNativeWindowType window, bool wideColorGamut);
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

private:

    void initExtensions();
    void createPBufferSurface();
    void loadConfigs();
    void createContext();
    EGLint queryBufferAge(EGLSurface surface);

    EGLDisplay mEglDisplay;
    EGLConfig mEglConfig;
    EGLConfig mEglConfigWideGamut;
    EGLContext mEglContext;
    EGLSurface mPBufferSurface;

    EGLSurface mCurrentSurface;

    enum class SwapBehavior {
        Discard,
        Preserved,
        BufferAge,
    };
    SwapBehavior mSwapBehavior = SwapBehavior::Discard;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* EGLMANAGER_H */
