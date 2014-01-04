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
#include <strings.h>

#include "../Caches.h"
#include "../Stencil.h"

#define PROPERTY_RENDER_DIRTY_REGIONS "debug.hwui.render_dirty_regions"
#define GLES_VERSION 2

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

    // Returns true if EGL was initialized,
    // false if it was already initialized
    bool initialize();

    bool usePBufferSurface();
    EGLSurface createSurface(EGLNativeWindowType window);
    void destroySurface(EGLSurface surface);

    void destroy();

    bool isCurrent(EGLSurface surface) { return mCurrentSurface == surface; }
    bool makeCurrent(EGLSurface surface);
    bool swapBuffers(EGLSurface surface);

    bool enableDirtyRegions(EGLSurface surface);

private:
    GlobalContext();
    // GlobalContext is never destroyed, method is purposely not implemented
    ~GlobalContext();

    bool loadConfig();
    bool createContext();

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

bool GlobalContext::initialize() {
    if (mEglDisplay != EGL_NO_DISPLAY) return false;

    mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (mEglDisplay == EGL_NO_DISPLAY) {
        ALOGE("Failed to get EGL_DEFAULT_DISPLAY! err=%s", egl_error_str());
        return false;
    }

    EGLint major, minor;
    if (eglInitialize(mEglDisplay, &major, &minor) == EGL_FALSE) {
        ALOGE("Failed to initialize display %p! err=%s", mEglDisplay, egl_error_str());
        return false;
    }
    ALOGI("Initialized EGL, version %d.%d", (int)major, (int)minor);

    if (!loadConfig()) {
        return false;
    }
    if (!createContext()) {
        return false;
    }

    return true;
}

bool GlobalContext::loadConfig() {
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
            ALOGE("Failed to choose config, error = %s", egl_error_str());
            return false;
        }
    }
    return true;
}

bool GlobalContext::createContext() {
    EGLint attribs[] = { EGL_CONTEXT_CLIENT_VERSION, GLES_VERSION, EGL_NONE };
    mEglContext = eglCreateContext(mEglDisplay, mEglConfig, EGL_NO_CONTEXT, attribs);
    if (mEglContext == EGL_NO_CONTEXT) {
        ALOGE("Failed to create context, error = %s", egl_error_str());
        return false;
    }
    return true;
}

bool GlobalContext::usePBufferSurface() {
    if (mEglDisplay == EGL_NO_DISPLAY) return false;

    if (mPBufferSurface == EGL_NO_SURFACE) {
        EGLint attribs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
        mPBufferSurface = eglCreatePbufferSurface(mEglDisplay, mEglConfig, attribs);
    }
    return makeCurrent(mPBufferSurface);
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

bool GlobalContext::makeCurrent(EGLSurface surface) {
    if (isCurrent(surface)) return true;

    if (surface == EGL_NO_SURFACE) {
        // If we are setting EGL_NO_SURFACE we don't care about any of the potential
        // return errors, which would only happen if mEglDisplay had already been
        // destroyed in which case the current context is already NO_CONTEXT
        eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    } else if (!eglMakeCurrent(mEglDisplay, surface, surface, mEglContext)) {
        ALOGE("Failed to make current on surface %p, error=%s", (void*)surface, egl_error_str());
        return false;
    }
    mCurrentSurface = surface;
    return true;
}

bool GlobalContext::swapBuffers(EGLSurface surface) {
    if (!eglSwapBuffers(mEglDisplay, surface)) {
        ALOGW("eglSwapBuffers failed on surface %p, error=%s", (void*)surface, egl_error_str());
        return false;
    }
    return true;
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

CanvasContext::CanvasContext()
        : mEglSurface(EGL_NO_SURFACE)
        , mDirtyRegionsEnabled(false) {
    mGlobalContext = GlobalContext::get();
}

CanvasContext::~CanvasContext() {
    setSurface(NULL);
}

bool CanvasContext::setSurface(EGLNativeWindowType window) {
    if (mEglSurface != EGL_NO_SURFACE) {
        mGlobalContext->destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (window) {
        mEglSurface = mGlobalContext->createSurface(window);
    }

    if (mEglSurface != EGL_NO_SURFACE) {
        mDirtyRegionsEnabled = mGlobalContext->enableDirtyRegions(mEglSurface);
    }
    return !window || mEglSurface != EGL_NO_SURFACE;
}

bool CanvasContext::swapBuffers() {
    return mGlobalContext->swapBuffers(mEglSurface);
}

bool CanvasContext::makeCurrent() {
    return mGlobalContext->makeCurrent(mEglSurface);
}

bool CanvasContext::useGlobalPBufferSurface() {
    return GlobalContext::get()->usePBufferSurface();
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
