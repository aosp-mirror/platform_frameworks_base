/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "pipeline/skia/SkiaOpenGLPipeline.h"

#include <GrBackendSurface.h>
#include <SkBlendMode.h>
#include <SkImageInfo.h>
#include <cutils/properties.h>
#include <gui/TraceUtils.h>
#include <include/gpu/ganesh/SkSurfaceGanesh.h>
#include <include/gpu/ganesh/gl/GrGLBackendSurface.h>
#include <include/gpu/gl/GrGLTypes.h>
#include <strings.h>

#include "DeferredLayerUpdater.h"
#include "FrameInfo.h"
#include "LightingInfo.h"
#include "hwui/Bitmap.h"
#include "pipeline/skia/LayerDrawable.h"
#include "pipeline/skia/SkiaGpuPipeline.h"
#include "pipeline/skia/SkiaProfileRenderer.h"
#include "private/hwui/DrawGlInfo.h"
#include "renderstate/RenderState.h"
#include "renderthread/EglManager.h"
#include "renderthread/Frame.h"
#include "renderthread/IRenderPipeline.h"
#include "utils/GLUtils.h"

using namespace android::uirenderer::renderthread;

namespace android {
namespace uirenderer {
namespace skiapipeline {

SkiaOpenGLPipeline::SkiaOpenGLPipeline(RenderThread& thread)
        : SkiaGpuPipeline(thread), mEglManager(thread.eglManager()) {
    thread.renderState().registerContextCallback(this);
}

SkiaOpenGLPipeline::~SkiaOpenGLPipeline() {
    mRenderThread.renderState().removeContextCallback(this);
}

MakeCurrentResult SkiaOpenGLPipeline::makeCurrent() {
    bool wasSurfaceless = mEglManager.isCurrent(EGL_NO_SURFACE);

    // In case the surface was destroyed (e.g. a previous trimMemory call) we
    // need to recreate it here.
    if (mHardwareBuffer) {
        mRenderThread.requireGlContext();
    } else if (!isSurfaceReady() && mNativeWindow) {
        setSurface(mNativeWindow.get(), mSwapBehavior);
    }

    EGLint error = 0;
    if (!mEglManager.makeCurrent(mEglSurface, &error)) {
        return MakeCurrentResult::AlreadyCurrent;
    }

    EGLint majorVersion = 0;
    eglQueryContext(eglGetCurrentDisplay(), eglGetCurrentContext(), EGL_CONTEXT_CLIENT_VERSION, &majorVersion);

    // Make sure read/draw buffer state of default framebuffer is GL_BACK for ES 3.X. Vendor implementations
    // disagree on the draw/read buffer state if the default framebuffer transitions from a surface
    // to EGL_NO_SURFACE and vice-versa. There was a related discussion within Khronos on this topic.
    // See https://cvs.khronos.org/bugzilla/show_bug.cgi?id=13534.
    // The discussion was not resolved with a clear consensus
    if (error == 0 && (majorVersion > 2) && wasSurfaceless && mEglSurface != EGL_NO_SURFACE) {
        GLint curReadFB = 0;
        GLint curDrawFB = 0;
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &curReadFB);
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &curDrawFB);

        GLint buffer = GL_NONE;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glGetIntegerv(GL_DRAW_BUFFER0, &buffer);
        if (buffer == GL_NONE) {
            const GLenum drawBuffer = GL_BACK;
            glDrawBuffers(1, &drawBuffer);
        }

        glGetIntegerv(GL_READ_BUFFER, &buffer);
        if (buffer == GL_NONE) {
            glReadBuffer(GL_BACK);
        }

        glBindFramebuffer(GL_READ_FRAMEBUFFER, curReadFB);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, curDrawFB);

        GL_CHECKPOINT(LOW);
    }

    return error ? MakeCurrentResult::Failed : MakeCurrentResult::Succeeded;
}

Frame SkiaOpenGLPipeline::getFrame() {
    LOG_ALWAYS_FATAL_IF(mEglSurface == EGL_NO_SURFACE,
                        "drawRenderNode called on a context with no surface!");
    return mEglManager.beginFrame(mEglSurface);
}

IRenderPipeline::DrawResult SkiaOpenGLPipeline::draw(
        const Frame& frame, const SkRect& screenDirty, const SkRect& dirty,
        const LightGeometry& lightGeometry, LayerUpdateQueue* layerUpdateQueue,
        const Rect& contentDrawBounds, bool opaque, const LightInfo& lightInfo,
        const std::vector<sp<RenderNode>>& renderNodes, FrameInfoVisualizer* profiler,
        const HardwareBufferRenderParams& bufferParams, std::mutex& profilerLock) {
    if (!isCapturingSkp() && !mHardwareBuffer) {
        mEglManager.damageFrame(frame, dirty);
    }

    SkColorType colorType = getSurfaceColorType();
    // setup surface for fbo0
    GrGLFramebufferInfo fboInfo;
    fboInfo.fFBOID = 0;
    if (colorType == kRGBA_F16_SkColorType) {
        fboInfo.fFormat = GL_RGBA16F;
    } else if (colorType == kN32_SkColorType) {
        // Note: The default preference of pixel format is RGBA_8888, when other
        // pixel format is available, we should branch out and do more check.
        fboInfo.fFormat = GL_RGBA8;
    } else if (colorType == kRGBA_1010102_SkColorType) {
        fboInfo.fFormat = GL_RGB10_A2;
    } else if (colorType == kAlpha_8_SkColorType) {
        fboInfo.fFormat = GL_R8;
    } else {
        LOG_ALWAYS_FATAL("Unsupported color type.");
    }

    auto backendRT = GrBackendRenderTargets::MakeGL(frame.width(), frame.height(), 0,
                                                    STENCIL_BUFFER_SIZE, fboInfo);

    SkSurfaceProps props(mColorMode == ColorMode::Default ? 0 : SkSurfaceProps::kAlwaysDither_Flag,
                         kUnknown_SkPixelGeometry);

    SkASSERT(mRenderThread.getGrContext() != nullptr);
    sk_sp<SkSurface> surface;
    SkMatrix preTransform;
    if (mHardwareBuffer) {
        surface = getBufferSkSurface(bufferParams);
        preTransform = bufferParams.getTransform();
    } else {
        surface = SkSurfaces::WrapBackendRenderTarget(mRenderThread.getGrContext(), backendRT,
                                                      getSurfaceOrigin(), colorType,
                                                      mSurfaceColorSpace, &props);
        preTransform = SkMatrix::I();
    }

    SkPoint lightCenter = preTransform.mapXY(lightGeometry.center.x, lightGeometry.center.y);
    LightGeometry localGeometry = lightGeometry;
    localGeometry.center.x = lightCenter.fX;
    localGeometry.center.y = lightCenter.fY;
    LightingInfo::updateLighting(localGeometry, lightInfo);
    renderFrame(*layerUpdateQueue, dirty, renderNodes, opaque, contentDrawBounds, surface,
                preTransform);

    // Draw visual debugging features
    if (CC_UNLIKELY(Properties::showDirtyRegions ||
                    ProfileType::None != Properties::getProfileType())) {
        std::scoped_lock lock(profilerLock);
        SkCanvas* profileCanvas = surface->getCanvas();
        SkiaProfileRenderer profileRenderer(profileCanvas, frame.width(), frame.height());
        profiler->draw(profileRenderer);
    }

    {
        ATRACE_NAME("flush commands");
        skgpu::ganesh::FlushAndSubmit(surface);
    }
    layerUpdateQueue->clear();

    // Log memory statistics
    if (CC_UNLIKELY(Properties::debugLevel != kDebugDisabled)) {
        dumpResourceCacheUsage();
    }

    return {true, IRenderPipeline::DrawResult::kUnknownTime, android::base::unique_fd{}};
}

bool SkiaOpenGLPipeline::swapBuffers(const Frame& frame, IRenderPipeline::DrawResult& drawResult,
                                     const SkRect& screenDirty, FrameInfo* currentFrameInfo,
                                     bool* requireSwap) {
    GL_CHECKPOINT(LOW);

    // Even if we decided to cancel the frame, from the perspective of jank
    // metrics the frame was swapped at this point
    currentFrameInfo->markSwapBuffers();

    if (mHardwareBuffer) {
        return false;
    }

    *requireSwap = drawResult.success || mEglManager.damageRequiresSwap();

    if (*requireSwap && (CC_UNLIKELY(!mEglManager.swapBuffers(frame, screenDirty)))) {
        return false;
    }

    return *requireSwap;
}

DeferredLayerUpdater* SkiaOpenGLPipeline::createTextureLayer() {
    mRenderThread.requireGlContext();
    return new DeferredLayerUpdater(mRenderThread.renderState());
}

void SkiaOpenGLPipeline::onContextDestroyed() {
    if (mEglSurface != EGL_NO_SURFACE) {
        mEglManager.destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }
}

void SkiaOpenGLPipeline::onStop() {
    if (mEglManager.isCurrent(mEglSurface)) {
        mEglManager.makeCurrent(EGL_NO_SURFACE);
    }
}

bool SkiaOpenGLPipeline::setSurface(ANativeWindow* surface, SwapBehavior swapBehavior) {
    mNativeWindow = surface;
    mSwapBehavior = swapBehavior;

    if (mEglSurface != EGL_NO_SURFACE) {
        mEglManager.destroySurface(mEglSurface);
        mEglSurface = EGL_NO_SURFACE;
    }

    if (surface) {
        mRenderThread.requireGlContext();
        auto newSurface = mEglManager.createSurface(surface, mColorMode, mSurfaceColorSpace);
        if (!newSurface) {
            return false;
        }
        mEglSurface = newSurface.unwrap();
    }

    if (mEglSurface != EGL_NO_SURFACE) {
        const bool preserveBuffer = (swapBehavior != SwapBehavior::kSwap_discardBuffer);
        mEglManager.setPreserveBuffer(mEglSurface, preserveBuffer);
        return true;
    }

    return false;
}

[[nodiscard]] android::base::unique_fd SkiaOpenGLPipeline::flush() {
    int fence = -1;
    EGLSyncKHR sync = EGL_NO_SYNC_KHR;
    mEglManager.createReleaseFence(true, &sync, &fence);
    // If a sync object is returned here then the device does not support native
    // fences, we block on the returned sync and return -1 as a file descriptor
    if (sync != EGL_NO_SYNC_KHR) {
        EGLDisplay display = mEglManager.eglDisplay();
        EGLint result = eglClientWaitSyncKHR(display, sync, 0, 1000000000);
        if (result == EGL_FALSE) {
            ALOGE("EglManager::createReleaseFence: error waiting for previous fence: %#x",
                  eglGetError());
        } else if (result == EGL_TIMEOUT_EXPIRED_KHR) {
            ALOGE("EglManager::createReleaseFence: timeout waiting for previous fence");
        }
        eglDestroySyncKHR(display, sync);
    }
    return android::base::unique_fd(fence);
}

bool SkiaOpenGLPipeline::isSurfaceReady() {
    return CC_UNLIKELY(mEglSurface != EGL_NO_SURFACE);
}

bool SkiaOpenGLPipeline::isContextReady() {
    return CC_LIKELY(mEglManager.hasEglContext());
}

void SkiaOpenGLPipeline::invokeFunctor(const RenderThread& thread, Functor* functor) {
    DrawGlInfo::Mode mode = DrawGlInfo::kModeProcessNoContext;
    if (thread.eglManager().hasEglContext()) {
        mode = DrawGlInfo::kModeProcess;
    }

    (*functor)(mode, nullptr);

    // If there's no context we don't need to reset as there's no gl state to save/restore
    if (mode != DrawGlInfo::kModeProcessNoContext) {
        thread.getGrContext()->resetContext();
    }
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
