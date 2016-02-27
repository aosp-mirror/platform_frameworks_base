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

#include <cutils/compiler.h>
#include <EGL/egl.h>
#include <SkRect.h>
#include <ui/GraphicBuffer.h>
#include <utils/StrongPointer.h>

namespace android {
namespace uirenderer {
namespace renderthread {

class RenderThread;
class EglManager;

class Frame {
public:
    EGLint width() const { return mWidth; }
    EGLint height() const { return mHeight; }

    // See: https://www.khronos.org/registry/egl/extensions/EXT/EGL_EXT_buffer_age.txt
    // for what this means
    EGLint bufferAge() const { return mBufferAge; }

private:
    friend class EglManager;

    EGLSurface mSurface;
    EGLint mWidth;
    EGLint mHeight;
    EGLint mBufferAge;

    // Maps from 0,0 in top-left to 0,0 in bottom-left
    // If out is not an EGLint[4] you're going to have a bad time
    void map(const SkRect& in, EGLint* out) const;
};

// This class contains the shared global EGL objects, such as EGLDisplay
// and EGLConfig, which are re-used by CanvasContext
class EglManager {
public:
    // Returns true on success, false on failure
    void initialize();

    bool hasEglContext();

    EGLSurface createSurface(EGLNativeWindowType window);
    void destroySurface(EGLSurface surface);

    void destroy();

    bool isCurrent(EGLSurface surface) { return mCurrentSurface == surface; }
    // Returns true if the current surface changed, false if it was already current
    bool makeCurrent(EGLSurface surface, EGLint* errOut = nullptr);
    Frame beginFrame(EGLSurface surface);
    void damageFrame(const Frame& frame, const SkRect& dirty);
    // If this returns true it is mandatory that swapBuffers is called
    // if damageFrame is called without subsequent calls to damageFrame().
    // See EGL_KHR_partial_update for more information
    bool damageRequiresSwap();
    bool swapBuffers(const Frame& frame, const SkRect& screenDirty);

    // Returns true iff the surface is now preserving buffers.
    bool setPreserveBuffer(EGLSurface surface, bool preserve);

    void setTextureAtlas(const sp<GraphicBuffer>& buffer, int64_t* map, size_t mapSize);

    void fence();

private:
    friend class RenderThread;

    EglManager(RenderThread& thread);
    // EglContext is never destroyed, method is purposely not implemented
    ~EglManager();

    void initExtensions();
    void createPBufferSurface();
    void loadConfig();
    void createContext();
    void initAtlas();
    EGLint queryBufferAge(EGLSurface surface);

    RenderThread& mRenderThread;

    EGLDisplay mEglDisplay;
    EGLConfig mEglConfig;
    EGLContext mEglContext;
    EGLSurface mPBufferSurface;

    EGLSurface mCurrentSurface;

    sp<GraphicBuffer> mAtlasBuffer;
    int64_t* mAtlasMap;
    size_t mAtlasMapSize;

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
