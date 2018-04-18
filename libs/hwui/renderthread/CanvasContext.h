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

#include "BakedOpDispatcher.h"
#include "BakedOpRenderer.h"
#include "DamageAccumulator.h"
#include "FrameBuilder.h"
#include "FrameInfo.h"
#include "FrameInfoVisualizer.h"
#include "FrameMetricsReporter.h"
#include "IContextFactory.h"
#include "IRenderPipeline.h"
#include "LayerUpdateQueue.h"
#include "RenderNode.h"
#include "renderthread/RenderTask.h"
#include "renderthread/RenderThread.h"
#include "thread/Task.h"
#include "thread/TaskProcessor.h"

#include <EGL/egl.h>
#include <SkBitmap.h>
#include <SkRect.h>
#include <cutils/compiler.h>
#include <gui/Surface.h>
#include <utils/Functor.h>

#include <functional>
#include <set>
#include <string>
#include <vector>

namespace android {
namespace uirenderer {

class AnimationContext;
class DeferredLayerUpdater;
class ErrorHandler;
class Layer;
class Rect;
class RenderState;

namespace renderthread {

class EglManager;
class Frame;

// This per-renderer class manages the bridge between the global EGL context
// and the render surface.
// TODO: Rename to Renderer or some other per-window, top-level manager
class CanvasContext : public IFrameCallback {
public:
    static CanvasContext* create(RenderThread& thread, bool translucent, RenderNode* rootRenderNode,
                                 IContextFactory* contextFactory);
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
        return mRenderPipeline->createOrUpdateLayer(node, dmgAccumulator, mWideColorGamut,
                errorHandler);
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
        return mRenderPipeline->pinImages(mutableImages);
    }
    bool pinImages(LsaVector<sk_sp<Bitmap>>& images) { return mRenderPipeline->pinImages(images); }

    /**
     * Unpin any image that had be previously pinned to the GPU cache
     */
    void unpinImages() { mRenderPipeline->unpinImages(); }

    /**
     * Destroy any layers that have been attached to the provided RenderNode removing
     * any state that may have been set during createOrUpdateLayer().
     */
    static void destroyLayer(RenderNode* node);

    static void invokeFunctor(const RenderThread& thread, Functor* functor);

    static void prepareToDraw(const RenderThread& thread, Bitmap* bitmap);

    /*
     * If Properties::isSkiaEnabled() is true then this will return the Skia
     * grContext associated with the current RenderPipeline.
     */
    GrContext* getGrContext() const { return mRenderThread.getGrContext(); }

    // Won't take effect until next EGLSurface creation
    void setSwapBehavior(SwapBehavior swapBehavior);

    void setSurface(sp<Surface>&& surface);
    bool pauseSurface();
    void setStopped(bool stopped);
    bool hasSurface() { return mNativeSurface.get(); }

    void setup(float lightRadius, uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha);
    void setLightCenter(const Vector3& lightCenter);
    void setOpaque(bool opaque);
    void setWideGamut(bool wideGamut);
    bool makeCurrent();
    void prepareTree(TreeInfo& info, int64_t* uiFrameInfo, int64_t syncQueued, RenderNode* target);
    void draw();
    void destroy();

    // IFrameCallback, Choreographer-driven frame callback entry point
    virtual void doFrame() override;
    void prepareAndDraw(RenderNode* node);

    void buildLayer(RenderNode* node);
    bool copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap);
    void markLayerInUse(RenderNode* node);

    void destroyHardwareResources();
    static void trimMemory(RenderThread& thread, int level);

    DeferredLayerUpdater* createTextureLayer();

    void stopDrawing();
    void notifyFramePending();

    FrameInfoVisualizer& profiler() { return mProfiler; }

    void dumpFrames(int fd);
    void resetFrameStats();

    void setName(const std::string&& name);

    void addRenderNode(RenderNode* node, bool placeFront);
    void removeRenderNode(RenderNode* node);

    void setContentDrawBounds(const Rect& bounds) { mContentDrawBounds = bounds; }

    RenderState& getRenderState() { return mRenderThread.renderState(); }

    void addFrameMetricsObserver(FrameMetricsObserver* observer) {
        if (mFrameMetricsReporter.get() == nullptr) {
            mFrameMetricsReporter.reset(new FrameMetricsReporter());
        }

        mFrameMetricsReporter->addObserver(observer);
    }

    void removeFrameMetricsObserver(FrameMetricsObserver* observer) {
        if (mFrameMetricsReporter.get() != nullptr) {
            mFrameMetricsReporter->removeObserver(observer);
            if (!mFrameMetricsReporter->hasObservers()) {
                mFrameMetricsReporter.reset(nullptr);
            }
        }
    }

    // Used to queue up work that needs to be completed before this frame completes
    ANDROID_API void enqueueFrameWork(std::function<void()>&& func);

    ANDROID_API int64_t getFrameNumber();

    void waitOnFences();

    IRenderPipeline* getRenderPipeline() { return mRenderPipeline.get(); }

private:
    CanvasContext(RenderThread& thread, bool translucent, RenderNode* rootRenderNode,
                  IContextFactory* contextFactory, std::unique_ptr<IRenderPipeline> renderPipeline);

    friend class RegisterFrameCallbackTask;
    // TODO: Replace with something better for layer & other GL object
    // lifecycle tracking
    friend class android::uirenderer::RenderState;

    void freePrefetchedLayers();

    bool isSwapChainStuffed();

    SkRect computeDirtyRect(const Frame& frame, SkRect* dirty);

    EGLint mLastFrameWidth = 0;
    EGLint mLastFrameHeight = 0;

    RenderThread& mRenderThread;
    sp<Surface> mNativeSurface;
    // stopped indicates the CanvasContext will reject actual redraw operations,
    // and defer repaint until it is un-stopped
    bool mStopped = false;
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

    RingBuffer<SwapHistory, 3> mSwapHistory;
    int64_t mFrameNumber = -1;

    // last vsync for a dropped frame due to stuffed queue
    nsecs_t mLastDropVsync = 0;

    bool mOpaque;
    bool mWideColorGamut = false;
    BakedOpRenderer::LightInfo mLightInfo;
    FrameBuilder::LightGeometry mLightGeometry = {{0, 0, 0}, 0};

    bool mHaveNewSurface = false;
    DamageAccumulator mDamageAccumulator;
    LayerUpdateQueue mLayerUpdateQueue;
    std::unique_ptr<AnimationContext> mAnimationContext;

    std::vector<sp<RenderNode>> mRenderNodes;

    FrameInfo* mCurrentFrameInfo = nullptr;
    std::string mName;
    JankTracker mJankTracker;
    FrameInfoVisualizer mProfiler;
    std::unique_ptr<FrameMetricsReporter> mFrameMetricsReporter;

    std::set<RenderNode*> mPrefetchedLayers;

    // Stores the bounds of the main content.
    Rect mContentDrawBounds;

    // TODO: This is really a Task<void> but that doesn't really work
    // when Future<> expects to be able to get/set a value
    struct FuncTask : public Task<bool> {
        std::function<void()> func;
    };
    class FuncTaskProcessor;

    std::vector<sp<FuncTask>> mFrameFences;
    sp<TaskProcessor<bool>> mFrameWorkProcessor;
    std::unique_ptr<IRenderPipeline> mRenderPipeline;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
