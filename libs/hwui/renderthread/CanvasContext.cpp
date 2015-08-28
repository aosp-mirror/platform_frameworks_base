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

#include "AnimationContext.h"
#include "Caches.h"
#include "DeferredLayerUpdater.h"
#include "EglManager.h"
#include "LayerRenderer.h"
#include "OpenGLRenderer.h"
#include "Properties.h"
#include "RenderThread.h"
#include "renderstate/RenderState.h"
#include "renderstate/Stencil.h"
#include "protos/hwui.pb.h"

#include <cutils/properties.h>
#include <google/protobuf/io/zero_copy_stream_impl.h>
#include <private/hwui/DrawGlInfo.h>
#include <strings.h>

#include <algorithm>
#include <fcntl.h>
#include <sys/stat.h>

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
        , mRootRenderNode(rootRenderNode)
        , mJankTracker(thread.timeLord().frameIntervalNanos())
        , mProfiler(mFrames) {
    mRenderThread.renderState().registerCanvasContext(this);
    mProfiler.setDensity(mRenderThread.mainDisplayInfo().density);
}

CanvasContext::~CanvasContext() {
    destroy();
    mRenderThread.renderState().unregisterCanvasContext(this);
}

void CanvasContext::destroy() {
    stopDrawing();
    setSurface(nullptr);
    freePrefetechedLayers();
    destroyHardwareResources();
    mAnimationContext->destroy();
    if (mCanvas) {
        delete mCanvas;
        mCanvas = nullptr;
    }
}

void CanvasContext::setSurface(ANativeWindow* window) {
    ATRACE_CALL();

    mNativeWindow = window;

    if (mEglSurface != EGL_NO_SURFACE) {
        mEglManager.destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (window) {
        mEglSurface = mEglManager.createSurface(window);
    }

    if (mEglSurface != EGL_NO_SURFACE) {
        const bool preserveBuffer = (mSwapBehavior != kSwap_discardBuffer);
        mBufferPreserved = mEglManager.setPreserveBuffer(mEglSurface, preserveBuffer);
        mHaveNewSurface = true;
        makeCurrent();
    } else {
        mRenderThread.removeFrameCallback(this);
    }
}

void CanvasContext::requireSurface() {
    LOG_ALWAYS_FATAL_IF(mEglSurface == EGL_NO_SURFACE,
            "requireSurface() called but no surface set!");
    makeCurrent();
}

void CanvasContext::setSwapBehavior(SwapBehavior swapBehavior) {
    mSwapBehavior = swapBehavior;
}

bool CanvasContext::initialize(ANativeWindow* window) {
    setSurface(window);
    if (mCanvas) return false;
    mCanvas = new OpenGLRenderer(mRenderThread.renderState());
    mCanvas->initProperties();
    return true;
}

void CanvasContext::updateSurface(ANativeWindow* window) {
    setSurface(window);
}

bool CanvasContext::pauseSurface(ANativeWindow* window) {
    return mRenderThread.removeFrameCallback(this);
}

// TODO: don't pass viewport size, it's automatic via EGL
void CanvasContext::setup(int width, int height, float lightRadius,
        uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
    if (!mCanvas) return;
    mCanvas->initLight(lightRadius, ambientShadowAlpha, spotShadowAlpha);
}

void CanvasContext::setLightCenter(const Vector3& lightCenter) {
    if (!mCanvas) return;
    mCanvas->setLightCenter(lightCenter);
}

void CanvasContext::setOpaque(bool opaque) {
    mOpaque = opaque;
}

void CanvasContext::makeCurrent() {
    // TODO: Figure out why this workaround is needed, see b/13913604
    // In the meantime this matches the behavior of GLRenderer, so it is not a regression
    EGLint error = 0;
    mHaveNewSurface |= mEglManager.makeCurrent(mEglSurface, &error);
    if (error) {
        setSurface(nullptr);
    }
}

void CanvasContext::processLayerUpdate(DeferredLayerUpdater* layerUpdater) {
    bool success = layerUpdater->apply();
    LOG_ALWAYS_FATAL_IF(!success, "Failed to update layer!");
    if (layerUpdater->backingLayer()->deferredUpdateScheduled) {
        mCanvas->pushLayerUpdate(layerUpdater->backingLayer());
    }
}

static bool wasSkipped(FrameInfo* info) {
    return info && ((*info)[FrameInfoIndex::Flags] & FrameInfoFlags::SkippedFrame);
}

void CanvasContext::prepareTree(TreeInfo& info, int64_t* uiFrameInfo, int64_t syncQueued) {
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
    info.renderer = mCanvas;
    info.canvasContext = this;

    mAnimationContext->startFrame(info.mode);
    mRootRenderNode->prepareTree(info);
    mAnimationContext->runRemainingAnimations(info);

    freePrefetechedLayers();

    if (CC_UNLIKELY(!mNativeWindow.get())) {
        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
        info.out.canDrawThisFrame = false;
        return;
    }

    int runningBehind = 0;
    // TODO: This query is moderately expensive, investigate adding some sort
    // of fast-path based off when we last called eglSwapBuffers() as well as
    // last vsync time. Or something.
    mNativeWindow->query(mNativeWindow.get(),
            NATIVE_WINDOW_CONSUMER_RUNNING_BEHIND, &runningBehind);
    info.out.canDrawThisFrame = !runningBehind;

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
}

void CanvasContext::notifyFramePending() {
    ATRACE_CALL();
    mRenderThread.pushBackFrameCallback(this);
}

void CanvasContext::draw() {
    LOG_ALWAYS_FATAL_IF(!mCanvas || mEglSurface == EGL_NO_SURFACE,
            "drawRenderNode called on a context with no canvas or surface!");

    SkRect dirty;
    mDamageAccumulator.finish(&dirty);

    // TODO: Re-enable after figuring out cause of b/22592975
//    if (dirty.isEmpty() && Properties::skipEmptyFrames) {
//        mCurrentFrameInfo->addFlag(FrameInfoFlags::SkippedFrame);
//        return;
//    }

    mCurrentFrameInfo->markIssueDrawCommandsStart();

    Frame frame = mEglManager.beginFrame(mEglSurface);
    if (frame.width() != mCanvas->getViewportWidth()
            || frame.height() != mCanvas->getViewportHeight()) {
        mCanvas->setViewport(frame.width(), frame.height());
        dirty.setEmpty();
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
        if (frame.bufferAge() > (int) mDamageHistory.size()) {
            // We don't have enough history to handle this old of a buffer
            // Just do a full-draw
            dirty.set(0, 0, frame.width(), frame.height());
        } else {
            // At this point we haven't yet added the latest frame
            // to the damage history (happens below)
            // So we need to damage
            for (int i = mDamageHistory.size() - 1;
                    i > ((int) mDamageHistory.size()) - frame.bufferAge(); i--) {
                dirty.join(mDamageHistory[i]);
            }
        }
    }

    // Add the screen damage to the ring buffer.
    mDamageHistory.next() = screenDirty;

    mEglManager.damageFrame(frame, dirty);
    mCanvas->prepareDirty(dirty.fLeft, dirty.fTop,
            dirty.fRight, dirty.fBottom, mOpaque);

    Rect outBounds;
    mCanvas->drawRenderNode(mRootRenderNode.get(), outBounds);

    profiler().draw(mCanvas);

    bool drew = mCanvas->finish();

    // Even if we decided to cancel the frame, from the perspective of jank
    // metrics the frame was swapped at this point
    mCurrentFrameInfo->markSwapBuffers();

    if (drew) {
        if (CC_UNLIKELY(!mEglManager.swapBuffers(frame, screenDirty))) {
            setSurface(nullptr);
        }
        mHaveNewSurface = false;
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
}

// Called by choreographer to do an RT-driven animation
void CanvasContext::doFrame() {
    if (CC_UNLIKELY(!mCanvas || mEglSurface == EGL_NO_SURFACE)) {
        return;
    }

    ATRACE_CALL();

    int64_t frameInfo[UI_THREAD_FRAME_INFO_SIZE];
    UiFrameInfoBuilder(frameInfo)
        .addFlag(FrameInfoFlags::RTAnimation)
        .setVsync(mRenderThread.timeLord().computeFrameTimeNanos(),
                mRenderThread.timeLord().latestVsync());

    TreeInfo info(TreeInfo::MODE_RT_ONLY, mRenderThread.renderState());
    prepareTree(info, frameInfo, systemTime(CLOCK_MONOTONIC));
    if (info.out.canDrawThisFrame) {
        draw();
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
    if (mPrefetechedLayers.erase(node)) {
        node->decStrong(nullptr);
    }
}

static void destroyPrefetechedNode(RenderNode* node) {
    ALOGW("Incorrectly called buildLayer on View: %s, destroying layer...", node->getName());
    node->destroyHardwareResources();
    node->decStrong(nullptr);
}

void CanvasContext::freePrefetechedLayers() {
    if (mPrefetechedLayers.size()) {
        std::for_each(mPrefetechedLayers.begin(), mPrefetechedLayers.end(), destroyPrefetechedNode);
        mPrefetechedLayers.clear();
    }
}

void CanvasContext::buildLayer(RenderNode* node) {
    ATRACE_CALL();
    if (!mEglManager.hasEglContext() || !mCanvas) {
        return;
    }
    // buildLayer() will leave the tree in an unknown state, so we must stop drawing
    stopDrawing();

    TreeInfo info(TreeInfo::MODE_FULL, mRenderThread.renderState());
    info.damageAccumulator = &mDamageAccumulator;
    info.renderer = mCanvas;
    info.runAnimations = false;
    node->prepareTree(info);
    SkRect ignore;
    mDamageAccumulator.finish(&ignore);
    // Tickle the GENERIC property on node to mark it as dirty for damaging
    // purposes when the frame is actually drawn
    node->setPropertyFieldsDirty(RenderNode::GENERIC);

    mCanvas->markLayersAsBuildLayers();
    mCanvas->flushLayerUpdates();

    node->incStrong(nullptr);
    mPrefetechedLayers.insert(node);
}

bool CanvasContext::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) {
    layer->apply();
    return LayerRenderer::copyLayer(mRenderThread.renderState(), layer->backingLayer(), bitmap);
}

void CanvasContext::destroyHardwareResources() {
    stopDrawing();
    if (mEglManager.hasEglContext()) {
        freePrefetechedLayers();
        mRootRenderNode->destroyHardwareResources();
        Caches& caches = Caches::getInstance();
        // Make sure to release all the textures we were owning as there won't
        // be another draw
        caches.textureCache.resetMarkInUse(this);
        caches.flush(Caches::FlushMode::Layers);
    }
}

void CanvasContext::trimMemory(RenderThread& thread, int level) {
    // No context means nothing to free
    if (!thread.eglManager().hasEglContext()) return;

    ATRACE_CALL();
    if (level >= TRIM_MEMORY_COMPLETE) {
        Caches::getInstance().flush(Caches::FlushMode::Full);
        thread.eglManager().destroy();
    } else if (level >= TRIM_MEMORY_UI_HIDDEN) {
        Caches::getInstance().flush(Caches::FlushMode::Moderate);
    }
}

void CanvasContext::runWithGlContext(RenderTask* task) {
    LOG_ALWAYS_FATAL_IF(!mEglManager.hasEglContext(),
            "GL context not initialized!");
    task->run();
}

Layer* CanvasContext::createTextureLayer() {
    requireSurface();
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

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
