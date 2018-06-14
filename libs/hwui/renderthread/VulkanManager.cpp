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

#include "VulkanManager.h"

#include "DeviceInfo.h"
#include "Properties.h"
#include "RenderThread.h"
#include "renderstate/RenderState.h"
#include "utils/FatVector.h"

#include <GrBackendSurface.h>
#include <GrContext.h>
#include <GrTypes.h>
#include <vk/GrVkTypes.h>

namespace android {
namespace uirenderer {
namespace renderthread {

#define GET_PROC(F) m##F = (PFN_vk##F)vkGetInstanceProcAddr(VK_NULL_HANDLE, "vk" #F)
#define GET_INST_PROC(F) m##F = (PFN_vk##F)vkGetInstanceProcAddr(mInstance, "vk" #F)
#define GET_DEV_PROC(F) m##F = (PFN_vk##F)vkGetDeviceProcAddr(mDevice, "vk" #F)

VulkanManager::VulkanManager(RenderThread& thread) : mRenderThread(thread) {}

void VulkanManager::destroy() {
    mRenderThread.renderState().onVkContextDestroyed();
    mRenderThread.setGrContext(nullptr);

    if (VK_NULL_HANDLE != mCommandPool) {
        mDestroyCommandPool(mDevice, mCommandPool, nullptr);
        mCommandPool = VK_NULL_HANDLE;
    }

    if (mDevice != VK_NULL_HANDLE) {
        mDeviceWaitIdle(mDevice);
        mDestroyDevice(mDevice, nullptr);
    }

    if (mInstance != VK_NULL_HANDLE) {
        mDestroyInstance(mInstance, nullptr);
    }

    mGraphicsQueue = VK_NULL_HANDLE;
    mPresentQueue = VK_NULL_HANDLE;
    mDevice = VK_NULL_HANDLE;
    mPhysicalDevice = VK_NULL_HANDLE;
    mInstance = VK_NULL_HANDLE;
}

bool VulkanManager::setupDevice(VkPhysicalDeviceFeatures& deviceFeatures) {
    VkResult err;

    constexpr VkApplicationInfo app_info = {
        VK_STRUCTURE_TYPE_APPLICATION_INFO, // sType
        nullptr,                            // pNext
        "android framework",                // pApplicationName
        0,                                  // applicationVersion
        "android framework",                // pEngineName
        0,                                  // engineVerison
        VK_MAKE_VERSION(1, 0, 0),           // apiVersion
    };

    std::vector<const char*> instanceExtensions;
    {
        GET_PROC(EnumerateInstanceExtensionProperties);

        uint32_t extensionCount = 0;
        err = mEnumerateInstanceExtensionProperties(nullptr, &extensionCount, nullptr);
        if (VK_SUCCESS != err) {
            return false;
        }
        std::unique_ptr<VkExtensionProperties[]> extensions(
                new VkExtensionProperties[extensionCount]);
        err = mEnumerateInstanceExtensionProperties(nullptr, &extensionCount, extensions.get());
        if (VK_SUCCESS != err) {
            return false;
        }
        bool hasKHRSurfaceExtension = false;
        bool hasKHRAndroidSurfaceExtension = false;
        for (uint32_t i = 0; i < extensionCount; ++i) {
            instanceExtensions.push_back(extensions[i].extensionName);
            if (!strcmp(extensions[i].extensionName, VK_KHR_SURFACE_EXTENSION_NAME)) {
                hasKHRSurfaceExtension = true;
            }
            if (!strcmp(extensions[i].extensionName,VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
                hasKHRAndroidSurfaceExtension = true;
            }
        }
        if (!hasKHRSurfaceExtension || !hasKHRAndroidSurfaceExtension) {
            this->destroy();
            return false;
        }
    }

    const VkInstanceCreateInfo instance_create = {
        VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,    // sType
        nullptr,                                   // pNext
        0,                                         // flags
        &app_info,                                 // pApplicationInfo
        0,                                         // enabledLayerNameCount
        nullptr,                                   // ppEnabledLayerNames
        (uint32_t) instanceExtensions.size(),      // enabledExtensionNameCount
        instanceExtensions.data(),                 // ppEnabledExtensionNames
    };

    GET_PROC(CreateInstance);
    err = mCreateInstance(&instance_create, nullptr, &mInstance);
    if (err < 0) {
        this->destroy();
        return false;
    }

    GET_INST_PROC(DestroyInstance);
    GET_INST_PROC(EnumeratePhysicalDevices);
    GET_INST_PROC(GetPhysicalDeviceQueueFamilyProperties);
    GET_INST_PROC(GetPhysicalDeviceFeatures);
    GET_INST_PROC(CreateDevice);
    GET_INST_PROC(EnumerateDeviceExtensionProperties);
    GET_INST_PROC(CreateAndroidSurfaceKHR);
    GET_INST_PROC(DestroySurfaceKHR);
    GET_INST_PROC(GetPhysicalDeviceSurfaceSupportKHR);
    GET_INST_PROC(GetPhysicalDeviceSurfaceCapabilitiesKHR);
    GET_INST_PROC(GetPhysicalDeviceSurfaceFormatsKHR);
    GET_INST_PROC(GetPhysicalDeviceSurfacePresentModesKHR);

    uint32_t gpuCount;
    err = mEnumeratePhysicalDevices(mInstance, &gpuCount, nullptr);
    if (err) {
        this->destroy();
        return false;
    }
    if (!gpuCount) {
        this->destroy();
        return false;
    }
    // Just returning the first physical device instead of getting the whole array. Since there
    // should only be one device on android.
    gpuCount = 1;
    err = mEnumeratePhysicalDevices(mInstance, &gpuCount, &mPhysicalDevice);
    // VK_INCOMPLETE is returned when the count we provide is less than the total device count.
    if (err && VK_INCOMPLETE != err) {
        this->destroy();
        return false;
    }

    // query to get the initial queue props size
    uint32_t queueCount;
    mGetPhysicalDeviceQueueFamilyProperties(mPhysicalDevice, &queueCount, nullptr);
    if (!queueCount) {
        this->destroy();
        return false;
    }

    // now get the actual queue props
    std::unique_ptr<VkQueueFamilyProperties[]> queueProps(new VkQueueFamilyProperties[queueCount]);
    mGetPhysicalDeviceQueueFamilyProperties(mPhysicalDevice, &queueCount, queueProps.get());

    // iterate to find the graphics queue
    mGraphicsQueueIndex = queueCount;
    for (uint32_t i = 0; i < queueCount; i++) {
        if (queueProps[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) {
            mGraphicsQueueIndex = i;
            break;
        }
    }
    if (mGraphicsQueueIndex == queueCount) {
        this->destroy();
        return false;
    }

    // All physical devices and queue families on Android must be capable of
    // presentation with any native window. So just use the first one.
    mPresentQueueIndex = 0;

    std::vector<const char*> deviceExtensions;
    {
        uint32_t extensionCount = 0;
        err = mEnumerateDeviceExtensionProperties(mPhysicalDevice, nullptr, &extensionCount,
                nullptr);
        if (VK_SUCCESS != err) {
            this->destroy();
            return false;
        }
        std::unique_ptr<VkExtensionProperties[]> extensions(
                new VkExtensionProperties[extensionCount]);
        err = mEnumerateDeviceExtensionProperties(mPhysicalDevice, nullptr, &extensionCount,
                extensions.get());
        if (VK_SUCCESS != err) {
            this->destroy();
            return false;
        }
        bool hasKHRSwapchainExtension = false;
        for (uint32_t i = 0; i < extensionCount; ++i) {
            deviceExtensions.push_back(extensions[i].extensionName);
            if (!strcmp(extensions[i].extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                hasKHRSwapchainExtension = true;
            }
        }
        if (!hasKHRSwapchainExtension) {
            this->destroy();
            return false;
        }
    }

    // query to get the physical device properties
    mGetPhysicalDeviceFeatures(mPhysicalDevice, &deviceFeatures);
    // this looks like it would slow things down,
    // and we can't depend on it on all platforms
    deviceFeatures.robustBufferAccess = VK_FALSE;

    float queuePriorities[1] = { 0.0 };

    const VkDeviceQueueCreateInfo queueInfo[2] = {
        {
            VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, // sType
            nullptr,                                    // pNext
            0,                                          // VkDeviceQueueCreateFlags
            mGraphicsQueueIndex,                        // queueFamilyIndex
            1,                                          // queueCount
            queuePriorities,                            // pQueuePriorities
        },
        {
            VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, // sType
            nullptr,                                    // pNext
            0,                                          // VkDeviceQueueCreateFlags
            mPresentQueueIndex,                         // queueFamilyIndex
            1,                                          // queueCount
            queuePriorities,                            // pQueuePriorities
        }
    };
    uint32_t queueInfoCount = (mPresentQueueIndex != mGraphicsQueueIndex) ? 2 : 1;

    const VkDeviceCreateInfo deviceInfo = {
        VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,    // sType
        nullptr,                                 // pNext
        0,                                       // VkDeviceCreateFlags
        queueInfoCount,                          // queueCreateInfoCount
        queueInfo,                               // pQueueCreateInfos
        0,                                       // layerCount
        nullptr,                                 // ppEnabledLayerNames
        (uint32_t) deviceExtensions.size(),      // extensionCount
        deviceExtensions.data(),                 // ppEnabledExtensionNames
        &deviceFeatures                          // ppEnabledFeatures
    };

    err = mCreateDevice(mPhysicalDevice, &deviceInfo, nullptr, &mDevice);
    if (err) {
        this->destroy();
        return false;
    }

    GET_DEV_PROC(GetDeviceQueue);
    GET_DEV_PROC(DeviceWaitIdle);
    GET_DEV_PROC(DestroyDevice);
    GET_DEV_PROC(CreateSwapchainKHR);
    GET_DEV_PROC(DestroySwapchainKHR);
    GET_DEV_PROC(GetSwapchainImagesKHR);
    GET_DEV_PROC(AcquireNextImageKHR);
    GET_DEV_PROC(QueuePresentKHR);
    GET_DEV_PROC(CreateCommandPool);
    GET_DEV_PROC(DestroyCommandPool);
    GET_DEV_PROC(AllocateCommandBuffers);
    GET_DEV_PROC(FreeCommandBuffers);
    GET_DEV_PROC(ResetCommandBuffer);
    GET_DEV_PROC(BeginCommandBuffer);
    GET_DEV_PROC(EndCommandBuffer);
    GET_DEV_PROC(CmdPipelineBarrier);
    GET_DEV_PROC(GetDeviceQueue);
    GET_DEV_PROC(QueueSubmit);
    GET_DEV_PROC(QueueWaitIdle);
    GET_DEV_PROC(DeviceWaitIdle);
    GET_DEV_PROC(CreateSemaphore);
    GET_DEV_PROC(DestroySemaphore);
    GET_DEV_PROC(CreateFence);
    GET_DEV_PROC(DestroyFence);
    GET_DEV_PROC(WaitForFences);
    GET_DEV_PROC(ResetFences);

    return true;
}

void VulkanManager::initialize() {
    if (mDevice != VK_NULL_HANDLE) {
        return;
    }

    std::vector<const char*> instanceExtensions;
    std::vector<const char*> deviceExtensions;
    VkPhysicalDeviceFeatures deviceFeatures;
    LOG_ALWAYS_FATAL_IF(!this->setupDevice(deviceFeatures));

    mGetDeviceQueue(mDevice, mGraphicsQueueIndex, 0, &mGraphicsQueue);

    uint32_t extensionFlags = kKHR_surface_GrVkExtensionFlag |
                              kKHR_android_surface_GrVkExtensionFlag |
                              kKHR_swapchain_GrVkExtensionFlag;

    uint32_t featureFlags = 0;
    if (deviceFeatures.geometryShader) {
        featureFlags |= kGeometryShader_GrVkFeatureFlag;
    }
    if (deviceFeatures.dualSrcBlend) {
        featureFlags |= kDualSrcBlend_GrVkFeatureFlag;
    }
    if (deviceFeatures.sampleRateShading) {
        featureFlags |= kSampleRateShading_GrVkFeatureFlag;
    }

    auto getProc = [] (const char* proc_name, VkInstance instance, VkDevice device) {
        if (device != VK_NULL_HANDLE) {
            return vkGetDeviceProcAddr(device, proc_name);
        }
        return vkGetInstanceProcAddr(instance, proc_name);
    };
    auto interface =
        sk_make_sp<GrVkInterface>(getProc, mInstance, mDevice, extensionFlags);

    GrVkBackendContext backendContext;
    backendContext.fInstance = mInstance;
    backendContext.fPhysicalDevice = mPhysicalDevice;
    backendContext.fDevice = mDevice;
    backendContext.fQueue = mGraphicsQueue;
    backendContext.fGraphicsQueueIndex = mGraphicsQueueIndex;
    backendContext.fMinAPIVersion = VK_MAKE_VERSION(1, 0, 0);
    backendContext.fExtensions = extensionFlags;
    backendContext.fFeatures = featureFlags;
    backendContext.fInterface = std::move(interface);
    backendContext.fOwnsInstanceAndDevice = false;

    // create the command pool for the command buffers
    if (VK_NULL_HANDLE == mCommandPool) {
        VkCommandPoolCreateInfo commandPoolInfo;
        memset(&commandPoolInfo, 0, sizeof(VkCommandPoolCreateInfo));
        commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        // this needs to be on the render queue
        commandPoolInfo.queueFamilyIndex = mGraphicsQueueIndex;
        commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        SkDEBUGCODE(VkResult res =) mCreateCommandPool(mDevice, &commandPoolInfo, nullptr,
                &mCommandPool);
        SkASSERT(VK_SUCCESS == res);
    }

    mGetDeviceQueue(mDevice, mPresentQueueIndex, 0, &mPresentQueue);

    GrContextOptions options;
    options.fDisableDistanceFieldPaths = true;
    mRenderThread.cacheManager().configureContext(&options);
    sk_sp<GrContext> grContext(GrContext::MakeVulkan(backendContext, options));
    LOG_ALWAYS_FATAL_IF(!grContext.get());
    mRenderThread.setGrContext(grContext);
    DeviceInfo::initialize(mRenderThread.getGrContext()->maxRenderTargetSize());

    if (Properties::enablePartialUpdates && Properties::useBufferAge) {
        mSwapBehavior = SwapBehavior::BufferAge;
    }

    mRenderThread.renderState().onVkContextCreated();
}

// Returns the next BackbufferInfo to use for the next draw. The function will make sure all
// previous uses have finished before returning.
VulkanSurface::BackbufferInfo* VulkanManager::getAvailableBackbuffer(VulkanSurface* surface) {
    SkASSERT(surface->mBackbuffers);

    ++surface->mCurrentBackbufferIndex;
    if (surface->mCurrentBackbufferIndex > surface->mImageCount) {
        surface->mCurrentBackbufferIndex = 0;
    }

    VulkanSurface::BackbufferInfo* backbuffer =
            surface->mBackbuffers + surface->mCurrentBackbufferIndex;

    // Before we reuse a backbuffer, make sure its fences have all signaled so that we can safely
    // reuse its commands buffers.
    VkResult res = mWaitForFences(mDevice, 2, backbuffer->mUsageFences, true, UINT64_MAX);
    if (res != VK_SUCCESS) {
        return nullptr;
    }

    return backbuffer;
}

SkSurface* VulkanManager::getBackbufferSurface(VulkanSurface* surface) {
    VulkanSurface::BackbufferInfo* backbuffer = getAvailableBackbuffer(surface);
    SkASSERT(backbuffer);

    VkResult res;

    res = mResetFences(mDevice, 2, backbuffer->mUsageFences);
    SkASSERT(VK_SUCCESS == res);

    // The acquire will signal the attached mAcquireSemaphore. We use this to know the image has
    // finished presenting and that it is safe to begin sending new commands to the returned image.
    res = mAcquireNextImageKHR(mDevice, surface->mSwapchain, UINT64_MAX,
                               backbuffer->mAcquireSemaphore, VK_NULL_HANDLE,
                               &backbuffer->mImageIndex);

    if (VK_ERROR_SURFACE_LOST_KHR == res) {
        // need to figure out how to create a new vkSurface without the platformData*
        // maybe use attach somehow? but need a Window
        return nullptr;
    }
    if (VK_ERROR_OUT_OF_DATE_KHR == res) {
        // tear swapchain down and try again
        if (!createSwapchain(surface)) {
            return nullptr;
        }
        backbuffer = getAvailableBackbuffer(surface);
        res = mResetFences(mDevice, 2, backbuffer->mUsageFences);
        SkASSERT(VK_SUCCESS == res);

        // acquire the image
        res = mAcquireNextImageKHR(mDevice, surface->mSwapchain, UINT64_MAX,
                                   backbuffer->mAcquireSemaphore, VK_NULL_HANDLE,
                                   &backbuffer->mImageIndex);

        if (VK_SUCCESS != res) {
            return nullptr;
        }
    }

    // set up layout transfer from initial to color attachment
    VkImageLayout layout = surface->mImageInfos[backbuffer->mImageIndex].mImageLayout;
    SkASSERT(VK_IMAGE_LAYOUT_UNDEFINED == layout || VK_IMAGE_LAYOUT_PRESENT_SRC_KHR == layout);
    VkPipelineStageFlags srcStageMask = (VK_IMAGE_LAYOUT_UNDEFINED == layout)
                                                ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                                                : VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkPipelineStageFlags dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkAccessFlags srcAccessMask =
            (VK_IMAGE_LAYOUT_UNDEFINED == layout) ? 0 : VK_ACCESS_MEMORY_READ_BIT;
    VkAccessFlags dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkImageMemoryBarrier imageMemoryBarrier = {
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,     // sType
            NULL,                                       // pNext
            srcAccessMask,                              // outputMask
            dstAccessMask,                              // inputMask
            layout,                                     // oldLayout
            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,   // newLayout
            mPresentQueueIndex,                         // srcQueueFamilyIndex
            mGraphicsQueueIndex,       // dstQueueFamilyIndex
            surface->mImages[backbuffer->mImageIndex],  // image
            {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}     // subresourceRange
    };
    mResetCommandBuffer(backbuffer->mTransitionCmdBuffers[0], 0);

    VkCommandBufferBeginInfo info;
    memset(&info, 0, sizeof(VkCommandBufferBeginInfo));
    info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    info.flags = 0;
    mBeginCommandBuffer(backbuffer->mTransitionCmdBuffers[0], &info);

    mCmdPipelineBarrier(backbuffer->mTransitionCmdBuffers[0], srcStageMask, dstStageMask, 0, 0,
                        nullptr, 0, nullptr, 1, &imageMemoryBarrier);

    mEndCommandBuffer(backbuffer->mTransitionCmdBuffers[0]);

    VkPipelineStageFlags waitDstStageFlags = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    // insert the layout transfer into the queue and wait on the acquire
    VkSubmitInfo submitInfo;
    memset(&submitInfo, 0, sizeof(VkSubmitInfo));
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.waitSemaphoreCount = 1;
    // Wait to make sure aquire semaphore set above has signaled.
    submitInfo.pWaitSemaphores = &backbuffer->mAcquireSemaphore;
    submitInfo.pWaitDstStageMask = &waitDstStageFlags;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &backbuffer->mTransitionCmdBuffers[0];
    submitInfo.signalSemaphoreCount = 0;

    // Attach first fence to submission here so we can track when the command buffer finishes.
    mQueueSubmit(mGraphicsQueue, 1, &submitInfo, backbuffer->mUsageFences[0]);

    // We need to notify Skia that we changed the layout of the wrapped VkImage
    sk_sp<SkSurface> skSurface = surface->mImageInfos[backbuffer->mImageIndex].mSurface;
    GrBackendRenderTarget backendRT = skSurface->getBackendRenderTarget(
            SkSurface::kFlushRead_BackendHandleAccess);
    if (!backendRT.isValid()) {
        SkASSERT(backendRT.isValid());
        return nullptr;
    }
    backendRT.setVkImageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

    surface->mBackbuffer = std::move(skSurface);
    return surface->mBackbuffer.get();
}

void VulkanManager::destroyBuffers(VulkanSurface* surface) {
    if (surface->mBackbuffers) {
        for (uint32_t i = 0; i < surface->mImageCount + 1; ++i) {
            mWaitForFences(mDevice, 2, surface->mBackbuffers[i].mUsageFences, true, UINT64_MAX);
            surface->mBackbuffers[i].mImageIndex = -1;
            mDestroySemaphore(mDevice, surface->mBackbuffers[i].mAcquireSemaphore, nullptr);
            mDestroySemaphore(mDevice, surface->mBackbuffers[i].mRenderSemaphore, nullptr);
            mFreeCommandBuffers(mDevice, mCommandPool, 2,
                    surface->mBackbuffers[i].mTransitionCmdBuffers);
            mDestroyFence(mDevice, surface->mBackbuffers[i].mUsageFences[0], 0);
            mDestroyFence(mDevice, surface->mBackbuffers[i].mUsageFences[1], 0);
        }
    }

    delete[] surface->mBackbuffers;
    surface->mBackbuffers = nullptr;
    delete[] surface->mImageInfos;
    surface->mImageInfos = nullptr;
    delete[] surface->mImages;
    surface->mImages = nullptr;
}

void VulkanManager::destroySurface(VulkanSurface* surface) {
    // Make sure all submit commands have finished before starting to destroy objects.
    if (VK_NULL_HANDLE != mPresentQueue) {
        mQueueWaitIdle(mPresentQueue);
    }
    mDeviceWaitIdle(mDevice);

    destroyBuffers(surface);

    if (VK_NULL_HANDLE != surface->mSwapchain) {
        mDestroySwapchainKHR(mDevice, surface->mSwapchain, nullptr);
        surface->mSwapchain = VK_NULL_HANDLE;
    }

    if (VK_NULL_HANDLE != surface->mVkSurface) {
        mDestroySurfaceKHR(mInstance, surface->mVkSurface, nullptr);
        surface->mVkSurface = VK_NULL_HANDLE;
    }
    delete surface;
}

void VulkanManager::createBuffers(VulkanSurface* surface, VkFormat format, VkExtent2D extent) {
    mGetSwapchainImagesKHR(mDevice, surface->mSwapchain, &surface->mImageCount, nullptr);
    SkASSERT(surface->mImageCount);
    surface->mImages = new VkImage[surface->mImageCount];
    mGetSwapchainImagesKHR(mDevice, surface->mSwapchain, &surface->mImageCount, surface->mImages);

    SkSurfaceProps props(0, kUnknown_SkPixelGeometry);

    // set up initial image layouts and create surfaces
    surface->mImageInfos = new VulkanSurface::ImageInfo[surface->mImageCount];
    for (uint32_t i = 0; i < surface->mImageCount; ++i) {
        GrVkImageInfo info;
        info.fImage = surface->mImages[i];
        info.fAlloc = GrVkAlloc();
        info.fImageLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        info.fImageTiling = VK_IMAGE_TILING_OPTIMAL;
        info.fFormat = format;
        info.fLevelCount = 1;

        GrBackendRenderTarget backendRT(extent.width, extent.height, 0, 0, info);

        VulkanSurface::ImageInfo& imageInfo = surface->mImageInfos[i];
        imageInfo.mSurface = SkSurface::MakeFromBackendRenderTarget(
                mRenderThread.getGrContext(), backendRT, kTopLeft_GrSurfaceOrigin,
                kRGBA_8888_SkColorType, nullptr, &props);
    }

    SkASSERT(mCommandPool != VK_NULL_HANDLE);

    // set up the backbuffers
    VkSemaphoreCreateInfo semaphoreInfo;
    memset(&semaphoreInfo, 0, sizeof(VkSemaphoreCreateInfo));
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semaphoreInfo.pNext = nullptr;
    semaphoreInfo.flags = 0;
    VkCommandBufferAllocateInfo commandBuffersInfo;
    memset(&commandBuffersInfo, 0, sizeof(VkCommandBufferAllocateInfo));
    commandBuffersInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandBuffersInfo.pNext = nullptr;
    commandBuffersInfo.commandPool = mCommandPool;
    commandBuffersInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandBuffersInfo.commandBufferCount = 2;
    VkFenceCreateInfo fenceInfo;
    memset(&fenceInfo, 0, sizeof(VkFenceCreateInfo));
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.pNext = nullptr;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;

    // we create one additional backbuffer structure here, because we want to
    // give the command buffers they contain a chance to finish before we cycle back
    surface->mBackbuffers = new VulkanSurface::BackbufferInfo[surface->mImageCount + 1];
    for (uint32_t i = 0; i < surface->mImageCount + 1; ++i) {
        SkDEBUGCODE(VkResult res);
        surface->mBackbuffers[i].mImageIndex = -1;
        SkDEBUGCODE(res =) mCreateSemaphore(mDevice, &semaphoreInfo, nullptr,
                                            &surface->mBackbuffers[i].mAcquireSemaphore);
        SkDEBUGCODE(res =) mCreateSemaphore(mDevice, &semaphoreInfo, nullptr,
                                            &surface->mBackbuffers[i].mRenderSemaphore);
        SkDEBUGCODE(res =) mAllocateCommandBuffers(mDevice, &commandBuffersInfo,
                                                   surface->mBackbuffers[i].mTransitionCmdBuffers);
        SkDEBUGCODE(res =) mCreateFence(mDevice, &fenceInfo, nullptr,
                                        &surface->mBackbuffers[i].mUsageFences[0]);
        SkDEBUGCODE(res =) mCreateFence(mDevice, &fenceInfo, nullptr,
                                        &surface->mBackbuffers[i].mUsageFences[1]);
        SkASSERT(VK_SUCCESS == res);
    }
    surface->mCurrentBackbufferIndex = surface->mImageCount;
}

bool VulkanManager::createSwapchain(VulkanSurface* surface) {
    // check for capabilities
    VkSurfaceCapabilitiesKHR caps;
    VkResult res = mGetPhysicalDeviceSurfaceCapabilitiesKHR(mPhysicalDevice,
                                                            surface->mVkSurface, &caps);
    if (VK_SUCCESS != res) {
        return false;
    }

    uint32_t surfaceFormatCount;
    res = mGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, surface->mVkSurface,
                                              &surfaceFormatCount, nullptr);
    if (VK_SUCCESS != res) {
        return false;
    }

    FatVector<VkSurfaceFormatKHR, 4> surfaceFormats(surfaceFormatCount);
    res = mGetPhysicalDeviceSurfaceFormatsKHR(mPhysicalDevice, surface->mVkSurface,
                                              &surfaceFormatCount, surfaceFormats.data());
    if (VK_SUCCESS != res) {
        return false;
    }

    uint32_t presentModeCount;
    res = mGetPhysicalDeviceSurfacePresentModesKHR(mPhysicalDevice,
                                                   surface->mVkSurface, &presentModeCount, nullptr);
    if (VK_SUCCESS != res) {
        return false;
    }

    FatVector<VkPresentModeKHR, VK_PRESENT_MODE_RANGE_SIZE_KHR> presentModes(presentModeCount);
    res = mGetPhysicalDeviceSurfacePresentModesKHR(mPhysicalDevice,
                                                   surface->mVkSurface, &presentModeCount,
                                                   presentModes.data());
    if (VK_SUCCESS != res) {
        return false;
    }

    VkExtent2D extent = caps.currentExtent;
    // clamp width; to handle currentExtent of -1 and  protect us from broken hints
    if (extent.width < caps.minImageExtent.width) {
        extent.width = caps.minImageExtent.width;
    }
    SkASSERT(extent.width <= caps.maxImageExtent.width);
    // clamp height
    if (extent.height < caps.minImageExtent.height) {
        extent.height = caps.minImageExtent.height;
    }
    SkASSERT(extent.height <= caps.maxImageExtent.height);

    uint32_t imageCount = caps.minImageCount + 2;
    if (caps.maxImageCount > 0 && imageCount > caps.maxImageCount) {
        // Application must settle for fewer images than desired:
        imageCount = caps.maxImageCount;
    }

    // Currently Skia requires the images to be color attchments and support all transfer
    // operations.
    VkImageUsageFlags usageFlags = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT |
                                   VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                   VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    SkASSERT((caps.supportedUsageFlags & usageFlags) == usageFlags);
    SkASSERT(caps.supportedTransforms & caps.currentTransform);
    SkASSERT(caps.supportedCompositeAlpha &
             (VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR | VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR));
    VkCompositeAlphaFlagBitsKHR composite_alpha =
            (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)
                    ? VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
                    : VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;

    // Pick our surface format. For now, just make sure it matches our sRGB request:
    VkFormat surfaceFormat = VK_FORMAT_UNDEFINED;
    VkColorSpaceKHR colorSpace = VK_COLORSPACE_SRGB_NONLINEAR_KHR;

    bool wantSRGB = false;
#ifdef ANDROID_ENABLE_LINEAR_BLENDING
    wantSRGB = true;
#endif
    for (uint32_t i = 0; i < surfaceFormatCount; ++i) {
        // We are assuming we can get either R8G8B8A8_UNORM or R8G8B8A8_SRGB
        VkFormat desiredFormat = wantSRGB ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_R8G8B8A8_UNORM;
        if (desiredFormat == surfaceFormats[i].format) {
            surfaceFormat = surfaceFormats[i].format;
            colorSpace = surfaceFormats[i].colorSpace;
        }
    }

    if (VK_FORMAT_UNDEFINED == surfaceFormat) {
        return false;
    }

    // If mailbox mode is available, use it, as it is the lowest-latency non-
    // tearing mode. If not, fall back to FIFO which is always available.
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;
    for (uint32_t i = 0; i < presentModeCount; ++i) {
        // use mailbox
        if (VK_PRESENT_MODE_MAILBOX_KHR == presentModes[i]) {
            mode = presentModes[i];
            break;
        }
    }

    VkSwapchainCreateInfoKHR swapchainCreateInfo;
    memset(&swapchainCreateInfo, 0, sizeof(VkSwapchainCreateInfoKHR));
    swapchainCreateInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swapchainCreateInfo.surface = surface->mVkSurface;
    swapchainCreateInfo.minImageCount = imageCount;
    swapchainCreateInfo.imageFormat = surfaceFormat;
    swapchainCreateInfo.imageColorSpace = colorSpace;
    swapchainCreateInfo.imageExtent = extent;
    swapchainCreateInfo.imageArrayLayers = 1;
    swapchainCreateInfo.imageUsage = usageFlags;

    uint32_t queueFamilies[] = {mGraphicsQueueIndex, mPresentQueueIndex};
    if (mGraphicsQueueIndex != mPresentQueueIndex) {
        swapchainCreateInfo.imageSharingMode = VK_SHARING_MODE_CONCURRENT;
        swapchainCreateInfo.queueFamilyIndexCount = 2;
        swapchainCreateInfo.pQueueFamilyIndices = queueFamilies;
    } else {
        swapchainCreateInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        swapchainCreateInfo.queueFamilyIndexCount = 0;
        swapchainCreateInfo.pQueueFamilyIndices = nullptr;
    }

    swapchainCreateInfo.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    swapchainCreateInfo.compositeAlpha = composite_alpha;
    swapchainCreateInfo.presentMode = mode;
    swapchainCreateInfo.clipped = true;
    swapchainCreateInfo.oldSwapchain = surface->mSwapchain;

    res = mCreateSwapchainKHR(mDevice, &swapchainCreateInfo, nullptr, &surface->mSwapchain);
    if (VK_SUCCESS != res) {
        return false;
    }

    // destroy the old swapchain
    if (swapchainCreateInfo.oldSwapchain != VK_NULL_HANDLE) {
        mDeviceWaitIdle(mDevice);

        destroyBuffers(surface);

        mDestroySwapchainKHR(mDevice, swapchainCreateInfo.oldSwapchain, nullptr);
    }

    createBuffers(surface, surfaceFormat, extent);

    return true;
}

VulkanSurface* VulkanManager::createSurface(ANativeWindow* window) {
    initialize();

    if (!window) {
        return nullptr;
    }

    VulkanSurface* surface = new VulkanSurface();

    VkAndroidSurfaceCreateInfoKHR surfaceCreateInfo;
    memset(&surfaceCreateInfo, 0, sizeof(VkAndroidSurfaceCreateInfoKHR));
    surfaceCreateInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceCreateInfo.pNext = nullptr;
    surfaceCreateInfo.flags = 0;
    surfaceCreateInfo.window = window;

    VkResult res = mCreateAndroidSurfaceKHR(mInstance, &surfaceCreateInfo, nullptr,
            &surface->mVkSurface);
    if (VK_SUCCESS != res) {
        delete surface;
        return nullptr;
    }

    SkDEBUGCODE(VkBool32 supported; res = mGetPhysicalDeviceSurfaceSupportKHR(
            mPhysicalDevice, mPresentQueueIndex, surface->mVkSurface, &supported);
    // All physical devices and queue families on Android must be capable of
    // presentation with any native window.
    SkASSERT(VK_SUCCESS == res && supported););

    if (!createSwapchain(surface)) {
        destroySurface(surface);
        return nullptr;
    }

    return surface;
}

// Helper to know which src stage flags we need to set when transitioning to the present layout
static VkPipelineStageFlags layoutToPipelineStageFlags(const VkImageLayout layout) {
    if (VK_IMAGE_LAYOUT_GENERAL == layout) {
        return VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    } else if (VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL == layout ||
               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL == layout) {
        return VK_PIPELINE_STAGE_TRANSFER_BIT;
    } else if (VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL == layout ||
               VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL == layout ||
               VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL == layout ||
               VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL == layout) {
        return VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT;
    } else if (VK_IMAGE_LAYOUT_PREINITIALIZED == layout) {
        return VK_PIPELINE_STAGE_HOST_BIT;
    }

    SkASSERT(VK_IMAGE_LAYOUT_UNDEFINED == layout);
    return VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
}

// Helper to know which src access mask we need to set when transitioning to the present layout
static VkAccessFlags layoutToSrcAccessMask(const VkImageLayout layout) {
    VkAccessFlags flags = 0;
    if (VK_IMAGE_LAYOUT_GENERAL == layout) {
        flags = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT |
                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT |
                VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_HOST_WRITE_BIT |
                VK_ACCESS_HOST_READ_BIT;
    } else if (VK_IMAGE_LAYOUT_PREINITIALIZED == layout) {
        flags = VK_ACCESS_HOST_WRITE_BIT;
    } else if (VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL == layout) {
        flags = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    } else if (VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL == layout) {
        flags = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    } else if (VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL == layout) {
        flags = VK_ACCESS_TRANSFER_WRITE_BIT;
    } else if (VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL == layout) {
        flags = VK_ACCESS_TRANSFER_READ_BIT;
    } else if (VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL == layout) {
        flags = VK_ACCESS_SHADER_READ_BIT;
    }
    return flags;
}

void VulkanManager::swapBuffers(VulkanSurface* surface) {
    if (CC_UNLIKELY(Properties::waitForGpuCompletion)) {
        ATRACE_NAME("Finishing GPU work");
        mDeviceWaitIdle(mDevice);
    }

    SkASSERT(surface->mBackbuffers);
    VulkanSurface::BackbufferInfo* backbuffer =
            surface->mBackbuffers + surface->mCurrentBackbufferIndex;

    SkSurface* skSurface = surface->mImageInfos[backbuffer->mImageIndex].mSurface.get();
    GrBackendRenderTarget backendRT = skSurface->getBackendRenderTarget(
            SkSurface::kFlushRead_BackendHandleAccess);
    SkASSERT(backendRT.isValid());

    GrVkImageInfo imageInfo;
    SkAssertResult(backendRT.getVkImageInfo(&imageInfo));

    // Check to make sure we never change the actually wrapped image
    SkASSERT(imageInfo.fImage == surface->mImages[backbuffer->mImageIndex]);

    // We need to transition the image to VK_IMAGE_LAYOUT_PRESENT_SRC_KHR and make sure that all
    // previous work is complete for before presenting. So we first add the necessary barrier here.
    VkImageLayout layout = imageInfo.fImageLayout;
    VkPipelineStageFlags srcStageMask = layoutToPipelineStageFlags(layout);
    VkPipelineStageFlags dstStageMask = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    VkAccessFlags srcAccessMask = layoutToSrcAccessMask(layout);
    VkAccessFlags dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;

    VkImageMemoryBarrier imageMemoryBarrier = {
            VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,     // sType
            NULL,                                       // pNext
            srcAccessMask,                              // outputMask
            dstAccessMask,                              // inputMask
            layout,                                     // oldLayout
            VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,            // newLayout
            mGraphicsQueueIndex,                        // srcQueueFamilyIndex
            mPresentQueueIndex,                         // dstQueueFamilyIndex
            surface->mImages[backbuffer->mImageIndex],  // image
            {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1}     // subresourceRange
    };

    mResetCommandBuffer(backbuffer->mTransitionCmdBuffers[1], 0);
    VkCommandBufferBeginInfo info;
    memset(&info, 0, sizeof(VkCommandBufferBeginInfo));
    info.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    info.flags = 0;
    mBeginCommandBuffer(backbuffer->mTransitionCmdBuffers[1], &info);
    mCmdPipelineBarrier(backbuffer->mTransitionCmdBuffers[1], srcStageMask, dstStageMask, 0, 0,
                        nullptr, 0, nullptr, 1, &imageMemoryBarrier);
    mEndCommandBuffer(backbuffer->mTransitionCmdBuffers[1]);

    surface->mImageInfos[backbuffer->mImageIndex].mImageLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    // insert the layout transfer into the queue and wait on the acquire
    VkSubmitInfo submitInfo;
    memset(&submitInfo, 0, sizeof(VkSubmitInfo));
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.waitSemaphoreCount = 0;
    submitInfo.pWaitDstStageMask = 0;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &backbuffer->mTransitionCmdBuffers[1];
    submitInfo.signalSemaphoreCount = 1;
    // When this command buffer finishes we will signal this semaphore so that we know it is now
    // safe to present the image to the screen.
    submitInfo.pSignalSemaphores = &backbuffer->mRenderSemaphore;

    // Attach second fence to submission here so we can track when the command buffer finishes.
    mQueueSubmit(mGraphicsQueue, 1, &submitInfo, backbuffer->mUsageFences[1]);

    // Submit present operation to present queue. We use a semaphore here to make sure all rendering
    // to the image is complete and that the layout has been change to present on the graphics
    // queue.
    const VkPresentInfoKHR presentInfo = {
            VK_STRUCTURE_TYPE_PRESENT_INFO_KHR,  // sType
            NULL,                                // pNext
            1,                                   // waitSemaphoreCount
            &backbuffer->mRenderSemaphore,       // pWaitSemaphores
            1,                                   // swapchainCount
            &surface->mSwapchain,                // pSwapchains
            &backbuffer->mImageIndex,            // pImageIndices
            NULL                                 // pResults
    };

    mQueuePresentKHR(mPresentQueue, &presentInfo);

    surface->mBackbuffer.reset();
    surface->mImageInfos[backbuffer->mImageIndex].mLastUsed = surface->mCurrentTime;
    surface->mImageInfos[backbuffer->mImageIndex].mInvalid = false;
    surface->mCurrentTime++;
}

int VulkanManager::getAge(VulkanSurface* surface) {
    SkASSERT(surface->mBackbuffers);
    VulkanSurface::BackbufferInfo* backbuffer =
            surface->mBackbuffers + surface->mCurrentBackbufferIndex;
    if (mSwapBehavior == SwapBehavior::Discard ||
        surface->mImageInfos[backbuffer->mImageIndex].mInvalid) {
        return 0;
    }
    uint16_t lastUsed = surface->mImageInfos[backbuffer->mImageIndex].mLastUsed;
    return surface->mCurrentTime - lastUsed;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
