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

#ifndef VULKANMANAGER_H
#define VULKANMANAGER_H

#if !defined(VK_USE_PLATFORM_ANDROID_KHR)
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>

#include <GrContextOptions.h>
#include <SkSurface.h>
#include <ui/Fence.h>
#include <utils/StrongPointer.h>
#include <vk/GrVkBackendContext.h>
#include <vk/GrVkExtensions.h>
#include "Frame.h"
#include "IRenderPipeline.h"
#include "VulkanSurface.h"

class GrVkExtensions;

namespace android {
namespace uirenderer {
namespace renderthread {

class RenderThread;

// This class contains the shared global Vulkan objects, such as VkInstance, VkDevice and VkQueue,
// which are re-used by CanvasContext. This class is created once and should be used by all vulkan
// windowing contexts. The VulkanManager must be initialized before use.
class VulkanManager {
public:
    explicit VulkanManager() {}
    ~VulkanManager() { destroy(); }

    // Sets up the vulkan context that is shared amonst all clients of the VulkanManager. This must
    // be call once before use of the VulkanManager. Multiple calls after the first will simiply
    // return.
    void initialize();

    // Quick check to see if the VulkanManager has been initialized.
    bool hasVkContext() { return mDevice != VK_NULL_HANDLE; }

    // Create and destroy functions for wrapping an ANativeWindow in a VulkanSurface
    VulkanSurface* createSurface(ANativeWindow* window, ColorMode colorMode,
                                 sk_sp<SkColorSpace> surfaceColorSpace,
                                 SkColorType surfaceColorType, GrContext* grContext,
                                 uint32_t extraBuffers);
    void destroySurface(VulkanSurface* surface);

    Frame dequeueNextBuffer(VulkanSurface* surface);
    void swapBuffers(VulkanSurface* surface, const SkRect& dirtyRect);

    // Cleans up all the global state in the VulkanManger.
    void destroy();

    // Inserts a wait on fence command into the Vulkan command buffer.
    status_t fenceWait(sp<Fence>& fence, GrContext* grContext);

    // Creates a fence that is signaled when all the pending Vulkan commands are finished on the
    // GPU.
    status_t createReleaseFence(sp<Fence>& nativeFence, GrContext* grContext);

    // Returned pointers are owned by VulkanManager.
    // An instance of VkFunctorInitParams returned from getVkFunctorInitParams refers to
    // the internal state of VulkanManager: VulkanManager must be alive to use the returned value.
    VkFunctorInitParams getVkFunctorInitParams() const;

    sk_sp<GrContext> createContext(const GrContextOptions& options);

    uint32_t getDriverVersion() const { return mDriverVersion; }

private:
    friend class VulkanSurface;
    // Sets up the VkInstance and VkDevice objects. Also fills out the passed in
    // VkPhysicalDeviceFeatures struct.
    void setupDevice(GrVkExtensions&, VkPhysicalDeviceFeatures2&);

    // simple wrapper class that exists only to initialize a pointer to NULL
    template <typename FNPTR_TYPE>
    class VkPtr {
    public:
        VkPtr() : fPtr(NULL) {}
        VkPtr operator=(FNPTR_TYPE ptr) {
            fPtr = ptr;
            return *this;
        }
        // NOLINTNEXTLINE(google-explicit-constructor)
        operator FNPTR_TYPE() const { return fPtr; }

    private:
        FNPTR_TYPE fPtr;
    };

    // WSI interface functions
    VkPtr<PFN_vkCreateAndroidSurfaceKHR> mCreateAndroidSurfaceKHR;
    VkPtr<PFN_vkDestroySurfaceKHR> mDestroySurfaceKHR;
    VkPtr<PFN_vkGetPhysicalDeviceSurfaceSupportKHR> mGetPhysicalDeviceSurfaceSupportKHR;
    VkPtr<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR> mGetPhysicalDeviceSurfaceCapabilitiesKHR;
    VkPtr<PFN_vkGetPhysicalDeviceSurfaceFormatsKHR> mGetPhysicalDeviceSurfaceFormatsKHR;
    VkPtr<PFN_vkGetPhysicalDeviceSurfacePresentModesKHR> mGetPhysicalDeviceSurfacePresentModesKHR;

    // Instance Functions
    VkPtr<PFN_vkEnumerateInstanceVersion> mEnumerateInstanceVersion;
    VkPtr<PFN_vkEnumerateInstanceExtensionProperties> mEnumerateInstanceExtensionProperties;
    VkPtr<PFN_vkCreateInstance> mCreateInstance;

    VkPtr<PFN_vkDestroyInstance> mDestroyInstance;
    VkPtr<PFN_vkEnumeratePhysicalDevices> mEnumeratePhysicalDevices;
    VkPtr<PFN_vkGetPhysicalDeviceProperties> mGetPhysicalDeviceProperties;
    VkPtr<PFN_vkGetPhysicalDeviceQueueFamilyProperties> mGetPhysicalDeviceQueueFamilyProperties;
    VkPtr<PFN_vkGetPhysicalDeviceFeatures2> mGetPhysicalDeviceFeatures2;
    VkPtr<PFN_vkGetPhysicalDeviceImageFormatProperties2> mGetPhysicalDeviceImageFormatProperties2;
    VkPtr<PFN_vkCreateDevice> mCreateDevice;
    VkPtr<PFN_vkEnumerateDeviceExtensionProperties> mEnumerateDeviceExtensionProperties;

    // Device Functions
    VkPtr<PFN_vkGetDeviceQueue> mGetDeviceQueue;
    VkPtr<PFN_vkDeviceWaitIdle> mDeviceWaitIdle;
    VkPtr<PFN_vkDestroyDevice> mDestroyDevice;
    VkPtr<PFN_vkCreateCommandPool> mCreateCommandPool;
    VkPtr<PFN_vkDestroyCommandPool> mDestroyCommandPool;
    VkPtr<PFN_vkAllocateCommandBuffers> mAllocateCommandBuffers;
    VkPtr<PFN_vkFreeCommandBuffers> mFreeCommandBuffers;
    VkPtr<PFN_vkResetCommandBuffer> mResetCommandBuffer;
    VkPtr<PFN_vkBeginCommandBuffer> mBeginCommandBuffer;
    VkPtr<PFN_vkEndCommandBuffer> mEndCommandBuffer;
    VkPtr<PFN_vkCmdPipelineBarrier> mCmdPipelineBarrier;

    VkPtr<PFN_vkQueueSubmit> mQueueSubmit;
    VkPtr<PFN_vkQueueWaitIdle> mQueueWaitIdle;

    VkPtr<PFN_vkCreateSemaphore> mCreateSemaphore;
    VkPtr<PFN_vkDestroySemaphore> mDestroySemaphore;
    VkPtr<PFN_vkImportSemaphoreFdKHR> mImportSemaphoreFdKHR;
    VkPtr<PFN_vkGetSemaphoreFdKHR> mGetSemaphoreFdKHR;
    VkPtr<PFN_vkCreateFence> mCreateFence;
    VkPtr<PFN_vkDestroyFence> mDestroyFence;
    VkPtr<PFN_vkWaitForFences> mWaitForFences;
    VkPtr<PFN_vkResetFences> mResetFences;

    VkInstance mInstance = VK_NULL_HANDLE;
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;
    VkDevice mDevice = VK_NULL_HANDLE;

    uint32_t mGraphicsQueueIndex;
    VkQueue mGraphicsQueue = VK_NULL_HANDLE;
    uint32_t mPresentQueueIndex;
    VkQueue mPresentQueue = VK_NULL_HANDLE;
    VkCommandPool mCommandPool = VK_NULL_HANDLE;

    // Variables saved to populate VkFunctorInitParams.
    static const uint32_t mAPIVersion = VK_MAKE_VERSION(1, 1, 0);
    std::vector<VkExtensionProperties> mInstanceExtensionsOwner;
    std::vector<const char*> mInstanceExtensions;
    std::vector<VkExtensionProperties> mDeviceExtensionsOwner;
    std::vector<const char*> mDeviceExtensions;
    VkPhysicalDeviceFeatures2 mPhysicalDeviceFeatures2{};

    enum class SwapBehavior {
        Discard,
        BufferAge,
    };
    SwapBehavior mSwapBehavior = SwapBehavior::Discard;
    GrVkExtensions mExtensions;
    uint32_t mDriverVersion = 0;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* VULKANMANAGER_H */
