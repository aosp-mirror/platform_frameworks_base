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

#define LOG_TAG "RenderProxy"

#include "RenderProxy.h"

#include "CanvasContext.h"
#include "RenderTask.h"
#include "RenderThread.h"

#include "../DeferredLayerUpdater.h"
#include "../DisplayList.h"
#include "../LayerRenderer.h"
#include "../Rect.h"

namespace android {
namespace uirenderer {
namespace renderthread {

#define ARGS(method) method ## Args

#define CREATE_BRIDGE0(name) CREATE_BRIDGE(name,,,,,,,,)
#define CREATE_BRIDGE1(name, a1) CREATE_BRIDGE(name, a1,,,,,,,)
#define CREATE_BRIDGE2(name, a1, a2) CREATE_BRIDGE(name, a1,a2,,,,,,)
#define CREATE_BRIDGE3(name, a1, a2, a3) CREATE_BRIDGE(name, a1,a2,a3,,,,,)
#define CREATE_BRIDGE4(name, a1, a2, a3, a4) CREATE_BRIDGE(name, a1,a2,a3,a4,,,,)
#define CREATE_BRIDGE(name, a1, a2, a3, a4, a5, a6, a7, a8) \
    typedef struct { \
        a1; a2; a3; a4; a5; a6; a7; a8; \
    } ARGS(name); \
    static void* Bridge_ ## name(ARGS(name)* args)

#define SETUP_TASK(method) \
    LOG_ALWAYS_FATAL_IF( METHOD_INVOKE_PAYLOAD_SIZE < sizeof(ARGS(method)), \
        "METHOD_INVOKE_PAYLOAD_SIZE %d is smaller than sizeof(" #method "Args) %d", \
                METHOD_INVOKE_PAYLOAD_SIZE, sizeof(ARGS(method))); \
    MethodInvokeRenderTask* task = createTask((RunnableMethod) Bridge_ ## method); \
    ARGS(method) *args = (ARGS(method) *) task->payload()

CREATE_BRIDGE1(createContext, bool translucent) {
    return new CanvasContext(args->translucent);
}

RenderProxy::RenderProxy(bool translucent)
        : mRenderThread(RenderThread::getInstance())
        , mContext(0) {
    SETUP_TASK(createContext);
    args->translucent = translucent;
    mContext = (CanvasContext*) postAndWait(task);
}

RenderProxy::~RenderProxy() {
    destroyContext();
}

CREATE_BRIDGE1(destroyContext, CanvasContext* context) {
    delete args->context;
    return NULL;
}

void RenderProxy::destroyContext() {
    if (mContext) {
        SETUP_TASK(destroyContext);
        args->context = mContext;
        mContext = 0;
        post(task);
    }
}

CREATE_BRIDGE2(initialize, CanvasContext* context, EGLNativeWindowType window) {
    return (void*) args->context->initialize(args->window);
}

bool RenderProxy::initialize(EGLNativeWindowType window) {
    SETUP_TASK(initialize);
    args->context = mContext;
    args->window = window;
    return (bool) postAndWait(task);
}

CREATE_BRIDGE2(updateSurface, CanvasContext* context, EGLNativeWindowType window) {
    args->context->updateSurface(args->window);
    return NULL;
}

void RenderProxy::updateSurface(EGLNativeWindowType window) {
    SETUP_TASK(updateSurface);
    args->context = mContext;
    args->window = window;
    post(task);
}

CREATE_BRIDGE3(setup, CanvasContext* context, int width, int height) {
    args->context->setup(args->width, args->height);
    return NULL;
}

void RenderProxy::setup(int width, int height) {
    SETUP_TASK(setup);
    args->context = mContext;
    args->width = width;
    args->height = height;
    post(task);
}

CREATE_BRIDGE4(drawDisplayList, CanvasContext* context, DisplayList* displayList,
        Rect dirty, const Vector<DeferredLayerUpdater*>* layerUpdates) {
    Rect* dirty = &args->dirty;
    if (dirty->bottom == -1 && dirty->left == -1 &&
            dirty->top == -1 && dirty->right == -1) {
        dirty = 0;
    }
    args->context->processLayerUpdates(args->layerUpdates);
    args->context->drawDisplayList(args->displayList, dirty);
    return NULL;
}

void RenderProxy::drawDisplayList(DisplayList* displayList,
        int dirtyLeft, int dirtyTop, int dirtyRight, int dirtyBottom) {
    SETUP_TASK(drawDisplayList);
    args->context = mContext;
    args->displayList = displayList;
    args->dirty.set(dirtyLeft, dirtyTop, dirtyRight, dirtyBottom);
    args->layerUpdates = &mLayers;
    // TODO: Switch to post() once some form of thread safety strategy is in place
    postAndWait(task);
}

CREATE_BRIDGE1(destroyCanvas, CanvasContext* context) {
    args->context->destroyCanvas();
    return NULL;
}

void RenderProxy::destroyCanvas() {
    SETUP_TASK(destroyCanvas);
    args->context = mContext;
    post(task);
}

CREATE_BRIDGE2(attachFunctor, CanvasContext* context, Functor* functor) {
    args->context->attachFunctor(args->functor);
    return NULL;
}

void RenderProxy::attachFunctor(Functor* functor) {
    SETUP_TASK(attachFunctor);
    args->context = mContext;
    args->functor = functor;
    post(task);
}

CREATE_BRIDGE2(detachFunctor, CanvasContext* context, Functor* functor) {
    args->context->detachFunctor(args->functor);
    return NULL;
}

void RenderProxy::detachFunctor(Functor* functor) {
    SETUP_TASK(detachFunctor);
    args->context = mContext;
    args->functor = functor;
    post(task);
}

CREATE_BRIDGE2(runWithGlContext, CanvasContext* context, RenderTask* task) {
    args->context->runWithGlContext(args->task);
    return NULL;
}

void RenderProxy::runWithGlContext(RenderTask* gltask) {
    SETUP_TASK(runWithGlContext);
    args->context = mContext;
    args->task = gltask;
    postAndWait(task);
}

CREATE_BRIDGE2(createDisplayListLayer, int width, int height) {
    Layer* layer = LayerRenderer::createRenderLayer(args->width, args->height);
    if (!layer) return 0;

    OpenGLRenderer* renderer = new LayerRenderer(layer);
    renderer->initProperties();
    return new DeferredLayerUpdater(layer, renderer);
}

DeferredLayerUpdater* RenderProxy::createDisplayListLayer(int width, int height) {
    SETUP_TASK(createDisplayListLayer);
    args->width = width;
    args->height = height;
    void* retval = postAndWait(task);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(retval);
    mLayers.push(layer);
    return layer;
}

CREATE_BRIDGE0(createTextureLayer) {
    Layer* layer = LayerRenderer::createTextureLayer();
    if (!layer) return 0;
    return new DeferredLayerUpdater(layer);
}

DeferredLayerUpdater* RenderProxy::createTextureLayer() {
    SETUP_TASK(createTextureLayer);
    void* retval = postAndWait(task);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(retval);
    mLayers.push(layer);
    return layer;
}

CREATE_BRIDGE1(destroyLayer, Layer* layer) {
    LayerRenderer::destroyLayer(args->layer);
    return NULL;
}

CREATE_BRIDGE3(copyLayerInto, CanvasContext* context, DeferredLayerUpdater* layer,
        SkBitmap* bitmap) {
    bool success = args->context->copyLayerInto(args->layer, args->bitmap);
    return (void*) success;
}

bool RenderProxy::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) {
    SETUP_TASK(copyLayerInto);
    args->context = mContext;
    args->layer = layer;
    args->bitmap = bitmap;
    return (bool) postAndWait(task);
}

void RenderProxy::destroyLayer(DeferredLayerUpdater* layer) {
    for (size_t i = 0; i < mLayers.size(); i++) {
        if (mLayers[i] == layer) {
            mLayers.removeAt(i);
            break;
        }
    }
    SETUP_TASK(destroyLayer);
    args->layer = layer->detachBackingLayer();
    post(task);
}

MethodInvokeRenderTask* RenderProxy::createTask(RunnableMethod method) {
    // TODO: Consider having a small pool of these to avoid alloc churn
    return new MethodInvokeRenderTask(method);
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

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
