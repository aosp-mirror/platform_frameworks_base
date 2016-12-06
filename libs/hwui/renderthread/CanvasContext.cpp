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

#include <GpuMemoryTracker.h>
#include "CanvasContext.h"

#include "AnimationContext.h"
#include "Caches.h"
#include "DeferredLayerUpdater.h"
#include "EglManager.h"
#include "LayerUpdateQueue.h"
#include "LayerRenderer.h"
#include "OpenGLRenderer.h"
#include "Properties.h"
#include "RenderThread.h"
#include "hwui/Canvas.h"
#include "renderstate/RenderState.h"
#include "renderstate/Stencil.h"
#include "protos/hwui.pb.h"
#include "utils/GLUtils.h"
#include "utils/TimeUtils.h"

#include <cutils/properties.h>
#include <google/protobuf/io/zero_copy_stream_impl.h>
#include <private/hwui/DrawGlInfo.h>
#include <strings.h>

#include <algorithm>
#include <fcntl.h>
#include <sys/stat.h>

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

CanvasContext::CanvasContext(RenderThread& thread, bool translucent,
        RenderNode* rootRenderNode, IContextFactory* contextFactory)
        : mRenderThread(thread)
        , mEglManager(thread.eglManager())
        , mOpaque(!translucent)
        , mAnimationContext(contextFactory->createAnimationContext(mRenderThread.timeLord()))
        , mJankTracker(thread.mainDisplayInfo())
        , mProfiler(mFrames)
        , mContentDrawBounds(0, 0, 0, 0) {
    mRenderNodes.emplace_back(rootRenderNode);
    mRenderThread.renderState().registerCanvasContext(this);
    mProfiler.setDensity(mRenderThread.mainDisplayInfo().density);
}

CanvasContext::~CanvasContext() {
    destroy(nullptr);
    mRenderThread.renderState().unregisterCanvasContext(this);
}

void CanvasContext::destroy(TreeObserver* observer) {
    stopDrawing();
    setSurface(nullptr);
    freePrefetchedLayers(observer);
    destroyHardwareResources(observer);
    mAnimationContext->destroy();
#if !HWUI_NEW_OPS
    if (mCanvas) {
        delete mCanvas;
        mCanvas = nullptr;
    }
#endif
}

void CanvasContext::setSurface(Surface* surface) {
    ATRACE_CALL();

    mNativeSurface = surface;

    if (mEglSurface != EGL_NO_SURFACE) {
        mEglManager.destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (surface) {
        mEglSurface = mEglManager.createSurface(surface);
    }

    mFrameNumber = -1;

    if (mEglSurface != EGL_NO_SURFACE) {
        const bool preserveBuffer = (mSwapBehavior != kSwap_discardBuffer);
        mBufferPreserved = mEglManager.setPreserveBuffer(mEglSurface, preserveBuffer);
        mHaveNewSurface = true;
        mSwapHistory.clear();
    } else {
        mRenderThread.removeFrameCallback(this);
    }
}

void CanvasContext::setSwapBehavior(SwapBehavior swapBehavior) {
    mSwapBehavior = swapBehavior;
}

void CanvasContext::initialize(Surface* surface) {
    setSurface(surface);
#if !HWUI_NEW_OPS
    if (mCanvas) return;
    mCanvas = new OpenGLRenderer(mRenderThread.renderState());
    mCanvas->initProperties();
#endif
}

void CanvasContext::updateSurface(Surface* surface) {
    setSurface(surface);
}

bool CanvasContext::pauseSurface(Surface* surface) {
    return mRenderThread.removeFrameCallback(this);
}

void CanvasContext::setStopped(bool stopped) {
    if (mStopped != stopped) {
        mStopped = stopped;
        if (mStopped) {
            mRenderThread.removeFrameCallback(this);
            if (mEglManager.isCurrent(mEglSurface)) {
                mEglManager.makeCurrent(EGL_NO_SURFACE);
            }
        } else if (mIsDirty && hasSurface()) {
            mRenderThread.postFrameCallback(this);
        }
    }
}

// TODO: don't pass viewport size, it's automatic via EGL
void CanvasContext::setup(int width, int height, float lightRadius,
        uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
#if HWUI_NEW_OPS
    mLightGeometry.radius = lightRadius;
    mLightInfo.ambientShadowAlpha = ambientShadowAlpha;
    mLightInfo.spotShadowAlpha = spotShadowAlpha;
#else
    if (!mCanvas) return;
    mCanvas->initLight(lightRadius, ambientShadowAlpha, spotShadowAlpha);
#endif
}

void CanvasContext::setLightCenter(const Vector3& lightCenter) {
#if HWUI_NEW_OPS
    mLightGeometry.center = lightCenter;
#else
    if (!mCanvas) return;
    mCanvas->setLightCenter(lightCenter);
#endif
}

void CanvasContext::setOpaque(bool opaque) {
    mOpaque = opaque;
}

bool CanvasContext::makeCurrent() {
    if (mStopped) return false;

    // TODO: Figure out why this workaround is needed, see b/13913604
    // In the meantime this matches the behavior of GLRenderer, so it is not a regression
    EGLint error = 0;
    mHaveNewSurface |= mEglManager.makeCurrent(mEglSurface, &error);
    if (error) {
        setSurface(nullptr);
    }
    return !error;
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
    if (swapA.dequeueDuration < SLOW_THRESHOLD
            && swapA.queueDuration < SLOW_THRESHOLD) {
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
        if (swapB.dequeueDuration < SLOW_THRESHOLD
                && swapB.queueDuration < SLOW_THRESHOLD) {
            return false;
        }

        swapA = swapB;
    }

    // All signs point to a stuffed swap chain
    ATRACE_NAME("swap chain stuffed");
    return true;
}

void CanvasContext::prepareTree(TreeInfo& info, int64_t* uiFrameInfo,
        int64_t syncQueued, RenderNode* target) {
    mRenderThread.removeFrameCallback(this);

    // If the previous frame was dropped we don't need to hold onto it, so
    // just keep using the previous frame's structure instead
    if (!wasSkipped(mCurrentFrameInfo)) {
        mCurrentFrameInfo = &mFrames.next();
    }
    mCurrentFrameInfo->importUiThreadInfo(uiFrameInfo);
    mCurrentFrameInfo->set(FrameInfoIndex::SyncQueued) = syncQueued;
    mCurrentFrameInfo->markSyncStart();

    info.damageAccumulator = &mDamageAccumulator;
#if HWUI_NEW_OPS
    info.layerUpdateQueue = &mLayerUpdateQueue;
#else
    info.renderer = mCanvas;
#endif

    mAnimationContext->startFrame(info.mode);
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

    freePrefetchedLayers(info.observer);
    GL_CHECKPOINT(MODERATE);

    mIsDirty = true;

    if (CC_UNLIKELY(!mNativeSurface.get())) {
        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
        info.out.canDrawThisFrame = false;
        return;
    }

    if (CC_LIKELY(mSwapHistory.size())) {
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
        } else if (vsyncDelta >= mRenderThread.timeLord().frameIntervalNanos() * 3
                || (latestVsync - mLastDropVsync) < 500_ms) {
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
    } else {
        info.out.canDrawThisFrame = true;
    }

    if (!info.out.canDrawThisFrame) {
        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
    }

    if (info.out.hasAnimations || !info.out.canDrawThisFrame) {
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
#if !HWUI_NEW_OPS
    LOG_ALWAYS_FATAL_IF(!mCanvas || mEglSurface == EGL_NO_SURFACE,
            "drawRenderNode called on a context with no canvas or surface!");
#endif

    SkRect dirty;
    mDamageAccumulator.finish(&dirty);

    // TODO: Re-enable after figuring out cause of b/22592975
//    if (dirty.isEmpty() && Properties::skipEmptyFrames) {
//        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
//        return;
//    }

    mCurrentFrameInfo->markIssueDrawCommandsStart();

    Frame frame = mEglManager.beginFrame(mEglSurface);

    if (frame.width() != mLastFrameWidth || frame.height() != mLastFrameHeight) {
        // can't rely on prior content of window if viewport size changes
        dirty.setEmpty();
        mLastFrameWidth = frame.width();
        mLastFrameHeight = frame.height();
    } else if (mHaveNewSurface || frame.bufferAge() == 0) {
        // New surface needs a full draw
        dirty.setEmpty();
    } else {
        if (!dirty.isEmpty() && !dirty.intersect(0, 0, frame.width(), frame.height())) {
            ALOGW("Dirty " RECT_STRING " doesn't intersect with 0 0 %d %d ?",
                    SK_RECT_ARGS(dirty), frame.width(), frame.height());
            dirty.setEmpty();
        }
        profiler().unionDirty(&dirty);
    }

    if (dirty.isEmpty()) {
        dirty.set(0, 0, frame.width(), frame.height());
    }

    // At this point dirty is the area of the screen to update. However,
    // the area of the frame we need to repaint is potentially different, so
    // stash the screen area for later
    SkRect screenDirty(dirty);

    // If the buffer age is 0 we do a full-screen repaint (handled above)
    // If the buffer age is 1 the buffer contents are the same as they were
    // last frame so there's nothing to union() against
    // Therefore we only care about the > 1 case.
    if (frame.bufferAge() > 1) {
        if (frame.bufferAge() > (int) mSwapHistory.size()) {
            // We don't have enough history to handle this old of a buffer
            // Just do a full-draw
            dirty.set(0, 0, frame.width(), frame.height());
        } else {
            // At this point we haven't yet added the latest frame
            // to the damage history (happens below)
            // So we need to damage
            for (int i = mSwapHistory.size() - 1;
                    i > ((int) mSwapHistory.size()) - frame.bufferAge(); i--) {
                dirty.join(mSwapHistory[i].damage);
            }
        }
    }

    mEglManager.damageFrame(frame, dirty);

#if HWUI_NEW_OPS
    auto& caches = Caches::getInstance();
    FrameBuilder frameBuilder(dirty, frame.width(), frame.height(), mLightGeometry, caches);

    frameBuilder.deferLayers(mLayerUpdateQueue);
    mLayerUpdateQueue.clear();

    frameBuilder.deferRenderNodeScene(mRenderNodes, mContentDrawBounds);

    BakedOpRenderer renderer(caches, mRenderThread.renderState(),
            mOpaque, mLightInfo);
    frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
    profiler().draw(&renderer);
    bool drew = renderer.didDraw();

    // post frame cleanup
    caches.clearGarbage();
    caches.pathCache.trim();
    caches.tessellationCache.trim();

#if DEBUG_MEMORY_USAGE
    mCaches.dumpMemoryUsage();
#else
    if (CC_UNLIKELY(Properties::debugLevel & kDebugMemory)) {
        caches.dumpMemoryUsage();
    }
#endif

#else
    mCanvas->prepareDirty(frame.width(), frame.height(),
            dirty.fLeft, dirty.fTop, dirty.fRight, dirty.fBottom, mOpaque);

    Rect outBounds;
    // It there are multiple render nodes, they are laid out as follows:
    // #0 - backdrop (content + caption)
    // #1 - content (positioned at (0,0) and clipped to - its bounds mContentDrawBounds)
    // #2 - additional overlay nodes
    // Usually the backdrop cannot be seen since it will be entirely covered by the content. While
    // resizing however it might become partially visible. The following render loop will crop the
    // backdrop against the content and draw the remaining part of it. It will then draw the content
    // cropped to the backdrop (since that indicates a shrinking of the window).
    //
    // Additional nodes will be drawn on top with no particular clipping semantics.

    // The bounds of the backdrop against which the content should be clipped.
    Rect backdropBounds = mContentDrawBounds;
    // Usually the contents bounds should be mContentDrawBounds - however - we will
    // move it towards the fixed edge to give it a more stable appearance (for the moment).
    Rect contentBounds;
    // If there is no content bounds we ignore the layering as stated above and start with 2.
    int layer = (mContentDrawBounds.isEmpty() || mRenderNodes.size() == 1) ? 2 : 0;
    // Draw all render nodes. Note that
    for (const sp<RenderNode>& node : mRenderNodes) {
        if (layer == 0) { // Backdrop.
            // Draw the backdrop clipped to the inverse content bounds, but assume that the content
            // was moved to the upper left corner.
            const RenderProperties& properties = node->properties();
            Rect targetBounds(properties.getLeft(), properties.getTop(),
                              properties.getRight(), properties.getBottom());
            // Move the content bounds towards the fixed corner of the backdrop.
            const int x = targetBounds.left;
            const int y = targetBounds.top;
            contentBounds.set(x, y, x + mContentDrawBounds.getWidth(),
                                    y + mContentDrawBounds.getHeight());
            // Remember the intersection of the target bounds and the intersection bounds against
            // which we have to crop the content.
            backdropBounds.set(x, y, x + backdropBounds.getWidth(), y + backdropBounds.getHeight());
            backdropBounds.doIntersect(targetBounds);
            // Check if we have to draw something on the left side ...
            if (targetBounds.left < contentBounds.left) {
                mCanvas->save(SaveFlags::Clip);
                if (mCanvas->clipRect(targetBounds.left, targetBounds.top,
                                      contentBounds.left, targetBounds.bottom,
                                      SkRegion::kIntersect_Op)) {
                    mCanvas->drawRenderNode(node.get(), outBounds);
                }
                // Reduce the target area by the area we have just painted.
                targetBounds.left = std::min(contentBounds.left, targetBounds.right);
                mCanvas->restore();
            }
            // ... or on the right side ...
            if (targetBounds.right > contentBounds.right &&
                !targetBounds.isEmpty()) {
                mCanvas->save(SaveFlags::Clip);
                if (mCanvas->clipRect(contentBounds.right, targetBounds.top,
                                      targetBounds.right, targetBounds.bottom,
                                      SkRegion::kIntersect_Op)) {
                    mCanvas->drawRenderNode(node.get(), outBounds);
                }
                // Reduce the target area by the area we have just painted.
                targetBounds.right = std::max(targetBounds.left, contentBounds.right);
                mCanvas->restore();
            }
            // ... or at the top ...
            if (targetBounds.top < contentBounds.top &&
                !targetBounds.isEmpty()) {
                mCanvas->save(SaveFlags::Clip);
                if (mCanvas->clipRect(targetBounds.left, targetBounds.top, targetBounds.right,
                                      contentBounds.top,
                                      SkRegion::kIntersect_Op)) {
                    mCanvas->drawRenderNode(node.get(), outBounds);
                }
                // Reduce the target area by the area we have just painted.
                targetBounds.top = std::min(contentBounds.top, targetBounds.bottom);
                mCanvas->restore();
            }
            // ... or at the bottom.
            if (targetBounds.bottom > contentBounds.bottom &&
                !targetBounds.isEmpty()) {
                mCanvas->save(SaveFlags::Clip);
                if (mCanvas->clipRect(targetBounds.left, contentBounds.bottom, targetBounds.right,
                                      targetBounds.bottom, SkRegion::kIntersect_Op)) {
                    mCanvas->drawRenderNode(node.get(), outBounds);
                }
                mCanvas->restore();
            }
        } else if (layer == 1) { // Content
            // It gets cropped against the bounds of the backdrop to stay inside.
            mCanvas->save(SaveFlags::MatrixClip);

            // We shift and clip the content to match its final location in the window.
            const float left = mContentDrawBounds.left;
            const float top = mContentDrawBounds.top;
            const float dx = backdropBounds.left - left;
            const float dy = backdropBounds.top - top;
            const float width = backdropBounds.getWidth();
            const float height = backdropBounds.getHeight();

            mCanvas->translate(dx, dy);
            if (mCanvas->clipRect(left, top, left + width, top + height, SkRegion::kIntersect_Op)) {
                mCanvas->drawRenderNode(node.get(), outBounds);
            }
            mCanvas->restore();
        } else { // draw the rest on top at will!
            mCanvas->drawRenderNode(node.get(), outBounds);
        }
        layer++;
    }

    profiler().draw(mCanvas);

    bool drew = mCanvas->finish();
#endif

    waitOnFences();

    GL_CHECKPOINT(LOW);

    // Even if we decided to cancel the frame, from the perspective of jank
    // metrics the frame was swapped at this point
    mCurrentFrameInfo->markSwapBuffers();
    mIsDirty = false;

    if (drew || mEglManager.damageRequiresSwap()) {
        if (CC_UNLIKELY(!mEglManager.swapBuffers(frame, screenDirty))) {
            setSurface(nullptr);
        }
        SwapHistory& swap = mSwapHistory.next();
        swap.damage = screenDirty;
        swap.swapCompletedTime = systemTime(CLOCK_MONOTONIC);
        swap.vsyncTime = mRenderThread.timeLord().latestVsync();
        if (mNativeSurface.get()) {
            int durationUs;
            mNativeSurface->query(NATIVE_WINDOW_LAST_DEQUEUE_DURATION, &durationUs);
            swap.dequeueDuration = us2ns(durationUs);
            mNativeSurface->query(NATIVE_WINDOW_LAST_QUEUE_DURATION, &durationUs);
            swap.queueDuration = us2ns(durationUs);
        } else {
            swap.dequeueDuration = 0;
            swap.queueDuration = 0;
        }
        mCurrentFrameInfo->set(FrameInfoIndex::DequeueBufferDuration)
                = swap.dequeueDuration;
        mCurrentFrameInfo->set(FrameInfoIndex::QueueBufferDuration)
                = swap.queueDuration;
        mHaveNewSurface = false;
        mFrameNumber = -1;
    } else {
        mCurrentFrameInfo->set(FrameInfoIndex::DequeueBufferDuration) = 0;
        mCurrentFrameInfo->set(FrameInfoIndex::QueueBufferDuration) = 0;
    }

    // TODO: Use a fence for real completion?
    mCurrentFrameInfo->markFrameCompleted();

#if LOG_FRAMETIME_MMA
    float thisFrame = mCurrentFrameInfo->duration(
            FrameInfoIndex::IssueDrawCommandsStart,
            FrameInfoIndex::FrameCompleted) / NANOS_PER_MILLIS_F;
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

    mJankTracker.addFrame(*mCurrentFrameInfo);
    mRenderThread.jankTracker().addFrame(*mCurrentFrameInfo);
    if (CC_UNLIKELY(mFrameMetricsReporter.get() != nullptr)) {
        mFrameMetricsReporter->reportFrameMetrics(mCurrentFrameInfo->data());
    }

    GpuMemoryTracker::onFrameCompleted();
#ifdef BUGREPORT_FONT_CACHE_USAGE
    caches.fontRenderer.getFontRenderer().historyTracker().frameCompleted();
#endif

}

// Called by choreographer to do an RT-driven animation
void CanvasContext::doFrame() {
#if HWUI_NEW_OPS
    if (CC_UNLIKELY(mEglSurface == EGL_NO_SURFACE)) return;
#else
    if (CC_UNLIKELY(!mCanvas || mEglSurface == EGL_NO_SURFACE)) return;
#endif
    prepareAndDraw(nullptr);
}

void CanvasContext::prepareAndDraw(RenderNode* node) {
    ATRACE_CALL();

    nsecs_t vsync = mRenderThread.timeLord().computeFrameTimeNanos();
    int64_t frameInfo[UI_THREAD_FRAME_INFO_SIZE];
    UiFrameInfoBuilder(frameInfo)
        .addFlag(FrameInfoFlags::RTAnimation)
        .setVsync(vsync, vsync);

    TreeInfo info(TreeInfo::MODE_RT_ONLY, *this);
    prepareTree(info, frameInfo, systemTime(CLOCK_MONOTONIC), node);
    if (info.out.canDrawThisFrame) {
        draw();
    } else {
        // wait on fences so tasks don't overlap next frame
        waitOnFences();
    }
}

void CanvasContext::invokeFunctor(RenderThread& thread, Functor* functor) {
    ATRACE_CALL();
    DrawGlInfo::Mode mode = DrawGlInfo::kModeProcessNoContext;
    if (thread.eglManager().hasEglContext()) {
        mode = DrawGlInfo::kModeProcess;
    }

    thread.renderState().invokeFunctor(functor, mode, nullptr);
}

void CanvasContext::markLayerInUse(RenderNode* node) {
    if (mPrefetchedLayers.erase(node)) {
        node->decStrong(nullptr);
    }
}

void CanvasContext::freePrefetchedLayers(TreeObserver* observer) {
    if (mPrefetchedLayers.size()) {
        for (auto& node : mPrefetchedLayers) {
            ALOGW("Incorrectly called buildLayer on View: %s, destroying layer...",
                    node->getName());
            node->destroyHardwareResources(observer);
            node->decStrong(observer);
        }
        mPrefetchedLayers.clear();
    }
}

void CanvasContext::buildLayer(RenderNode* node, TreeObserver* observer) {
    ATRACE_CALL();
    if (!mEglManager.hasEglContext()) return;
#if !HWUI_NEW_OPS
    if (!mCanvas) return;
#endif

    // buildLayer() will leave the tree in an unknown state, so we must stop drawing
    stopDrawing();

    TreeInfo info(TreeInfo::MODE_FULL, *this);
    info.damageAccumulator = &mDamageAccumulator;
    info.observer = observer;
#if HWUI_NEW_OPS
    info.layerUpdateQueue = &mLayerUpdateQueue;
#else
    info.renderer = mCanvas;
#endif
    info.runAnimations = false;
    node->prepareTree(info);
    SkRect ignore;
    mDamageAccumulator.finish(&ignore);
    // Tickle the GENERIC property on node to mark it as dirty for damaging
    // purposes when the frame is actually drawn
    node->setPropertyFieldsDirty(RenderNode::GENERIC);

#if HWUI_NEW_OPS
    static const std::vector< sp<RenderNode> > emptyNodeList;
    auto& caches = Caches::getInstance();
    FrameBuilder frameBuilder(mLayerUpdateQueue, mLightGeometry, caches);
    mLayerUpdateQueue.clear();
    BakedOpRenderer renderer(caches, mRenderThread.renderState(),
            mOpaque, mLightInfo);
    LOG_ALWAYS_FATAL_IF(renderer.didDraw(), "shouldn't draw in buildlayer case");
    frameBuilder.replayBakedOps<BakedOpDispatcher>(renderer);
#else
    mCanvas->markLayersAsBuildLayers();
    mCanvas->flushLayerUpdates();
#endif

    node->incStrong(nullptr);
    mPrefetchedLayers.insert(node);
}

bool CanvasContext::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) {
    layer->apply();
    return LayerRenderer::copyLayer(mRenderThread.renderState(), layer->backingLayer(), bitmap);
}

void CanvasContext::destroyHardwareResources(TreeObserver* observer) {
    stopDrawing();
    if (mEglManager.hasEglContext()) {
        freePrefetchedLayers(observer);
        for (const sp<RenderNode>& node : mRenderNodes) {
            node->destroyHardwareResources(observer);
        }
        Caches& caches = Caches::getInstance();
        // Make sure to release all the textures we were owning as there won't
        // be another draw
        caches.textureCache.resetMarkInUse(this);
        mRenderThread.renderState().flush(Caches::FlushMode::Layers);
    }
}

void CanvasContext::trimMemory(RenderThread& thread, int level) {
    // No context means nothing to free
    if (!thread.eglManager().hasEglContext()) return;

    ATRACE_CALL();
    if (level >= TRIM_MEMORY_COMPLETE) {
        thread.renderState().flush(Caches::FlushMode::Full);
        thread.eglManager().destroy();
    } else if (level >= TRIM_MEMORY_UI_HIDDEN) {
        thread.renderState().flush(Caches::FlushMode::Moderate);
    }
}

void CanvasContext::runWithGlContext(RenderTask* task) {
    LOG_ALWAYS_FATAL_IF(!mEglManager.hasEglContext(),
            "GL context not initialized!");
    task->run();
}

Layer* CanvasContext::createTextureLayer() {
    mEglManager.initialize();
    return LayerRenderer::createTextureLayer(mRenderThread.renderState());
}

void CanvasContext::setTextureAtlas(RenderThread& thread,
        const sp<GraphicBuffer>& buffer, int64_t* map, size_t mapSize) {
    thread.eglManager().setTextureAtlas(buffer, map, mapSize);
}

void CanvasContext::dumpFrames(int fd) {
    FILE* file = fdopen(fd, "a");
    fprintf(file, "\n\n---PROFILEDATA---\n");
    for (size_t i = 0; i < static_cast<size_t>(FrameInfoIndex::NumIndexes); i++) {
        fprintf(file, "%s", FrameInfoNames[i].c_str());
        fprintf(file, ",");
    }
    for (size_t i = 0; i < mFrames.size(); i++) {
        FrameInfo& frame = mFrames[i];
        if (frame[FrameInfoIndex::SyncStart] == 0) {
            continue;
        }
        fprintf(file, "\n");
        for (int i = 0; i < static_cast<int>(FrameInfoIndex::NumIndexes); i++) {
            fprintf(file, "%" PRId64 ",", frame[i]);
        }
    }
    fprintf(file, "\n---PROFILEDATA---\n\n");
    fflush(file);
}

void CanvasContext::resetFrameStats() {
    mFrames.clear();
    mRenderThread.jankTracker().reset();
}

void CanvasContext::serializeDisplayListTree() {
#if ENABLE_RENDERNODE_SERIALIZATION
    using namespace google::protobuf::io;
    char package[128];
    // Check whether tracing is enabled for this process.
    FILE * file = fopen("/proc/self/cmdline", "r");
    if (file) {
        if (!fgets(package, 128, file)) {
            ALOGE("Error reading cmdline: %s (%d)", strerror(errno), errno);
            fclose(file);
            return;
        }
        fclose(file);
    } else {
        ALOGE("Error opening /proc/self/cmdline: %s (%d)", strerror(errno),
                errno);
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
    FuncTaskProcessor(Caches& caches)
            : TaskProcessor<bool>(&caches.tasks) {}

    virtual void onProcess(const sp<Task<bool> >& task) override {
        FuncTask* t = static_cast<FuncTask*>(task.get());
        t->func();
        task->setResult(true);
    }
};

void CanvasContext::enqueueFrameWork(std::function<void()>&& func) {
    if (!mFrameWorkProcessor.get()) {
        mFrameWorkProcessor = new FuncTaskProcessor(Caches::getInstance());
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

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
