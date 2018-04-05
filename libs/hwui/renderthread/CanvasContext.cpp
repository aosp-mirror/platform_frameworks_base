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
#include "Caches.h"
#include "EglManager.h"
#include "Frame.h"
#include "LayerUpdateQueue.h"
#include "OpenGLPipeline.h"
#include "Properties.h"
#include "RenderThread.h"
#include "hwui/Canvas.h"
#include "pipeline/skia/SkiaOpenGLPipeline.h"
#include "pipeline/skia/SkiaPipeline.h"
#include "pipeline/skia/SkiaVulkanPipeline.h"
#include "protos/hwui.pb.h"
#include "renderstate/RenderState.h"
#include "renderstate/Stencil.h"
#include "utils/GLUtils.h"
#include "utils/TimeUtils.h"

#include <cutils/properties.h>
#include <google/protobuf/io/zero_copy_stream_impl.h>
#include <private/hwui/DrawGlInfo.h>
#include <strings.h>

#include <fcntl.h>
#include <sys/stat.h>
#include <algorithm>

#include <cstdlib>

#define TRIM_MEMORY_COMPLETE 80
#define TRIM_MEMORY_UI_HIDDEN 20

#define ENABLE_RENDERNODE_SERIALIZATION false

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
        case RenderPipelineType::OpenGL:
            return new CanvasContext(thread, translucent, rootRenderNode, contextFactory,
                                     std::make_unique<OpenGLPipeline>(thread));
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

void CanvasContext::destroyLayer(RenderNode* node) {
    auto renderType = Properties::getRenderPipelineType();
    switch (renderType) {
        case RenderPipelineType::OpenGL:
            OpenGLPipeline::destroyLayer(node);
            break;
        case RenderPipelineType::SkiaGL:
        case RenderPipelineType::SkiaVulkan:
            skiapipeline::SkiaPipeline::destroyLayer(node);
            break;
        default:
            LOG_ALWAYS_FATAL("canvas context type %d not supported", (int32_t)renderType);
            break;
    }
}

void CanvasContext::invokeFunctor(const RenderThread& thread, Functor* functor) {
    ATRACE_CALL();
    auto renderType = Properties::getRenderPipelineType();
    switch (renderType) {
        case RenderPipelineType::OpenGL:
            OpenGLPipeline::invokeFunctor(thread, functor);
            break;
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
    auto renderType = Properties::getRenderPipelineType();
    switch (renderType) {
        case RenderPipelineType::OpenGL:
            OpenGLPipeline::prepareToDraw(thread, bitmap);
            break;
        case RenderPipelineType::SkiaGL:
        case RenderPipelineType::SkiaVulkan:
            skiapipeline::SkiaPipeline::prepareToDraw(thread, bitmap);
            break;
        default:
            LOG_ALWAYS_FATAL("canvas context type %d not supported", (int32_t)renderType);
            break;
    }
}

CanvasContext::CanvasContext(RenderThread& thread, bool translucent, RenderNode* rootRenderNode,
                             IContextFactory* contextFactory,
                             std::unique_ptr<IRenderPipeline> renderPipeline)
        : mRenderThread(thread)
        , mOpaque(!translucent)
        , mAnimationContext(contextFactory->createAnimationContext(mRenderThread.timeLord()))
        , mJankTracker(&thread.globalProfileData(), thread.mainDisplayInfo())
        , mProfiler(mJankTracker.frames())
        , mContentDrawBounds(0, 0, 0, 0)
        , mRenderPipeline(std::move(renderPipeline)) {
    rootRenderNode->makeRoot();
    mRenderNodes.emplace_back(rootRenderNode);
    mRenderThread.renderState().registerCanvasContext(this);
    mProfiler.setDensity(mRenderThread.mainDisplayInfo().density);
}

CanvasContext::~CanvasContext() {
    destroy();
    mRenderThread.renderState().unregisterCanvasContext(this);
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

    mNativeSurface = std::move(surface);

    ColorMode colorMode = mWideColorGamut ? ColorMode::WideColorGamut : ColorMode::Srgb;
    bool hasSurface = mRenderPipeline->setSurface(mNativeSurface.get(), mSwapBehavior, colorMode);

    mFrameNumber = -1;

    if (hasSurface) {
        mHaveNewSurface = true;
        mSwapHistory.clear();
        updateBufferCount();
    } else {
        mRenderThread.removeFrameCallback(this);
    }
}

void CanvasContext::setSwapBehavior(SwapBehavior swapBehavior) {
    mSwapBehavior = swapBehavior;
}

bool CanvasContext::pauseSurface() {
    return mRenderThread.removeFrameCallback(this);
}

void CanvasContext::setStopped(bool stopped) {
    if (mStopped != stopped) {
        mStopped = stopped;
        if (mStopped) {
            mRenderThread.removeFrameCallback(this);
            mRenderPipeline->onStop();
        } else if (mIsDirty && hasSurface()) {
            mRenderThread.postFrameCallback(this);
        }
    }
}

void CanvasContext::setup(float lightRadius, uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
    mLightGeometry.radius = lightRadius;
    mLightInfo.ambientShadowAlpha = ambientShadowAlpha;
    mLightInfo.spotShadowAlpha = spotShadowAlpha;
}

void CanvasContext::setLightCenter(const Vector3& lightCenter) {
    mLightGeometry.center = lightCenter;
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
        if (swapA.swapCompletedTime - swapB.swapCompletedTime > frameInterval * 3) {
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

    if (CC_UNLIKELY(!mNativeSurface.get())) {
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
        /* This logic exists to try and recover from a display latch miss, which essentially
         * results in the bufferqueue being double-buffered instead of triple-buffered.
         * SurfaceFlinger itself now tries to handle & recover from this situation, so this
         * logic should no longer be necessary. As it's occasionally triggering when
         * undesired disable it.
         * TODO: Remove this entirely if the results are solid.
        else if (vsyncDelta >= mRenderThread.timeLord().frameIntervalNanos() * 3 ||
                   (latestVsync - mLastDropVsync) < 500_ms) {
            // It's been several frame intervals, assume the buffer queue is fine
            // or the last drop was too recent
            info.out.canDrawThisFrame = true;
        } else {
            info.out.canDrawThisFrame = !isSwapChainStuffed();
            if (!info.out.canDrawThisFrame) {
                // dropping frame
                mLastDropVsync = mRenderThread.timeLord().latestVsync();
            }
        }
        */
    } else {
        info.out.canDrawThisFrame = true;
    }

    if (!info.out.canDrawThisFrame) {
        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
    }

    if (info.out.hasAnimations || !info.out.canDrawThisFrame) {
        if (CC_UNLIKELY(!Properties::enableRTAnimations)) {
            info.out.requiresUiRedraw = true;
        }
        if (!info.out.requiresUiRedraw) {
            // If animationsNeedsRedraw is set don't bother posting for an RT anim
            // as we will just end up fighting the UI thread.
            mRenderThread.postFrameCallback(this);
        }
    }
}

void CanvasContext::stopDrawing() {
    mRenderThread.removeFrameCallback(this);
    mAnimationContext->pauseAnimators();
}

void CanvasContext::notifyFramePending() {
    ATRACE_CALL();
    mRenderThread.pushBackFrameCallback(this);
}

void CanvasContext::draw() {
    SkRect dirty;
    mDamageAccumulator.finish(&dirty);

    // TODO: Re-enable after figuring out cause of b/22592975
    //    if (dirty.isEmpty() && Properties::skipEmptyFrames) {
    //        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
    //        return;
    //    }

    mCurrentFrameInfo->markIssueDrawCommandsStart();

    Frame frame = mRenderPipeline->getFrame();

    SkRect windowDirty = computeDirtyRect(frame, &dirty);

    bool drew = mRenderPipeline->draw(frame, windowDirty, dirty, mLightGeometry, &mLayerUpdateQueue,
                                      mContentDrawBounds, mOpaque, mWideColorGamut, mLightInfo,
                                      mRenderNodes, &(profiler()));

    waitOnFences();

    frame.setPresentTime(mCurrentFrameInfo->get(FrameInfoIndex::Vsync) +
                         (mRenderThread.timeLord().frameIntervalNanos() * (mRenderAheadDepth + 1)));

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

    mJankTracker.finishFrame(*mCurrentFrameInfo);
    if (CC_UNLIKELY(mFrameMetricsReporter.get() != nullptr)) {
        mFrameMetricsReporter->reportFrameMetrics(mCurrentFrameInfo->data());
    }

    GpuMemoryTracker::onFrameCompleted();
#ifdef BUGREPORT_FONT_CACHE_USAGE
    auto renderType = Properties::getRenderPipelineType();
    if (RenderPipelineType::OpenGL == renderType) {
        Caches& caches = Caches::getInstance();
        caches.fontRenderer.getFontRenderer().historyTracker().frameCompleted();
    }
#endif
}

// Called by choreographer to do an RT-driven animation
void CanvasContext::doFrame() {
    if (!mRenderPipeline->isSurfaceReady()) return;
    prepareAndDraw(nullptr);
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

    mRenderPipeline->renderLayers(mLightGeometry, &mLayerUpdateQueue, mOpaque, mWideColorGamut,
                                  mLightInfo);

    node->incStrong(nullptr);
    mPrefetchedLayers.insert(node);
}

bool CanvasContext::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) {
    return mRenderPipeline->copyLayerInto(layer, bitmap);
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
    auto renderType = Properties::getRenderPipelineType();
    switch (renderType) {
        case RenderPipelineType::OpenGL: {
            // No context means nothing to free
            if (!thread.eglManager().hasEglContext()) return;
            ATRACE_CALL();
            if (level >= TRIM_MEMORY_COMPLETE) {
                thread.renderState().flush(Caches::FlushMode::Full);
                thread.eglManager().destroy();
            } else if (level >= TRIM_MEMORY_UI_HIDDEN) {
                thread.renderState().flush(Caches::FlushMode::Moderate);
            }
            break;
        }
        case RenderPipelineType::SkiaGL:
        case RenderPipelineType::SkiaVulkan: {
            // No context means nothing to free
            if (!thread.getGrContext()) return;
            ATRACE_CALL();
            if (level >= TRIM_MEMORY_COMPLETE) {
                thread.cacheManager().trimMemory(CacheManager::TrimMemoryMode::Complete);
                thread.eglManager().destroy();
                thread.vulkanManager().destroy();
            } else if (level >= TRIM_MEMORY_UI_HIDDEN) {
                thread.cacheManager().trimMemory(CacheManager::TrimMemoryMode::UiHidden);
            }
            break;
        }
        default:
            LOG_ALWAYS_FATAL("canvas context type %d not supported", (int32_t)renderType);
            break;
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

void CanvasContext::serializeDisplayListTree() {
#if ENABLE_RENDERNODE_SERIALIZATION
    using namespace google::protobuf::io;
    char package[128];
    // Check whether tracing is enabled for this process.
    FILE* file = fopen("/proc/self/cmdline", "r");
    if (file) {
        if (!fgets(package, 128, file)) {
            ALOGE("Error reading cmdline: %s (%d)", strerror(errno), errno);
            fclose(file);
            return;
        }
        fclose(file);
    } else {
        ALOGE("Error opening /proc/self/cmdline: %s (%d)", strerror(errno), errno);
        return;
    }
    char path[1024];
    snprintf(path, 1024, "/data/data/%s/cache/rendertree_dump", package);
    int fd = open(path, O_CREAT | O_WRONLY, S_IRWXU | S_IRGRP | S_IROTH);
    if (fd == -1) {
        ALOGD("Failed to open '%s'", path);
        return;
    }
    proto::RenderNode tree;
    // TODO: Streaming writes?
    mRootRenderNode->copyTo(&tree);
    std::string data = tree.SerializeAsString();
    write(fd, data.c_str(), data.length());
    close(fd);
#endif
}

void CanvasContext::waitOnFences() {
    if (mFrameFences.size()) {
        ATRACE_CALL();
        for (auto& fence : mFrameFences) {
            fence->getResult();
        }
        mFrameFences.clear();
    }
}

class CanvasContext::FuncTaskProcessor : public TaskProcessor<bool> {
public:
    explicit FuncTaskProcessor(TaskManager* taskManager) : TaskProcessor<bool>(taskManager) {}

    virtual void onProcess(const sp<Task<bool> >& task) override {
        FuncTask* t = static_cast<FuncTask*>(task.get());
        t->func();
        task->setResult(true);
    }
};

void CanvasContext::enqueueFrameWork(std::function<void()>&& func) {
    if (!mFrameWorkProcessor.get()) {
        mFrameWorkProcessor = new FuncTaskProcessor(mRenderPipeline->getTaskManager());
    }
    sp<FuncTask> task(new FuncTask());
    task->func = func;
    mFrameFences.push_back(task);
    mFrameWorkProcessor->add(task);
}

int64_t CanvasContext::getFrameNumber() {
    // mFrameNumber is reset to -1 when the surface changes or we swap buffers
    if (mFrameNumber == -1 && mNativeSurface.get()) {
        mFrameNumber = static_cast<int64_t>(mNativeSurface->getNextFrameNumber());
    }
    return mFrameNumber;
}

void overrideBufferCount(const sp<Surface>& surface, int bufferCount) {
    struct SurfaceExposer : Surface {
        using Surface::setBufferCount;
    };
    // Protected is just a sign, not a cop
    ((*surface.get()).*&SurfaceExposer::setBufferCount)(bufferCount);
}

void CanvasContext::updateBufferCount() {
    overrideBufferCount(mNativeSurface, 3 + mRenderAheadDepth);
}

void CanvasContext::setRenderAheadDepth(int renderAhead) {
    if (renderAhead < 0 || renderAhead > 2 || renderAhead == mRenderAheadDepth) {
        return;
    }
    mRenderAheadDepth = renderAhead;
    updateBufferCount();
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
