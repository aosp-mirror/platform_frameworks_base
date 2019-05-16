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

#include "CanvasContext.h"
#include <GpuMemoryTracker.h>

#include "../Properties.h"
#include "AnimationContext.h"
#include "EglManager.h"
#include "Frame.h"
#include "LayerUpdateQueue.h"
#include "Properties.h"
#include "RenderThread.h"
#include "hwui/Canvas.h"
#include "pipeline/skia/SkiaOpenGLPipeline.h"
#include "pipeline/skia/SkiaPipeline.h"
#include "pipeline/skia/SkiaVulkanPipeline.h"
#include "thread/CommonPool.h"
#include "utils/GLUtils.h"
#include "utils/TimeUtils.h"
#include "utils/TraceUtils.h"

#include <cutils/properties.h>
#include <private/hwui/DrawGlInfo.h>
#include <strings.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <algorithm>

#include <cstdint>
#include <cstdlib>
#include <functional>

#define TRIM_MEMORY_COMPLETE 80
#define TRIM_MEMORY_UI_HIDDEN 20

#define LOG_FRAMETIME_MMA 0

#if LOG_FRAMETIME_MMA
static float sBenchMma = 0;
static int sFrameCount = 0;
static const float NANOS_PER_MILLIS_F = 1000000.0f;
#endif

namespace android {
namespace uirenderer {
namespace renderthread {

CanvasContext* CanvasContext::create(RenderThread& thread, bool translucent,
                                     RenderNode* rootRenderNode, IContextFactory* contextFactory) {
    auto renderType = Properties::getRenderPipelineType();

    switch (renderType) {
        case RenderPipelineType::SkiaGL:
            return new CanvasContext(thread, translucent, rootRenderNode, contextFactory,
                                     std::make_unique<skiapipeline::SkiaOpenGLPipeline>(thread));
        case RenderPipelineType::SkiaVulkan:
            return new CanvasContext(thread, translucent, rootRenderNode, contextFactory,
                                     std::make_unique<skiapipeline::SkiaVulkanPipeline>(thread));
        default:
            LOG_ALWAYS_FATAL("canvas context type %d not supported", (int32_t)renderType);
            break;
    }
    return nullptr;
}

void CanvasContext::invokeFunctor(const RenderThread& thread, Functor* functor) {
    ATRACE_CALL();
    auto renderType = Properties::getRenderPipelineType();
    switch (renderType) {
        case RenderPipelineType::SkiaGL:
            skiapipeline::SkiaOpenGLPipeline::invokeFunctor(thread, functor);
            break;
        case RenderPipelineType::SkiaVulkan:
            skiapipeline::SkiaVulkanPipeline::invokeFunctor(thread, functor);
            break;
        default:
            LOG_ALWAYS_FATAL("canvas context type %d not supported", (int32_t)renderType);
            break;
    }
}

void CanvasContext::prepareToDraw(const RenderThread& thread, Bitmap* bitmap) {
    skiapipeline::SkiaPipeline::prepareToDraw(thread, bitmap);
}

CanvasContext::CanvasContext(RenderThread& thread, bool translucent, RenderNode* rootRenderNode,
                             IContextFactory* contextFactory,
                             std::unique_ptr<IRenderPipeline> renderPipeline)
        : mRenderThread(thread)
        , mGenerationID(0)
        , mOpaque(!translucent)
        , mAnimationContext(contextFactory->createAnimationContext(mRenderThread.timeLord()))
        , mJankTracker(&thread.globalProfileData(), DeviceInfo::get()->displayInfo())
        , mProfiler(mJankTracker.frames(), thread.timeLord().frameIntervalNanos())
        , mContentDrawBounds(0, 0, 0, 0)
        , mRenderPipeline(std::move(renderPipeline)) {
    rootRenderNode->makeRoot();
    mRenderNodes.emplace_back(rootRenderNode);
    mProfiler.setDensity(DeviceInfo::get()->displayInfo().density);
    setRenderAheadDepth(Properties::defaultRenderAhead);
}

CanvasContext::~CanvasContext() {
    destroy();
    for (auto& node : mRenderNodes) {
        node->clearRoot();
    }
    mRenderNodes.clear();
}

void CanvasContext::addRenderNode(RenderNode* node, bool placeFront) {
    int pos = placeFront ? 0 : static_cast<int>(mRenderNodes.size());
    node->makeRoot();
    mRenderNodes.emplace(mRenderNodes.begin() + pos, node);
}

void CanvasContext::removeRenderNode(RenderNode* node) {
    node->clearRoot();
    mRenderNodes.erase(std::remove(mRenderNodes.begin(), mRenderNodes.end(), node),
                       mRenderNodes.end());
}

void CanvasContext::destroy() {
    stopDrawing();
    setSurface(nullptr);
    freePrefetchedLayers();
    destroyHardwareResources();
    mAnimationContext->destroy();
}

void CanvasContext::setSurface(sp<Surface>&& surface) {
    ATRACE_CALL();

    if (surface) {
        mNativeSurface = new ReliableSurface{std::move(surface)};
        mNativeSurface->setDequeueTimeout(500_ms);
    } else {
        mNativeSurface = nullptr;
    }

    if (mRenderAheadDepth == 0 && DeviceInfo::get()->getMaxRefreshRate() > 66.6f) {
        mFixedRenderAhead = false;
        mRenderAheadCapacity = 1;
    } else {
        mFixedRenderAhead = true;
        mRenderAheadCapacity = mRenderAheadDepth;
    }

    ColorMode colorMode = mWideColorGamut ? ColorMode::WideColorGamut : ColorMode::SRGB;
    bool hasSurface = mRenderPipeline->setSurface(mNativeSurface.get(), mSwapBehavior, colorMode,
                                                  mRenderAheadCapacity);

    mFrameNumber = -1;

    if (hasSurface) {
        mHaveNewSurface = true;
        mSwapHistory.clear();
    } else {
        mRenderThread.removeFrameCallback(this);
        mGenerationID++;
    }
}

void CanvasContext::setSwapBehavior(SwapBehavior swapBehavior) {
    mSwapBehavior = swapBehavior;
}

bool CanvasContext::pauseSurface() {
    mGenerationID++;
    return mRenderThread.removeFrameCallback(this);
}

void CanvasContext::setStopped(bool stopped) {
    if (mStopped != stopped) {
        mStopped = stopped;
        if (mStopped) {
            mGenerationID++;
            mRenderThread.removeFrameCallback(this);
            mRenderPipeline->onStop();
        } else if (mIsDirty && hasSurface()) {
            mRenderThread.postFrameCallback(this);
        }
    }
}

void CanvasContext::allocateBuffers() {
    if (mNativeSurface) {
        mNativeSurface->allocateBuffers();
    }
}

void CanvasContext::setLightAlpha(uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
    mLightInfo.ambientShadowAlpha = ambientShadowAlpha;
    mLightInfo.spotShadowAlpha = spotShadowAlpha;
}

void CanvasContext::setLightGeometry(const Vector3& lightCenter, float lightRadius) {
    mLightGeometry.center = lightCenter;
    mLightGeometry.radius = lightRadius;
}

void CanvasContext::setOpaque(bool opaque) {
    mOpaque = opaque;
}

void CanvasContext::setWideGamut(bool wideGamut) {
    mWideColorGamut = wideGamut;
}

bool CanvasContext::makeCurrent() {
    if (mStopped) return false;

    auto result = mRenderPipeline->makeCurrent();
    switch (result) {
        case MakeCurrentResult::AlreadyCurrent:
            return true;
        case MakeCurrentResult::Failed:
            mHaveNewSurface = true;
            setSurface(nullptr);
            return false;
        case MakeCurrentResult::Succeeded:
            mHaveNewSurface = true;
            return true;
        default:
            LOG_ALWAYS_FATAL("unexpected result %d from IRenderPipeline::makeCurrent",
                             (int32_t)result);
    }

    return true;
}

static bool wasSkipped(FrameInfo* info) {
    return info && ((*info)[FrameInfoIndex::Flags] & FrameInfoFlags::SkippedFrame);
}

bool CanvasContext::isSwapChainStuffed() {
    static const auto SLOW_THRESHOLD = 6_ms;

    if (mSwapHistory.size() != mSwapHistory.capacity()) {
        // We want at least 3 frames of history before attempting to
        // guess if the queue is stuffed
        return false;
    }
    nsecs_t frameInterval = mRenderThread.timeLord().frameIntervalNanos();
    auto& swapA = mSwapHistory[0];

    // Was there a happy queue & dequeue time? If so, don't
    // consider it stuffed
    if (swapA.dequeueDuration < SLOW_THRESHOLD && swapA.queueDuration < SLOW_THRESHOLD) {
        return false;
    }

    for (size_t i = 1; i < mSwapHistory.size(); i++) {
        auto& swapB = mSwapHistory[i];

        // If there's a multi-frameInterval gap we effectively already dropped a frame,
        // so consider the queue healthy.
        if (std::abs(swapA.swapCompletedTime - swapB.swapCompletedTime) > frameInterval * 3) {
            return false;
        }

        // Was there a happy queue & dequeue time? If so, don't
        // consider it stuffed
        if (swapB.dequeueDuration < SLOW_THRESHOLD && swapB.queueDuration < SLOW_THRESHOLD) {
            return false;
        }

        swapA = swapB;
    }

    // All signs point to a stuffed swap chain
    ATRACE_NAME("swap chain stuffed");
    return true;
}

void CanvasContext::prepareTree(TreeInfo& info, int64_t* uiFrameInfo, int64_t syncQueued,
                                RenderNode* target) {
    mRenderThread.removeFrameCallback(this);

    // If the previous frame was dropped we don't need to hold onto it, so
    // just keep using the previous frame's structure instead
    if (!wasSkipped(mCurrentFrameInfo)) {
        mCurrentFrameInfo = mJankTracker.startFrame();
    }
    mCurrentFrameInfo->importUiThreadInfo(uiFrameInfo);
    mCurrentFrameInfo->set(FrameInfoIndex::SyncQueued) = syncQueued;
    mCurrentFrameInfo->markSyncStart();

    info.damageAccumulator = &mDamageAccumulator;
    info.layerUpdateQueue = &mLayerUpdateQueue;
    info.out.canDrawThisFrame = true;

    mAnimationContext->startFrame(info.mode);
    mRenderPipeline->onPrepareTree();
    for (const sp<RenderNode>& node : mRenderNodes) {
        // Only the primary target node will be drawn full - all other nodes would get drawn in
        // real time mode. In case of a window, the primary node is the window content and the other
        // node(s) are non client / filler nodes.
        info.mode = (node.get() == target ? TreeInfo::MODE_FULL : TreeInfo::MODE_RT_ONLY);
        node->prepareTree(info);
        GL_CHECKPOINT(MODERATE);
    }
    mAnimationContext->runRemainingAnimations(info);
    GL_CHECKPOINT(MODERATE);

    freePrefetchedLayers();
    GL_CHECKPOINT(MODERATE);

    mIsDirty = true;

    if (CC_UNLIKELY(!hasSurface())) {
        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
        info.out.canDrawThisFrame = false;
        return;
    }

    if (CC_LIKELY(mSwapHistory.size() && !Properties::forceDrawFrame)) {
        nsecs_t latestVsync = mRenderThread.timeLord().latestVsync();
        SwapHistory& lastSwap = mSwapHistory.back();
        nsecs_t vsyncDelta = std::abs(lastSwap.vsyncTime - latestVsync);
        // The slight fudge-factor is to deal with cases where
        // the vsync was estimated due to being slow handling the signal.
        // See the logic in TimeLord#computeFrameTimeNanos or in
        // Choreographer.java for details on when this happens
        if (vsyncDelta < 2_ms) {
            // Already drew for this vsync pulse, UI draw request missed
            // the deadline for RT animations
            info.out.canDrawThisFrame = false;
        }
    } else {
        info.out.canDrawThisFrame = true;
    }

    // TODO: Do we need to abort out if the backdrop is added but not ready? Should that even
    // be an allowable combination?
    if (mRenderNodes.size() > 2 && !mRenderNodes[1]->isRenderable()) {
        info.out.canDrawThisFrame = false;
    }

    if (info.out.canDrawThisFrame) {
        int err = mNativeSurface->reserveNext();
        if (err != OK) {
            mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
            info.out.canDrawThisFrame = false;
            ALOGW("reserveNext failed, error = %d (%s)", err, strerror(-err));
            if (err != TIMED_OUT) {
                // A timed out surface can still recover, but assume others are permanently dead.
                setSurface(nullptr);
                return;
            }
        }
    } else {
        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
    }

    bool postedFrameCallback = false;
    if (info.out.hasAnimations || !info.out.canDrawThisFrame) {
        if (CC_UNLIKELY(!Properties::enableRTAnimations)) {
            info.out.requiresUiRedraw = true;
        }
        if (!info.out.requiresUiRedraw) {
            // If animationsNeedsRedraw is set don't bother posting for an RT anim
            // as we will just end up fighting the UI thread.
            mRenderThread.postFrameCallback(this);
            postedFrameCallback = true;
        }
    }

    if (!postedFrameCallback &&
        info.out.animatedImageDelay != TreeInfo::Out::kNoAnimatedImageDelay) {
        // Subtract the time of one frame so it can be displayed on time.
        const nsecs_t kFrameTime = mRenderThread.timeLord().frameIntervalNanos();
        if (info.out.animatedImageDelay <= kFrameTime) {
            mRenderThread.postFrameCallback(this);
        } else {
            const auto delay = info.out.animatedImageDelay - kFrameTime;
            int genId = mGenerationID;
            mRenderThread.queue().postDelayed(delay, [this, genId]() {
                if (mGenerationID == genId) {
                    mRenderThread.postFrameCallback(this);
                }
            });
        }
    }
}

void CanvasContext::stopDrawing() {
    mRenderThread.removeFrameCallback(this);
    mAnimationContext->pauseAnimators();
    mGenerationID++;
}

void CanvasContext::notifyFramePending() {
    ATRACE_CALL();
    mRenderThread.pushBackFrameCallback(this);
}

void CanvasContext::setPresentTime() {
    int64_t presentTime = NATIVE_WINDOW_TIMESTAMP_AUTO;
    int renderAhead = 0;
    const auto frameIntervalNanos = mRenderThread.timeLord().frameIntervalNanos();
    if (mFixedRenderAhead) {
        renderAhead = std::min(mRenderAheadDepth, mRenderAheadCapacity);
    } else if (frameIntervalNanos < 15_ms) {
        renderAhead = std::min(1, static_cast<int>(mRenderAheadCapacity));
    }

    if (renderAhead) {
        presentTime = mCurrentFrameInfo->get(FrameInfoIndex::Vsync) +
                (frameIntervalNanos * (renderAhead + 1));
    }
    native_window_set_buffers_timestamp(mNativeSurface.get(), presentTime);
}

void CanvasContext::draw() {
    SkRect dirty;
    mDamageAccumulator.finish(&dirty);

    if (dirty.isEmpty() && Properties::skipEmptyFrames && !surfaceRequiresRedraw()) {
        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
        return;
    }

    mCurrentFrameInfo->markIssueDrawCommandsStart();

    Frame frame = mRenderPipeline->getFrame();
    setPresentTime();

    SkRect windowDirty = computeDirtyRect(frame, &dirty);

    bool drew = mRenderPipeline->draw(frame, windowDirty, dirty, mLightGeometry, &mLayerUpdateQueue,
                                      mContentDrawBounds, mOpaque, mLightInfo, mRenderNodes,
                                      &(profiler()));

    int64_t frameCompleteNr = mFrameCompleteCallbacks.size() ? getFrameNumber() : -1;

    waitOnFences();

    bool requireSwap = false;
    bool didSwap =
            mRenderPipeline->swapBuffers(frame, drew, windowDirty, mCurrentFrameInfo, &requireSwap);

    mIsDirty = false;

    if (requireSwap) {
        if (!didSwap) {  // some error happened
            setSurface(nullptr);
        }
        SwapHistory& swap = mSwapHistory.next();
        swap.damage = windowDirty;
        swap.swapCompletedTime = systemTime(CLOCK_MONOTONIC);
        swap.vsyncTime = mRenderThread.timeLord().latestVsync();
        if (mNativeSurface.get()) {
            int durationUs;
            nsecs_t dequeueStart = mNativeSurface->getLastDequeueStartTime();
            if (dequeueStart < mCurrentFrameInfo->get(FrameInfoIndex::SyncStart)) {
                // Ignoring dequeue duration as it happened prior to frame render start
                // and thus is not part of the frame.
                swap.dequeueDuration = 0;
            } else {
                mNativeSurface->query(NATIVE_WINDOW_LAST_DEQUEUE_DURATION, &durationUs);
                swap.dequeueDuration = us2ns(durationUs);
            }
            mNativeSurface->query(NATIVE_WINDOW_LAST_QUEUE_DURATION, &durationUs);
            swap.queueDuration = us2ns(durationUs);
        } else {
            swap.dequeueDuration = 0;
            swap.queueDuration = 0;
        }
        mCurrentFrameInfo->set(FrameInfoIndex::DequeueBufferDuration) = swap.dequeueDuration;
        mCurrentFrameInfo->set(FrameInfoIndex::QueueBufferDuration) = swap.queueDuration;
        mHaveNewSurface = false;
        mFrameNumber = -1;
    } else {
        mCurrentFrameInfo->set(FrameInfoIndex::DequeueBufferDuration) = 0;
        mCurrentFrameInfo->set(FrameInfoIndex::QueueBufferDuration) = 0;
    }

    // TODO: Use a fence for real completion?
    mCurrentFrameInfo->markFrameCompleted();

#if LOG_FRAMETIME_MMA
    float thisFrame = mCurrentFrameInfo->duration(FrameInfoIndex::IssueDrawCommandsStart,
                                                  FrameInfoIndex::FrameCompleted) /
                      NANOS_PER_MILLIS_F;
    if (sFrameCount) {
        sBenchMma = ((9 * sBenchMma) + thisFrame) / 10;
    } else {
        sBenchMma = thisFrame;
    }
    if (++sFrameCount == 10) {
        sFrameCount = 1;
        ALOGD("Average frame time: %.4f", sBenchMma);
    }
#endif

    if (didSwap) {
        for (auto& func : mFrameCompleteCallbacks) {
            std::invoke(func, frameCompleteNr);
        }
        mFrameCompleteCallbacks.clear();
    }

    mJankTracker.finishFrame(*mCurrentFrameInfo);
    if (CC_UNLIKELY(mFrameMetricsReporter.get() != nullptr)) {
        mFrameMetricsReporter->reportFrameMetrics(mCurrentFrameInfo->data());
    }

    GpuMemoryTracker::onFrameCompleted();
}

// Called by choreographer to do an RT-driven animation
void CanvasContext::doFrame() {
    if (!mRenderPipeline->isSurfaceReady()) return;
    prepareAndDraw(nullptr);
}

SkISize CanvasContext::getNextFrameSize() const {
    ReliableSurface* surface = mNativeSurface.get();
    if (surface) {
        SkISize size;
        surface->query(NATIVE_WINDOW_WIDTH, &size.fWidth);
        surface->query(NATIVE_WINDOW_HEIGHT, &size.fHeight);
        return size;
    }
    return {INT32_MAX, INT32_MAX};
}

void CanvasContext::prepareAndDraw(RenderNode* node) {
    ATRACE_CALL();

    nsecs_t vsync = mRenderThread.timeLord().computeFrameTimeNanos();
    int64_t frameInfo[UI_THREAD_FRAME_INFO_SIZE];
    UiFrameInfoBuilder(frameInfo).addFlag(FrameInfoFlags::RTAnimation).setVsync(vsync, vsync);

    TreeInfo info(TreeInfo::MODE_RT_ONLY, *this);
    prepareTree(info, frameInfo, systemTime(CLOCK_MONOTONIC), node);
    if (info.out.canDrawThisFrame) {
        draw();
    } else {
        // wait on fences so tasks don't overlap next frame
        waitOnFences();
    }
}

void CanvasContext::markLayerInUse(RenderNode* node) {
    if (mPrefetchedLayers.erase(node)) {
        node->decStrong(nullptr);
    }
}

void CanvasContext::freePrefetchedLayers() {
    if (mPrefetchedLayers.size()) {
        for (auto& node : mPrefetchedLayers) {
            ALOGW("Incorrectly called buildLayer on View: %s, destroying layer...",
                  node->getName());
            node->destroyLayers();
            node->decStrong(nullptr);
        }
        mPrefetchedLayers.clear();
    }
}

void CanvasContext::buildLayer(RenderNode* node) {
    ATRACE_CALL();
    if (!mRenderPipeline->isContextReady()) return;

    // buildLayer() will leave the tree in an unknown state, so we must stop drawing
    stopDrawing();

    TreeInfo info(TreeInfo::MODE_FULL, *this);
    info.damageAccumulator = &mDamageAccumulator;
    info.layerUpdateQueue = &mLayerUpdateQueue;
    info.runAnimations = false;
    node->prepareTree(info);
    SkRect ignore;
    mDamageAccumulator.finish(&ignore);
    // Tickle the GENERIC property on node to mark it as dirty for damaging
    // purposes when the frame is actually drawn
    node->setPropertyFieldsDirty(RenderNode::GENERIC);

    mRenderPipeline->renderLayers(mLightGeometry, &mLayerUpdateQueue, mOpaque, mLightInfo);

    node->incStrong(nullptr);
    mPrefetchedLayers.insert(node);
}

void CanvasContext::destroyHardwareResources() {
    stopDrawing();
    if (mRenderPipeline->isContextReady()) {
        freePrefetchedLayers();
        for (const sp<RenderNode>& node : mRenderNodes) {
            node->destroyHardwareResources();
        }
        mRenderPipeline->onDestroyHardwareResources();
    }
}

void CanvasContext::trimMemory(RenderThread& thread, int level) {
    ATRACE_CALL();
    if (!thread.getGrContext()) return;
    ATRACE_CALL();
    if (level >= TRIM_MEMORY_COMPLETE) {
        thread.cacheManager().trimMemory(CacheManager::TrimMemoryMode::Complete);
        thread.destroyRenderingContext();
    } else if (level >= TRIM_MEMORY_UI_HIDDEN) {
        thread.cacheManager().trimMemory(CacheManager::TrimMemoryMode::UiHidden);
    }
}

DeferredLayerUpdater* CanvasContext::createTextureLayer() {
    return mRenderPipeline->createTextureLayer();
}

void CanvasContext::dumpFrames(int fd) {
    mJankTracker.dumpStats(fd);
    mJankTracker.dumpFrames(fd);
}

void CanvasContext::resetFrameStats() {
    mJankTracker.reset();
}

void CanvasContext::setName(const std::string&& name) {
    mJankTracker.setDescription(JankTrackerType::Window, std::move(name));
}

void CanvasContext::waitOnFences() {
    if (mFrameFences.size()) {
        ATRACE_CALL();
        for (auto& fence : mFrameFences) {
            fence.get();
        }
        mFrameFences.clear();
    }
}

void CanvasContext::enqueueFrameWork(std::function<void()>&& func) {
    mFrameFences.push_back(CommonPool::async(std::move(func)));
}

int64_t CanvasContext::getFrameNumber() {
    // mFrameNumber is reset to -1 when the surface changes or we swap buffers
    if (mFrameNumber == -1 && mNativeSurface.get()) {
        mFrameNumber = static_cast<int64_t>(mNativeSurface->getNextFrameNumber());
    }
    return mFrameNumber;
}

bool CanvasContext::surfaceRequiresRedraw() {
    if (!mNativeSurface) return false;
    if (mHaveNewSurface) return true;

    int width = -1;
    int height = -1;
    ReliableSurface* surface = mNativeSurface.get();
    surface->query(NATIVE_WINDOW_WIDTH, &width);
    surface->query(NATIVE_WINDOW_HEIGHT, &height);

    return width == mLastFrameWidth && height == mLastFrameHeight;
}

void CanvasContext::setRenderAheadDepth(int renderAhead) {
    if (renderAhead > 2 || renderAhead < 0 || mNativeSurface) {
        return;
    }
    mFixedRenderAhead = true;
    mRenderAheadDepth = static_cast<uint32_t>(renderAhead);
}

SkRect CanvasContext::computeDirtyRect(const Frame& frame, SkRect* dirty) {
    if (frame.width() != mLastFrameWidth || frame.height() != mLastFrameHeight) {
        // can't rely on prior content of window if viewport size changes
        dirty->setEmpty();
        mLastFrameWidth = frame.width();
        mLastFrameHeight = frame.height();
    } else if (mHaveNewSurface || frame.bufferAge() == 0) {
        // New surface needs a full draw
        dirty->setEmpty();
    } else {
        if (!dirty->isEmpty() && !dirty->intersect(0, 0, frame.width(), frame.height())) {
            ALOGW("Dirty " RECT_STRING " doesn't intersect with 0 0 %d %d ?", SK_RECT_ARGS(*dirty),
                  frame.width(), frame.height());
            dirty->setEmpty();
        }
        profiler().unionDirty(dirty);
    }

    if (dirty->isEmpty()) {
        dirty->set(0, 0, frame.width(), frame.height());
    }

    // At this point dirty is the area of the window to update. However,
    // the area of the frame we need to repaint is potentially different, so
    // stash the screen area for later
    SkRect windowDirty(*dirty);

    // If the buffer age is 0 we do a full-screen repaint (handled above)
    // If the buffer age is 1 the buffer contents are the same as they were
    // last frame so there's nothing to union() against
    // Therefore we only care about the > 1 case.
    if (frame.bufferAge() > 1) {
        if (frame.bufferAge() > (int)mSwapHistory.size()) {
            // We don't have enough history to handle this old of a buffer
            // Just do a full-draw
            dirty->set(0, 0, frame.width(), frame.height());
        } else {
            // At this point we haven't yet added the latest frame
            // to the damage history (happens below)
            // So we need to damage
            for (int i = mSwapHistory.size() - 1;
                 i > ((int)mSwapHistory.size()) - frame.bufferAge(); i--) {
                dirty->join(mSwapHistory[i].damage);
            }
        }
    }

    return windowDirty;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
