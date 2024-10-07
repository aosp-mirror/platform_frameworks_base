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

#pragma once

#include <SkBitmap.h>
#include <SkRect.h>
#include <SkSize.h>
#include <cutils/compiler.h>
#include <utils/Functor.h>
#include <utils/Mutex.h>

#include <functional>
#include <future>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "ColorMode.h"
#include "DamageAccumulator.h"
#include "FrameInfo.h"
#include "FrameInfoVisualizer.h"
#include "FrameMetricsReporter.h"
#include "HintSessionWrapper.h"
#include "IContextFactory.h"
#include "IRenderPipeline.h"
#include "JankTracker.h"
#include "LayerUpdateQueue.h"
#include "Lighting.h"
#include "ReliableSurface.h"
#include "RenderNode.h"
#include "renderstate/RenderState.h"
#include "renderthread/RenderTask.h"
#include "renderthread/RenderThread.h"
#include "utils/ForceDark.h"
#include "utils/RingBuffer.h"

namespace android {
namespace uirenderer {

class AnimationContext;
class DeferredLayerUpdater;
class ErrorHandler;
class Layer;
class Rect;
class RenderState;

namespace renderthread {

class Frame;

// This per-renderer class manages the bridge between the global EGL context
// and the render surface.
// TODO: Rename to Renderer or some other per-window, top-level manager
class CanvasContext : public IFrameCallback, public IGpuContextCallback {
public:
    static CanvasContext* create(RenderThread& thread, bool translucent, RenderNode* rootRenderNode,
                                 IContextFactory* contextFactory, pid_t uiThreadId,
                                 pid_t renderThreadId);
    virtual ~CanvasContext();

    /**
     * Update or create a layer specific for the provided RenderNode. The layer
     * attached to the node will be specific to the RenderPipeline used by this
     * context
     *
     *  @return true if the layer has been created or updated
     */
    bool createOrUpdateLayer(RenderNode* node, const DamageAccumulator& dmgAccumulator,
                             ErrorHandler* errorHandler) {
        return mRenderPipeline->createOrUpdateLayer(node, dmgAccumulator, errorHandler);
    }

    /**
     * Pin any mutable images to the GPU cache. A pinned images is guaranteed to
     * remain in the cache until it has been unpinned. We leverage this feature
     * to avoid making a CPU copy of the pixels.
     *
     * @return true if all images have been successfully pinned to the GPU cache
     *         and false otherwise (e.g. cache limits have been exceeded).
     */
    bool pinImages(std::vector<SkImage*>& mutableImages) {
        if (!Properties::isDrawingEnabled()) {
            return true;
        }
        return mRenderPipeline->pinImages(mutableImages);
    }
    bool pinImages(LsaVector<sk_sp<Bitmap>>& images) {
        if (!Properties::isDrawingEnabled()) {
            return true;
        }
        return mRenderPipeline->pinImages(images);
    }

    /**
     * Unpin any image that had be previously pinned to the GPU cache
     */
    void unpinImages() { mRenderPipeline->unpinImages(); }

    static void invokeFunctor(const RenderThread& thread, Functor* functor);

    static void prepareToDraw(const RenderThread& thread, Bitmap* bitmap);

    /*
     * If Properties::isSkiaEnabled() is true then this will return the Skia
     * grContext associated with the current RenderPipeline.
     */
    GrDirectContext* getGrContext() const { return mRenderThread.getGrContext(); }

    ASurfaceControl* getSurfaceControl() const { return mSurfaceControl; }
    int32_t getSurfaceControlGenerationId() const { return mSurfaceControlGenerationId; }

    // Won't take effect until next EGLSurface creation
    void setSwapBehavior(SwapBehavior swapBehavior);

    void setHardwareBuffer(AHardwareBuffer* buffer);
    void setSurface(ANativeWindow* window, bool enableTimeout = true);
    void setSurfaceControl(ASurfaceControl* surfaceControl);
    bool pauseSurface();
    void setStopped(bool stopped);
    bool isStopped() { return mStopped || !hasOutputTarget(); }
    bool hasOutputTarget() const { return mNativeSurface.get() || mHardwareBuffer; }
    void allocateBuffers();

    void setLightAlpha(uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha);
    void setLightGeometry(const Vector3& lightCenter, float lightRadius);
    void setOpaque(bool opaque);
    float setColorMode(ColorMode mode);
    float targetSdrHdrRatio() const;
    void setTargetSdrHdrRatio(float ratio);
    bool makeCurrent();
    void prepareTree(TreeInfo& info, int64_t* uiFrameInfo, int64_t syncQueued, RenderNode* target);
    // Returns the DequeueBufferDuration.
    void draw(bool solelyTextureViewUpdates);
    void destroy();

    // IFrameCallback, Choreographer-driven frame callback entry point
    virtual void doFrame() override;
    void prepareAndDraw(RenderNode* node);

    void buildLayer(RenderNode* node);
    void markLayerInUse(RenderNode* node);

    void destroyHardwareResources();
    void onContextDestroyed() override;

    DeferredLayerUpdater* createTextureLayer();

    void stopDrawing();
    void notifyFramePending();

    FrameInfoVisualizer& profiler() { return mProfiler; }
    std::mutex& profilerLock() { return mFrameInfoMutex; }

    void dumpFrames(int fd);
    void resetFrameStats();

    void setName(const std::string&& name);

    void addRenderNode(RenderNode* node, bool placeFront);
    void removeRenderNode(RenderNode* node);

    void setContentDrawBounds(const Rect& bounds) { mContentDrawBounds = bounds; }

    void addFrameMetricsObserver(FrameMetricsObserver* observer);
    void removeFrameMetricsObserver(FrameMetricsObserver* observer);

    // Used to queue up work that needs to be completed before this frame completes
    void enqueueFrameWork(std::function<void()>&& func);

    uint64_t getFrameNumber();

    void waitOnFences();

    IRenderPipeline* getRenderPipeline() { return mRenderPipeline.get(); }

    void addFrameCommitListener(std::function<void(bool)>&& func) {
        mFrameCommitCallbacks.push_back(std::move(func));
    }

    void setPictureCapturedCallback(const std::function<void(sk_sp<SkPicture>&&)>& callback) {
        mRenderPipeline->setPictureCapturedCallback(callback);
    }

    void setForceDark(ForceDarkType type) { mForceDarkType = type; }

    ForceDarkType getForceDarkType() { return mForceDarkType; }

    SkISize getNextFrameSize() const;

    // Returns the matrix to use to nudge non-AA'd points/lines towards the fragment center
    const SkM44& getPixelSnapMatrix() const;

    // Called when SurfaceStats are available.
    static void onSurfaceStatsAvailable(void* context, int32_t surfaceControlId,
                                        ASurfaceControlStats* stats);

    void setASurfaceTransactionCallback(
            const std::function<bool(int64_t, int64_t, int64_t)>& callback) {
        mASurfaceTransactionCallback = callback;
    }

    void setHardwareBufferRenderParams(const HardwareBufferRenderParams& params) {
        mBufferParams = params;
    }

    bool mergeTransaction(ASurfaceTransaction* transaction, ASurfaceControl* control);

    void setPrepareSurfaceControlForWebviewCallback(const std::function<void()>& callback) {
        mPrepareSurfaceControlForWebviewCallback = callback;
    }

    void prepareSurfaceControlForWebview();

    static CanvasContext* getActiveContext();

    void sendLoadResetHint();

    void sendLoadIncreaseHint();

    void setSyncDelayDuration(nsecs_t duration);

    void startHintSession();

    static bool shouldDither();

    void visitAllRenderNodes(std::function<void(const RenderNode&)>) const;

private:
    CanvasContext(RenderThread& thread, bool translucent, RenderNode* rootRenderNode,
                  IContextFactory* contextFactory, std::unique_ptr<IRenderPipeline> renderPipeline,
                  pid_t uiThreadId, pid_t renderThreadId);

    friend class RegisterFrameCallbackTask;
    // TODO: Replace with something better for layer & other GL object
    // lifecycle tracking
    friend class android::uirenderer::RenderState;

    void freePrefetchedLayers();

    bool isSwapChainStuffed();
    bool surfaceRequiresRedraw();
    void setupPipelineSurface();

    SkRect computeDirtyRect(const Frame& frame, SkRect* dirty);
    void finishFrame(FrameInfo* frameInfo);

    /**
     * Invoke 'reportFrameMetrics' on the last frame stored in 'mLastFrameInfos'.
     * Populate the 'presentTime' field before calling.
     */
    void reportMetricsWithPresentTime();

    struct FrameMetricsInfo {
        FrameInfo* frameInfo;
        int64_t frameNumber;
        int32_t surfaceId;
    };

    FrameInfo* getFrameInfoFromLastFew(uint64_t frameNumber, uint32_t surfaceControlId);

    Frame getFrame();

    // The same type as Frame.mWidth and Frame.mHeight
    int32_t mLastFrameWidth = 0;
    int32_t mLastFrameHeight = 0;

    RenderThread& mRenderThread;

    AHardwareBuffer* mHardwareBuffer = nullptr;
    HardwareBufferRenderParams mBufferParams;
    std::unique_ptr<ReliableSurface> mNativeSurface;
    // The SurfaceControl reference is passed from ViewRootImpl, can be set to
    // NULL to remove the reference
    ASurfaceControl* mSurfaceControl = nullptr;
    // id to track surface control changes and WebViewFunctor uses it to determine
    // whether reparenting is needed also used by FrameMetricsReporter to determine
    // if a frame is from an "old" surface (i.e. one that existed before the
    // observer was attched) and therefore shouldn't be reported.
    // NOTE: It is important that this is an increasing counter.
    int32_t mSurfaceControlGenerationId = 0;
    // stopped indicates the CanvasContext will reject actual redraw operations,
    // and defer repaint until it is un-stopped
    bool mStopped = false;
    // Incremented each time the CanvasContext is stopped. Used to ignore
    // delayed messages that are triggered after stopping.
    int mGenerationID;
    // CanvasContext is dirty if it has received an update that it has not
    // painted onto its surface.
    bool mIsDirty = false;
    SwapBehavior mSwapBehavior = SwapBehavior::kSwap_default;
    struct SwapHistory {
        SkRect damage;
        nsecs_t vsyncTime;
        nsecs_t swapCompletedTime;
        nsecs_t dequeueDuration;
        nsecs_t queueDuration;
    };

    // Need at least 4 because we do quad buffer. Add a few more for good measure.
    RingBuffer<SwapHistory, 7> mSwapHistory;
    // Frame numbers start at 1, 0 means uninitialized
    uint64_t mFrameNumber = 0;
    int64_t mDamageId = 0;

    // last vsync for a dropped frame due to stuffed queue
    nsecs_t mLastDropVsync = 0;

    bool mOpaque;
    ForceDarkType mForceDarkType = ForceDarkType::NONE;
    LightInfo mLightInfo;
    LightGeometry mLightGeometry = {{0, 0, 0}, 0};

    bool mHaveNewSurface = false;
    DamageAccumulator mDamageAccumulator;
    LayerUpdateQueue mLayerUpdateQueue;
    std::unique_ptr<AnimationContext> mAnimationContext;

    std::vector<sp<RenderNode>> mRenderNodes;

    FrameInfo* mCurrentFrameInfo = nullptr;

    // List of data of frames that are awaiting GPU completion reporting. Used to compute frame
    // metrics and determine whether or not to report the metrics.
    RingBuffer<FrameMetricsInfo, 6> mLastFrameMetricsInfos
            GUARDED_BY(mLastFrameMetricsInfosMutex);
    std::mutex mLastFrameMetricsInfosMutex;

    std::string mName;
    JankTracker mJankTracker;
    FrameInfoVisualizer mProfiler;
    std::unique_ptr<FrameMetricsReporter> mFrameMetricsReporter GUARDED_BY(mFrameInfoMutex);
    std::mutex mFrameInfoMutex;

    std::set<RenderNode*> mPrefetchedLayers;

    // Stores the bounds of the main content.
    Rect mContentDrawBounds;

    std::vector<std::future<void>> mFrameFences;
    std::unique_ptr<IRenderPipeline> mRenderPipeline;

    std::vector<std::function<void(bool)>> mFrameCommitCallbacks;

    // If set to true, we expect that callbacks into onSurfaceStatsAvailable
    bool mExpectSurfaceStats = false;

    std::function<bool(int64_t, int64_t, int64_t)> mASurfaceTransactionCallback;
    std::function<void()> mPrepareSurfaceControlForWebviewCallback;

    std::shared_ptr<HintSessionWrapper> mHintSessionWrapper;
    nsecs_t mLastDequeueBufferDuration = 0;
    nsecs_t mSyncDelayDuration = 0;
    nsecs_t mIdleDuration = 0;

    ColorMode mColorMode = ColorMode::Default;
    float mTargetSdrHdrRatio = 1.f;

    struct SkippedFrameInfo {
        int64_t vsyncId;
        int64_t startTime;
    };
    std::optional<SkippedFrameInfo> mSkippedFrameInfo;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
