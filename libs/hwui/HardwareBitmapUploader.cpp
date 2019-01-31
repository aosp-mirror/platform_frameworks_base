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

#include "HardwareBitmapUploader.h"

#include "hwui/Bitmap.h"
#include "renderthread/EglManager.h"
#include "thread/ThreadBase.h"
#include "utils/TimeUtils.h"

#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <SkCanvas.h>
#include <utils/GLUtils.h>
#include <utils/Trace.h>
#include <utils/TraceUtils.h>
#include <thread>

namespace android::uirenderer {

static std::mutex sLock{};
static sp<ThreadBase> sUploadThread = nullptr;
static renderthread::EglManager sEglManager;
static int sPendingUploads = 0;
static nsecs_t sLastUpload = 0;

static bool shouldTimeOutLocked() {
    nsecs_t durationSince = systemTime() - sLastUpload;
    return durationSince > 2000_ms;
}

static void checkIdleTimeout() {
    std::lock_guard _lock{sLock};
    if (sPendingUploads == 0 && shouldTimeOutLocked()) {
        sEglManager.destroy();
    } else {
        sUploadThread->queue().postDelayed(5000_ms, checkIdleTimeout);
    }
}

static void beginUpload() {
    std::lock_guard _lock{sLock};
    sPendingUploads++;

    if (!sUploadThread) {
        sUploadThread = new ThreadBase{};
    }

    if (!sUploadThread->isRunning()) {
        sUploadThread->start("GrallocUploadThread");
    }

    if (!sEglManager.hasEglContext()) {
        sUploadThread->queue().runSync([]() {
            sEglManager.initialize();
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        });
        sUploadThread->queue().postDelayed(5000_ms, checkIdleTimeout);
    }
}

static void endUpload() {
    std::lock_guard _lock{sLock};
    sPendingUploads--;
    sLastUpload = systemTime();
}

static EGLDisplay getUploadEglDisplay() {
    std::lock_guard _lock{sLock};
    LOG_ALWAYS_FATAL_IF(!sEglManager.hasEglContext(), "Forgot to begin an upload?");
    return sEglManager.eglDisplay();
}

bool HardwareBitmapUploader::hasFP16Support() {
    static std::once_flag sOnce;
    static bool hasFP16Support = false;

    // Gralloc shouldn't let us create a USAGE_HW_TEXTURE if GLES is unable to consume it, so
    // we don't need to double-check the GLES version/extension.
    std::call_once(sOnce, []() {
        sp<GraphicBuffer> buffer = new GraphicBuffer(1, 1, PIXEL_FORMAT_RGBA_FP16,
                                                     GraphicBuffer::USAGE_HW_TEXTURE |
                                                             GraphicBuffer::USAGE_SW_WRITE_NEVER |
                                                             GraphicBuffer::USAGE_SW_READ_NEVER,
                                                     "tempFp16Buffer");
        status_t error = buffer->initCheck();
        hasFP16Support = !error;
    });

    return hasFP16Support;
}

#define FENCE_TIMEOUT 2000000000

struct FormatInfo {
    PixelFormat pixelFormat;
    GLint format, type;
    bool isSupported = false;
    bool valid = true;
};

static FormatInfo determineFormat(const SkBitmap& skBitmap) {
    FormatInfo formatInfo;
    switch (skBitmap.info().colorType()) {
        case kRGBA_8888_SkColorType:
            formatInfo.isSupported = true;
        // ARGB_4444 is upconverted to RGBA_8888
        case kARGB_4444_SkColorType:
            formatInfo.pixelFormat = PIXEL_FORMAT_RGBA_8888;
            formatInfo.format = GL_RGBA;
            formatInfo.type = GL_UNSIGNED_BYTE;
            break;
        case kRGBA_F16_SkColorType:
            formatInfo.isSupported = HardwareBitmapUploader::hasFP16Support();
            if (formatInfo.isSupported) {
                formatInfo.type = GL_HALF_FLOAT;
                formatInfo.pixelFormat = PIXEL_FORMAT_RGBA_FP16;
            } else {
                formatInfo.type = GL_UNSIGNED_BYTE;
                formatInfo.pixelFormat = PIXEL_FORMAT_RGBA_8888;
            }
            formatInfo.format = GL_RGBA;
            break;
        case kRGB_565_SkColorType:
            formatInfo.isSupported = true;
            formatInfo.pixelFormat = PIXEL_FORMAT_RGB_565;
            formatInfo.format = GL_RGB;
            formatInfo.type = GL_UNSIGNED_SHORT_5_6_5;
            break;
        case kGray_8_SkColorType:
            formatInfo.isSupported = true;
            formatInfo.pixelFormat = PIXEL_FORMAT_RGBA_8888;
            formatInfo.format = GL_LUMINANCE;
            formatInfo.type = GL_UNSIGNED_BYTE;
            break;
        default:
            ALOGW("unable to create hardware bitmap of colortype: %d", skBitmap.info().colorType());
            formatInfo.valid = false;
    }
    return formatInfo;
}

static SkBitmap makeHwCompatible(const FormatInfo& format, const SkBitmap& source) {
    if (format.isSupported) {
        return source;
    } else {
        SkBitmap bitmap;
        const SkImageInfo& info = source.info();
        bitmap.allocPixels(
                SkImageInfo::MakeN32(info.width(), info.height(), info.alphaType(), nullptr));

        SkCanvas canvas(bitmap);
        canvas.drawColor(0);
        canvas.drawBitmap(source, 0.0f, 0.0f, nullptr);

        return bitmap;
    }
}

class ScopedUploadRequest {
public:
    ScopedUploadRequest() { beginUpload(); }
    ~ScopedUploadRequest() { endUpload(); }
};

sk_sp<Bitmap> HardwareBitmapUploader::allocateHardwareBitmap(const SkBitmap& sourceBitmap) {
    ATRACE_CALL();

    FormatInfo format = determineFormat(sourceBitmap);
    if (!format.valid) {
        return nullptr;
    }

    ScopedUploadRequest _uploadRequest{};

    SkBitmap bitmap = makeHwCompatible(format, sourceBitmap);
    sp<GraphicBuffer> buffer = new GraphicBuffer(
            static_cast<uint32_t>(bitmap.width()), static_cast<uint32_t>(bitmap.height()),
            format.pixelFormat,
            GraphicBuffer::USAGE_HW_TEXTURE | GraphicBuffer::USAGE_SW_WRITE_NEVER |
                    GraphicBuffer::USAGE_SW_READ_NEVER,
            std::string("Bitmap::allocateSkiaHardwareBitmap pid [") + std::to_string(getpid()) +
                    "]");

    status_t error = buffer->initCheck();
    if (error < 0) {
        ALOGW("createGraphicBuffer() failed in GraphicBuffer.create()");
        return nullptr;
    }

    EGLDisplay display = getUploadEglDisplay();

    LOG_ALWAYS_FATAL_IF(display == EGL_NO_DISPLAY, "Failed to get EGL_DEFAULT_DISPLAY! err=%s",
                        uirenderer::renderthread::EglManager::eglErrorString());
    // We use an EGLImage to access the content of the GraphicBuffer
    // The EGL image is later bound to a 2D texture
    EGLClientBuffer clientBuffer = (EGLClientBuffer)buffer->getNativeBuffer();
    AutoEglImage autoImage(display, clientBuffer);
    if (autoImage.image == EGL_NO_IMAGE_KHR) {
        ALOGW("Could not create EGL image, err =%s",
              uirenderer::renderthread::EglManager::eglErrorString());
        return nullptr;
    }

    {
        ATRACE_FORMAT("CPU -> gralloc transfer (%dx%d)", bitmap.width(), bitmap.height());
        EGLSyncKHR fence = sUploadThread->queue().runSync([&]() -> EGLSyncKHR {
            AutoSkiaGlTexture glTexture;
            glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, autoImage.image);
            GL_CHECKPOINT(MODERATE);

            // glTexSubImage2D is synchronous in sense that it memcpy() from pointer that we
            // provide.
            // But asynchronous in sense that driver may upload texture onto hardware buffer when we
            // first
            // use it in drawing
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bitmap.width(), bitmap.height(), format.format,
                            format.type, bitmap.getPixels());
            GL_CHECKPOINT(MODERATE);

            EGLSyncKHR uploadFence =
                    eglCreateSyncKHR(eglGetCurrentDisplay(), EGL_SYNC_FENCE_KHR, NULL);
            LOG_ALWAYS_FATAL_IF(uploadFence == EGL_NO_SYNC_KHR, "Could not create sync fence %#x",
                                eglGetError());
            glFlush();
            return uploadFence;
        });

        EGLint waitStatus = eglClientWaitSyncKHR(display, fence, 0, FENCE_TIMEOUT);
        LOG_ALWAYS_FATAL_IF(waitStatus != EGL_CONDITION_SATISFIED_KHR,
                            "Failed to wait for the fence %#x", eglGetError());

        eglDestroySyncKHR(display, fence);
    }

    return Bitmap::createFrom(buffer.get(), bitmap.colorType(), bitmap.refColorSpace(),
                              bitmap.alphaType(), Bitmap::computePalette(bitmap));
}

void HardwareBitmapUploader::terminate() {
    std::lock_guard _lock{sLock};
    LOG_ALWAYS_FATAL_IF(sPendingUploads, "terminate called while uploads in progress");
    if (sUploadThread) {
        sUploadThread->requestExit();
        sUploadThread->join();
        sUploadThread = nullptr;
    }
    sEglManager.destroy();
}

}  // namespace android::uirenderer
