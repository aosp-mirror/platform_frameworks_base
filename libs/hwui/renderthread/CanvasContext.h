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

#ifndef CANVASCONTEXT_H_
#define CANVASCONTEXT_H_

#include "DamageAccumulator.h"
#include "FrameInfo.h"
#include "FrameInfoVisualizer.h"
#include "FrameMetricsReporter.h"
#include "IContextFactory.h"
#include "LayerUpdateQueue.h"
#include "RenderNode.h"
#include "thread/Task.h"
#include "thread/TaskProcessor.h"
#include "utils/RingBuffer.h"
#include "renderthread/RenderTask.h"
#include "renderthread/RenderThread.h"

#if HWUI_NEW_OPS
#include "BakedOpDispatcher.h"
#include "BakedOpRenderer.h"
#include "FrameBuilder.h"
#endif

#include <cutils/compiler.h>
#include <EGL/egl.h>
#include <SkBitmap.h>
#include <SkRect.h>
#include <utils/Functor.h>
#include <gui/Surface.h>

#include <functional>
#include <set>
#include <string>
#include <vector>

namespace android {
namespace uirenderer {

class AnimationContext;
class DeferredLayerUpdater;
class OpenGLRenderer;
class Rect;
class Layer;
class RenderState;

namespace renderthread {

class EglManager;

enum SwapBehavior {
    kSwap_default,
    kSwap_discardBuffer,
};

// This per-renderer class manages the bridge between the global EGL context
// and the render surface.
// TODO: Rename to Renderer or some other per-window, top-level manager
class CanvasContext : public IFrameCallback {
public:
    CanvasContext(RenderThread& thread, bool translucent, RenderNode* rootRenderNode,
            IContextFactory* contextFactory);
    virtual ~CanvasContext();

    // Won't take effect until next EGLSurface creation
    void setSwapBehavior(SwapBehavior swapBehavior);

    void initialize(Surface* surface);
    void updateSurface(Surface* surface);
    bool pauseSurface(Surface* surface);
    void setStopped(bool stopped);
    bool hasSurface() { return mNativeSurface.get(); }

    void setup(int width, int height, float lightRadius,
            uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha);
    void setLightCenter(const Vector3& lightCenter);
    void setOpaque(bool opaque);
    bool makeCurrent();
    void prepareTree(TreeInfo& info, int64_t* uiFrameInfo,
            int64_t syncQueued, RenderNode* target);
    void draw();
    void destroy(TreeObserver* observer);

    // IFrameCallback, Choreographer-driven frame callback entry point
    virtual void doFrame() override;
    void prepareAndDraw(RenderNode* node);

    void buildLayer(RenderNode* node, TreeObserver* observer);
    bool copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap);
    void markLayerInUse(RenderNode* node);

    void destroyHardwareResources(TreeObserver* observer);
    static void trimMemory(RenderThread& thread, int level);

    static void invokeFunctor(RenderThread& thread, Functor* functor);

    void runWithGlContext(RenderTask* task);

    Layer* createTextureLayer();

    ANDROID_API static void setTextureAtlas(RenderThread& thread,
            const sp<GraphicBuffer>& buffer, int64_t* map, size_t mapSize);

    void stopDrawing();
    void notifyFramePending();

    FrameInfoVisualizer& profiler() { return mProfiler; }

    void dumpFrames(int fd);
    void resetFrameStats();

    void setName(const std::string&& name) { mName = name; }
    const std::string& name() { return mName; }

    void serializeDisplayListTree();

    void addRenderNode(RenderNode* node, bool placeFront) {
        int pos = placeFront ? 0 : static_cast<int>(mRenderNodes.size());
        mRenderNodes.emplace(mRenderNodes.begin() + pos, node);
    }

    void removeRenderNode(RenderNode* node) {
        mRenderNodes.erase(std::remove(mRenderNodes.begin(), mRenderNodes.end(), node),
                mRenderNodes.end());
    }

    void setContentDrawBounds(int left, int top, int right, int bottom) {
        mContentDrawBounds.set(left, top, right, bottom);
    }

    RenderState& getRenderState() {
        return mRenderThread.renderState();
    }

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

private:
    friend class RegisterFrameCallbackTask;
    // TODO: Replace with something better for layer & other GL object
    // lifecycle tracking
    friend class android::uirenderer::RenderState;

    void setSurface(Surface* window);

    void freePrefetchedLayers(TreeObserver* observer);

    void waitOnFences();

    bool isSwapChainStuffed();

    EGLint mLastFrameWidth = 0;
    EGLint mLastFrameHeight = 0;

    RenderThread& mRenderThread;
    EglManager& mEglManager;
    sp<Surface> mNativeSurface;
    EGLSurface mEglSurface = EGL_NO_SURFACE;
    // stopped indicates the CanvasContext will reject actual redraw operations,
    // and defer repaint until it is un-stopped
    bool mStopped = false;
    // CanvasContext is dirty if it has received an update that it has not
    // painted onto its surface.
    bool mIsDirty = false;
    bool mBufferPreserved = false;
    SwapBehavior mSwapBehavior = kSwap_default;
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
#if HWUI_NEW_OPS
    BakedOpRenderer::LightInfo mLightInfo;
    FrameBuilder::LightGeometry mLightGeometry = { {0, 0, 0}, 0 };
#else
    OpenGLRenderer* mCanvas = nullptr;
#endif

    bool mHaveNewSurface = false;
    DamageAccumulator mDamageAccumulator;
    LayerUpdateQueue mLayerUpdateQueue;
    std::unique_ptr<AnimationContext> mAnimationContext;

    std::vector< sp<RenderNode> > mRenderNodes;

    FrameInfo* mCurrentFrameInfo = nullptr;
    // Ring buffer large enough for 2 seconds worth of frames
    RingBuffer<FrameInfo, 120> mFrames;
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

    std::vector< sp<FuncTask> > mFrameFences;
    sp<TaskProcessor<bool> > mFrameWorkProcessor;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* CANVASCONTEXT_H_ */
