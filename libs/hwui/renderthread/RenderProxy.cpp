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

#include "RenderProxy.h"

#include "DeferredLayerUpdater.h"
#include "DisplayList.h"
#include "LayerRenderer.h"
#include "Rect.h"
#include "renderthread/CanvasContext.h"
#include "renderthread/RenderTask.h"
#include "renderthread/RenderThread.h"
#include "utils/Macros.h"

namespace android {
namespace uirenderer {
namespace renderthread {

#define ARGS(method) method ## Args

#define CREATE_BRIDGE0(name) CREATE_BRIDGE(name,,,,,,,,)
#define CREATE_BRIDGE1(name, a1) CREATE_BRIDGE(name, a1,,,,,,,)
#define CREATE_BRIDGE2(name, a1, a2) CREATE_BRIDGE(name, a1,a2,,,,,,)
#define CREATE_BRIDGE3(name, a1, a2, a3) CREATE_BRIDGE(name, a1,a2,a3,,,,,)
#define CREATE_BRIDGE4(name, a1, a2, a3, a4) CREATE_BRIDGE(name, a1,a2,a3,a4,,,,)
#define CREATE_BRIDGE5(name, a1, a2, a3, a4, a5) CREATE_BRIDGE(name, a1,a2,a3,a4,a5,,,)
#define CREATE_BRIDGE6(name, a1, a2, a3, a4, a5, a6) CREATE_BRIDGE(name, a1,a2,a3,a4,a5,a6,,)
#define CREATE_BRIDGE7(name, a1, a2, a3, a4, a5, a6, a7) CREATE_BRIDGE(name, a1,a2,a3,a4,a5,a6,a7,)
#define CREATE_BRIDGE(name, a1, a2, a3, a4, a5, a6, a7, a8) \
    typedef struct { \
        a1; a2; a3; a4; a5; a6; a7; a8; \
    } ARGS(name); \
    static void* Bridge_ ## name(ARGS(name)* args)

#define SETUP_TASK(method) \
    LOG_ALWAYS_FATAL_IF( METHOD_INVOKE_PAYLOAD_SIZE < sizeof(ARGS(method)), \
        "METHOD_INVOKE_PAYLOAD_SIZE %zu is smaller than sizeof(" #method "Args) %zu", \
                METHOD_INVOKE_PAYLOAD_SIZE, sizeof(ARGS(method))); \
    MethodInvokeRenderTask* task = new MethodInvokeRenderTask((RunnableMethod) Bridge_ ## method); \
    ARGS(method) *args = (ARGS(method) *) task->payload()

namespace DumpFlags {
    enum {
        FrameStats = 1 << 0,
        Reset      = 1 << 1,
    };
};

CREATE_BRIDGE4(createContext, RenderThread* thread, bool translucent,
        RenderNode* rootRenderNode, IContextFactory* contextFactory) {
    return new CanvasContext(*args->thread, args->translucent,
            args->rootRenderNode, args->contextFactory);
}

RenderProxy::RenderProxy(bool translucent, RenderNode* rootRenderNode, IContextFactory* contextFactory)
        : mRenderThread(RenderThread::getInstance())
        , mContext(nullptr) {
    SETUP_TASK(createContext);
    args->translucent = translucent;
    args->rootRenderNode = rootRenderNode;
    args->thread = &mRenderThread;
    args->contextFactory = contextFactory;
    mContext = (CanvasContext*) postAndWait(task);
    mDrawFrameTask.setContext(&mRenderThread, mContext);
}

RenderProxy::~RenderProxy() {
    destroyContext();
}

CREATE_BRIDGE1(destroyContext, CanvasContext* context) {
    delete args->context;
    return nullptr;
}

void RenderProxy::destroyContext() {
    if (mContext) {
        SETUP_TASK(destroyContext);
        args->context = mContext;
        mContext = nullptr;
        mDrawFrameTask.setContext(nullptr, nullptr);
        // This is also a fence as we need to be certain that there are no
        // outstanding mDrawFrame tasks posted before it is destroyed
        postAndWait(task);
    }
}

CREATE_BRIDGE2(setSwapBehavior, CanvasContext* context, SwapBehavior swapBehavior) {
    args->context->setSwapBehavior(args->swapBehavior);
    return nullptr;
}

void RenderProxy::setSwapBehavior(SwapBehavior swapBehavior) {
    SETUP_TASK(setSwapBehavior);
    args->context = mContext;
    args->swapBehavior = swapBehavior;
    post(task);
}

CREATE_BRIDGE1(loadSystemProperties, CanvasContext* context) {
    bool needsRedraw = false;
    if (Caches::hasInstance()) {
        needsRedraw = Properties::load();
    }
    if (args->context->profiler().consumeProperties()) {
        needsRedraw = true;
    }
    return (void*) needsRedraw;
}

bool RenderProxy::loadSystemProperties() {
    SETUP_TASK(loadSystemProperties);
    args->context = mContext;
    return (bool) postAndWait(task);
}

CREATE_BRIDGE2(setName, CanvasContext* context, const char* name) {
    args->context->setName(std::string(args->name));
    return nullptr;
}

void RenderProxy::setName(const char* name) {
    SETUP_TASK(setName);
    args->context = mContext;
    args->name = name;
    postAndWait(task); // block since name/value pointers owned by caller
}

CREATE_BRIDGE2(initialize, CanvasContext* context, ANativeWindow* window) {
    args->context->initialize(args->window);
    return nullptr;
}

void RenderProxy::initialize(const sp<ANativeWindow>& window) {
    SETUP_TASK(initialize);
    args->context = mContext;
    args->window = window.get();
    post(task);
}

CREATE_BRIDGE2(updateSurface, CanvasContext* context, ANativeWindow* window) {
    args->context->updateSurface(args->window);
    return nullptr;
}

void RenderProxy::updateSurface(const sp<ANativeWindow>& window) {
    SETUP_TASK(updateSurface);
    args->context = mContext;
    args->window = window.get();
    postAndWait(task);
}

CREATE_BRIDGE2(pauseSurface, CanvasContext* context, ANativeWindow* window) {
    return (void*) args->context->pauseSurface(args->window);
}

bool RenderProxy::pauseSurface(const sp<ANativeWindow>& window) {
    SETUP_TASK(pauseSurface);
    args->context = mContext;
    args->window = window.get();
    return (bool) postAndWait(task);
}

CREATE_BRIDGE6(setup, CanvasContext* context, int width, int height,
        float lightRadius, uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
    args->context->setup(args->width, args->height, args->lightRadius,
            args->ambientShadowAlpha, args->spotShadowAlpha);
    return nullptr;
}

void RenderProxy::setup(int width, int height, float lightRadius,
        uint8_t ambientShadowAlpha, uint8_t spotShadowAlpha) {
    SETUP_TASK(setup);
    args->context = mContext;
    args->width = width;
    args->height = height;
    args->lightRadius = lightRadius;
    args->ambientShadowAlpha = ambientShadowAlpha;
    args->spotShadowAlpha = spotShadowAlpha;
    post(task);
}

CREATE_BRIDGE2(setLightCenter, CanvasContext* context, Vector3 lightCenter) {
    args->context->setLightCenter(args->lightCenter);
    return nullptr;
}

void RenderProxy::setLightCenter(const Vector3& lightCenter) {
    SETUP_TASK(setLightCenter);
    args->context = mContext;
    args->lightCenter = lightCenter;
    post(task);
}

CREATE_BRIDGE2(setOpaque, CanvasContext* context, bool opaque) {
    args->context->setOpaque(args->opaque);
    return nullptr;
}

void RenderProxy::setOpaque(bool opaque) {
    SETUP_TASK(setOpaque);
    args->context = mContext;
    args->opaque = opaque;
    post(task);
}

int64_t* RenderProxy::frameInfo() {
    return mDrawFrameTask.frameInfo();
}

int RenderProxy::syncAndDrawFrame() {
    return mDrawFrameTask.drawFrame();
}

CREATE_BRIDGE1(destroy, CanvasContext* context) {
    args->context->destroy();
    return nullptr;
}

void RenderProxy::destroy() {
    SETUP_TASK(destroy);
    args->context = mContext;
    // destroyCanvasAndSurface() needs a fence as when it returns the
    // underlying BufferQueue is going to be released from under
    // the render thread.
    postAndWait(task);
}

CREATE_BRIDGE2(invokeFunctor, RenderThread* thread, Functor* functor) {
    CanvasContext::invokeFunctor(*args->thread, args->functor);
    return nullptr;
}

void RenderProxy::invokeFunctor(Functor* functor, bool waitForCompletion) {
    ATRACE_CALL();
    RenderThread& thread = RenderThread::getInstance();
    SETUP_TASK(invokeFunctor);
    args->thread = &thread;
    args->functor = functor;
    if (waitForCompletion) {
        // waitForCompletion = true is expected to be fairly rare and only
        // happen in destruction. Thus it should be fine to temporarily
        // create a Mutex
        staticPostAndWait(task);
    } else {
        thread.queue(task);
    }
}

CREATE_BRIDGE2(runWithGlContext, CanvasContext* context, RenderTask* task) {
    args->context->runWithGlContext(args->task);
    return nullptr;
}

void RenderProxy::runWithGlContext(RenderTask* gltask) {
    SETUP_TASK(runWithGlContext);
    args->context = mContext;
    args->task = gltask;
    postAndWait(task);
}

CREATE_BRIDGE2(createTextureLayer, RenderThread* thread, CanvasContext* context) {
    Layer* layer = args->context->createTextureLayer();
    if (!layer) return nullptr;
    return new DeferredLayerUpdater(*args->thread, layer);
}

DeferredLayerUpdater* RenderProxy::createTextureLayer() {
    SETUP_TASK(createTextureLayer);
    args->context = mContext;
    args->thread = &mRenderThread;
    void* retval = postAndWait(task);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(retval);
    return layer;
}

CREATE_BRIDGE2(buildLayer, CanvasContext* context, RenderNode* node) {
    args->context->buildLayer(args->node);
    return nullptr;
}

void RenderProxy::buildLayer(RenderNode* node) {
    SETUP_TASK(buildLayer);
    args->context = mContext;
    args->node = node;
    postAndWait(task);
}

CREATE_BRIDGE3(copyLayerInto, CanvasContext* context, DeferredLayerUpdater* layer,
        SkBitmap* bitmap) {
    bool success = args->context->copyLayerInto(args->layer, args->bitmap);
    return (void*) success;
}

bool RenderProxy::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap& bitmap) {
    SETUP_TASK(copyLayerInto);
    args->context = mContext;
    args->layer = layer;
    args->bitmap = &bitmap;
    return (bool) postAndWait(task);
}

void RenderProxy::pushLayerUpdate(DeferredLayerUpdater* layer) {
    mDrawFrameTask.pushLayerUpdate(layer);
}

void RenderProxy::cancelLayerUpdate(DeferredLayerUpdater* layer) {
    mDrawFrameTask.removeLayerUpdate(layer);
}

CREATE_BRIDGE1(detachSurfaceTexture, DeferredLayerUpdater* layer) {
    args->layer->detachSurfaceTexture();
    return nullptr;
}

void RenderProxy::detachSurfaceTexture(DeferredLayerUpdater* layer) {
    SETUP_TASK(detachSurfaceTexture);
    args->layer = layer;
    postAndWait(task);
}

CREATE_BRIDGE1(destroyHardwareResources, CanvasContext* context) {
    args->context->destroyHardwareResources();
    return nullptr;
}

void RenderProxy::destroyHardwareResources() {
    SETUP_TASK(destroyHardwareResources);
    args->context = mContext;
    post(task);
}

CREATE_BRIDGE2(trimMemory, RenderThread* thread, int level) {
    CanvasContext::trimMemory(*args->thread, args->level);
    return nullptr;
}

void RenderProxy::trimMemory(int level) {
    // Avoid creating a RenderThread to do a trimMemory.
    if (RenderThread::hasInstance()) {
        RenderThread& thread = RenderThread::getInstance();
        SETUP_TASK(trimMemory);
        args->thread = &thread;
        args->level = level;
        thread.queue(task);
    }
}

CREATE_BRIDGE2(overrideProperty, const char* name, const char* value) {
    Properties::overrideProperty(args->name, args->value);
    return nullptr;
}

void RenderProxy::overrideProperty(const char* name, const char* value) {
    SETUP_TASK(overrideProperty);
    args->name = name;
    args->value = value;
    staticPostAndWait(task); // expensive, but block here since name/value pointers owned by caller
}

CREATE_BRIDGE0(fence) {
    // Intentionally empty
    return nullptr;
}

template <typename T>
void UNUSED(T t) {}

void RenderProxy::fence() {
    SETUP_TASK(fence);
    UNUSED(args);
    postAndWait(task);
}

CREATE_BRIDGE1(stopDrawing, CanvasContext* context) {
    args->context->stopDrawing();
    return nullptr;
}

void RenderProxy::stopDrawing() {
    SETUP_TASK(stopDrawing);
    args->context = mContext;
    postAndWait(task);
}

CREATE_BRIDGE1(notifyFramePending, CanvasContext* context) {
    args->context->notifyFramePending();
    return nullptr;
}

void RenderProxy::notifyFramePending() {
    SETUP_TASK(notifyFramePending);
    args->context = mContext;
    mRenderThread.queueAtFront(task);
}

CREATE_BRIDGE4(dumpProfileInfo, CanvasContext* context, RenderThread* thread,
        int fd, int dumpFlags) {
    args->context->profiler().dumpData(args->fd);
    args->thread->jankTracker().dump(args->fd);
    if (args->dumpFlags & DumpFlags::FrameStats) {
        args->context->dumpFrames(args->fd);
    }
    if (args->dumpFlags & DumpFlags::Reset) {
        args->context->resetFrameStats();
    }
    return nullptr;
}

void RenderProxy::dumpProfileInfo(int fd, int dumpFlags) {
    SETUP_TASK(dumpProfileInfo);
    args->context = mContext;
    args->thread = &mRenderThread;
    args->fd = fd;
    args->dumpFlags = dumpFlags;
    postAndWait(task);
}

CREATE_BRIDGE1(resetProfileInfo, CanvasContext* context) {
    args->context->resetFrameStats();
    return nullptr;
}

void RenderProxy::resetProfileInfo() {
    SETUP_TASK(resetProfileInfo);
    args->context = mContext;
    postAndWait(task);
}

CREATE_BRIDGE2(dumpGraphicsMemory, int fd, RenderThread* thread) {
    args->thread->jankTracker().dump(args->fd);

    FILE *file = fdopen(args->fd, "a");
    if (Caches::hasInstance()) {
        String8 cachesLog;
        Caches::getInstance().dumpMemoryUsage(cachesLog);
        fprintf(file, "\nCaches:\n%s\n", cachesLog.string());
    } else {
        fprintf(file, "\nNo caches instance.\n");
    }
    fflush(file);
    return nullptr;
}

void RenderProxy::dumpGraphicsMemory(int fd) {
    if (!RenderThread::hasInstance()) return;
    SETUP_TASK(dumpGraphicsMemory);
    args->fd = fd;
    args->thread = &RenderThread::getInstance();
    staticPostAndWait(task);
}

CREATE_BRIDGE4(setTextureAtlas, RenderThread* thread, GraphicBuffer* buffer, int64_t* map, size_t size) {
    CanvasContext::setTextureAtlas(*args->thread, args->buffer, args->map, args->size);
    args->buffer->decStrong(nullptr);
    return nullptr;
}

void RenderProxy::setTextureAtlas(const sp<GraphicBuffer>& buffer, int64_t* map, size_t size) {
    SETUP_TASK(setTextureAtlas);
    args->thread = &mRenderThread;
    args->buffer = buffer.get();
    args->buffer->incStrong(nullptr);
    args->map = map;
    args->size = size;
    post(task);
}

CREATE_BRIDGE2(setProcessStatsBuffer, RenderThread* thread, int fd) {
    args->thread->jankTracker().switchStorageToAshmem(args->fd);
    close(args->fd);
    return nullptr;
}

void RenderProxy::setProcessStatsBuffer(int fd) {
    SETUP_TASK(setProcessStatsBuffer);
    args->thread = &mRenderThread;
    args->fd = dup(fd);
    post(task);
}

void RenderProxy::post(RenderTask* task) {
    mRenderThread.queue(task);
}

void* RenderProxy::postAndWait(MethodInvokeRenderTask* task) {
    void* retval;
    task->setReturnPtr(&retval);
    SignalingRenderTask syncTask(task, &mSyncMutex, &mSyncCondition);
    AutoMutex _lock(mSyncMutex);
    mRenderThread.queue(&syncTask);
    mSyncCondition.wait(mSyncMutex);
    return retval;
}

void* RenderProxy::staticPostAndWait(MethodInvokeRenderTask* task) {
    RenderThread& thread = RenderThread::getInstance();
    void* retval;
    task->setReturnPtr(&retval);
    Mutex mutex;
    Condition condition;
    SignalingRenderTask syncTask(task, &mutex, &condition);
    AutoMutex _lock(mutex);
    thread.queue(&syncTask);
    condition.wait(mutex);
    return retval;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
