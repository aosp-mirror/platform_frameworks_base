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

#ifndef RENDERTHREAD_H_
#define RENDERTHREAD_H_

#include <GrContext.h>
#include <SkBitmap.h>
#include <apex/choreographer.h>
#include <cutils/compiler.h>
#include <thread/ThreadBase.h>
#include <utils/Looper.h>
#include <utils/Thread.h>

#include <memory>
#include <mutex>
#include <set>

#include "CacheManager.h"
#include "ProfileDataContainer.h"
#include "RenderTask.h"
#include "TimeLord.h"
#include "WebViewFunctorManager.h"
#include "thread/ThreadBase.h"
#include "utils/TimeUtils.h"

namespace android {

class Bitmap;

namespace uirenderer {

class AutoBackendTextureRelease;
class Readback;
class RenderState;
class TestUtils;

namespace skiapipeline {
class VkFunctorDrawHandler;
}

namespace VectorDrawable {
class Tree;
}

namespace renderthread {

class CanvasContext;
class EglManager;
class RenderProxy;
class VulkanManager;

// Mimics android.view.Choreographer.FrameCallback
class IFrameCallback {
public:
    virtual void doFrame() = 0;

protected:
    virtual ~IFrameCallback() {}
};

struct VsyncSource {
    virtual void requestNextVsync() = 0;
    virtual void drainPendingEvents() = 0;
    virtual ~VsyncSource() {}
};

class ChoreographerSource;
class DummyVsyncSource;

typedef void (*JVMAttachHook)(const char* name);

class RenderThread : private ThreadBase {
    PREVENT_COPY_AND_ASSIGN(RenderThread);

public:
    // Sets a callback that fires before any RenderThread setup has occurred.
    ANDROID_API static void setOnStartHook(JVMAttachHook onStartHook);
    static JVMAttachHook getOnStartHook();

    WorkQueue& queue() { return ThreadBase::queue(); }

    // Mimics android.view.Choreographer
    void postFrameCallback(IFrameCallback* callback);
    bool removeFrameCallback(IFrameCallback* callback);
    // If the callback is currently registered, it will be pushed back until
    // the next vsync. If it is not currently registered this does nothing.
    void pushBackFrameCallback(IFrameCallback* callback);

    TimeLord& timeLord() { return mTimeLord; }
    RenderState& renderState() const { return *mRenderState; }
    EglManager& eglManager() const { return *mEglManager; }
    ProfileDataContainer& globalProfileData() { return mGlobalProfileData; }
    Readback& readback();

    GrContext* getGrContext() const { return mGrContext.get(); }
    void setGrContext(sk_sp<GrContext> cxt);

    CacheManager& cacheManager() { return *mCacheManager; }
    VulkanManager& vulkanManager() { return *mVkManager; }

    sk_sp<Bitmap> allocateHardwareBitmap(SkBitmap& skBitmap);
    void dumpGraphicsMemory(int fd);

    void requireGlContext();
    void requireVkContext();
    void destroyRenderingContext();

    void preload();

    /**
     * isCurrent provides a way to query, if the caller is running on
     * the render thread.
     *
     * @return true only if isCurrent is invoked from the render thread.
     */
    static bool isCurrent();

    static void initGrContextOptions(GrContextOptions& options);

protected:
    virtual bool threadLoop() override;

private:
    friend class DispatchFrameCallbacks;
    friend class RenderProxy;
    friend class DummyVsyncSource;
    friend class ChoreographerSource;
    friend class android::uirenderer::AutoBackendTextureRelease;
    friend class android::uirenderer::TestUtils;
    friend class android::uirenderer::WebViewFunctor;
    friend class android::uirenderer::skiapipeline::VkFunctorDrawHandler;
    friend class android::uirenderer::VectorDrawable::Tree;

    RenderThread();
    virtual ~RenderThread();

    static bool hasInstance();
    static RenderThread& getInstance();

    void initThreadLocals();
    void initializeChoreographer();
    void setupFrameInterval();
    // Callbacks for choreographer events:
    // choreographerCallback will call AChoreograper_handleEvent to call the
    // corresponding callbacks for each display event type
    static int choreographerCallback(int fd, int events, void* data);
    // Callback that will be run on vsync ticks.
    static void frameCallback(int64_t frameTimeNanos, void* data);
    // Callback that will be run whenver there is a refresh rate change.
    static void refreshRateCallback(int64_t vsyncPeriod, void* data);
    void drainDisplayEventQueue();
    void dispatchFrameCallbacks();
    void requestVsync();

    AChoreographer* mChoreographer;
    VsyncSource* mVsyncSource;
    bool mVsyncRequested;
    std::set<IFrameCallback*> mFrameCallbacks;
    // We defer the actual registration of these callbacks until
    // both mQueue *and* mDisplayEventReceiver have been drained off all
    // immediate events. This makes sure that we catch the next vsync, not
    // the previous one
    std::set<IFrameCallback*> mPendingRegistrationFrameCallbacks;
    bool mFrameCallbackTaskPending;

    TimeLord mTimeLord;
    nsecs_t mDispatchFrameDelay = 4_ms;
    RenderState* mRenderState;
    EglManager* mEglManager;
    WebViewFunctorManager& mFunctorManager;

    ProfileDataContainer mGlobalProfileData;
    Readback* mReadback = nullptr;

    sk_sp<GrContext> mGrContext;
    CacheManager* mCacheManager;
    VulkanManager* mVkManager;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* RENDERTHREAD_H_ */
