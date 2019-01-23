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

#include "EglManager.h"

#include <cutils/properties.h>
#include <log/log.h>
#include <private/gui/SyncFeatures.h>
#include <utils/Trace.h>
#include "utils/Color.h"
#include "utils/StringUtils.h"

#include "Frame.h"
#include "Properties.h"

#include <EGL/eglext.h>
#include <GLES/gl.h>

#include <string>
#include <vector>
#include <system/window.h>
#include <gui/Surface.h>

#define GLES_VERSION 2

// Android-specific addition that is used to show when frames began in systrace
EGLAPI void EGLAPIENTRY eglBeginFrame(EGLDisplay dpy, EGLSurface surface);

namespace android {
namespace uirenderer {
namespace renderthread {

#define ERROR_CASE(x) \
    case x:           \
        return #x;
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
const char* EglManager::eglErrorString() {
    return egl_error_str(eglGetError());
}

static struct {
    bool bufferAge = false;
    bool setDamage = false;
    bool noConfigContext = false;
    bool pixelFormatFloat = false;
    bool glColorSpace = false;
    bool scRGB = false;
    bool displayP3 = false;
    bool contextPriority = false;
    bool surfacelessContext = false;
} EglExtensions;

EglManager::EglManager()
        : mEglDisplay(EGL_NO_DISPLAY)
        , mEglConfig(nullptr)
        , mEglConfigWideGamut(nullptr)
        , mEglContext(EGL_NO_CONTEXT)
        , mPBufferSurface(EGL_NO_SURFACE)
        , mCurrentSurface(EGL_NO_SURFACE)
        , mHasWideColorGamutSupport(false) {}

EglManager::~EglManager() {
    if (hasEglContext()) {
        ALOGW("~EglManager() leaked an EGL context");
    }
}

void EglManager::initialize() {
    if (hasEglContext()) return;

    ATRACE_NAME("Creating EGLContext");

    mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    LOG_ALWAYS_FATAL_IF(mEglDisplay == EGL_NO_DISPLAY, "Failed to get EGL_DEFAULT_DISPLAY! err=%s",
                        eglErrorString());

    EGLint major, minor;
    LOG_ALWAYS_FATAL_IF(eglInitialize(mEglDisplay, &major, &minor) == EGL_FALSE,
                        "Failed to initialize display %p! err=%s", mEglDisplay, eglErrorString());

    ALOGV("Initialized EGL, version %d.%d", (int)major, (int)minor);

    initExtensions();

    // Now that extensions are loaded, pick a swap behavior
    if (Properties::enablePartialUpdates) {
        // An Adreno driver bug is causing rendering problems for SkiaGL with
        // buffer age swap behavior (b/31957043).  To temporarily workaround,
        // we will use preserved swap behavior.
        if (Properties::useBufferAge && EglExtensions.bufferAge) {
            mSwapBehavior = SwapBehavior::BufferAge;
        } else {
            mSwapBehavior = SwapBehavior::Preserved;
        }
    }

    loadConfigs();
    createContext();
    createPBufferSurface();
    makeCurrent(mPBufferSurface, nullptr, /* force */ true);

    skcms_Matrix3x3 wideColorGamut;
    LOG_ALWAYS_FATAL_IF(!DeviceInfo::get()->getWideColorSpace()->toXYZD50(&wideColorGamut),
                        "Could not get gamut matrix from wideColorSpace");
    bool hasWideColorSpaceExtension = false;
    if (memcmp(&wideColorGamut, &SkNamedGamut::kDCIP3, sizeof(wideColorGamut)) == 0) {
        hasWideColorSpaceExtension = EglExtensions.displayP3;
    } else if (memcmp(&wideColorGamut, &SkNamedGamut::kSRGB, sizeof(wideColorGamut)) == 0) {
        hasWideColorSpaceExtension = EglExtensions.scRGB;
    } else {
        LOG_ALWAYS_FATAL("Unsupported wide color space.");
    }
    mHasWideColorGamutSupport = EglExtensions.glColorSpace && hasWideColorSpaceExtension &&
                                mEglConfigWideGamut != EGL_NO_CONFIG_KHR;
}

EGLConfig EglManager::load8BitsConfig(EGLDisplay display, EglManager::SwapBehavior swapBehavior) {
    EGLint eglSwapBehavior =
            (swapBehavior == SwapBehavior::Preserved) ? EGL_SWAP_BEHAVIOR_PRESERVED_BIT : 0;
    EGLint attribs[] = {EGL_RENDERABLE_TYPE,
                        EGL_OPENGL_ES2_BIT,
                        EGL_RED_SIZE,
                        8,
                        EGL_GREEN_SIZE,
                        8,
                        EGL_BLUE_SIZE,
                        8,
                        EGL_ALPHA_SIZE,
                        8,
                        EGL_DEPTH_SIZE,
                        0,
                        EGL_CONFIG_CAVEAT,
                        EGL_NONE,
                        EGL_STENCIL_SIZE,
                        STENCIL_BUFFER_SIZE,
                        EGL_SURFACE_TYPE,
                        EGL_WINDOW_BIT | eglSwapBehavior,
                        EGL_NONE};
    EGLConfig config = EGL_NO_CONFIG_KHR;
    EGLint numConfigs = 1;
    if (!eglChooseConfig(display, attribs, &config, numConfigs, &numConfigs) ||
        numConfigs != 1) {
        return EGL_NO_CONFIG_KHR;
    }
    return config;
}

EGLConfig EglManager::loadFP16Config(EGLDisplay display, SwapBehavior swapBehavior) {
    EGLint eglSwapBehavior =
            (swapBehavior == SwapBehavior::Preserved) ? EGL_SWAP_BEHAVIOR_PRESERVED_BIT : 0;
    // If we reached this point, we have a valid swap behavior
    EGLint attribs[] = {EGL_RENDERABLE_TYPE,
                        EGL_OPENGL_ES2_BIT,
                        EGL_COLOR_COMPONENT_TYPE_EXT,
                        EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT,
                        EGL_RED_SIZE,
                        16,
                        EGL_GREEN_SIZE,
                        16,
                        EGL_BLUE_SIZE,
                        16,
                        EGL_ALPHA_SIZE,
                        16,
                        EGL_DEPTH_SIZE,
                        0,
                        EGL_STENCIL_SIZE,
                        STENCIL_BUFFER_SIZE,
                        EGL_SURFACE_TYPE,
                        EGL_WINDOW_BIT | eglSwapBehavior,
                        EGL_NONE};
    EGLConfig config = EGL_NO_CONFIG_KHR;
    EGLint numConfigs = 1;
    if (!eglChooseConfig(display, attribs, &config, numConfigs, &numConfigs) ||
        numConfigs != 1) {
        return EGL_NO_CONFIG_KHR;
    }
    return config;
}

void EglManager::initExtensions() {
    auto extensions = StringUtils::split(eglQueryString(mEglDisplay, EGL_EXTENSIONS));

    // For our purposes we don't care if EGL_BUFFER_AGE is a result of
    // EGL_EXT_buffer_age or EGL_KHR_partial_update as our usage is covered
    // under EGL_KHR_partial_update and we don't need the expanded scope
    // that EGL_EXT_buffer_age provides.
    EglExtensions.bufferAge =
            extensions.has("EGL_EXT_buffer_age") || extensions.has("EGL_KHR_partial_update");
    EglExtensions.setDamage = extensions.has("EGL_KHR_partial_update");
    LOG_ALWAYS_FATAL_IF(!extensions.has("EGL_KHR_swap_buffers_with_damage"),
                        "Missing required extension EGL_KHR_swap_buffers_with_damage");

    EglExtensions.glColorSpace = extensions.has("EGL_KHR_gl_colorspace");
    EglExtensions.noConfigContext = extensions.has("EGL_KHR_no_config_context");
    EglExtensions.pixelFormatFloat = extensions.has("EGL_EXT_pixel_format_float");
    EglExtensions.scRGB = extensions.has("EGL_EXT_gl_colorspace_scrgb");
    EglExtensions.displayP3 = extensions.has("EGL_EXT_gl_colorspace_display_p3_passthrough");
    EglExtensions.contextPriority = extensions.has("EGL_IMG_context_priority");
    EglExtensions.surfacelessContext = extensions.has("EGL_KHR_surfaceless_context");
}

bool EglManager::hasEglContext() {
    return mEglDisplay != EGL_NO_DISPLAY;
}

void EglManager::loadConfigs() {
    ALOGD("Swap behavior %d", static_cast<int>(mSwapBehavior));

    // Note: The default pixel format is RGBA_8888, when other formats are
    // available, we should check the target pixel format and configure the
    // attributes list properly.
    mEglConfig = load8BitsConfig(mEglDisplay, mSwapBehavior);
    if (mEglConfig == EGL_NO_CONFIG_KHR) {
        if (mSwapBehavior == SwapBehavior::Preserved) {
            // Try again without dirty regions enabled
            ALOGW("Failed to choose config with EGL_SWAP_BEHAVIOR_PRESERVED, retrying without...");
            mSwapBehavior = SwapBehavior::Discard;
            ALOGD("Swap behavior %d", static_cast<int>(mSwapBehavior));
            mEglConfig = load8BitsConfig(mEglDisplay, mSwapBehavior);
        } else {
            // Failed to get a valid config
            LOG_ALWAYS_FATAL("Failed to choose config, error = %s", eglErrorString());
        }
    }
    SkColorType wideColorType = DeviceInfo::get()->getWideColorType();

    // When we reach this point, we have a valid swap behavior
    if (wideColorType == SkColorType::kRGBA_F16_SkColorType && EglExtensions.pixelFormatFloat) {
        mEglConfigWideGamut = loadFP16Config(mEglDisplay, mSwapBehavior);
        if (mEglConfigWideGamut == EGL_NO_CONFIG_KHR) {
            ALOGE("Device claims wide gamut support, cannot find matching config, error = %s",
                    eglErrorString());
            EglExtensions.pixelFormatFloat = false;
        }
    } else if (wideColorType == SkColorType::kN32_SkColorType) {
        mEglConfigWideGamut = load8BitsConfig(mEglDisplay, mSwapBehavior);
    }
}

void EglManager::createContext() {
    std::vector<EGLint> contextAttributes;
    contextAttributes.reserve(5);
    contextAttributes.push_back(EGL_CONTEXT_CLIENT_VERSION);
    contextAttributes.push_back(GLES_VERSION);
    if (Properties::contextPriority != 0 && EglExtensions.contextPriority) {
        contextAttributes.push_back(EGL_CONTEXT_PRIORITY_LEVEL_IMG);
        contextAttributes.push_back(Properties::contextPriority);
    }
    contextAttributes.push_back(EGL_NONE);
    mEglContext = eglCreateContext(
            mEglDisplay, EglExtensions.noConfigContext ? ((EGLConfig) nullptr) : mEglConfig,
            EGL_NO_CONTEXT, contextAttributes.data());
    LOG_ALWAYS_FATAL_IF(mEglContext == EGL_NO_CONTEXT, "Failed to create context, error = %s",
                        eglErrorString());
}

void EglManager::createPBufferSurface() {
    LOG_ALWAYS_FATAL_IF(mEglDisplay == EGL_NO_DISPLAY,
                        "usePBufferSurface() called on uninitialized GlobalContext!");

    if (mPBufferSurface == EGL_NO_SURFACE && !EglExtensions.surfacelessContext) {
        EGLint attribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        mPBufferSurface = eglCreatePbufferSurface(mEglDisplay, mEglConfig, attribs);
    }
}

Result<EGLSurface, EGLint> EglManager::createSurface(EGLNativeWindowType window,
                                                     ColorMode colorMode,
                                                     sk_sp<SkColorSpace> colorSpace) {
    LOG_ALWAYS_FATAL_IF(!hasEglContext(), "Not initialized");

    bool wideColorGamut = colorMode == ColorMode::WideColorGamut && mHasWideColorGamutSupport &&
                          EglExtensions.noConfigContext;

    // The color space we want to use depends on whether linear blending is turned
    // on and whether the app has requested wide color gamut rendering. When wide
    // color gamut rendering is off, the app simply renders in the display's native
    // color gamut.
    //
    // When wide gamut rendering is off:
    // - Blending is done by default in gamma space, which requires using a
    //   linear EGL color space (the GPU uses the color values as is)
    // - If linear blending is on, we must use the non-linear EGL color space
    //   (the GPU will perform sRGB to linear and linear to SRGB conversions
    //   before and after blending)
    //
    // When wide gamut rendering is on we cannot rely on the GPU performing
    // linear blending for us. We use two different color spaces to tag the
    // surface appropriately for SurfaceFlinger:
    // - Gamma blending (default) requires the use of the non-linear color space
    // - Linear blending requires the use of the linear color space

    // Not all Android targets support the EGL_GL_COLORSPACE_KHR extension
    // We insert to placeholders to set EGL_GL_COLORSPACE_KHR and its value.
    // According to section 3.4.1 of the EGL specification, the attributes
    // list is considered empty if the first entry is EGL_NONE
    EGLint attribs[] = {EGL_NONE, EGL_NONE, EGL_NONE};

    if (EglExtensions.glColorSpace) {
        attribs[0] = EGL_GL_COLORSPACE_KHR;
        if (wideColorGamut) {
            skcms_Matrix3x3 colorGamut;
            LOG_ALWAYS_FATAL_IF(!colorSpace->toXYZD50(&colorGamut),
                                "Could not get gamut matrix from color space");
            if (memcmp(&colorGamut, &SkNamedGamut::kDCIP3, sizeof(colorGamut)) == 0) {
                attribs[1] = EGL_GL_COLORSPACE_DISPLAY_P3_PASSTHROUGH_EXT;
            } else if (memcmp(&colorGamut, &SkNamedGamut::kSRGB, sizeof(colorGamut)) == 0) {
                attribs[1] = EGL_GL_COLORSPACE_SCRGB_EXT;
            } else {
                LOG_ALWAYS_FATAL("Unreachable: unsupported wide color space.");
            }
        } else {
            attribs[1] = EGL_GL_COLORSPACE_LINEAR_KHR;
        }
    }

    EGLSurface surface = eglCreateWindowSurface(
            mEglDisplay, wideColorGamut ? mEglConfigWideGamut : mEglConfig, window, attribs);
    if (surface == EGL_NO_SURFACE) {
        return Error<EGLint> { eglGetError() };
    }

    if (mSwapBehavior != SwapBehavior::Preserved) {
        LOG_ALWAYS_FATAL_IF(eglSurfaceAttrib(mEglDisplay, surface, EGL_SWAP_BEHAVIOR,
                                             EGL_BUFFER_DESTROYED) == EGL_FALSE,
                            "Failed to set swap behavior to destroyed for window %p, eglErr = %s",
                            (void*)window, eglErrorString());
    }

    return surface;
}

void EglManager::destroySurface(EGLSurface surface) {
    if (isCurrent(surface)) {
        makeCurrent(EGL_NO_SURFACE);
    }
    if (!eglDestroySurface(mEglDisplay, surface)) {
        ALOGW("Failed to destroy surface %p, error=%s", (void*)surface, eglErrorString());
    }
}

void EglManager::destroy() {
    if (mEglDisplay == EGL_NO_DISPLAY) return;

    eglDestroyContext(mEglDisplay, mEglContext);
    if (mPBufferSurface != EGL_NO_SURFACE) {
        eglDestroySurface(mEglDisplay, mPBufferSurface);
    }
    eglMakeCurrent(mEglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglTerminate(mEglDisplay);
    eglReleaseThread();

    mEglDisplay = EGL_NO_DISPLAY;
    mEglContext = EGL_NO_CONTEXT;
    mPBufferSurface = EGL_NO_SURFACE;
    mCurrentSurface = EGL_NO_SURFACE;
}

bool EglManager::makeCurrent(EGLSurface surface, EGLint* errOut, bool force) {
    if (!force && isCurrent(surface)) return false;

    if (surface == EGL_NO_SURFACE) {
        // Ensure we always have a valid surface & context
        surface = mPBufferSurface;
    }
    if (!eglMakeCurrent(mEglDisplay, surface, surface, mEglContext)) {
        if (errOut) {
            *errOut = eglGetError();
            ALOGW("Failed to make current on surface %p, error=%s", (void*)surface,
                  egl_error_str(*errOut));
        } else {
            LOG_ALWAYS_FATAL("Failed to make current on surface %p, error=%s", (void*)surface,
                             eglErrorString());
        }
    }
    mCurrentSurface = surface;
    if (Properties::disableVsync) {
        eglSwapInterval(mEglDisplay, 0);
    }
    return true;
}

EGLint EglManager::queryBufferAge(EGLSurface surface) {
    switch (mSwapBehavior) {
        case SwapBehavior::Discard:
            return 0;
        case SwapBehavior::Preserved:
            return 1;
        case SwapBehavior::BufferAge:
            EGLint bufferAge;
            eglQuerySurface(mEglDisplay, surface, EGL_BUFFER_AGE_EXT, &bufferAge);
            return bufferAge;
    }
    return 0;
}

Frame EglManager::beginFrame(EGLSurface surface) {
    LOG_ALWAYS_FATAL_IF(surface == EGL_NO_SURFACE, "Tried to beginFrame on EGL_NO_SURFACE!");
    makeCurrent(surface);
    Frame frame;
    frame.mSurface = surface;
    eglQuerySurface(mEglDisplay, surface, EGL_WIDTH, &frame.mWidth);
    eglQuerySurface(mEglDisplay, surface, EGL_HEIGHT, &frame.mHeight);
    frame.mBufferAge = queryBufferAge(surface);
    eglBeginFrame(mEglDisplay, surface);
    return frame;
}

void EglManager::damageFrame(const Frame& frame, const SkRect& dirty) {
#ifdef EGL_KHR_partial_update
    if (EglExtensions.setDamage && mSwapBehavior == SwapBehavior::BufferAge) {
        EGLint rects[4];
        frame.map(dirty, rects);
        if (!eglSetDamageRegionKHR(mEglDisplay, frame.mSurface, rects, 1)) {
            LOG_ALWAYS_FATAL("Failed to set damage region on surface %p, error=%s",
                             (void*)frame.mSurface, eglErrorString());
        }
    }
#endif
}

bool EglManager::damageRequiresSwap() {
    return EglExtensions.setDamage && mSwapBehavior == SwapBehavior::BufferAge;
}

bool EglManager::swapBuffers(const Frame& frame, const SkRect& screenDirty) {
    if (CC_UNLIKELY(Properties::waitForGpuCompletion)) {
        ATRACE_NAME("Finishing GPU work");
        fence();
    }

    EGLint rects[4];
    frame.map(screenDirty, rects);
    eglSwapBuffersWithDamageKHR(mEglDisplay, frame.mSurface, rects, screenDirty.isEmpty() ? 0 : 1);

    EGLint err = eglGetError();
    if (CC_LIKELY(err == EGL_SUCCESS)) {
        return true;
    }
    if (err == EGL_BAD_SURFACE || err == EGL_BAD_NATIVE_WINDOW) {
        // For some reason our surface was destroyed out from under us
        // This really shouldn't happen, but if it does we can recover easily
        // by just not trying to use the surface anymore
        ALOGW("swapBuffers encountered EGL error %d on %p, halting rendering...", err,
              frame.mSurface);
        return false;
    }
    LOG_ALWAYS_FATAL("Encountered EGL error %d %s during rendering", err, egl_error_str(err));
    // Impossible to hit this, but the compiler doesn't know that
    return false;
}

void EglManager::fence() {
    EGLSyncKHR fence = eglCreateSyncKHR(mEglDisplay, EGL_SYNC_FENCE_KHR, NULL);
    eglClientWaitSyncKHR(mEglDisplay, fence, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, EGL_FOREVER_KHR);
    eglDestroySyncKHR(mEglDisplay, fence);
}

bool EglManager::setPreserveBuffer(EGLSurface surface, bool preserve) {
    if (mSwapBehavior != SwapBehavior::Preserved) return false;

    bool preserved = eglSurfaceAttrib(mEglDisplay, surface, EGL_SWAP_BEHAVIOR,
                                      preserve ? EGL_BUFFER_PRESERVED : EGL_BUFFER_DESTROYED);
    if (!preserved) {
        ALOGW("Failed to set EGL_SWAP_BEHAVIOR on surface %p, error=%s", (void*)surface,
              eglErrorString());
        // Maybe it's already set?
        EGLint swapBehavior;
        if (eglQuerySurface(mEglDisplay, surface, EGL_SWAP_BEHAVIOR, &swapBehavior)) {
            preserved = (swapBehavior == EGL_BUFFER_PRESERVED);
        } else {
            ALOGW("Failed to query EGL_SWAP_BEHAVIOR on surface %p, error=%p", (void*)surface,
                  eglErrorString());
        }
    }

    return preserved;
}

status_t EglManager::fenceWait(sp<Fence>& fence) {
    if (!hasEglContext()) {
        ALOGE("EglManager::fenceWait: EGLDisplay not initialized");
        return INVALID_OPERATION;
    }

    if (SyncFeatures::getInstance().useWaitSync() &&
        SyncFeatures::getInstance().useNativeFenceSync()) {
        // Block GPU on the fence.
        // Create an EGLSyncKHR from the current fence.
        int fenceFd = fence->dup();
        if (fenceFd == -1) {
            ALOGE("EglManager::fenceWait: error dup'ing fence fd: %d", errno);
            return -errno;
        }
        EGLint attribs[] = {
            EGL_SYNC_NATIVE_FENCE_FD_ANDROID, fenceFd,
            EGL_NONE
        };
        EGLSyncKHR sync = eglCreateSyncKHR(mEglDisplay,
                EGL_SYNC_NATIVE_FENCE_ANDROID, attribs);
        if (sync == EGL_NO_SYNC_KHR) {
            close(fenceFd);
            ALOGE("EglManager::fenceWait: error creating EGL fence: %#x", eglGetError());
            return UNKNOWN_ERROR;
        }

        // XXX: The spec draft is inconsistent as to whether this should
        // return an EGLint or void.  Ignore the return value for now, as
        // it's not strictly needed.
        eglWaitSyncKHR(mEglDisplay, sync, 0);
        EGLint eglErr = eglGetError();
        eglDestroySyncKHR(mEglDisplay, sync);
        if (eglErr != EGL_SUCCESS) {
            ALOGE("EglManager::fenceWait: error waiting for EGL fence: %#x", eglErr);
            return UNKNOWN_ERROR;
        }
    } else {
        // Block CPU on the fence.
        status_t err = fence->waitForever("EglManager::fenceWait");
        if (err != NO_ERROR) {
            ALOGE("EglManager::fenceWait: error waiting for fence: %d", err);
            return err;
        }
    }
    return OK;
}

status_t EglManager::createReleaseFence(bool useFenceSync, EGLSyncKHR* eglFence,
        sp<Fence>& nativeFence) {
    if (!hasEglContext()) {
        ALOGE("EglManager::createReleaseFence: EGLDisplay not initialized");
        return INVALID_OPERATION;
    }

    if (SyncFeatures::getInstance().useNativeFenceSync()) {
        EGLSyncKHR sync = eglCreateSyncKHR(mEglDisplay,
                EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
        if (sync == EGL_NO_SYNC_KHR) {
            ALOGE("EglManager::createReleaseFence: error creating EGL fence: %#x",
                    eglGetError());
            return UNKNOWN_ERROR;
        }
        glFlush();
        int fenceFd = eglDupNativeFenceFDANDROID(mEglDisplay, sync);
        eglDestroySyncKHR(mEglDisplay, sync);
        if (fenceFd == EGL_NO_NATIVE_FENCE_FD_ANDROID) {
            ALOGE("EglManager::createReleaseFence: error dup'ing native fence "
                    "fd: %#x", eglGetError());
            return UNKNOWN_ERROR;
        }
        nativeFence = new Fence(fenceFd);
        *eglFence = EGL_NO_SYNC_KHR;
    } else if (useFenceSync && SyncFeatures::getInstance().useFenceSync()) {
        if (*eglFence != EGL_NO_SYNC_KHR) {
            // There is already a fence for the current slot.  We need to
            // wait on that before replacing it with another fence to
            // ensure that all outstanding buffer accesses have completed
            // before the producer accesses it.
            EGLint result = eglClientWaitSyncKHR(mEglDisplay, *eglFence, 0, 1000000000);
            if (result == EGL_FALSE) {
                ALOGE("EglManager::createReleaseFence: error waiting for previous fence: %#x",
                        eglGetError());
                return UNKNOWN_ERROR;
            } else if (result == EGL_TIMEOUT_EXPIRED_KHR) {
                ALOGE("EglManager::createReleaseFence: timeout waiting for previous fence");
                return TIMED_OUT;
            }
            eglDestroySyncKHR(mEglDisplay, *eglFence);
        }

        // Create a fence for the outstanding accesses in the current
        // OpenGL ES context.
        *eglFence = eglCreateSyncKHR(mEglDisplay, EGL_SYNC_FENCE_KHR, nullptr);
        if (*eglFence == EGL_NO_SYNC_KHR) {
            ALOGE("EglManager::createReleaseFence: error creating fence: %#x", eglGetError());
            return UNKNOWN_ERROR;
        }
        glFlush();
    }
    return OK;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
