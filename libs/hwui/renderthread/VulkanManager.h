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

#include <functional>
#include <mutex>

#include "vulkan/vulkan_core.h"
#if !defined(VK_USE_PLATFORM_ANDROID_KHR)
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <GrContextOptions.h>
#include <SkSurface.h>
#include <utils/StrongPointer.h>
#include <vk/GrVkBackendContext.h>
#include <vk/GrVkExtensions.h>
#include <vulkan/vulkan.h>

// VK_ANDROID_frame_boundary is a bespoke extension defined by AGI
// (https://github.com/google/agi) to enable profiling of apps rendering via
// HWUI. This extension is not defined in Khronos, hence the need to declare it
// manually here. There's a superseding extension (VK_EXT_frame_boundary) being
// discussed in Khronos, but in the meantime we use the bespoke
// VK_ANDROID_frame_boundary. This is a device extension that is implemented by
// AGI's Vulkan capture layer, such that it is only supported by devices when
// AGI is doing a capture of the app.
//
// TODO(b/182165045): use the Khronos blessed VK_EXT_frame_boudary once it has
// landed in the spec.
typedef void(VKAPI_PTR* PFN_vkFrameBoundaryANDROID)(VkDevice device, VkSemaphore semaphore,
                                                    VkImage image);
#define VK_ANDROID_FRAME_BOUNDARY_EXTENSION_NAME "VK_ANDROID_frame_boundary"

#include "Frame.h"
#include "IRenderPipeline.h"
#include "VulkanSurface.h"
#include "private/hwui/DrawVkInfo.h"

class GrVkExtensions;

namespace android {
namespace uirenderer {
namespace renderthread {

class RenderThread;

// This class contains the shared global Vulkan objects, such as VkInstance, VkDevice and VkQueue,
// which are re-used by CanvasContext. This class is created once and should be used by all vulkan
// windowing contexts. The VulkanManager must be initialized before use.
class VulkanManager final : public RefBase {
public:
    static sp<VulkanManager> getInstance();

    // Sets up the vulkan context that is shared amonst all clients of the VulkanManager. This must
    // be call once before use of the VulkanManager. Multiple calls after the first will simiply
    // return.
    void initialize();

    // Quick check to see if the VulkanManager has been initialized.
    bool hasVkContext() { return mDevice != VK_NULL_HANDLE; }

    // Create and destroy functions for wrapping an ANativeWindow in a VulkanSurface
    VulkanSurface* createSurface(ANativeWindow* window,
                                 ColorMode colorMode,
                                 sk_sp<SkColorSpace> surfaceColorSpace,
                                 SkColorType surfaceColorType,
                                 GrDirectContext* grContext,
                                 uint32_t extraBuffers);
    void destroySurface(VulkanSurface* surface);

    Frame dequeueNextBuffer(VulkanSurface* surface);
    void finishFrame(SkSurface* surface);
    void swapBuffers(VulkanSurface* surface, const SkRect& dirtyRect);

    // Inserts a wait on fence command into the Vulkan command buffer.
    status_t fenceWait(int fence, GrDirectContext* grContext);

    // Creates a fence that is signaled when all the pending Vulkan commands are finished on the
    // GPU.
    status_t createReleaseFence(int* nativeFence, GrDirectContext* grContext);

    // Returned pointers are owned by VulkanManager.
    // An instance of VkFunctorInitParams returned from getVkFunctorInitParams refers to
    // the internal state of VulkanManager: VulkanManager must be alive to use the returned value.
    VkFunctorInitParams getVkFunctorInitParams() const;


    enum class ContextType {
        kRenderThread,
        kUploadThread
    };

    // returns a Skia graphic context used to draw content on the specified thread
    sk_sp<GrDirectContext> createContext(const GrContextOptions& options,
                                         ContextType contextType = ContextType::kRenderThread);

    uint32_t getDriverVersion() const { return mDriverVersion; }

private:
    friend class VulkanSurface;

    explicit VulkanManager() {}
    ~VulkanManager();

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
    VkPtr<PFN_vkFrameBoundaryANDROID> mFrameBoundaryANDROID;

    VkInstance mInstance = VK_NULL_HANDLE;
    VkPhysicalDevice mPhysicalDevice = VK_NULL_HANDLE;
    VkDevice mDevice = VK_NULL_HANDLE;

    uint32_t mGraphicsQueueIndex;

    std::mutex mGraphicsQueueMutex;
    VkQueue mGraphicsQueue = VK_NULL_HANDLE;

    static VKAPI_ATTR VkResult interceptedVkQueueSubmit(VkQueue queue, uint32_t submitCount,
                                                        const VkSubmitInfo* pSubmits,
                                                        VkFence fence) {
        sp<VulkanManager> manager = VulkanManager::getInstance();
        std::lock_guard<std::mutex> lock(manager->mGraphicsQueueMutex);
        return manager->mQueueSubmit(queue, submitCount, pSubmits, fence);
    }

    static VKAPI_ATTR VkResult interceptedVkQueueWaitIdle(VkQueue queue) {
        sp<VulkanManager> manager = VulkanManager::getInstance();
        std::lock_guard<std::mutex> lock(manager->mGraphicsQueueMutex);
        return manager->mQueueWaitIdle(queue);
    }

    static GrVkGetProc sSkiaGetProp;

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

    VkSemaphore mSwapSemaphore = VK_NULL_HANDLE;
    void* mDestroySemaphoreContext = nullptr;

    std::mutex mInitializeLock;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* VULKANMANAGER_H */
