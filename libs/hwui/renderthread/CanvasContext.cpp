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

#define LOG_TAG "CanvasContext"

#include "CanvasContext.h"

#include <cutils/properties.h>
#include <private/hwui/DrawGlInfo.h>
#include <strings.h>

#include "RenderThread.h"
#include "../Caches.h"
#include "../DeferredLayerUpdater.h"
#include "../LayerRenderer.h"
#include "../OpenGLRenderer.h"
#include "../Stencil.h"

#define PROPERTY_RENDER_DIRTY_REGIONS "debug.hwui.render_dirty_regions"
#define GLES_VERSION 2

#ifdef USE_OPENGL_RENDERER
// Android-specific addition that is used to show when frames began in systrace
EGLAPI void EGLAPIENTRY eglBeginFrame(EGLDisplay dpy, EGLSurface surface);
#endif

namespace android {
namespace uirenderer {
namespace renderthread {

#define ERROR_CASE(x) case x: return #x;
static const char* egl_error_str(EGLint error) {
    switch (error) {
        ERROR_CASE(EGL_SUCCESS)
        ERROR_CASE(EGL_NOT_INITIALIZED)
        ERROR_CASE(EGL_BAD_ACCESS)
        ERROR_CASE(EGL_BAD_ALLOC)
        ERROR_CASE(EGL_BAD_ATTRIBUTE)
        ERROR_CASE(EGL_BAD_CONFIG)
        ERROR_CASE(EGL_BAD_CONTEXT)
        ERROR_CASE(EGL_BAD_CURRENT_SURFACE)
        ERROR_CASE(EGL_BAD_DISPLAY)
        ERROR_CASE(EGL_BAD_MATCH)
        ERROR_CASE(EGL_BAD_NATIVE_PIXMAP)
        ERROR_CASE(EGL_BAD_NATIVE_WINDOW)
        ERROR_CASE(EGL_BAD_PARAMETER)
        ERROR_CASE(EGL_BAD_SURFACE)
        ERROR_CASE(EGL_CONTEXT_LOST)
    default:
        return "Unknown error";
    }
}
static const char* egl_error_str() {
    return egl_error_str(eglGetError());
}

static bool load_dirty_regions_property() {
    char buf[PROPERTY_VALUE_MAX];
    int len = property_get(PROPERTY_RENDER_DIRTY_REGIONS, buf, "true");
    return !strncasecmp("true", buf, len);
}

// This class contains the shared global EGL objects, such as EGLDisplay
// and EGLConfig, which are re-used by CanvasContext
class GlobalContext {
public:
    static GlobalContext* get();

    // Returns true on success, false on failure
    void initialize();

    void usePBufferSurface();
    EGLSurface createSurface(EGLNativeWindowType window);
    void destroySurface(EGLSurface surface);

    void destroy();

    bool isCurrent(EGLSurface surface) { return mCurrentSurface == surface; }
    void makeCurrent(EGLSurface surface);
    void beginFrame(EGLSurface surface, EGLint* width, EGLint* height);
    void swapBuffers(EGLSurface surface);

    bool enableDirtyRegions(EGLSurface surface);

private:
    GlobalContext();
    // GlobalContext is never destroyed, method is purposely not implemented
    ~GlobalContext();

    void loadConfig();
    void createContext();
    void initAtlas();

    static GlobalContext* sContext;

    EGLDisplay mEglDisplay;
    EGLConfig mEglConfig;
    EGLContext mEglContext;
    EGLSurface mPBufferSurface;

    const bool mRequestDirtyRegions;
    bool mCanSetDirtyRegions;

    EGLSurface mCurrentSurface;
};

GlobalContext* GlobalContext::sContext = 0;

GlobalContext* GlobalContext::get() {
    if (!sContext) {
        sContext = new GlobalContext();
    }
    return sContext;
}

GlobalContext::GlobalContext()
        : mEglDisplay(EGL_NO_DISPLAY)
        , mEglConfig(0)
        , mEglContext(EGL_NO_CONTEXT)
        , mPBufferSurface(EGL_NO_SURFACE)
        , mRequestDirtyRegions(load_dirty_regions_property())
        , mCurrentSurface(EGL_NO_SURFACE) {
    mCanSetDirtyRegions = mRequestDirtyRegions;
    ALOGD("Render dirty regions requested: %s", mRequestDirtyRegions ? "true" : "false");
}

void GlobalContext::initialize() {
    if (mEglDisplay != EGL_NO_DISPLAY) return;

    mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    LOG_ALWAYS_FATAL_IF(mEglDisplay == EGL_NO_DISPLAY,
            "Failed to get EGL_DEFAULT_DISPLAY! err=%s", egl_error_str());

    EGLint major, minor;
    LOG_ALWAYS_FATAL_IF(eglInitialize(mEglDisplay, &major, &minor) == EGL_FALSE,
            "Failed to initialize display %p! err=%s", mEglDisplay, egl_error_str());

    ALOGI("Initialized EGL, version %d.%d", (int)major, (int)minor);

    loadConfig();
    createContext();
    usePBufferSurface();
    Caches::getInstance().init();
    initAtlas();
}

void GlobalContext::loadConfig() {
    EGLint swapBehavior = mCanSetDirtyRegions ? EGL_SWAP_BEHAVIOR_PRESERVED_BIT : 0;
    EGLint attribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0,
            EGL_CONFIG_CAVEAT, EGL_NONE,
            EGL_STENCIL_SIZE, Stencil::getStencilSize(),
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | swapBehavior,
            EGL_NONE
    };

    EGLint num_configs = 1;
    if (!eglChooseConfig(mEglDisplay, attribs, &mEglConfig, num_configs, &num_configs)
            || num_configs != 1) {
        // Failed to get a valid config
        if (mCanSetDirtyRegions) {
            ALOGW("Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED, retrying without...");
            // Try again without dirty regions enabled
            mCanSetDirtyRegions = false;
            loadConfig();
        } else {
            LOG_ALWAYS_FATAL("Failed to choose config, error = %s", egl_error_str());
        }
    }
}

void GlobalContext::createContext() {
    EGLint attribs[] = { EGL_CONTEXT_CLIENT_VERSION, GLES_VERSION, EGL_NONE };
    mEglContext = eglCreateContext(mEglDisplay, mEglConfig, EGL_NO_CONTEXT, attribs);
    LOG_ALWAYS_FATAL_IF(mEglContext == EGL_NO_CONTEXT,
        "Failed to create context, error = %s", egl_error_str());
}

void GlobalContext::initAtlas() {
    // TODO implement
    // For now just run without an atlas
}

void GlobalContext::usePBufferSurface() {
    LOG_ALWAYS_FATAL_IF(mEglDisplay == EGL_NO_DISPLAY,
            "usePBufferSurface() called on uninitialized GlobalContext!");

    if (mPBufferSurface == EGL_NO_SURFACE) {
        EGLint attribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        mPBufferSurface = eglCreatePbufferSurface(mEglDisplay, mEglConfig, attribs);
    }
    makeCurrent(mPBufferSurface);
}

EGLSurface GlobalContext::createSurface(EGLNativeWindowType window) {
    initialize();
    return eglCreateWindowSurface(mEglDisplay, mEglConfig, window, NULL);
}

void GlobalContext::destroySurface(EGLSurface surface) {
    if (isCurrent(surface)) {
        makeCurrent(EGL_NO_SURFACE);
    }
    if (!eglDestroySurface(mEglDisplay, surface)) {
        ALOGW("Failed to destroy surface %p, error=%s", (void*)surface, egl_error_str());
    }
}

void GlobalContext::destroy() {
    if (mEglDisplay == EGL_NO_DISPLAY) return;

    usePBufferSurface();
    if (Caches::hasInstance()) {
        Caches::getInstance().terminate();
    }

    eglDestroyContext(mEglDisplay, mEglContext);
    eglDestroySurface(mEglDisplay, mPBufferSurface);
    eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglTerminate(mEglDisplay);
    eglReleaseThread();

    mEglDisplay = EGL_NO_DISPLAY;
    mEglContext = EGL_NO_CONTEXT;
    mPBufferSurface = EGL_NO_SURFACE;
    mCurrentSurface = EGL_NO_SURFACE;
}

void GlobalContext::makeCurrent(EGLSurface surface) {
    if (isCurrent(surface)) return;

    if (surface == EGL_NO_SURFACE) {
        // If we are setting EGL_NO_SURFACE we don't care about any of the potential
        // return errors, which would only happen if mEglDisplay had already been
        // destroyed in which case the current context is already NO_CONTEXT
        eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    } else if (!eglMakeCurrent(mEglDisplay, surface, surface, mEglContext)) {
        LOG_ALWAYS_FATAL("Failed to make current on surface %p, error=%s",
                (void*)surface, egl_error_str());
    }
    mCurrentSurface = surface;
}

void GlobalContext::beginFrame(EGLSurface surface, EGLint* width, EGLint* height) {
    LOG_ALWAYS_FATAL_IF(surface == EGL_NO_SURFACE,
            "Tried to beginFrame on EGL_NO_SURFACE!");
    makeCurrent(surface);
    if (width) {
        eglQuerySurface(mEglDisplay, surface, EGL_WIDTH, width);
    }
    if (height) {
        eglQuerySurface(mEglDisplay, surface, EGL_HEIGHT, height);
    }
    eglBeginFrame(mEglDisplay, surface);
}

void GlobalContext::swapBuffers(EGLSurface surface) {
    eglSwapBuffers(mEglDisplay, surface);
    EGLint err = eglGetError();
    // TODO: Check whether we need to special case EGL_CONTEXT_LOST
    LOG_ALWAYS_FATAL_IF(err != EGL_SUCCESS,
            "Encountered EGL error %d %s during rendering", err, egl_error_str(err));
}

bool GlobalContext::enableDirtyRegions(EGLSurface surface) {
    if (!mRequestDirtyRegions) return false;

    if (mCanSetDirtyRegions) {
        if (!eglSurfaceAttrib(mEglDisplay, surface, EGL_SWAP_BEHAVIOR, EGL_BUFFER_PRESERVED)) {
            ALOGW("Failed to set EGL_SWAP_BEHAVIOR on surface %p, error=%s",
                    (void*) surface, egl_error_str());
            return false;
        }
        return true;
    }
    // Perhaps it is already enabled?
    EGLint value;
    if (!eglQuerySurface(mEglDisplay, surface, EGL_SWAP_BEHAVIOR, &value)) {
        ALOGW("Failed to query EGL_SWAP_BEHAVIOR on surface %p, error=%p",
                (void*) surface, egl_error_str());
        return false;
    }
    return value == EGL_BUFFER_PRESERVED;
}

CanvasContext::CanvasContext(bool translucent)
        : mRenderThread(RenderThread::getInstance())
        , mEglSurface(EGL_NO_SURFACE)
        , mDirtyRegionsEnabled(false)
        , mOpaque(!translucent)
        , mCanvas(0)
        , mHaveNewSurface(false)
        , mInvokeFunctorsPending(false)
        , mInvokeFunctorsTask(this) {
    mGlobalContext = GlobalContext::get();
}

CanvasContext::~CanvasContext() {
    removeFunctorsTask();
    destroyCanvas();
}

void CanvasContext::destroyCanvas() {
    if (mCanvas) {
        delete mCanvas;
        mCanvas = 0;
    }
    setSurface(NULL);
}

void CanvasContext::setSurface(EGLNativeWindowType window) {
    if (mEglSurface != EGL_NO_SURFACE) {
        mGlobalContext->destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (window) {
        mEglSurface = mGlobalContext->createSurface(window);
        LOG_ALWAYS_FATAL_IF(mEglSurface == EGL_NO_SURFACE,
                "Failed to create EGLSurface for window %p, eglErr = %s",
                (void*) window, egl_error_str());
    }

    if (mEglSurface != EGL_NO_SURFACE) {
        mDirtyRegionsEnabled = mGlobalContext->enableDirtyRegions(mEglSurface);
        mHaveNewSurface = true;
    }
}

void CanvasContext::swapBuffers() {
    mGlobalContext->swapBuffers(mEglSurface);
    mHaveNewSurface = false;
}

void CanvasContext::makeCurrent() {
    mGlobalContext->makeCurrent(mEglSurface);
}

bool CanvasContext::initialize(EGLNativeWindowType window) {
    if (mCanvas) return false;
    setSurface(window);
    makeCurrent();
    mCanvas = new OpenGLRenderer();
    mCanvas->initProperties();
    return true;
}

void CanvasContext::updateSurface(EGLNativeWindowType window) {
    setSurface(window);
    makeCurrent();
}

void CanvasContext::setup(int width, int height) {
    if (!mCanvas) return;
    mCanvas->setViewport(width, height);
}

void CanvasContext::setDisplayListData(RenderNode* displayList, DisplayListData* newData) {
    displayList->setData(newData);
}

void CanvasContext::processLayerUpdates(const Vector<DeferredLayerUpdater*>* layerUpdaters) {
    mGlobalContext->makeCurrent(mEglSurface);
    for (size_t i = 0; i < layerUpdaters->size(); i++) {
        DeferredLayerUpdater* update = layerUpdaters->itemAt(i);
        LOG_ALWAYS_FATAL_IF(!update->apply(), "Failed to update layer!");
        if (update->backingLayer()->deferredUpdateScheduled) {
            mCanvas->pushLayerUpdate(update->backingLayer());
        }
    }
}

void CanvasContext::drawDisplayList(RenderNode* displayList, Rect* dirty) {
    LOG_ALWAYS_FATAL_IF(!mCanvas || mEglSurface == EGL_NO_SURFACE,
            "drawDisplayList called on a context with no canvas or surface!");

    EGLint width, height;
    mGlobalContext->beginFrame(mEglSurface, &width, &height);
    if (width != mCanvas->getViewportWidth() || height != mCanvas->getViewportHeight()) {
        mCanvas->setViewport(width, height);
        dirty = NULL;
    } else if (!mDirtyRegionsEnabled || mHaveNewSurface) {
        dirty = NULL;
    }

    status_t status;
    if (dirty) {
        status = mCanvas->prepareDirty(dirty->left, dirty->top,
                dirty->right, dirty->bottom, mOpaque);
    } else {
        status = mCanvas->prepare(mOpaque);
    }

    Rect outBounds;
    status |= mCanvas->drawDisplayList(displayList, outBounds);
    handleFunctorStatus(status, outBounds);

    // TODO: Draw debug info
    // TODO: Performance tracking

    mCanvas->finish();

    if (status & DrawGlInfo::kStatusDrew) {
        swapBuffers();
    }
}

void InvokeFunctorsTask::run() {
    mContext->invokeFunctors();
}

void CanvasContext::attachFunctor(Functor* functor) {
    if (!mCanvas) return;

    mCanvas->attachFunctor(functor);
    removeFunctorsTask();
    queueFunctorsTask(0);
}

void CanvasContext::detachFunctor(Functor* functor) {
    if (!mCanvas) return;

    mCanvas->detachFunctor(functor);
}

void CanvasContext::invokeFunctors() {
    mInvokeFunctorsPending = false;

    if (!mCanvas) return;

    makeCurrent();
    Rect dirty;
    int status = mCanvas->invokeFunctors(dirty);
    handleFunctorStatus(status, dirty);
}

void CanvasContext::handleFunctorStatus(int status, const Rect& redrawClip) {
    if (status & DrawGlInfo::kStatusDraw) {
        // TODO: Invalidate the redrawClip
        // Do we need to post to ViewRootImpl like the current renderer?
        // Can we just enqueue ourselves to re-invoke the same display list?
        // Something else entirely? Does ChromiumView still want this in a
        // RenderThread world?
    }

    if (status & DrawGlInfo::kStatusInvoke) {
        queueFunctorsTask();
    }
}

void CanvasContext::removeFunctorsTask() {
    if (!mInvokeFunctorsPending) return;

    mInvokeFunctorsPending = false;
    mRenderThread.remove(&mInvokeFunctorsTask);
}

void CanvasContext::queueFunctorsTask(int delayMs) {
    if (mInvokeFunctorsPending) return;

    mInvokeFunctorsPending = true;
    mRenderThread.queueDelayed(&mInvokeFunctorsTask, delayMs);
}

bool CanvasContext::copyLayerInto(DeferredLayerUpdater* layer, SkBitmap* bitmap) {
    requireGlContext();
    layer->apply();
    return LayerRenderer::copyLayer(layer->backingLayer(), bitmap);
}

void CanvasContext::runWithGlContext(RenderTask* task) {
    requireGlContext();
    task->run();
}

void CanvasContext::requireGlContext() {
    if (mEglSurface != EGL_NO_SURFACE) {
        mGlobalContext->makeCurrent(mEglSurface);
    } else {
        mGlobalContext->usePBufferSurface();
    }
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
