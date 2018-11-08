/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "VkInteropFunctorDrawable.h"
#include <private/hwui/DrawGlInfo.h>

#include "renderthread/EglManager.h"
#include "thread/ThreadBase.h"
#include "utils/TimeUtils.h"
#include <thread>
#include <utils/Color.h>
#include <utils/Trace.h>
#include <utils/TraceUtils.h>

#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>

#include <utils/GLUtils.h>

namespace android {
namespace uirenderer {
namespace skiapipeline {

static std::mutex sLock{};
static ThreadBase* sGLDrawThread = nullptr;
static renderthread::EglManager sEglManager;

// ScopedDrawRequest makes sure a GL thread is started and EGL context is initialized on it.
class ScopedDrawRequest {
public:
    ScopedDrawRequest() { beginDraw(); }
private:
    void beginDraw() {
        std::lock_guard{sLock};

        if (!sGLDrawThread) {
            sGLDrawThread = new ThreadBase{};
        }

        if (!sGLDrawThread->isRunning()) {
            sGLDrawThread->start("GLFunctorThread");
        }

        if (!sEglManager.hasEglContext()) {
            sGLDrawThread->queue().runSync([]() {
                sEglManager.initialize();
            });
        }
    }
};

void VkInteropFunctorDrawable::vkInvokeFunctor(Functor* functor) {
    ScopedDrawRequest _drawRequest{};
    sGLDrawThread->queue().runSync([&]() {
        EGLDisplay display = sEglManager.eglDisplay();
        DrawGlInfo::Mode mode = DrawGlInfo::kModeProcessNoContext;
        if (display != EGL_NO_DISPLAY) {
            mode = DrawGlInfo::kModeProcess;
        }
        (*functor)(mode, nullptr);
    });
}

#define FENCE_TIMEOUT 2000000000

void VkInteropFunctorDrawable::onDraw(SkCanvas* canvas) {
    ATRACE_CALL();

    if (canvas->getGrContext() == nullptr) {
        SkDEBUGF(("Attempting to draw VkInteropFunctor into an unsupported surface"));
        return;
    }

    ScopedDrawRequest _drawRequest{};

    SkImageInfo surfaceInfo = canvas->imageInfo();

    if (!mFrameBuffer.get() || mFBInfo != surfaceInfo) {
        // Buffer will be used as an OpenGL ES render target.
        mFrameBuffer = new GraphicBuffer(
                 //TODO: try to reduce the size of the buffer: possibly by using clip bounds.
                 static_cast<uint32_t>(surfaceInfo.width()),
                 static_cast<uint32_t>(surfaceInfo.height()),
                 ColorTypeToPixelFormat(surfaceInfo.colorType()),
                 GraphicBuffer::USAGE_HW_TEXTURE | GraphicBuffer::USAGE_SW_WRITE_NEVER |
                         GraphicBuffer::USAGE_SW_READ_NEVER | GraphicBuffer::USAGE_HW_RENDER,
                 std::string("VkInteropFunctorDrawable::onDraw pid [") + std::to_string(getpid()) +
                         "]");
        status_t error = mFrameBuffer->initCheck();
        if (error < 0) {
            ALOGW("VkInteropFunctorDrawable::onDraw() failed in GraphicBuffer.create()");
            return;
        }

        mFBInfo = surfaceInfo;
    }

    //TODO: Synchronization is needed on mFrameBuffer to guarantee that the previous Vulkan
    //TODO: draw command has completed.
    //TODO: A simple but inefficient way is to flush and issue a QueueWaitIdle call. See
    //TODO: GrVkGpu::destroyResources() for example.
    bool success = sGLDrawThread->queue().runSync([&]() -> bool {
        ATRACE_FORMAT("WebViewDraw_%dx%d", mFBInfo.width(), mFBInfo.height());
        EGLDisplay display = sEglManager.eglDisplay();
        LOG_ALWAYS_FATAL_IF(display == EGL_NO_DISPLAY,
                "Failed to get EGL_DEFAULT_DISPLAY! err=%s",
                uirenderer::renderthread::EglManager::eglErrorString());
        // We use an EGLImage to access the content of the GraphicBuffer
        // The EGL image is later bound to a 2D texture
        EGLClientBuffer clientBuffer = (EGLClientBuffer)mFrameBuffer->getNativeBuffer();
        AutoEglImage autoImage(display, clientBuffer);
        if (autoImage.image == EGL_NO_IMAGE_KHR) {
            ALOGW("Could not create EGL image, err =%s",
                  uirenderer::renderthread::EglManager::eglErrorString());
            return false;
        }

        AutoSkiaGlTexture glTexture;
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, autoImage.image);
        GL_CHECKPOINT(MODERATE);

        glBindTexture(GL_TEXTURE_2D, 0);

        DrawGlInfo info;
        SkMatrix44 mat4(canvas->getTotalMatrix());
        SkIRect clipBounds = canvas->getDeviceClipBounds();

        info.clipLeft = clipBounds.fLeft;
        info.clipTop = clipBounds.fTop;
        info.clipRight = clipBounds.fRight;
        info.clipBottom = clipBounds.fBottom;
        info.isLayer = true;
        info.width = mFBInfo.width();
        info.height = mFBInfo.height();
        mat4.asColMajorf(&info.transform[0]);

        glViewport(0, 0, info.width, info.height);

        AutoGLFramebuffer glFb;
        // Bind texture to the frame buffer.
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                             glTexture.mTexture, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            ALOGE("Failed framebuffer check for created target buffer: %s",
                    GLUtils::getGLFramebufferError());
            return false;
        }

        glDisable(GL_STENCIL_TEST);
        glDisable(GL_SCISSOR_TEST);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        (*mFunctor)(DrawGlInfo::kModeDraw, &info);

        EGLSyncKHR glDrawFinishedFence =
                eglCreateSyncKHR(eglGetCurrentDisplay(), EGL_SYNC_FENCE_KHR, NULL);
        LOG_ALWAYS_FATAL_IF(glDrawFinishedFence == EGL_NO_SYNC_KHR,
                "Could not create sync fence %#x", eglGetError());
        glFlush();
        // TODO: export EGLSyncKHR in file descr
        // TODO: import file desc in Vulkan Semaphore
        // TODO: instead block the GPU: probably by using external Vulkan semaphore.
        // Block the CPU until the glFlush finish.
        EGLint waitStatus = eglClientWaitSyncKHR(display, glDrawFinishedFence, 0,
                FENCE_TIMEOUT);
        LOG_ALWAYS_FATAL_IF(waitStatus != EGL_CONDITION_SATISFIED_KHR,
                            "Failed to wait for the fence %#x", eglGetError());
        eglDestroySyncKHR(display, glDrawFinishedFence);
        return true;
    });

    if (!success) {
        return;
    }

    SkPaint paint;
    paint.setBlendMode(SkBlendMode::kSrcOver);
    canvas->save();
    // The size of the image matches the size of the canvas. We've used the matrix already, while
    // drawing into the offscreen surface, so we need to reset it here.
    canvas->resetMatrix();

    auto functorImage = SkImage::MakeFromAHardwareBuffer(
        reinterpret_cast<AHardwareBuffer*>(mFrameBuffer.get()), kPremul_SkAlphaType,
        nullptr, kBottomLeft_GrSurfaceOrigin);
    canvas->drawImage(functorImage, 0, 0, &paint);
    canvas->restore();
}

VkInteropFunctorDrawable::~VkInteropFunctorDrawable() {
    if (mListener.get() != nullptr) {
        ScopedDrawRequest _drawRequest{};
        sGLDrawThread->queue().runSync([&]() {
             mListener->onGlFunctorReleased(mFunctor);
        });
    }
}

void VkInteropFunctorDrawable::syncFunctor() const {
    ScopedDrawRequest _drawRequest{};
    sGLDrawThread->queue().runSync([&]() {
         (*mFunctor)(DrawGlInfo::kModeSync, nullptr);
    });
}

};  // namespace skiapipeline
};  // namespace uirenderer
};  // namespace android
