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

#include <algorithm>
#include <SkSurface.h>

#include "VulkanManager.h"
#include "utils/TraceUtils.h"
#include "utils/Color.h"

namespace android {
namespace uirenderer {
namespace renderthread {

static bool IsTransformSupported(int transform) {
    // For now, only support pure rotations, not flip or flip-and-rotate, until we have
    // more time to test them and build sample code. As far as I know we never actually
    // use anything besides pure rotations anyway.
    return transform == 0
        || transform == NATIVE_WINDOW_TRANSFORM_ROT_90
        || transform == NATIVE_WINDOW_TRANSFORM_ROT_180
        || transform == NATIVE_WINDOW_TRANSFORM_ROT_270;
}

static int InvertTransform(int transform) {
    switch (transform) {
        case NATIVE_WINDOW_TRANSFORM_ROT_90:
            return NATIVE_WINDOW_TRANSFORM_ROT_270;
        case NATIVE_WINDOW_TRANSFORM_ROT_180:
            return NATIVE_WINDOW_TRANSFORM_ROT_180;
        case NATIVE_WINDOW_TRANSFORM_ROT_270:
            return NATIVE_WINDOW_TRANSFORM_ROT_90;
        default:
            return 0;
    }
}

static int ConvertVkTransformToNative(VkSurfaceTransformFlagsKHR transform) {
    switch (transform) {
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:
            return NATIVE_WINDOW_TRANSFORM_ROT_270;
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:
            return NATIVE_WINDOW_TRANSFORM_ROT_180;
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:
            return NATIVE_WINDOW_TRANSFORM_ROT_90;
        case VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR:
        case VK_SURFACE_TRANSFORM_INHERIT_BIT_KHR:
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
        case NATIVE_WINDOW_TRANSFORM_ROT_90:
            return SkMatrix::MakeAll(0, -1, height, 1, 0, 0, 0, 0, 1);
        case NATIVE_WINDOW_TRANSFORM_ROT_180:
            return SkMatrix::MakeAll(-1, 0, width, 0, -1, height, 0, 0, 1);
        case NATIVE_WINDOW_TRANSFORM_ROT_270:
            return SkMatrix::MakeAll(0, 1, 0, -1, 0, width, 0, 0, 1);
        default:
            LOG_ALWAYS_FATAL("Unsupported Window Transform (%d)", transform);
    }
    return SkMatrix::I();
}

void VulkanSurface::ComputeWindowSizeAndTransform(WindowInfo* windowInfo, const SkISize& minSize,
                                                   const SkISize& maxSize) {
    SkISize& windowSize = windowInfo->size;

    // clamp width & height to handle currentExtent of -1 and  protect us from broken hints
    if (windowSize.width() < minSize.width() || windowSize.width() > maxSize.width()
        || windowSize.height() < minSize.height() || windowSize.height() > maxSize.height()) {
        int width = std::min(maxSize.width(), std::max(minSize.width(), windowSize.width()));
        int height = std::min(maxSize.height(), std::max(minSize.height(), windowSize.height()));
        ALOGE("Invalid Window Dimensions [%d, %d]; clamping to [%d, %d]",
              windowSize.width(), windowSize.height(), width, height);
        windowSize.set(width, height);
    }

    windowInfo->actualSize = windowSize;
    if (windowInfo->transform & HAL_TRANSFORM_ROT_90) {
        windowInfo->actualSize.set(windowSize.height(), windowSize.width());
    }

    windowInfo->preTransform = GetPreTransformMatrix(windowInfo->size, windowInfo->transform);
}

static bool ResetNativeWindow(ANativeWindow* window) {
    // -- Reset the native window --
    // The native window might have been used previously, and had its properties
    // changed from defaults. That will affect the answer we get for queries
    // like MIN_UNDEQUEUED_BUFFERS. Reset to a known/default state before we
    // attempt such queries.

    int err = native_window_api_connect(window, NATIVE_WINDOW_API_EGL);
    if (err != 0) {
        ALOGW("native_window_api_connect failed: %s (%d)", strerror(-err), err);
        return false;
    }

    // this will match what we do on GL so pick that here.
    err = window->setSwapInterval(window, 1);
    if (err != 0) {
        ALOGW("native_window->setSwapInterval(1) failed: %s (%d)", strerror(-err), err);
        return false;
    }

    err = native_window_set_shared_buffer_mode(window, false);
    if (err != 0) {
        ALOGW("native_window_set_shared_buffer_mode(false) failed: %s (%d)", strerror(-err), err);
        return false;
    }

    err = native_window_set_auto_refresh(window, false);
    if (err != 0) {
        ALOGW("native_window_set_auto_refresh(false) failed: %s (%d)", strerror(-err), err);
        return false;
    }

    return true;
}

class VkSurfaceAutoDeleter {
public:
    VkSurfaceAutoDeleter(VkInstance instance, VkSurfaceKHR surface,
                         PFN_vkDestroySurfaceKHR destroySurfaceKHR)
            : mInstance(instance)
            , mSurface(surface)
            , mDestroySurfaceKHR(destroySurfaceKHR) {}
    ~VkSurfaceAutoDeleter() {
        destroy();
    }

    void destroy() {
        if (mSurface != VK_NULL_HANDLE) {
            mDestroySurfaceKHR(mInstance, mSurface, nullptr);
            mSurface = VK_NULL_HANDLE;
        }
    }

private:
    VkInstance mInstance;
    VkSurfaceKHR mSurface;
    PFN_vkDestroySurfaceKHR mDestroySurfaceKHR;
};

VulkanSurface* VulkanSurface::Create(ANativeWindow* window, ColorMode colorMode,
                                       SkColorType colorType, sk_sp<SkColorSpace> colorSpace,
                                       GrContext* grContext, const VulkanManager& vkManager) {

    VkAndroidSurfaceCreateInfoKHR surfaceCreateInfo;
    memset(&surfaceCreateInfo, 0, sizeof(VkAndroidSurfaceCreateInfoKHR));
    surfaceCreateInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceCreateInfo.pNext = nullptr;
    surfaceCreateInfo.flags = 0;
    surfaceCreateInfo.window = window;

    VkSurfaceKHR vkSurface = VK_NULL_HANDLE;
    VkResult res = vkManager.mCreateAndroidSurfaceKHR(vkManager.mInstance, &surfaceCreateInfo,
                                                      nullptr, &vkSurface);
    if (VK_SUCCESS != res) {
        ALOGE("VulkanSurface::Create() vkCreateAndroidSurfaceKHR failed (%d)", res);
        return nullptr;
    }

    VkSurfaceAutoDeleter vkSurfaceDeleter(vkManager.mInstance, vkSurface,
                                          vkManager.mDestroySurfaceKHR);

    SkDEBUGCODE(VkBool32 supported; res = vkManager.mGetPhysicalDeviceSurfaceSupportKHR(
            vkManager.mPhysicalDevice, vkManager.mPresentQueueIndex, vkSurface, &supported);
    // All physical devices and queue families on Android must be capable of
    // presentation with any native window.
    SkASSERT(VK_SUCCESS == res && supported););

    // check for capabilities
    VkSurfaceCapabilitiesKHR caps;
    res = vkManager.mGetPhysicalDeviceSurfaceCapabilitiesKHR(vkManager.mPhysicalDevice, vkSurface,
                                                             &caps);
    if (VK_SUCCESS != res) {
        ALOGE("VulkanSurface::Create() vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed (%d)", res);
        return nullptr;
    }

    LOG_ALWAYS_FATAL_IF(0 == (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR));

    /*
     * We must destroy the VK Surface before attempting to update the window as doing so after
     * will cause the native window to be modified in unexpected ways.
     */
    vkSurfaceDeleter.destroy();

    /*
     * Populate Window Info struct
     */
    WindowInfo windowInfo;

    windowInfo.transform = ConvertVkTransformToNative(caps.supportedTransforms);
    windowInfo.size = SkISize::Make(caps.currentExtent.width, caps.currentExtent.height);

    const SkISize minSize = SkISize::Make(caps.minImageExtent.width, caps.minImageExtent.height);
    const SkISize maxSize = SkISize::Make(caps.maxImageExtent.width, caps.maxImageExtent.height);
    ComputeWindowSizeAndTransform(&windowInfo, minSize, maxSize);

    windowInfo.bufferCount = std::max<uint32_t>(VulkanSurface::sMaxBufferCount, caps.minImageCount);
    if (caps.maxImageCount > 0 && windowInfo.bufferCount > caps.maxImageCount) {
        // Application must settle for fewer images than desired:
        windowInfo.bufferCount = caps.maxImageCount;
    }

    // Currently Skia requires the images to be color attachments and support all transfer
    // operations.
    VkImageUsageFlags usageFlags = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                   VK_IMAGE_USAGE_SAMPLED_BIT |
                                   VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                   VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    LOG_ALWAYS_FATAL_IF((caps.supportedUsageFlags & usageFlags) != usageFlags);

    windowInfo.dataspace = HAL_DATASPACE_V0_SRGB;
    if (colorMode == ColorMode::WideColorGamut) {
        skcms_Matrix3x3 surfaceGamut;
        LOG_ALWAYS_FATAL_IF(!colorSpace->toXYZD50(&surfaceGamut),
                            "Could not get gamut matrix from color space");
        if (memcmp(&surfaceGamut, &SkNamedGamut::kSRGB, sizeof(surfaceGamut)) == 0) {
            windowInfo.dataspace = HAL_DATASPACE_V0_SCRGB;
        } else if (memcmp(&surfaceGamut, &SkNamedGamut::kDCIP3, sizeof(surfaceGamut)) == 0) {
            windowInfo.dataspace = HAL_DATASPACE_DISPLAY_P3;
        } else {
            LOG_ALWAYS_FATAL("Unreachable: unsupported wide color space.");
        }
    }

    windowInfo.pixelFormat = ColorTypeToPixelFormat(colorType);
    VkFormat vkPixelFormat = VK_FORMAT_R8G8B8A8_UNORM;
    if (windowInfo.pixelFormat == PIXEL_FORMAT_RGBA_FP16) {
        vkPixelFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
    }

    uint64_t producerUsage =
            AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    uint64_t consumerUsage;
    native_window_get_consumer_usage(window, &consumerUsage);
    windowInfo.windowUsageFlags = consumerUsage | producerUsage;

    /*
     * Now we attempt to modify the window!
     */
    if (!UpdateWindow(window, windowInfo)) {
        return nullptr;
    }

    return new VulkanSurface(window, windowInfo, minSize, maxSize, grContext);
}

bool VulkanSurface::UpdateWindow(ANativeWindow* window, const WindowInfo& windowInfo) {
    ATRACE_CALL();

    if (!ResetNativeWindow(window)) {
        return false;
    }

    // -- Configure the native window --
    int err = native_window_set_buffers_format(window, windowInfo.pixelFormat);
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_buffers_format(%d) failed: %s (%d)",
              windowInfo.pixelFormat, strerror(-err), err);
        return false;
    }

    err = native_window_set_buffers_data_space(window, windowInfo.dataspace);
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_buffers_data_space(%d) "
              "failed: %s (%d)", windowInfo.dataspace, strerror(-err), err);
        return false;
    }

    const SkISize& size = windowInfo.actualSize;
    err = native_window_set_buffers_dimensions(window, size.width(), size.height());
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_buffers_dimensions(%d,%d) "
              "failed: %s (%d)", size.width(), size.height(), strerror(-err), err);
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
              "failed: %s (%d)", windowInfo.transform, strerror(-err), err);
        return false;
    }

    // Vulkan defaults to NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW, but this is different than
    // HWUI's expectation
    err = native_window_set_scaling_mode(window, NATIVE_WINDOW_SCALING_MODE_FREEZE);
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_scaling_mode(SCALE_TO_WINDOW) "
              "failed: %s (%d)", strerror(-err), err);
        return false;
    }

    // Lower layer insists that we have at least two buffers.
    err = native_window_set_buffer_count(window, std::max(2, windowInfo.bufferCount));
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_buffer_count(%d) failed: %s (%d)",
              windowInfo.bufferCount, strerror(-err), err);
        return false;
    }

    err = native_window_set_usage(window, windowInfo.windowUsageFlags);
    if (err != 0) {
        ALOGE("VulkanSurface::UpdateWindow() native_window_set_usage failed: %s (%d)",
              strerror(-err), err);
        return false;
    }

    return err == 0;
}

VulkanSurface::VulkanSurface(ANativeWindow* window, const WindowInfo& windowInfo,
                               SkISize minWindowSize, SkISize maxWindowSize, GrContext* grContext)
        : mNativeWindow(window)
        , mWindowInfo(windowInfo)
        , mGrContext(grContext)
        , mMinWindowSize(minWindowSize)
        , mMaxWindowSize(maxWindowSize) { }

VulkanSurface::~VulkanSurface() {
    releaseBuffers();

    // release the native window to be available for use by other clients
    int err = native_window_api_disconnect(mNativeWindow.get(), NATIVE_WINDOW_API_EGL);
    ALOGW_IF(err != 0, "native_window_api_disconnect failed: %s (%d)", strerror(-err), err);
}

void VulkanSurface::releaseBuffers() {
    for (uint32_t i = 0; i < VulkanSurface::sMaxBufferCount; i++) {
        VulkanSurface::NativeBufferInfo& bufferInfo = mNativeBuffers[i];

        if (bufferInfo.buffer.get() != nullptr && bufferInfo.dequeued) {
            int err = mNativeWindow->cancelBuffer(mNativeWindow.get(), bufferInfo.buffer.get(),
                                                  bufferInfo.dequeue_fence);
            if (err != 0) {
                ALOGE("cancelBuffer[%u] failed during destroy: %s (%d)", i, strerror(-err), err);
            }
            bufferInfo.dequeued = false;

            if (bufferInfo.dequeue_fence >= 0) {
                close(bufferInfo.dequeue_fence);
                bufferInfo.dequeue_fence = -1;
            }
        }

        LOG_ALWAYS_FATAL_IF(bufferInfo.dequeued);
        LOG_ALWAYS_FATAL_IF(bufferInfo.dequeue_fence != -1);

        bufferInfo.skSurface.reset();
        bufferInfo.buffer.clear();
        bufferInfo.hasValidContents = false;
        bufferInfo.lastPresentedCount = 0;
    }
}

VulkanSurface::NativeBufferInfo* VulkanSurface::dequeueNativeBuffer() {
    // Set the dequeue index to invalid in case of error and only reset it to the correct
    // value at the end of the function if everything dequeued correctly.
    mDequeuedIndex = -1;

    //check if the native window has been resized or rotated and update accordingly
    SkISize newSize = SkISize::MakeEmpty();
    int transformHint = 0;
    mNativeWindow->query(mNativeWindow.get(), NATIVE_WINDOW_WIDTH, &newSize.fWidth);
    mNativeWindow->query(mNativeWindow.get(), NATIVE_WINDOW_HEIGHT, &newSize.fHeight);
    mNativeWindow->query(mNativeWindow.get(), NATIVE_WINDOW_TRANSFORM_HINT, &transformHint);
    if (newSize != mWindowInfo.actualSize || transformHint != mWindowInfo.transform) {
        WindowInfo newWindowInfo = mWindowInfo;
        newWindowInfo.size = newSize;
        newWindowInfo.transform = IsTransformSupported(transformHint) ? transformHint : 0;
        ComputeWindowSizeAndTransform(&newWindowInfo, mMinWindowSize, mMaxWindowSize);

        int err = 0;
        if (newWindowInfo.actualSize != mWindowInfo.actualSize) {
            // reset the native buffers and update the window
            err = native_window_set_buffers_dimensions(mNativeWindow.get(),
                                                       newWindowInfo.actualSize.width(),
                                                       newWindowInfo.actualSize.height());
            if (err != 0) {
                ALOGE("native_window_set_buffers_dimensions(%d,%d) failed: %s (%d)",
                      newWindowInfo.actualSize.width(),
                      newWindowInfo.actualSize.height(), strerror(-err), err);
                return nullptr;
            }
            // reset the NativeBufferInfo (including SkSurface) associated with the old buffers. The
            // new NativeBufferInfo storage will be populated lazily as we dequeue each new buffer.
            releaseBuffers();
            // TODO should we ask the nativewindow to allocate buffers?
        }

        if (newWindowInfo.transform != mWindowInfo.transform) {
            err = native_window_set_buffers_transform(mNativeWindow.get(),
                    InvertTransform(newWindowInfo.transform));
            if (err != 0) {
                ALOGE("native_window_set_buffers_transform(%d) failed: %s (%d)",
                      newWindowInfo.transform, strerror(-err), err);
                newWindowInfo.transform = mWindowInfo.transform;
                ComputeWindowSizeAndTransform(&newWindowInfo, mMinWindowSize, mMaxWindowSize);
            }
        }

        mWindowInfo = newWindowInfo;
    }

    ANativeWindowBuffer* buffer;
    int fence_fd;
    int err = mNativeWindow->dequeueBuffer(mNativeWindow.get(), &buffer, &fence_fd);
    if (err != 0) {
        ALOGE("dequeueBuffer failed: %s (%d)", strerror(-err), err);
        return nullptr;
    }

    uint32_t idx;
    for (idx = 0; idx < mWindowInfo.bufferCount; idx++) {
        if (mNativeBuffers[idx].buffer.get() == buffer) {
            mNativeBuffers[idx].dequeued = true;
            mNativeBuffers[idx].dequeue_fence = fence_fd;
            break;
        } else if (mNativeBuffers[idx].buffer.get() == nullptr) {
            // increasing the number of buffers we have allocated
            mNativeBuffers[idx].buffer = buffer;
            mNativeBuffers[idx].dequeued = true;
            mNativeBuffers[idx].dequeue_fence = fence_fd;
            break;
        }
    }
    if (idx == mWindowInfo.bufferCount) {
        ALOGE("dequeueBuffer returned unrecognized buffer");
        mNativeWindow->cancelBuffer(mNativeWindow.get(), buffer, fence_fd);
        return nullptr;
    }

    VulkanSurface::NativeBufferInfo* bufferInfo = &mNativeBuffers[idx];

    if (bufferInfo->skSurface.get() == nullptr) {
        bufferInfo->skSurface =
                SkSurface::MakeFromAHardwareBuffer(mGrContext,
                        ANativeWindowBuffer_getHardwareBuffer(bufferInfo->buffer.get()),
                        kTopLeft_GrSurfaceOrigin, DataSpaceToColorSpace(mWindowInfo.dataspace),
                        nullptr);
        if (bufferInfo->skSurface.get() == nullptr) {
            ALOGE("SkSurface::MakeFromAHardwareBuffer failed");
            mNativeWindow->cancelBuffer(mNativeWindow.get(), buffer, fence_fd);
            return nullptr;
        }
    }

    mDequeuedIndex = idx;
    return bufferInfo;
}

bool VulkanSurface::presentCurrentBuffer(const SkRect& dirtyRect, int semaphoreFd) {
    if (!dirtyRect.isEmpty()) {
        SkRect transformedRect;
        mWindowInfo.preTransform.mapRect(&transformedRect, dirtyRect);

        SkIRect transformedIRect;
        transformedRect.roundOut(&transformedIRect);
        transformedIRect.intersect(0, 0, mWindowInfo.size.fWidth, mWindowInfo.size.fHeight);

        // map to bottom-left coordinate system
        android_native_rect_t aRect;
        aRect.left = transformedIRect.x();
        aRect.top = mWindowInfo.size.fHeight - (transformedIRect.y() + transformedIRect.height());
        aRect.right = aRect.left + transformedIRect.width();
        aRect.bottom = aRect.top - transformedIRect.height();

        int err = native_window_set_surface_damage(mNativeWindow.get(), &aRect, 1);
        ALOGE_IF(err != 0, "native_window_set_surface_damage failed: %s (%d)", strerror(-err), err);
    }

    VulkanSurface::NativeBufferInfo& currentBuffer = mNativeBuffers[mDequeuedIndex];
    int queuedFd = (semaphoreFd != -1) ? semaphoreFd : currentBuffer.dequeue_fence;
    int err = mNativeWindow->queueBuffer(mNativeWindow.get(), currentBuffer.buffer.get(), queuedFd);

    currentBuffer.dequeued = false;
    // queueBuffer always closes fence, even on error
    if (err != 0) {
        ALOGE("queueBuffer failed: %s (%d)", strerror(-err), err);
        mNativeWindow->cancelBuffer(mNativeWindow.get(), currentBuffer.buffer.get(),
                                    currentBuffer.dequeue_fence);
    } else {
        currentBuffer.hasValidContents = true;
        currentBuffer.lastPresentedCount = mPresentCount;
        mPresentCount++;
    }

    if (currentBuffer.dequeue_fence >= 0) {
        close(currentBuffer.dequeue_fence);
        currentBuffer.dequeue_fence = -1;
    }

    return err == 0;
}

int VulkanSurface::getCurrentBuffersAge() {
    VulkanSurface::NativeBufferInfo& currentBuffer = mNativeBuffers[mDequeuedIndex];
    return currentBuffer.hasValidContents ? (mPresentCount - currentBuffer.lastPresentedCount) : 0;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
