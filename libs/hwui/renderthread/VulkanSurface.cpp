/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "VulkanSurface.h"

#include <GrDirectContext.h>
#include <SkSurface.h>
#include <algorithm>

#include <gui/TraceUtils.h>
#include "VulkanManager.h"
#include "utils/Color.h"

#undef LOG_TAG
#define LOG_TAG "VulkanSurface"

namespace android {
namespace uirenderer {
namespace renderthread {

static int InvertTransform(int transform) {
    switch (transform) {
        case ANATIVEWINDOW_TRANSFORM_ROTATE_90:
            return ANATIVEWINDOW_TRANSFORM_ROTATE_270;
        case ANATIVEWINDOW_TRANSFORM_ROTATE_180:
            return ANATIVEWINDOW_TRANSFORM_ROTATE_180;
        case ANATIVEWINDOW_TRANSFORM_ROTATE_270:
            return ANATIVEWINDOW_TRANSFORM_ROTATE_90;
        default:
            return 0;
    }
}

static SkMatrix GetPreTransformMatrix(SkISize windowSize, int transform) {
    const int width = windowSize.width();
    const int height = windowSize.height();

    switch (transform) {
        case 0:
            return SkMatrix::I();
        case ANATIVEWINDOW_TRANSFORM_ROTATE_90:
            return SkMatrix::MakeAll(0, -1, height, 1, 0, 0, 0, 0, 1);
        case ANATIVEWINDOW_TRANSFORM_ROTATE_180:
            return SkMatrix::MakeAll(-1, 0, width, 0, -1, height, 0, 0, 1);
        case ANATIVEWINDOW_TRANSFORM_ROTATE_270:
            return SkMatrix::MakeAll(0, 1, 0, -1, 0, width, 0, 0, 1);
        default:
            LOG_ALWAYS_FATAL("Unsupported Window Transform (%d)", transform);
    }
    return SkMatrix::I();
}

static bool ConnectAndSetWindowDefaults(ANativeWindow* window) {
    ATRACE_CALL();

    int err = native_window_api_connect(window, NATIVE_WINDOW_API_EGL);
    if (err != 0) {
        ALOGE("native_window_api_connect failed: %s (%d)", strerror(-err), err);
        return false;
    }

    // this will match what we do on GL so pick that here.
    err = window->setSwapInterval(window, 1);
    if (err != 0) {
        ALOGE("native_window->setSwapInterval(1) failed: %s (%d)", strerror(-err), err);
        return false;
    }

    err = native_window_set_shared_buffer_mode(window, false);
    if (err != 0) {
        ALOGE("native_window_set_shared_buffer_mode(false) failed: %s (%d)", strerror(-err), err);
        return false;
    }

    err = native_window_set_auto_refresh(window, false);
    if (err != 0) {
        ALOGE("native_window_set_auto_refresh(false) failed: %s (%d)", strerror(-err), err);
        return false;
    }

    err = native_window_set_scaling_mode(window, NATIVE_WINDOW_SCALING_MODE_FREEZE);
    if (err != 0) {
        ALOGE("native_window_set_scaling_mode(NATIVE_WINDOW_SCALING_MODE_FREEZE) failed: %s (%d)",
              strerror(-err), err);
        return false;
    }

    // Let consumer drive the size of the buffers.
    err = native_window_set_buffers_dimensions(window, 0, 0);
    if (err != 0) {
        ALOGE("native_window_set_buffers_dimensions(0,0) failed: %s (%d)", strerror(-err), err);
        return false;
    }

    // Enable auto prerotation, so when buffer size is driven by the consumer
    // and the transform hint specifies a 90 or 270 degree rotation, the width
    // and height used for buffer pre-allocation and dequeueBuffer will be
    // additionally swapped.
    err = native_window_set_auto_prerotation(window, true);
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_auto_prerotation failed: %s (%d)",
              strerror(-err), err);
        return false;
    }

    return true;
}

VulkanSurface* VulkanSurface::Create(ANativeWindow* window, ColorMode colorMode,
                                     SkColorType colorType, sk_sp<SkColorSpace> colorSpace,
                                     GrDirectContext* grContext, const VulkanManager& vkManager,
                                     uint32_t extraBuffers) {
    // Connect and set native window to default configurations.
    if (!ConnectAndSetWindowDefaults(window)) {
        return nullptr;
    }

    // Initialize WindowInfo struct.
    WindowInfo windowInfo;
    if (!InitializeWindowInfoStruct(window, colorMode, colorType, colorSpace, vkManager,
                                    extraBuffers, &windowInfo)) {
        return nullptr;
    }

    // Now we attempt to modify the window.
    if (!UpdateWindow(window, windowInfo)) {
        return nullptr;
    }

    return new VulkanSurface(window, windowInfo, grContext);
}

bool VulkanSurface::InitializeWindowInfoStruct(ANativeWindow* window, ColorMode colorMode,
                                               SkColorType colorType,
                                               sk_sp<SkColorSpace> colorSpace,
                                               const VulkanManager& vkManager,
                                               uint32_t extraBuffers, WindowInfo* outWindowInfo) {
    ATRACE_CALL();

    int width, height;
    int err = window->query(window, NATIVE_WINDOW_DEFAULT_WIDTH, &width);
    if (err != 0 || width < 0) {
        ALOGE("window->query failed: %s (%d) value=%d", strerror(-err), err, width);
        return false;
    }
    err = window->query(window, NATIVE_WINDOW_DEFAULT_HEIGHT, &height);
    if (err != 0 || height < 0) {
        ALOGE("window->query failed: %s (%d) value=%d", strerror(-err), err, height);
        return false;
    }
    outWindowInfo->size = SkISize::Make(width, height);

    int query_value;
    err = window->query(window, NATIVE_WINDOW_TRANSFORM_HINT, &query_value);
    if (err != 0 || query_value < 0) {
        ALOGE("window->query failed: %s (%d) value=%d", strerror(-err), err, query_value);
        return false;
    }
    outWindowInfo->transform = query_value;

    outWindowInfo->actualSize = outWindowInfo->size;
    if (outWindowInfo->transform & ANATIVEWINDOW_TRANSFORM_ROTATE_90) {
        outWindowInfo->actualSize.set(outWindowInfo->size.height(), outWindowInfo->size.width());
    }

    outWindowInfo->preTransform =
            GetPreTransformMatrix(outWindowInfo->size, outWindowInfo->transform);

    err = window->query(window, NATIVE_WINDOW_MIN_UNDEQUEUED_BUFFERS, &query_value);
    if (err != 0 || query_value < 0) {
        ALOGE("window->query failed: %s (%d) value=%d", strerror(-err), err, query_value);
        return false;
    }
    outWindowInfo->bufferCount =
            static_cast<uint32_t>(query_value) + sTargetBufferCount + extraBuffers;

    err = window->query(window, NATIVE_WINDOW_MAX_BUFFER_COUNT, &query_value);
    if (err != 0 || query_value < 0) {
        ALOGE("window->query failed: %s (%d) value=%d", strerror(-err), err, query_value);
        return false;
    }
    if (outWindowInfo->bufferCount > static_cast<uint32_t>(query_value)) {
        // Application must settle for fewer images than desired:
        outWindowInfo->bufferCount = static_cast<uint32_t>(query_value);
    }

    outWindowInfo->bufferFormat = ColorTypeToBufferFormat(colorType);
    outWindowInfo->colorspace = colorSpace;
    outWindowInfo->dataspace = ColorSpaceToADataSpace(colorSpace.get(), colorType);
    LOG_ALWAYS_FATAL_IF(
            outWindowInfo->dataspace == HAL_DATASPACE_UNKNOWN && colorType != kAlpha_8_SkColorType,
            "Unsupported colorspace");

    VkFormat vkPixelFormat;
    switch (colorType) {
        case kRGBA_8888_SkColorType:
            vkPixelFormat = VK_FORMAT_R8G8B8A8_UNORM;
            break;
        case kRGBA_F16_SkColorType:
            vkPixelFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
            break;
        case kRGBA_1010102_SkColorType:
            vkPixelFormat = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            break;
        case kAlpha_8_SkColorType:
            vkPixelFormat = VK_FORMAT_R8_UNORM;
            break;
        default:
            LOG_ALWAYS_FATAL("Unsupported colorType: %d", (int)colorType);
    }

    LOG_ALWAYS_FATAL_IF(nullptr == vkManager.mGetPhysicalDeviceImageFormatProperties2,
                        "vkGetPhysicalDeviceImageFormatProperties2 is missing");
    VkPhysicalDeviceExternalImageFormatInfo externalImageFormatInfo;
    externalImageFormatInfo.sType =
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO;
    externalImageFormatInfo.pNext = nullptr;
    externalImageFormatInfo.handleType =
            VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkPhysicalDeviceImageFormatInfo2 imageFormatInfo;
    imageFormatInfo.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2;
    imageFormatInfo.pNext = &externalImageFormatInfo;
    imageFormatInfo.format = vkPixelFormat;
    imageFormatInfo.type = VK_IMAGE_TYPE_2D;
    imageFormatInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    // Currently Skia requires the images to be color attachments and support all transfer
    // operations.
    imageFormatInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageFormatInfo.flags = 0;

    VkAndroidHardwareBufferUsageANDROID hwbUsage;
    hwbUsage.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_USAGE_ANDROID;
    hwbUsage.pNext = nullptr;

    VkImageFormatProperties2 imgFormProps;
    imgFormProps.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2;
    imgFormProps.pNext = &hwbUsage;

    VkResult res = vkManager.mGetPhysicalDeviceImageFormatProperties2(
            vkManager.mPhysicalDevice, &imageFormatInfo, &imgFormProps);
    if (VK_SUCCESS != res) {
        ALOGE("Failed to query GetPhysicalDeviceImageFormatProperties2");
        return false;
    }

    uint64_t consumerUsage;
    err = native_window_get_consumer_usage(window, &consumerUsage);
    if (err != 0) {
        ALOGE("native_window_get_consumer_usage failed: %s (%d)", strerror(-err), err);
        return false;
    }
    outWindowInfo->windowUsageFlags = consumerUsage | hwbUsage.androidHardwareBufferUsage;

    return true;
}

bool VulkanSurface::UpdateWindow(ANativeWindow* window, const WindowInfo& windowInfo) {
    ATRACE_CALL();

    int err = native_window_set_buffers_format(window, windowInfo.bufferFormat);
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_buffers_format(%d) failed: %s (%d)",
              windowInfo.bufferFormat, strerror(-err), err);
        return false;
    }

    err = native_window_set_buffers_data_space(window, windowInfo.dataspace);
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_buffers_data_space(%d) "
              "failed: %s (%d)",
              windowInfo.dataspace, strerror(-err), err);
        return false;
    }

    // native_window_set_buffers_transform() expects the transform the app is requesting that
    // the compositor perform during composition. With native windows, pre-transform works by
    // rendering with the same transform the compositor is applying (as in Vulkan), but
    // then requesting the inverse transform, so that when the compositor does
    // it's job the two transforms cancel each other out and the compositor ends
    // up applying an identity transform to the app's buffer.
    err = native_window_set_buffers_transform(window, InvertTransform(windowInfo.transform));
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_buffers_transform(%d) "
              "failed: %s (%d)",
              windowInfo.transform, strerror(-err), err);
        return false;
    }

    err = native_window_set_buffer_count(window, windowInfo.bufferCount);
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_buffer_count(%zu) failed: %s (%d)",
              windowInfo.bufferCount, strerror(-err), err);
        return false;
    }

    err = native_window_set_usage(window, windowInfo.windowUsageFlags);
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_usage failed: %s (%d)",
              strerror(-err), err);
        return false;
    }

    return true;
}

VulkanSurface::VulkanSurface(ANativeWindow* window, const WindowInfo& windowInfo,
                             GrDirectContext* grContext)
        : mNativeWindow(window), mWindowInfo(windowInfo), mGrContext(grContext) {}

VulkanSurface::~VulkanSurface() {
    releaseBuffers();

    // release the native window to be available for use by other clients
    int err = native_window_api_disconnect(mNativeWindow.get(), NATIVE_WINDOW_API_EGL);
    ALOGW_IF(err != 0, "native_window_api_disconnect failed: %s (%d)", strerror(-err), err);
}

void VulkanSurface::releaseBuffers() {
    for (uint32_t i = 0; i < mWindowInfo.bufferCount; i++) {
        VulkanSurface::NativeBufferInfo& bufferInfo = mNativeBuffers[i];

        if (bufferInfo.buffer.get() != nullptr && bufferInfo.dequeued) {
            int err = mNativeWindow->cancelBuffer(mNativeWindow.get(), bufferInfo.buffer.get(),
                                                  bufferInfo.dequeue_fence.release());
            if (err != 0) {
                ALOGE("cancelBuffer[%u] failed during destroy: %s (%d)", i, strerror(-err), err);
            }
            bufferInfo.dequeued = false;
            bufferInfo.dequeue_fence.reset();
        }

        LOG_ALWAYS_FATAL_IF(bufferInfo.dequeued);
        LOG_ALWAYS_FATAL_IF(bufferInfo.dequeue_fence.ok());

        bufferInfo.skSurface.reset();
        bufferInfo.buffer.clear();
        bufferInfo.hasValidContents = false;
        bufferInfo.lastPresentedCount = 0;
    }
}

VulkanSurface::NativeBufferInfo* VulkanSurface::dequeueNativeBuffer() {
    // Set the mCurrentBufferInfo to invalid in case of error and only reset it to the correct
    // value at the end of the function if everything dequeued correctly.
    mCurrentBufferInfo = nullptr;

    // Query the transform hint synced from the initial Surface connect or last queueBuffer. The
    // auto prerotation on the buffer is based on the same transform hint in use by the producer.
    int transformHint = 0;
    int err =
            mNativeWindow->query(mNativeWindow.get(), NATIVE_WINDOW_TRANSFORM_HINT, &transformHint);

    // Since auto pre-rotation is enabled, dequeueBuffer to get the consumer driven buffer size
    // from ANativeWindowBuffer.
    ANativeWindowBuffer* buffer;
    base::unique_fd fence_fd;
    {
        int rawFd = -1;
        err = mNativeWindow->dequeueBuffer(mNativeWindow.get(), &buffer, &rawFd);
        fence_fd.reset(rawFd);
    }
    if (err != 0) {
        ALOGE("dequeueBuffer failed: %s (%d)", strerror(-err), err);
        return nullptr;
    }

    SkISize actualSize = SkISize::Make(buffer->width, buffer->height);
    if (actualSize != mWindowInfo.actualSize || transformHint != mWindowInfo.transform) {
        if (actualSize != mWindowInfo.actualSize) {
            // reset the NativeBufferInfo (including SkSurface) associated with the old buffers. The
            // new NativeBufferInfo storage will be populated lazily as we dequeue each new buffer.
            mWindowInfo.actualSize = actualSize;
            releaseBuffers();
        }

        if (transformHint != mWindowInfo.transform) {
            err = native_window_set_buffers_transform(mNativeWindow.get(),
                                                      InvertTransform(transformHint));
            if (err != 0) {
                ALOGE("native_window_set_buffers_transform(%d) failed: %s (%d)", transformHint,
                      strerror(-err), err);
                mNativeWindow->cancelBuffer(mNativeWindow.get(), buffer, fence_fd.release());
                return nullptr;
            }
            mWindowInfo.transform = transformHint;
        }

        mWindowInfo.size = actualSize;
        if (mWindowInfo.transform & NATIVE_WINDOW_TRANSFORM_ROT_90) {
            mWindowInfo.size.set(actualSize.height(), actualSize.width());
        }

        mWindowInfo.preTransform = GetPreTransformMatrix(mWindowInfo.size, mWindowInfo.transform);
    }

    uint32_t idx;
    for (idx = 0; idx < mWindowInfo.bufferCount; idx++) {
        if (mNativeBuffers[idx].buffer.get() == buffer) {
            mNativeBuffers[idx].dequeued = true;
            mNativeBuffers[idx].dequeue_fence = std::move(fence_fd);
            break;
        } else if (mNativeBuffers[idx].buffer.get() == nullptr) {
            // increasing the number of buffers we have allocated
            mNativeBuffers[idx].buffer = buffer;
            mNativeBuffers[idx].dequeued = true;
            mNativeBuffers[idx].dequeue_fence = std::move(fence_fd);
            break;
        }
    }
    if (idx == mWindowInfo.bufferCount) {
        ALOGE("dequeueBuffer returned unrecognized buffer");
        mNativeWindow->cancelBuffer(mNativeWindow.get(), buffer, fence_fd.release());
        return nullptr;
    }

    VulkanSurface::NativeBufferInfo* bufferInfo = &mNativeBuffers[idx];

    if (bufferInfo->skSurface.get() == nullptr) {
        bufferInfo->skSurface = SkSurface::MakeFromAHardwareBuffer(
                mGrContext, ANativeWindowBuffer_getHardwareBuffer(bufferInfo->buffer.get()),
                kTopLeft_GrSurfaceOrigin, mWindowInfo.colorspace, nullptr, /*from_window=*/true);
        if (bufferInfo->skSurface.get() == nullptr) {
            ALOGE("SkSurface::MakeFromAHardwareBuffer failed");
            mNativeWindow->cancelBuffer(mNativeWindow.get(), buffer,
                                        mNativeBuffers[idx].dequeue_fence.release());
            mNativeBuffers[idx].dequeued = false;
            return nullptr;
        }
    }

    mCurrentBufferInfo = bufferInfo;
    return bufferInfo;
}

bool VulkanSurface::presentCurrentBuffer(const SkRect& dirtyRect, int semaphoreFd) {
    if (!dirtyRect.isEmpty()) {

        // native_window_set_surface_damage takes a rectangle in prerotated space
        // with a bottom-left origin. That is, top > bottom.
        // The dirtyRect is also in prerotated space, so we just need to switch it to
        // a bottom-left origin space.

        SkIRect irect;
        dirtyRect.roundOut(&irect);
        android_native_rect_t aRect;
        aRect.left = irect.left();
        aRect.top = logicalHeight() - irect.top();
        aRect.right = irect.right();
        aRect.bottom = logicalHeight() - irect.bottom();

        int err = native_window_set_surface_damage(mNativeWindow.get(), &aRect, 1);
        ALOGE_IF(err != 0, "native_window_set_surface_damage failed: %s (%d)", strerror(-err), err);
    }

    LOG_ALWAYS_FATAL_IF(!mCurrentBufferInfo);
    VulkanSurface::NativeBufferInfo& currentBuffer = *mCurrentBufferInfo;
    // queueBuffer always closes fence, even on error
    int queuedFd = (semaphoreFd != -1) ? semaphoreFd : currentBuffer.dequeue_fence.release();
    int err = mNativeWindow->queueBuffer(mNativeWindow.get(), currentBuffer.buffer.get(), queuedFd);

    currentBuffer.dequeued = false;
    if (err != 0) {
        ALOGE("queueBuffer failed: %s (%d)", strerror(-err), err);
        // cancelBuffer takes ownership of the fence
        mNativeWindow->cancelBuffer(mNativeWindow.get(), currentBuffer.buffer.get(),
                                    currentBuffer.dequeue_fence.release());
    } else {
        currentBuffer.hasValidContents = true;
        currentBuffer.lastPresentedCount = mPresentCount;
        mPresentCount++;
    }

    currentBuffer.dequeue_fence.reset();

    return err == 0;
}

int VulkanSurface::getCurrentBuffersAge() {
    LOG_ALWAYS_FATAL_IF(!mCurrentBufferInfo);
    VulkanSurface::NativeBufferInfo& currentBuffer = *mCurrentBufferInfo;
    return currentBuffer.hasValidContents ? (mPresentCount - currentBuffer.lastPresentedCount) : 0;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
