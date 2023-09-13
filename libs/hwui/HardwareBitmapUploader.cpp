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

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <GrDirectContext.h>
#include <GrTypes.h>
#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkImage.h>
#include <SkImageAndroid.h>
#include <SkImageInfo.h>
#include <SkRefCnt.h>
#include <gui/TraceUtils.h>
#include <utils/GLUtils.h>
#include <utils/NdkUtils.h>
#include <utils/Trace.h>

#include <thread>

#include "hwui/Bitmap.h"
#include "renderthread/EglManager.h"
#include "renderthread/VulkanManager.h"
#include "thread/ThreadBase.h"
#include "utils/TimeUtils.h"

namespace android::uirenderer {

static constexpr auto kThreadTimeout = 60000_ms;

class AHBUploader;
// This helper uploader classes allows us to upload using either EGL or Vulkan using the same
// interface.
static sp<AHBUploader> sUploader = nullptr;

struct FormatInfo {
    AHardwareBuffer_Format bufferFormat;
    GLint format, type;
    VkFormat vkFormat;
    bool isSupported = false;
    bool valid = true;
};

class AHBUploader : public RefBase {
public:
    virtual ~AHBUploader() {}

    void destroy() {
        std::lock_guard _lock{mLock};
        LOG_ALWAYS_FATAL_IF(mPendingUploads, "terminate called while uploads in progress");
        if (mUploadThread) {
            mUploadThread->requestExit();
            mUploadThread->join();
            mUploadThread = nullptr;
        }
        onDestroy();
    }

    bool uploadHardwareBitmap(const SkBitmap& bitmap, const FormatInfo& format,
                              AHardwareBuffer* ahb) {
        ATRACE_CALL();
        beginUpload();
        bool result = onUploadHardwareBitmap(bitmap, format, ahb);
        endUpload();
        return result;
    }

    void postIdleTimeoutCheck() {
        mUploadThread->queue().postDelayed(kThreadTimeout, [this]() { this->idleTimeoutCheck(); });
    }

protected:
    std::mutex mLock;
    sp<ThreadBase> mUploadThread = nullptr;

private:
    virtual void onIdle() = 0;
    virtual void onDestroy() = 0;

    virtual bool onUploadHardwareBitmap(const SkBitmap& bitmap, const FormatInfo& format,
                                        AHardwareBuffer* ahb) = 0;
    virtual void onBeginUpload() = 0;

    bool shouldTimeOutLocked() {
        nsecs_t durationSince = systemTime() - mLastUpload;
        return durationSince > kThreadTimeout;
    }

    void idleTimeoutCheck() {
        std::lock_guard _lock{mLock};
        if (mPendingUploads == 0 && shouldTimeOutLocked()) {
            onIdle();
        } else {
            this->postIdleTimeoutCheck();
        }
    }

    void beginUpload() {
        std::lock_guard _lock{mLock};
        mPendingUploads++;

        if (!mUploadThread) {
            mUploadThread = new ThreadBase{};
        }
        if (!mUploadThread->isRunning()) {
            mUploadThread->start("GrallocUploadThread");
        }

        onBeginUpload();
    }

    void endUpload() {
        std::lock_guard _lock{mLock};
        mPendingUploads--;
        mLastUpload = systemTime();
    }

    int mPendingUploads = 0;
    nsecs_t mLastUpload = 0;
};

#define FENCE_TIMEOUT 2000000000

class EGLUploader : public AHBUploader {
private:
    void onDestroy() override {
        mEglManager.destroy();
    }
    void onIdle() override {
        mEglManager.destroy();
    }

    void onBeginUpload() override {
        if (!mEglManager.hasEglContext()) {
            mUploadThread->queue().runSync([this]() {
                this->mEglManager.initialize();
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            });

            this->postIdleTimeoutCheck();
        }
    }


    EGLDisplay getUploadEglDisplay() {
        std::lock_guard _lock{mLock};
        LOG_ALWAYS_FATAL_IF(!mEglManager.hasEglContext(), "Forgot to begin an upload?");
        return mEglManager.eglDisplay();
    }

    bool onUploadHardwareBitmap(const SkBitmap& bitmap, const FormatInfo& format,
                                AHardwareBuffer* ahb) override {
        ATRACE_CALL();

        EGLDisplay display = getUploadEglDisplay();

        LOG_ALWAYS_FATAL_IF(display == EGL_NO_DISPLAY, "Failed to get EGL_DEFAULT_DISPLAY! err=%s",
                            uirenderer::renderthread::EglManager::eglErrorString());
        // We use an EGLImage to access the content of the buffer
        // The EGL image is later bound to a 2D texture
        const EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(ahb);
        AutoEglImage autoImage(display, clientBuffer);
        if (autoImage.image == EGL_NO_IMAGE_KHR) {
            ALOGW("Could not create EGL image, err =%s",
                  uirenderer::renderthread::EglManager::eglErrorString());
            return false;
        }

        {
            ATRACE_FORMAT("CPU -> gralloc transfer (%dx%d)", bitmap.width(), bitmap.height());
            EGLSyncKHR fence = mUploadThread->queue().runSync([&]() -> EGLSyncKHR {
                AutoSkiaGlTexture glTexture;
                glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, autoImage.image);
                if (GLUtils::dumpGLErrors()) {
                    return EGL_NO_SYNC_KHR;
                }

                // glTexSubImage2D is synchronous in sense that it memcpy() from pointer that we
                // provide.
                // But asynchronous in sense that driver may upload texture onto hardware buffer
                // when we first use it in drawing
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, bitmap.width(), bitmap.height(),
                                format.format, format.type, bitmap.getPixels());
                if (GLUtils::dumpGLErrors()) {
                    return EGL_NO_SYNC_KHR;
                }

                EGLSyncKHR uploadFence =
                        eglCreateSyncKHR(eglGetCurrentDisplay(), EGL_SYNC_FENCE_KHR, NULL);
                if (uploadFence == EGL_NO_SYNC_KHR) {
                    ALOGW("Could not create sync fence %#x", eglGetError());
                };
                glFlush();
                GLUtils::dumpGLErrors();
                return uploadFence;
            });

            if (fence == EGL_NO_SYNC_KHR) {
                return false;
            }
            EGLint waitStatus = eglClientWaitSyncKHR(display, fence, 0, FENCE_TIMEOUT);
            ALOGE_IF(waitStatus != EGL_CONDITION_SATISFIED_KHR,
                    "Failed to wait for the fence %#x", eglGetError());

            eglDestroySyncKHR(display, fence);
        }
        return true;
    }

    renderthread::EglManager mEglManager;
};

class VkUploader : public AHBUploader {
private:
    void onDestroy() override {
        std::lock_guard _lock{mVkLock};
        mGrContext.reset();
        mVulkanManagerStrong.clear();
    }
    void onIdle() override {
        onDestroy();
    }

    void onBeginUpload() override {}

    bool onUploadHardwareBitmap(const SkBitmap& bitmap, const FormatInfo& format,
                                AHardwareBuffer* ahb) override {
        bool uploadSucceeded = false;
        mUploadThread->queue().runSync([this, &uploadSucceeded, bitmap, ahb]() {
          ATRACE_CALL();
          std::lock_guard _lock{mVkLock};

          renderthread::VulkanManager* vkManager = getVulkanManager();
          if (!vkManager->hasVkContext()) {
              LOG_ALWAYS_FATAL_IF(mGrContext,
                                  "GrContext exists with no VulkanManager for vulkan uploads");
              vkManager->initialize();
          }

          if (!mGrContext) {
              GrContextOptions options;
              mGrContext = vkManager->createContext(options,
                      renderthread::VulkanManager::ContextType::kUploadThread);
              LOG_ALWAYS_FATAL_IF(!mGrContext, "failed to create GrContext for vulkan uploads");
              this->postIdleTimeoutCheck();
          }

          sk_sp<SkImage> image =
              SkImages::TextureFromAHardwareBufferWithData(mGrContext.get(), bitmap.pixmap(),
                                                           ahb);
          mGrContext->submit(GrSyncCpu::kYes);

          uploadSucceeded = (image.get() != nullptr);
        });
        return uploadSucceeded;
    }

    /* must be called on the upload thread after the vkLock has been acquired  */
    renderthread::VulkanManager* getVulkanManager() {
        if (!mVulkanManagerStrong) {
            mVulkanManagerStrong = mVulkanManagerWeak.promote();

            // create a new manager if we couldn't promote the weak ref
            if (!mVulkanManagerStrong) {
                mVulkanManagerStrong = renderthread::VulkanManager::getInstance();
                mGrContext.reset();
                mVulkanManagerWeak = mVulkanManagerStrong;
            }
        }

        return mVulkanManagerStrong.get();
    }

    sk_sp<GrDirectContext> mGrContext;
    sp<renderthread::VulkanManager> mVulkanManagerStrong;
    wp<renderthread::VulkanManager> mVulkanManagerWeak;
    std::mutex mVkLock;
};

static bool checkSupport(AHardwareBuffer_Format format) {
    AHardwareBuffer_Desc desc = {
            .width = 1,
            .height = 1,
            .layers = 1,
            .format = format,
            .usage = AHARDWAREBUFFER_USAGE_CPU_READ_NEVER | AHARDWAREBUFFER_USAGE_CPU_WRITE_NEVER |
                     AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
    };
    UniqueAHardwareBuffer buffer = allocateAHardwareBuffer(desc);
    return buffer != nullptr;
}

bool HardwareBitmapUploader::hasFP16Support() {
    static bool hasFP16Support = checkSupport(AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT);
    return hasFP16Support;
}

bool HardwareBitmapUploader::has1010102Support() {
    static bool has101012Support = checkSupport(AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM);
    return has101012Support;
}

bool HardwareBitmapUploader::hasAlpha8Support() {
    static bool hasAlpha8Support = checkSupport(AHARDWAREBUFFER_FORMAT_R8_UNORM);
    return hasAlpha8Support;
}

static FormatInfo determineFormat(const SkBitmap& skBitmap, bool usingGL) {
    FormatInfo formatInfo;
    switch (skBitmap.info().colorType()) {
        case kRGBA_8888_SkColorType:
            formatInfo.isSupported = true;
            [[fallthrough]];
        // ARGB_4444 is upconverted to RGBA_8888
        case kARGB_4444_SkColorType:
            formatInfo.bufferFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            formatInfo.format = GL_RGBA;
            formatInfo.type = GL_UNSIGNED_BYTE;
            formatInfo.vkFormat = VK_FORMAT_R8G8B8A8_UNORM;
            break;
        case kRGBA_F16_SkColorType:
            formatInfo.isSupported = HardwareBitmapUploader::hasFP16Support();
            if (formatInfo.isSupported) {
                formatInfo.type = GL_HALF_FLOAT;
                formatInfo.bufferFormat = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
                formatInfo.vkFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
            } else {
                formatInfo.type = GL_UNSIGNED_BYTE;
                formatInfo.bufferFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
                formatInfo.vkFormat = VK_FORMAT_R8G8B8A8_UNORM;
            }
            formatInfo.format = GL_RGBA;
            break;
        case kRGB_565_SkColorType:
            formatInfo.isSupported = true;
            formatInfo.bufferFormat = AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM;
            formatInfo.format = GL_RGB;
            formatInfo.type = GL_UNSIGNED_SHORT_5_6_5;
            formatInfo.vkFormat = VK_FORMAT_R5G6B5_UNORM_PACK16;
            break;
        case kGray_8_SkColorType:
            formatInfo.isSupported = usingGL;
            formatInfo.bufferFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
            formatInfo.format = GL_LUMINANCE;
            formatInfo.type = GL_UNSIGNED_BYTE;
            formatInfo.vkFormat = VK_FORMAT_R8G8B8A8_UNORM;
            break;
        case kRGBA_1010102_SkColorType:
            formatInfo.isSupported = HardwareBitmapUploader::has1010102Support();
            if (formatInfo.isSupported) {
                formatInfo.type = GL_UNSIGNED_INT_2_10_10_10_REV;
                formatInfo.bufferFormat = AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
                formatInfo.vkFormat = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            } else {
                formatInfo.type = GL_UNSIGNED_BYTE;
                formatInfo.bufferFormat = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
                formatInfo.vkFormat = VK_FORMAT_R8G8B8A8_UNORM;
            }
            formatInfo.format = GL_RGBA;
            break;
        case kAlpha_8_SkColorType:
            formatInfo.isSupported = HardwareBitmapUploader::hasAlpha8Support();
            formatInfo.bufferFormat = AHARDWAREBUFFER_FORMAT_R8_UNORM;
            formatInfo.format = GL_R8;
            formatInfo.type = GL_UNSIGNED_BYTE;
            formatInfo.vkFormat = VK_FORMAT_R8_UNORM;
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
        bitmap.allocPixels(source.info().makeColorType(kN32_SkColorType));
        bitmap.writePixels(source.pixmap());
        return bitmap;
    }
}


static void createUploader(bool usingGL) {
    static std::mutex lock;
    std::lock_guard _lock{lock};
    if (!sUploader.get()) {
        if (usingGL) {
            sUploader = new EGLUploader();
        } else {
            sUploader = new VkUploader();
        }
    }
}

sk_sp<Bitmap> HardwareBitmapUploader::allocateHardwareBitmap(const SkBitmap& sourceBitmap) {
    ATRACE_CALL();

    bool usingGL = uirenderer::Properties::getRenderPipelineType() ==
            uirenderer::RenderPipelineType::SkiaGL;

    FormatInfo format = determineFormat(sourceBitmap, usingGL);
    if (!format.valid) {
        return nullptr;
    }

    SkBitmap bitmap = makeHwCompatible(format, sourceBitmap);
    AHardwareBuffer_Desc desc = {
            .width = static_cast<uint32_t>(bitmap.width()),
            .height = static_cast<uint32_t>(bitmap.height()),
            .layers = 1,
            .format = format.bufferFormat,
            .usage = AHARDWAREBUFFER_USAGE_CPU_READ_NEVER | AHARDWAREBUFFER_USAGE_CPU_WRITE_NEVER |
                     AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
    };
    UniqueAHardwareBuffer ahb = allocateAHardwareBuffer(desc);
    if (!ahb) {
        ALOGW("allocateHardwareBitmap() failed in AHardwareBuffer_allocate()");
        return nullptr;
    };

    createUploader(usingGL);

    if (!sUploader->uploadHardwareBitmap(bitmap, format, ahb.get())) {
        return nullptr;
    }
    return Bitmap::createFrom(ahb.get(), bitmap.colorType(), bitmap.refColorSpace(),
                              bitmap.alphaType(), Bitmap::computePalette(bitmap));
}

void HardwareBitmapUploader::initialize() {
    bool usingGL = uirenderer::Properties::getRenderPipelineType() ==
            uirenderer::RenderPipelineType::SkiaGL;
    createUploader(usingGL);
}

void HardwareBitmapUploader::terminate() {
    if (sUploader) {
        sUploader->destroy();
    }
}

}  // namespace android::uirenderer
