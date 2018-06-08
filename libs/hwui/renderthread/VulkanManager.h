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

#include <SkSurface.h>
#include <vk/GrVkBackendContext.h>

#include <vulkan/vulkan.h>

namespace android {
namespace uirenderer {
namespace renderthread {

class RenderThread;

class VulkanSurface {
public:
    VulkanSurface() {}

    sk_sp<SkSurface> getBackBufferSurface() { return mBackbuffer; }

private:
    friend class VulkanManager;
    struct BackbufferInfo {
        uint32_t mImageIndex;           // image this is associated with
        VkSemaphore mAcquireSemaphore;  // we signal on this for acquisition of image
        VkSemaphore mRenderSemaphore;   // we wait on this for rendering to be done
        VkCommandBuffer
                mTransitionCmdBuffers[2];  // to transition layout between present and render
        // We use these fences to make sure the above Command buffers have finished their work
        // before attempting to reuse them or destroy them.
        VkFence mUsageFences[2];
    };

    struct ImageInfo {
        VkImageLayout mImageLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        sk_sp<SkSurface> mSurface;
        uint16_t mLastUsed = 0;
        bool mInvalid = true;
    };

    sk_sp<SkSurface> mBackbuffer;

    VkSurfaceKHR mVkSurface = VK_NULL_HANDLE;
    VkSwapchainKHR mSwapchain = VK_NULL_HANDLE;

    BackbufferInfo* mBackbuffers = nullptr;
    uint32_t mCurrentBackbufferIndex;

    uint32_t mImageCount;
    VkImage* mImages = nullptr;
    ImageInfo* mImageInfos;
    uint16_t mCurrentTime = 0;
};

// This class contains the shared global Vulkan objects, such as VkInstance, VkDevice and VkQueue,
// which are re-used by CanvasContext. This class is created once and should be used by all vulkan
// windowing contexts. The VulkanManager must be initialized before use.
class VulkanManager {
public:
    // Sets up the vulkan context that is shared amonst all clients of the VulkanManager. This must
    // be call once before use of the VulkanManager. Multiple calls after the first will simiply
    // return.
    void initialize();

    // Quick check to see if the VulkanManager has been initialized.
    bool hasVkContext() { return mBackendContext.get() != nullptr; }

    // Given a window this creates a new VkSurfaceKHR and VkSwapchain and stores them inside a new
    // VulkanSurface object which is returned.
    VulkanSurface* createSurface(ANativeWindow* window);

    // Destroy the VulkanSurface and all associated vulkan objects.
    void destroySurface(VulkanSurface* surface);

    // Cleans up all the global state in the VulkanManger.
    void destroy();

    // No work is needed to make a VulkanSurface current, and all functions require that a
    // VulkanSurface is passed into them so we just return true here.
    bool isCurrent(VulkanSurface* surface) { return true; }

    int getAge(VulkanSurface* surface);

    // Returns an SkSurface which wraps the next image returned from vkAcquireNextImageKHR. It also
    // will transition the VkImage from a present layout to color attachment so that it can be used
    // by the client for drawing.
    SkSurface* getBackbufferSurface(VulkanSurface* surface);

    // Presents the current VkImage.
    void swapBuffers(VulkanSurface* surface);

private:
    friend class RenderThread;

    explicit VulkanManager(RenderThread& thread);
    ~VulkanManager() { destroy(); }

    void destroyBuffers(VulkanSurface* surface);

    bool createSwapchain(VulkanSurface* surface);
    void createBuffers(VulkanSurface* surface, VkFormat format, VkExtent2D extent);

    VulkanSurface::BackbufferInfo* getAvailableBackbuffer(VulkanSurface* surface);

    // simple wrapper class that exists only to initialize a pointer to NULL
    template <typename FNPTR_TYPE>
    class VkPtr {
    public:
        VkPtr() : fPtr(NULL) {}
        VkPtr operator=(FNPTR_TYPE ptr) {
            fPtr = ptr;
            return *this;
        }
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

    VkPtr<PFN_vkCreateSwapchainKHR> mCreateSwapchainKHR;
    VkPtr<PFN_vkDestroySwapchainKHR> mDestroySwapchainKHR;
    VkPtr<PFN_vkGetSwapchainImagesKHR> mGetSwapchainImagesKHR;
    VkPtr<PFN_vkAcquireNextImageKHR> mAcquireNextImageKHR;
    VkPtr<PFN_vkQueuePresentKHR> mQueuePresentKHR;
    VkPtr<PFN_vkCreateSharedSwapchainsKHR> mCreateSharedSwapchainsKHR;

    // Additional vulkan functions
    VkPtr<PFN_vkCreateCommandPool> mCreateCommandPool;
    VkPtr<PFN_vkDestroyCommandPool> mDestroyCommandPool;
    VkPtr<PFN_vkAllocateCommandBuffers> mAllocateCommandBuffers;
    VkPtr<PFN_vkFreeCommandBuffers> mFreeCommandBuffers;
    VkPtr<PFN_vkResetCommandBuffer> mResetCommandBuffer;
    VkPtr<PFN_vkBeginCommandBuffer> mBeginCommandBuffer;
    VkPtr<PFN_vkEndCommandBuffer> mEndCommandBuffer;
    VkPtr<PFN_vkCmdPipelineBarrier> mCmdPipelineBarrier;

    VkPtr<PFN_vkGetDeviceQueue> mGetDeviceQueue;
    VkPtr<PFN_vkQueueSubmit> mQueueSubmit;
    VkPtr<PFN_vkQueueWaitIdle> mQueueWaitIdle;
    VkPtr<PFN_vkDeviceWaitIdle> mDeviceWaitIdle;

    VkPtr<PFN_vkCreateSemaphore> mCreateSemaphore;
    VkPtr<PFN_vkDestroySemaphore> mDestroySemaphore;
    VkPtr<PFN_vkCreateFence> mCreateFence;
    VkPtr<PFN_vkDestroyFence> mDestroyFence;
    VkPtr<PFN_vkWaitForFences> mWaitForFences;
    VkPtr<PFN_vkResetFences> mResetFences;

    RenderThread& mRenderThread;

    sk_sp<const GrVkBackendContext> mBackendContext;
    uint32_t mPresentQueueIndex;
    VkQueue mPresentQueue = VK_NULL_HANDLE;
    VkCommandPool mCommandPool = VK_NULL_HANDLE;

    enum class SwapBehavior {
        Discard,
        BufferAge,
    };
    SwapBehavior mSwapBehavior = SwapBehavior::Discard;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* VULKANMANAGER_H */
