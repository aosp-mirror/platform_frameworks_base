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

#include <gui/Surface.h>

#include "Properties.h"
#include "RenderThread.h"
#include "renderstate/RenderState.h"
#include "utils/FatVector.h"

#include <GrBackendSurface.h>
#include <GrContext.h>
#include <GrTypes.h>
#include <GrTypes.h>
#include <vk/GrVkExtensions.h>
#include <vk/GrVkTypes.h>

namespace android {
namespace uirenderer {
namespace renderthread {

static void free_features_extensions_structs(const VkPhysicalDeviceFeatures2& features) {
    // All Vulkan structs that could be part of the features chain will start with the
    // structure type followed by the pNext pointer. We cast to the CommonVulkanHeader
    // so we can get access to the pNext for the next struct.
    struct CommonVulkanHeader {
        VkStructureType sType;
        void*           pNext;
    };

    void* pNext = features.pNext;
    while (pNext) {
        void* current = pNext;
        pNext = static_cast<CommonVulkanHeader*>(current)->pNext;
        free(current);
    }
}

#define GET_PROC(F) m##F = (PFN_vk##F)vkGetInstanceProcAddr(VK_NULL_HANDLE, "vk" #F)
#define GET_INST_PROC(F) m##F = (PFN_vk##F)vkGetInstanceProcAddr(mInstance, "vk" #F)
#define GET_DEV_PROC(F) m##F = (PFN_vk##F)vkGetDeviceProcAddr(mDevice, "vk" #F)

void VulkanManager::destroy() {
    // We don't need to explicitly free the command buffer since it automatically gets freed when we
    // delete the VkCommandPool below.
    mDummyCB = VK_NULL_HANDLE;

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
    mInstanceExtensions.clear();
    mDeviceExtensions.clear();
    free_features_extensions_structs(mPhysicalDeviceFeatures2);
    mPhysicalDeviceFeatures2 = {};
}

void VulkanManager::setupDevice(GrVkExtensions& grExtensions, VkPhysicalDeviceFeatures2& features) {
    VkResult err;

    constexpr VkApplicationInfo app_info = {
        VK_STRUCTURE_TYPE_APPLICATION_INFO, // sType
        nullptr,                            // pNext
        "android framework",                // pApplicationName
        0,                                  // applicationVersion
        "android framework",                // pEngineName
        0,                                  // engineVerison
        mAPIVersion,                        // apiVersion
    };

    {
        GET_PROC(EnumerateInstanceExtensionProperties);

        uint32_t extensionCount = 0;
        err = mEnumerateInstanceExtensionProperties(nullptr, &extensionCount, nullptr);
        LOG_ALWAYS_FATAL_IF(VK_SUCCESS != err);
        std::unique_ptr<VkExtensionProperties[]> extensions(
                new VkExtensionProperties[extensionCount]);
        err = mEnumerateInstanceExtensionProperties(nullptr, &extensionCount, extensions.get());
        LOG_ALWAYS_FATAL_IF(VK_SUCCESS != err);
        bool hasKHRSurfaceExtension = false;
        bool hasKHRAndroidSurfaceExtension = false;
        for (uint32_t i = 0; i < extensionCount; ++i) {
            mInstanceExtensions.push_back(extensions[i].extensionName);
            if (!strcmp(extensions[i].extensionName, VK_KHR_SURFACE_EXTENSION_NAME)) {
                hasKHRSurfaceExtension = true;
            }
            if (!strcmp(extensions[i].extensionName,VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
                hasKHRAndroidSurfaceExtension = true;
            }
        }
        LOG_ALWAYS_FATAL_IF(!hasKHRSurfaceExtension || !hasKHRAndroidSurfaceExtension);
    }

    const VkInstanceCreateInfo instance_create = {
        VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,    // sType
        nullptr,                                   // pNext
        0,                                         // flags
        &app_info,                                 // pApplicationInfo
        0,                                         // enabledLayerNameCount
        nullptr,                                   // ppEnabledLayerNames
        (uint32_t) mInstanceExtensions.size(),     // enabledExtensionNameCount
        mInstanceExtensions.data(),                // ppEnabledExtensionNames
    };

    GET_PROC(CreateInstance);
    err = mCreateInstance(&instance_create, nullptr, &mInstance);
    LOG_ALWAYS_FATAL_IF(err < 0);

    GET_INST_PROC(DestroyInstance);
    GET_INST_PROC(EnumeratePhysicalDevices);
    GET_INST_PROC(GetPhysicalDeviceProperties);
    GET_INST_PROC(GetPhysicalDeviceQueueFamilyProperties);
    GET_INST_PROC(GetPhysicalDeviceFeatures2);
    GET_INST_PROC(CreateDevice);
    GET_INST_PROC(EnumerateDeviceExtensionProperties);
    GET_INST_PROC(CreateAndroidSurfaceKHR);
    GET_INST_PROC(DestroySurfaceKHR);
    GET_INST_PROC(GetPhysicalDeviceSurfaceSupportKHR);
    GET_INST_PROC(GetPhysicalDeviceSurfaceCapabilitiesKHR);
    GET_INST_PROC(GetPhysicalDeviceSurfaceFormatsKHR);
    GET_INST_PROC(GetPhysicalDeviceSurfacePresentModesKHR);

    uint32_t gpuCount;
    LOG_ALWAYS_FATAL_IF(mEnumeratePhysicalDevices(mInstance, &gpuCount, nullptr));
    LOG_ALWAYS_FATAL_IF(!gpuCount);
    // Just returning the first physical device instead of getting the whole array. Since there
    // should only be one device on android.
    gpuCount = 1;
    err = mEnumeratePhysicalDevices(mInstance, &gpuCount, &mPhysicalDevice);
    // VK_INCOMPLETE is returned when the count we provide is less than the total device count.
    LOG_ALWAYS_FATAL_IF(err && VK_INCOMPLETE != err);

    VkPhysicalDeviceProperties physDeviceProperties;
    mGetPhysicalDeviceProperties(mPhysicalDevice, &physDeviceProperties);
    LOG_ALWAYS_FATAL_IF(physDeviceProperties.apiVersion < VK_MAKE_VERSION(1, 1, 0));

    // query to get the initial queue props size
    uint32_t queueCount;
    mGetPhysicalDeviceQueueFamilyProperties(mPhysicalDevice, &queueCount, nullptr);
    LOG_ALWAYS_FATAL_IF(!queueCount);

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
    LOG_ALWAYS_FATAL_IF(mGraphicsQueueIndex == queueCount);

    // All physical devices and queue families on Android must be capable of
    // presentation with any native window. So just use the first one.
    mPresentQueueIndex = 0;

    {
        uint32_t extensionCount = 0;
        err = mEnumerateDeviceExtensionProperties(mPhysicalDevice, nullptr, &extensionCount,
                nullptr);
        LOG_ALWAYS_FATAL_IF(VK_SUCCESS != err);
        std::unique_ptr<VkExtensionProperties[]> extensions(
                new VkExtensionProperties[extensionCount]);
        err = mEnumerateDeviceExtensionProperties(mPhysicalDevice, nullptr, &extensionCount,
                extensions.get());
        LOG_ALWAYS_FATAL_IF(VK_SUCCESS != err);
        bool hasKHRSwapchainExtension = false;
        for (uint32_t i = 0; i < extensionCount; ++i) {
            mDeviceExtensions.push_back(extensions[i].extensionName);
            if (!strcmp(extensions[i].extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                hasKHRSwapchainExtension = true;
            }
        }
        LOG_ALWAYS_FATAL_IF(!hasKHRSwapchainExtension);
    }

    auto getProc = [] (const char* proc_name, VkInstance instance, VkDevice device) {
        if (device != VK_NULL_HANDLE) {
            return vkGetDeviceProcAddr(device, proc_name);
        }
        return vkGetInstanceProcAddr(instance, proc_name);
    };
    grExtensions.init(getProc, mInstance, mPhysicalDevice, mInstanceExtensions.size(),
            mInstanceExtensions.data(), mDeviceExtensions.size(), mDeviceExtensions.data());

    LOG_ALWAYS_FATAL_IF(!grExtensions.hasExtension(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME, 1));

    memset(&features, 0, sizeof(VkPhysicalDeviceFeatures2));
    features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features.pNext = nullptr;

    // Setup all extension feature structs we may want to use.
    void** tailPNext = &features.pNext;

    if (grExtensions.hasExtension(VK_EXT_BLEND_OPERATION_ADVANCED_EXTENSION_NAME, 2)) {
        VkPhysicalDeviceBlendOperationAdvancedFeaturesEXT* blend;
        blend = (VkPhysicalDeviceBlendOperationAdvancedFeaturesEXT*) malloc(
                sizeof(VkPhysicalDeviceBlendOperationAdvancedFeaturesEXT));
        LOG_ALWAYS_FATAL_IF(!blend);
        blend->sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BLEND_OPERATION_ADVANCED_FEATURES_EXT;
        blend->pNext = nullptr;
        *tailPNext = blend;
        tailPNext = &blend->pNext;
    }

    VkPhysicalDeviceSamplerYcbcrConversionFeatures* ycbcrFeature;
    ycbcrFeature = (VkPhysicalDeviceSamplerYcbcrConversionFeatures*) malloc(
            sizeof(VkPhysicalDeviceSamplerYcbcrConversionFeatures));
    LOG_ALWAYS_FATAL_IF(!ycbcrFeature);
    ycbcrFeature->sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
    ycbcrFeature->pNext = nullptr;
    *tailPNext = ycbcrFeature;
    tailPNext = &ycbcrFeature->pNext;

    // query to get the physical device features
    mGetPhysicalDeviceFeatures2(mPhysicalDevice, &features);
    // this looks like it would slow things down,
    // and we can't depend on it on all platforms
    features.features.robustBufferAccess = VK_FALSE;

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
        &features,                               // pNext
        0,                                       // VkDeviceCreateFlags
        queueInfoCount,                          // queueCreateInfoCount
        queueInfo,                               // pQueueCreateInfos
        0,                                       // layerCount
        nullptr,                                 // ppEnabledLayerNames
        (uint32_t) mDeviceExtensions.size(),     // extensionCount
        mDeviceExtensions.data(),                // ppEnabledExtensionNames
        nullptr,                                 // ppEnabledFeatures
    };

    LOG_ALWAYS_FATAL_IF(mCreateDevice(mPhysicalDevice, &deviceInfo, nullptr, &mDevice));

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
    GET_DEV_PROC(ImportSemaphoreFdKHR);
    GET_DEV_PROC(GetSemaphoreFdKHR);
    GET_DEV_PROC(CreateFence);
    GET_DEV_PROC(DestroyFence);
    GET_DEV_PROC(WaitForFences);
    GET_DEV_PROC(ResetFences);
}

void VulkanManager::initialize() {
    if (mDevice != VK_NULL_HANDLE) {
        return;
    }

    GET_PROC(EnumerateInstanceVersion);
    uint32_t instanceVersion;
    LOG_ALWAYS_FATAL_IF(mEnumerateInstanceVersion(&instanceVersion));
    LOG_ALWAYS_FATAL_IF(instanceVersion < VK_MAKE_VERSION(1, 1, 0));

    this->setupDevice(mExtensions, mPhysicalDeviceFeatures2);

    mGetDeviceQueue(mDevice, mGraphicsQueueIndex, 0, &mGraphicsQueue);

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
    LOG_ALWAYS_FATAL_IF(mCommandPool == VK_NULL_HANDLE);

    if (!setupDummyCommandBuffer()) {
        this->destroy();
        // Pass through will crash on next line.
    }
    LOG_ALWAYS_FATAL_IF(mDummyCB == VK_NULL_HANDLE);

    mGetDeviceQueue(mDevice, mPresentQueueIndex, 0, &mPresentQueue);

    if (Properties::enablePartialUpdates && Properties::useBufferAge) {
        mSwapBehavior = SwapBehavior::BufferAge;
    }
}

sk_sp<GrContext> VulkanManager::createContext(const GrContextOptions& options) {
    auto getProc = [] (const char* proc_name, VkInstance instance, VkDevice device) {
        if (device != VK_NULL_HANDLE) {
            return vkGetDeviceProcAddr(device, proc_name);
        }
        return vkGetInstanceProcAddr(instance, proc_name);
    };

    GrVkBackendContext backendContext;
    backendContext.fInstance = mInstance;
    backendContext.fPhysicalDevice = mPhysicalDevice;
    backendContext.fDevice = mDevice;
    backendContext.fQueue = mGraphicsQueue;
    backendContext.fGraphicsQueueIndex = mGraphicsQueueIndex;
    backendContext.fMaxAPIVersion = mAPIVersion;
    backendContext.fVkExtensions = &mExtensions;
    backendContext.fDeviceFeatures2 = &mPhysicalDeviceFeatures2;
    backendContext.fGetProc = std::move(getProc);

    return GrContext::MakeVulkan(backendContext, options);
}

VkFunctorInitParams VulkanManager::getVkFunctorInitParams() const {
    return VkFunctorInitParams{
            .instance = mInstance,
            .physical_device = mPhysicalDevice,
            .device = mDevice,
            .queue = mGraphicsQueue,
            .graphics_queue_index = mGraphicsQueueIndex,
            .api_version = mAPIVersion,
            .enabled_instance_extension_names = mInstanceExtensions.data(),
            .enabled_instance_extension_names_length =
                    static_cast<uint32_t>(mInstanceExtensions.size()),
            .enabled_device_extension_names = mDeviceExtensions.data(),
            .enabled_device_extension_names_length =
                    static_cast<uint32_t>(mDeviceExtensions.size()),
            .device_features_2 = &mPhysicalDeviceFeatures2,
    };
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

static SkMatrix getPreTransformMatrix(int width, int height,
                                      VkSurfaceTransformFlagBitsKHR transform) {
    switch (transform) {
        case VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR:
            return SkMatrix::I();
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR:
            return SkMatrix::MakeAll(0, -1, height, 1, 0, 0, 0, 0, 1);
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR:
            return SkMatrix::MakeAll(-1, 0, width, 0, -1, height, 0, 0, 1);
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR:
            return SkMatrix::MakeAll(0, 1, 0, -1, 0, width, 0, 0, 1);
        case VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_BIT_KHR:
            return SkMatrix::MakeAll(-1, 0, width, 0, 1, 0, 0, 0, 1);
        case VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_90_BIT_KHR:
            return SkMatrix::MakeAll(0, -1, height, -1, 0, width, 0, 0, 1);
        case VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_180_BIT_KHR:
            return SkMatrix::MakeAll(1, 0, 0, 0, -1, height, 0, 0, 1);
        case VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_270_BIT_KHR:
            return SkMatrix::MakeAll(0, 1, 0, 1, 0, 0, 0, 0, 1);
        default:
            LOG_ALWAYS_FATAL("Unsupported pre transform of swapchain.");
    }
    return SkMatrix::I();
}


SkSurface* VulkanManager::getBackbufferSurface(VulkanSurface** surfaceOut) {
    // Recreate VulkanSurface, if ANativeWindow has been resized.
    VulkanSurface* surface = *surfaceOut;
    int windowWidth = 0, windowHeight = 0;
    ANativeWindow* window = surface->mNativeWindow;
    window->query(window, NATIVE_WINDOW_WIDTH, &windowWidth);
    window->query(window, NATIVE_WINDOW_HEIGHT, &windowHeight);
    if (windowWidth != surface->mWindowWidth || windowHeight != surface->mWindowHeight) {
        ColorMode colorMode = surface->mColorMode;
        sk_sp<SkColorSpace> colorSpace = surface->mColorSpace;
        SkColorType colorType = surface->mColorType;
        GrContext* grContext = surface->mGrContext;
        destroySurface(surface);
        *surfaceOut = createSurface(window, colorMode, colorSpace, colorType, grContext);
        surface = *surfaceOut;
        if (!surface) {
            return nullptr;
        }
    }

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
    if (VK_ERROR_OUT_OF_DATE_KHR == res || VK_SUBOPTIMAL_KHR == res) {
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
    VkPipelineStageFlags srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkPipelineStageFlags dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkAccessFlags srcAccessMask = 0;
    VkAccessFlags dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT |
                                  VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

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

    surface->mPreTransform = getPreTransformMatrix(surface->windowWidth(),
                                                   surface->windowHeight(),
                                                   surface->mTransform);

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
                surface->mGrContext, backendRT, kTopLeft_GrSurfaceOrigin,
                surface->mColorType, surface->mColorSpace, &props);
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

    if (!SkToBool(caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)) {
        return false;
    }
    VkSurfaceTransformFlagBitsKHR transform;
    if (SkToBool(caps.supportedTransforms & caps.currentTransform) &&
        !SkToBool(caps.currentTransform & VK_SURFACE_TRANSFORM_INHERIT_BIT_KHR)) {
        transform = caps.currentTransform;
    } else {
        transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
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

    VkExtent2D swapExtent = extent;
    if (transform == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR ||
        transform == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR ||
        transform == VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_90_BIT_KHR ||
        transform == VK_SURFACE_TRANSFORM_HORIZONTAL_MIRROR_ROTATE_270_BIT_KHR) {
        swapExtent.width = extent.height;
        swapExtent.height = extent.width;
    }

    surface->mWindowWidth = extent.width;
    surface->mWindowHeight = extent.height;

    uint32_t imageCount = std::max<uint32_t>(3, caps.minImageCount);
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

    SkASSERT(caps.supportedCompositeAlpha &
             (VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR | VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR));
    VkCompositeAlphaFlagBitsKHR composite_alpha =
            (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR)
                    ? VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
                    : VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;

    VkFormat surfaceFormat = VK_FORMAT_R8G8B8A8_UNORM;
    VkColorSpaceKHR colorSpace = VK_COLORSPACE_SRGB_NONLINEAR_KHR;
    if (surface->mColorType == SkColorType::kRGBA_F16_SkColorType) {
        surfaceFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
    }

    if (surface->mColorMode == ColorMode::WideColorGamut) {
        skcms_Matrix3x3 surfaceGamut;
        LOG_ALWAYS_FATAL_IF(!surface->mColorSpace->toXYZD50(&surfaceGamut),
                            "Could not get gamut matrix from color space");
        if (memcmp(&surfaceGamut, &SkNamedGamut::kSRGB, sizeof(surfaceGamut)) == 0) {
            colorSpace = VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT;
        } else if (memcmp(&surfaceGamut, &SkNamedGamut::kDCIP3, sizeof(surfaceGamut)) == 0) {
            colorSpace = VK_COLOR_SPACE_DISPLAY_P3_NONLINEAR_EXT;
        } else {
            LOG_ALWAYS_FATAL("Unreachable: unsupported wide color space.");
        }
    }

    bool foundSurfaceFormat = false;
    for (uint32_t i = 0; i < surfaceFormatCount; ++i) {
        if (surfaceFormat == surfaceFormats[i].format
                && colorSpace == surfaceFormats[i].colorSpace) {
            foundSurfaceFormat = true;
            break;
        }
    }

    if (!foundSurfaceFormat) {
        return false;
    }

    // FIFO is always available and will match what we do on GL so just pick that here.
    VkPresentModeKHR mode = VK_PRESENT_MODE_FIFO_KHR;

    VkSwapchainCreateInfoKHR swapchainCreateInfo;
    memset(&swapchainCreateInfo, 0, sizeof(VkSwapchainCreateInfoKHR));
    swapchainCreateInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swapchainCreateInfo.surface = surface->mVkSurface;
    swapchainCreateInfo.minImageCount = imageCount;
    swapchainCreateInfo.imageFormat = surfaceFormat;
    swapchainCreateInfo.imageColorSpace = colorSpace;
    swapchainCreateInfo.imageExtent = swapExtent;
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

    swapchainCreateInfo.preTransform = transform;
    swapchainCreateInfo.compositeAlpha = composite_alpha;
    swapchainCreateInfo.presentMode = mode;
    swapchainCreateInfo.clipped = true;
    swapchainCreateInfo.oldSwapchain = surface->mSwapchain;

    res = mCreateSwapchainKHR(mDevice, &swapchainCreateInfo, nullptr, &surface->mSwapchain);
    if (VK_SUCCESS != res) {
        return false;
    }

    surface->mTransform = transform;

    // destroy the old swapchain
    if (swapchainCreateInfo.oldSwapchain != VK_NULL_HANDLE) {
        mDeviceWaitIdle(mDevice);

        destroyBuffers(surface);

        mDestroySwapchainKHR(mDevice, swapchainCreateInfo.oldSwapchain, nullptr);
    }

    createBuffers(surface, surfaceFormat, swapExtent);

    // The window content is not updated (frozen) until a buffer of the window size is received.
    // This prevents temporary stretching of the window after it is resized, but before the first
    // buffer with new size is enqueued.
    native_window_set_scaling_mode(surface->mNativeWindow, NATIVE_WINDOW_SCALING_MODE_FREEZE);

    return true;
}

VulkanSurface* VulkanManager::createSurface(ANativeWindow* window, ColorMode colorMode,
                                            sk_sp<SkColorSpace> surfaceColorSpace,
                                            SkColorType surfaceColorType,
                                            GrContext* grContext) {
    LOG_ALWAYS_FATAL_IF(!hasVkContext(), "Not initialized");
    if (!window) {
        return nullptr;
    }

    VulkanSurface* surface = new VulkanSurface(colorMode, window, surfaceColorSpace,
                                               surfaceColorType, grContext);

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
static VkPipelineStageFlags layoutToPipelineSrcStageFlags(const VkImageLayout layout) {
    if (VK_IMAGE_LAYOUT_GENERAL == layout) {
        return VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
    } else if (VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL == layout ||
               VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL == layout) {
        return VK_PIPELINE_STAGE_TRANSFER_BIT;
    } else if (VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL == layout) {
        return VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    } else if (VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL == layout ||
               VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL == layout) {
        return VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    } else if (VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL == layout) {
        return VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
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
    VkPipelineStageFlags srcStageMask = layoutToPipelineSrcStageFlags(layout);
    VkPipelineStageFlags dstStageMask = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    VkAccessFlags srcAccessMask = layoutToSrcAccessMask(layout);
    VkAccessFlags dstAccessMask = 0;

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

bool VulkanManager::setupDummyCommandBuffer() {
    if (mDummyCB != VK_NULL_HANDLE) {
        return true;
    }

    VkCommandBufferAllocateInfo commandBuffersInfo;
    memset(&commandBuffersInfo, 0, sizeof(VkCommandBufferAllocateInfo));
    commandBuffersInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandBuffersInfo.pNext = nullptr;
    commandBuffersInfo.commandPool = mCommandPool;
    commandBuffersInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandBuffersInfo.commandBufferCount = 1;

    VkResult err = mAllocateCommandBuffers(mDevice, &commandBuffersInfo, &mDummyCB);
    if (err != VK_SUCCESS) {
        // It is probably unnecessary to set this back to VK_NULL_HANDLE, but we set it anyways to
        // make sure the driver didn't set a value and then return a failure.
        mDummyCB = VK_NULL_HANDLE;
        return false;
    }

    VkCommandBufferBeginInfo beginInfo;
    memset(&beginInfo, 0, sizeof(VkCommandBufferBeginInfo));
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;

    mBeginCommandBuffer(mDummyCB, &beginInfo);
    mEndCommandBuffer(mDummyCB);
    return true;
}

status_t VulkanManager::fenceWait(sp<Fence>& fence) {
    if (!hasVkContext()) {
        ALOGE("VulkanManager::fenceWait: VkDevice not initialized");
        return INVALID_OPERATION;
    }

    // Block GPU on the fence.
    int fenceFd = fence->dup();
    if (fenceFd == -1) {
        ALOGE("VulkanManager::fenceWait: error dup'ing fence fd: %d", errno);
        return -errno;
    }

    VkSemaphoreCreateInfo semaphoreInfo;
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semaphoreInfo.pNext = nullptr;
    semaphoreInfo.flags = 0;
    VkSemaphore semaphore;
    VkResult err = mCreateSemaphore(mDevice, &semaphoreInfo, nullptr, &semaphore);
    if (VK_SUCCESS != err) {
        ALOGE("Failed to create import semaphore, err: %d", err);
        return UNKNOWN_ERROR;
    }
    VkImportSemaphoreFdInfoKHR importInfo;
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
    importInfo.pNext = nullptr;
    importInfo.semaphore = semaphore;
    importInfo.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
    importInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
    importInfo.fd = fenceFd;

    err = mImportSemaphoreFdKHR(mDevice, &importInfo);
    if (VK_SUCCESS != err) {
        ALOGE("Failed to import semaphore, err: %d", err);
        return UNKNOWN_ERROR;
    }

    LOG_ALWAYS_FATAL_IF(mDummyCB == VK_NULL_HANDLE);

    VkPipelineStageFlags waitDstStageFlags = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;

    VkSubmitInfo submitInfo;
    memset(&submitInfo, 0, sizeof(VkSubmitInfo));
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.waitSemaphoreCount = 1;
    // Wait to make sure aquire semaphore set above has signaled.
    submitInfo.pWaitSemaphores = &semaphore;
    submitInfo.pWaitDstStageMask = &waitDstStageFlags;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &mDummyCB;
    submitInfo.signalSemaphoreCount = 0;

    mQueueSubmit(mGraphicsQueue, 1, &submitInfo, VK_NULL_HANDLE);

    // On Android when we import a semaphore, it is imported using temporary permanence. That
    // means as soon as we queue the semaphore for a wait it reverts to its previous permanent
    // state before importing. This means it will now be in an idle state with no pending
    // signal or wait operations, so it is safe to immediately delete it.
    mDestroySemaphore(mDevice, semaphore, nullptr);
    return OK;
}

status_t VulkanManager::createReleaseFence(sp<Fence>& nativeFence) {
    if (!hasVkContext()) {
        ALOGE("VulkanManager::createReleaseFence: VkDevice not initialized");
        return INVALID_OPERATION;
    }

    VkExportSemaphoreCreateInfo exportInfo;
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO;
    exportInfo.pNext = nullptr;
    exportInfo.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;

    VkSemaphoreCreateInfo semaphoreInfo;
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    semaphoreInfo.pNext = &exportInfo;
    semaphoreInfo.flags = 0;
    VkSemaphore semaphore;
    VkResult err = mCreateSemaphore(mDevice, &semaphoreInfo, nullptr, &semaphore);
    if (VK_SUCCESS != err) {
        ALOGE("VulkanManager::createReleaseFence: Failed to create semaphore");
        return INVALID_OPERATION;
    }

    LOG_ALWAYS_FATAL_IF(mDummyCB == VK_NULL_HANDLE);

    VkSubmitInfo submitInfo;
    memset(&submitInfo, 0, sizeof(VkSubmitInfo));
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.waitSemaphoreCount = 0;
    submitInfo.pWaitSemaphores = nullptr;
    submitInfo.pWaitDstStageMask = nullptr;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &mDummyCB;
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = &semaphore;

    mQueueSubmit(mGraphicsQueue, 1, &submitInfo, VK_NULL_HANDLE);

    VkSemaphoreGetFdInfoKHR getFdInfo;
    getFdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
    getFdInfo.pNext = nullptr;
    getFdInfo.semaphore = semaphore;
    getFdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;

    int fenceFd = 0;

    err = mGetSemaphoreFdKHR(mDevice, &getFdInfo, &fenceFd);
    if (VK_SUCCESS != err) {
        ALOGE("VulkanManager::createReleaseFence: Failed to get semaphore Fd");
        return INVALID_OPERATION;
    }
    nativeFence = new Fence(fenceFd);

    // Exporting a semaphore with copy transference via vkGetSemahporeFdKHR, has the same effect of
    // destroying the semaphore and creating a new one with the same handle, and the payloads
    // ownership is move to the Fd we created. Thus the semahpore is in a state that we can delete
    // it and we don't need to wait on the command buffer we submitted to finish.
    mDestroySemaphore(mDevice, semaphore, nullptr);

    return OK;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
