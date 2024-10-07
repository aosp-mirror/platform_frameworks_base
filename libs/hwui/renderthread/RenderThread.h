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

#include <SkBitmap.h>
#include <cutils/compiler.h>
#include <include/gpu/ganesh/GrDirectContext.h>
#include <surface_control_private.h>
#include <utils/Thread.h>

#include <memory>
#include <mutex>
#include <set>

#include "CacheManager.h"
#include "MemoryPolicy.h"
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

typedef ASurfaceControl* (*ASC_create)(ASurfaceControl* parent, const char* debug_name);
typedef void (*ASC_acquire)(ASurfaceControl* control);
typedef void (*ASC_release)(ASurfaceControl* control);

typedef void (*ASC_registerSurfaceStatsListener)(ASurfaceControl* control, int32_t id,
                                                 void* context,
                                                 ASurfaceControl_SurfaceStatsListener func);
typedef void (*ASC_unregisterSurfaceStatsListener)(void* context,
                                                   ASurfaceControl_SurfaceStatsListener func);

typedef int64_t (*ASCStats_getAcquireTime)(ASurfaceControlStats* stats);
typedef uint64_t (*ASCStats_getFrameNumber)(ASurfaceControlStats* stats);

typedef ASurfaceTransaction* (*AST_create)();
typedef void (*AST_delete)(ASurfaceTransaction* transaction);
typedef void (*AST_apply)(ASurfaceTransaction* transaction);
typedef void (*AST_reparent)(ASurfaceTransaction* aSurfaceTransaction,
                             ASurfaceControl* aSurfaceControl,
                             ASurfaceControl* newParentASurfaceControl);
typedef void (*AST_setVisibility)(ASurfaceTransaction* transaction,
                                  ASurfaceControl* surface_control, int8_t visibility);
typedef void (*AST_setZOrder)(ASurfaceTransaction* transaction, ASurfaceControl* surface_control,
                              int32_t z_order);

struct ASurfaceControlFunctions {
    ASurfaceControlFunctions();

    ASC_create createFunc;
    ASC_acquire acquireFunc;
    ASC_release releaseFunc;
    ASC_registerSurfaceStatsListener registerListenerFunc;
    ASC_unregisterSurfaceStatsListener unregisterListenerFunc;
    ASCStats_getAcquireTime getAcquireTimeFunc;
    ASCStats_getFrameNumber getFrameNumberFunc;

    AST_create transactionCreateFunc;
    AST_delete transactionDeleteFunc;
    AST_apply transactionApplyFunc;
    AST_reparent transactionReparentFunc;
    AST_setVisibility transactionSetVisibilityFunc;
    AST_setZOrder transactionSetZOrderFunc;
};

class ChoreographerSource;
class DummyVsyncSource;

typedef void (*JVMAttachHook)(const char* name);

class RenderThread : private ThreadBase {
    PREVENT_COPY_AND_ASSIGN(RenderThread);

public:
    // Sets a callback that fires before any RenderThread setup has occurred.
    static void setOnStartHook(JVMAttachHook onStartHook);
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
    std::mutex& getJankDataMutex() { return mJankDataMutex; }
    Readback& readback();

    GrDirectContext* getGrContext() const { return mGrContext.get(); }
    void setGrContext(sk_sp<GrDirectContext> cxt);
    sk_sp<GrDirectContext> requireGrContext();

    CacheManager& cacheManager() { return *mCacheManager; }
    VulkanManager& vulkanManager();

    sk_sp<Bitmap> allocateHardwareBitmap(SkBitmap& skBitmap);
    void dumpGraphicsMemory(int fd, bool includeProfileData);
    void getMemoryUsage(size_t* cpuUsage, size_t* gpuUsage);

    void requireGlContext();
    void requireVkContext();
    void destroyRenderingContext();

    void preload();

    const ASurfaceControlFunctions& getASurfaceControlFunctions() {
        return mASurfaceControlFunctions;
    }

    void trimMemory(TrimLevel level);
    void trimCaches(CacheTrimLevel level);

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
    friend class sp<RenderThread>;

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
    static void extendedFrameCallback(const AChoreographerFrameCallbackData* cbData, void* data);
    void frameCallback(int64_t vsyncId, int64_t frameDeadline, int64_t frameTimeNanos,
                       int64_t frameInterval);
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
    RenderState* mRenderState;
    EglManager* mEglManager;
    WebViewFunctorManager& mFunctorManager;

    ProfileDataContainer mGlobalProfileData;
    Readback* mReadback = nullptr;

    sk_sp<GrDirectContext> mGrContext;
    CacheManager* mCacheManager;
    sp<VulkanManager> mVkManager;

    ASurfaceControlFunctions mASurfaceControlFunctions;
    std::mutex mJankDataMutex;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* RENDERTHREAD_H_ */
