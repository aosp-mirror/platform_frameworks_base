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

#define LOG_TAG "EglContext"

#include "EglManager.h"

#include <cutils/log.h>
#include <cutils/properties.h>

#include "../RenderState.h"
#include "RenderThread.h"

#define PROPERTY_RENDER_DIRTY_REGIONS "debug.hwui.render_dirty_regions"
#define GLES_VERSION 2

// Android-specific addition that is used to show when frames began in systrace
EGLAPI void EGLAPIENTRY eglBeginFrame(EGLDisplay dpy, EGLSurface surface);

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

EglManager::EglManager(RenderThread& thread)
        : mRenderThread(thread)
        , mEglDisplay(EGL_NO_DISPLAY)
        , mEglConfig(0)
        , mEglContext(EGL_NO_CONTEXT)
        , mPBufferSurface(EGL_NO_SURFACE)
        , mRequestDirtyRegions(load_dirty_regions_property())
        , mCurrentSurface(EGL_NO_SURFACE)
        , mAtlasMap(NULL)
        , mAtlasMapSize(0) {
    mCanSetDirtyRegions = mRequestDirtyRegions;
    ALOGD("Render dirty regions requested: %s", mRequestDirtyRegions ? "true" : "false");
}

void EglManager::initialize() {
    if (hasEglContext()) return;

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
    mRenderThread.renderState().onGLContextCreated();
    initAtlas();
}

bool EglManager::hasEglContext() {
    return mEglDisplay != EGL_NO_DISPLAY;
}

void EglManager::requireGlContext() {
    LOG_ALWAYS_FATAL_IF(mEglDisplay == EGL_NO_DISPLAY, "No EGL context");

    // We don't care *WHAT* surface is active, just that one is active to give
    // us access to the GL context
    if (mCurrentSurface == EGL_NO_SURFACE) {
        usePBufferSurface();
    }
}

void EglManager::loadConfig() {
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

void EglManager::createContext() {
    EGLint attribs[] = { EGL_CONTEXT_CLIENT_VERSION, GLES_VERSION, EGL_NONE };
    mEglContext = eglCreateContext(mEglDisplay, mEglConfig, EGL_NO_CONTEXT, attribs);
    LOG_ALWAYS_FATAL_IF(mEglContext == EGL_NO_CONTEXT,
        "Failed to create context, error = %s", egl_error_str());
}

void EglManager::setTextureAtlas(const sp<GraphicBuffer>& buffer,
        int64_t* map, size_t mapSize) {

    // Already initialized
    if (mAtlasBuffer.get()) {
        ALOGW("Multiple calls to setTextureAtlas!");
        delete map;
        return;
    }

    mAtlasBuffer = buffer;
    mAtlasMap = map;
    mAtlasMapSize = mapSize;

    if (hasEglContext()) {
        usePBufferSurface();
        initAtlas();
    }
}

void EglManager::initAtlas() {
    if (mAtlasBuffer.get()) {
        Caches::getInstance().assetAtlas.init(mAtlasBuffer, mAtlasMap, mAtlasMapSize);
    }
}

void EglManager::usePBufferSurface() {
    LOG_ALWAYS_FATAL_IF(mEglDisplay == EGL_NO_DISPLAY,
            "usePBufferSurface() called on uninitialized GlobalContext!");

    if (mPBufferSurface == EGL_NO_SURFACE) {
        EGLint attribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        mPBufferSurface = eglCreatePbufferSurface(mEglDisplay, mEglConfig, attribs);
    }
    makeCurrent(mPBufferSurface);
}

EGLSurface EglManager::createSurface(EGLNativeWindowType window) {
    initialize();
    EGLSurface surface = eglCreateWindowSurface(mEglDisplay, mEglConfig, window, NULL);
    LOG_ALWAYS_FATAL_IF(surface == EGL_NO_SURFACE,
            "Failed to create EGLSurface for window %p, eglErr = %s",
            (void*) window, egl_error_str());
    return surface;
}

void EglManager::destroySurface(EGLSurface surface) {
    if (isCurrent(surface)) {
        makeCurrent(EGL_NO_SURFACE);
    }
    if (!eglDestroySurface(mEglDisplay, surface)) {
        ALOGW("Failed to destroy surface %p, error=%s", (void*)surface, egl_error_str());
    }
}

void EglManager::destroy() {
    if (mEglDisplay == EGL_NO_DISPLAY) return;

    usePBufferSurface();
    if (Caches::hasInstance()) {
        Caches::getInstance().terminate();
    }

    mRenderThread.renderState().onGLContextDestroyed();
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

bool EglManager::makeCurrent(EGLSurface surface) {
    if (isCurrent(surface)) return false;

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
    return true;
}

void EglManager::beginFrame(EGLSurface surface, EGLint* width, EGLint* height) {
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

void EglManager::swapBuffers(EGLSurface surface) {
    eglSwapBuffers(mEglDisplay, surface);
    EGLint err = eglGetError();
    LOG_ALWAYS_FATAL_IF(err != EGL_SUCCESS,
            "Encountered EGL error %d %s during rendering", err, egl_error_str(err));
}

bool EglManager::enableDirtyRegions(EGLSurface surface) {
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

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
