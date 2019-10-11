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

#include <android/sync.h>
#include <gui/Surface.h>

#include "Properties.h"
#include "RenderThread.h"
#include "renderstate/RenderState.h"
#include "utils/FatVector.h"
#include "utils/TraceUtils.h"

#include <GrBackendSemaphore.h>
#include <GrBackendSurface.h>
#include <GrContext.h>
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
        void* pNext;
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
    mInstanceExtensionsOwner.clear();
    mInstanceExtensions.clear();
    mDeviceExtensionsOwner.clear();
    mDeviceExtensions.clear();
    free_features_extensions_structs(mPhysicalDeviceFeatures2);
    mPhysicalDeviceFeatures2 = {};
}

void VulkanManager::setupDevice(GrVkExtensions& grExtensions, VkPhysicalDeviceFeatures2& features) {
    VkResult err;

    constexpr VkApplicationInfo app_info = {
            VK_STRUCTURE_TYPE_APPLICATION_INFO,  // sType
            nullptr,                             // pNext
            "android framework",                 // pApplicationName
            0,                                   // applicationVersion
            "android framework",                 // pEngineName
            0,                                   // engineVerison
            mAPIVersion,                         // apiVersion
    };

    {
        GET_PROC(EnumerateInstanceExtensionProperties);

        uint32_t extensionCount = 0;
        err = mEnumerateInstanceExtensionProperties(nullptr, &extensionCount, nullptr);
        LOG_ALWAYS_FATAL_IF(VK_SUCCESS != err);
        mInstanceExtensionsOwner.resize(extensionCount);
        err = mEnumerateInstanceExtensionProperties(nullptr, &extensionCount,
                                                    mInstanceExtensionsOwner.data());
        LOG_ALWAYS_FATAL_IF(VK_SUCCESS != err);
        bool hasKHRSurfaceExtension = false;
        bool hasKHRAndroidSurfaceExtension = false;
        for (const VkExtensionProperties& extension : mInstanceExtensionsOwner) {
            mInstanceExtensions.push_back(extension.extensionName);
            if (!strcmp(extension.extensionName, VK_KHR_SURFACE_EXTENSION_NAME)) {
                hasKHRSurfaceExtension = true;
            }
            if (!strcmp(extension.extensionName, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
                hasKHRAndroidSurfaceExtension = true;
            }
        }
        LOG_ALWAYS_FATAL_IF(!hasKHRSurfaceExtension || !hasKHRAndroidSurfaceExtension);
    }

    const VkInstanceCreateInfo instance_create = {
            VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,  // sType
            nullptr,                                 // pNext
            0,                                       // flags
            &app_info,                               // pApplicationInfo
            0,                                       // enabledLayerNameCount
            nullptr,                                 // ppEnabledLayerNames
            (uint32_t)mInstanceExtensions.size(),    // enabledExtensionNameCount
            mInstanceExtensions.data(),              // ppEnabledExtensionNames
    };

    GET_PROC(CreateInstance);
    err = mCreateInstance(&instance_create, nullptr, &mInstance);
    LOG_ALWAYS_FATAL_IF(err < 0);

    GET_INST_PROC(DestroyInstance);
    GET_INST_PROC(EnumeratePhysicalDevices);
    GET_INST_PROC(GetPhysicalDeviceProperties);
    GET_INST_PROC(GetPhysicalDeviceQueueFamilyProperties);
    GET_INST_PROC(GetPhysicalDeviceFeatures2);
    GET_INST_PROC(GetPhysicalDeviceImageFormatProperties2);
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
    mDriverVersion = physDeviceProperties.driverVersion;

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
        mDeviceExtensionsOwner.resize(extensionCount);
        err = mEnumerateDeviceExtensionProperties(mPhysicalDevice, nullptr, &extensionCount,
                                                  mDeviceExtensionsOwner.data());
        LOG_ALWAYS_FATAL_IF(VK_SUCCESS != err);
        bool hasKHRSwapchainExtension = false;
        for (const VkExtensionProperties& extension : mDeviceExtensionsOwner) {
            mDeviceExtensions.push_back(extension.extensionName);
            if (!strcmp(extension.extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                hasKHRSwapchainExtension = true;
            }
        }
        LOG_ALWAYS_FATAL_IF(!hasKHRSwapchainExtension);
    }

    auto getProc = [](const char* proc_name, VkInstance instance, VkDevice device) {
        if (device != VK_NULL_HANDLE) {
            return vkGetDeviceProcAddr(device, proc_name);
        }
        return vkGetInstanceProcAddr(instance, proc_name);
    };

    grExtensions.init(getProc, mInstance, mPhysicalDevice, mInstanceExtensions.size(),
                      mInstanceExtensions.data(), mDeviceExtensions.size(),
                      mDeviceExtensions.data());

    LOG_ALWAYS_FATAL_IF(!grExtensions.hasExtension(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME, 1));

    memset(&features, 0, sizeof(VkPhysicalDeviceFeatures2));
    features.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    features.pNext = nullptr;

    // Setup all extension feature structs we may want to use.
    void** tailPNext = &features.pNext;

    if (grExtensions.hasExtension(VK_EXT_BLEND_OPERATION_ADVANCED_EXTENSION_NAME, 2)) {
        VkPhysicalDeviceBlendOperationAdvancedFeaturesEXT* blend;
        blend = (VkPhysicalDeviceBlendOperationAdvancedFeaturesEXT*)malloc(
                sizeof(VkPhysicalDeviceBlendOperationAdvancedFeaturesEXT));
        LOG_ALWAYS_FATAL_IF(!blend);
        blend->sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BLEND_OPERATION_ADVANCED_FEATURES_EXT;
        blend->pNext = nullptr;
        *tailPNext = blend;
        tailPNext = &blend->pNext;
    }

    VkPhysicalDeviceSamplerYcbcrConversionFeatures* ycbcrFeature;
    ycbcrFeature = (VkPhysicalDeviceSamplerYcbcrConversionFeatures*)malloc(
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

    float queuePriorities[1] = {0.0};

    void* queueNextPtr = nullptr;

    VkDeviceQueueGlobalPriorityCreateInfoEXT queuePriorityCreateInfo;

    if (Properties::contextPriority != 0 &&
        grExtensions.hasExtension(VK_EXT_GLOBAL_PRIORITY_EXTENSION_NAME, 2)) {
        memset(&queuePriorityCreateInfo, 0, sizeof(VkDeviceQueueGlobalPriorityCreateInfoEXT));
        queuePriorityCreateInfo.sType =
                VK_STRUCTURE_TYPE_DEVICE_QUEUE_GLOBAL_PRIORITY_CREATE_INFO_EXT;
        queuePriorityCreateInfo.pNext = nullptr;
        switch (Properties::contextPriority) {
            case EGL_CONTEXT_PRIORITY_LOW_IMG:
                queuePriorityCreateInfo.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_LOW_EXT;
                break;
            case EGL_CONTEXT_PRIORITY_MEDIUM_IMG:
                queuePriorityCreateInfo.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_MEDIUM_EXT;
                break;
            case EGL_CONTEXT_PRIORITY_HIGH_IMG:
                queuePriorityCreateInfo.globalPriority = VK_QUEUE_GLOBAL_PRIORITY_HIGH_EXT;
                break;
            default:
                LOG_ALWAYS_FATAL("Unsupported context priority");
        }
        queueNextPtr = &queuePriorityCreateInfo;
    }

    const VkDeviceQueueCreateInfo queueInfo[2] = {
            {
                    VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,  // sType
                    queueNextPtr,                                // pNext
                    0,                                           // VkDeviceQueueCreateFlags
                    mGraphicsQueueIndex,                         // queueFamilyIndex
                    1,                                           // queueCount
                    queuePriorities,                             // pQueuePriorities
            },
            {
                    VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,  // sType
                    queueNextPtr,                                // pNext
                    0,                                           // VkDeviceQueueCreateFlags
                    mPresentQueueIndex,                          // queueFamilyIndex
                    1,                                           // queueCount
                    queuePriorities,                             // pQueuePriorities
            }};
    uint32_t queueInfoCount = (mPresentQueueIndex != mGraphicsQueueIndex) ? 2 : 1;

    const VkDeviceCreateInfo deviceInfo = {
            VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,  // sType
            &features,                             // pNext
            0,                                     // VkDeviceCreateFlags
            queueInfoCount,                        // queueCreateInfoCount
            queueInfo,                             // pQueueCreateInfos
            0,                                     // layerCount
            nullptr,                               // ppEnabledLayerNames
            (uint32_t)mDeviceExtensions.size(),    // extensionCount
            mDeviceExtensions.data(),              // ppEnabledExtensionNames
            nullptr,                               // ppEnabledFeatures
    };

    LOG_ALWAYS_FATAL_IF(mCreateDevice(mPhysicalDevice, &deviceInfo, nullptr, &mDevice));

    GET_DEV_PROC(GetDeviceQueue);
    GET_DEV_PROC(DeviceWaitIdle);
    GET_DEV_PROC(DestroyDevice);
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
        SkDEBUGCODE(VkResult res =)
                mCreateCommandPool(mDevice, &commandPoolInfo, nullptr, &mCommandPool);
        SkASSERT(VK_SUCCESS == res);
    }
    LOG_ALWAYS_FATAL_IF(mCommandPool == VK_NULL_HANDLE);

    mGetDeviceQueue(mDevice, mPresentQueueIndex, 0, &mPresentQueue);

    if (Properties::enablePartialUpdates && Properties::useBufferAge) {
        mSwapBehavior = SwapBehavior::BufferAge;
    }
}

sk_sp<GrContext> VulkanManager::createContext(const GrContextOptions& options) {
    auto getProc = [](const char* proc_name, VkInstance instance, VkDevice device) {
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

Frame VulkanManager::dequeueNextBuffer(VulkanSurface* surface) {
    VulkanSurface::NativeBufferInfo* bufferInfo = surface->dequeueNativeBuffer();

    if (bufferInfo == nullptr) {
        ALOGE("VulkanSurface::dequeueNativeBuffer called with an invalid surface!");
        return Frame(-1, -1, 0);
    }

    LOG_ALWAYS_FATAL_IF(!bufferInfo->dequeued);

    if (bufferInfo->dequeue_fence != -1) {
        struct sync_file_info* finfo = sync_file_info(bufferInfo->dequeue_fence);
        bool isSignalPending = false;
        if (finfo != NULL) {
            isSignalPending = finfo->status != 1;
            sync_file_info_free(finfo);
        }
        if (isSignalPending) {
            int fence_clone = dup(bufferInfo->dequeue_fence);
            if (fence_clone == -1) {
                ALOGE("dup(fence) failed, stalling until signalled: %s (%d)", strerror(errno),
                      errno);
                sync_wait(bufferInfo->dequeue_fence, -1 /* forever */);
            } else {
                VkSemaphoreCreateInfo semaphoreInfo;
                semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
                semaphoreInfo.pNext = nullptr;
                semaphoreInfo.flags = 0;
                VkSemaphore semaphore;
                VkResult err = mCreateSemaphore(mDevice, &semaphoreInfo, nullptr, &semaphore);
                LOG_ALWAYS_FATAL_IF(VK_SUCCESS != err, "Failed to create import semaphore, err: %d",
                                    err);

                VkImportSemaphoreFdInfoKHR importInfo;
                importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR;
                importInfo.pNext = nullptr;
                importInfo.semaphore = semaphore;
                importInfo.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
                importInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
                importInfo.fd = fence_clone;

                err = mImportSemaphoreFdKHR(mDevice, &importInfo);
                LOG_ALWAYS_FATAL_IF(VK_SUCCESS != err, "Failed to import semaphore, err: %d", err);

                GrBackendSemaphore backendSemaphore;
                backendSemaphore.initVulkan(semaphore);
                bufferInfo->skSurface->wait(1, &backendSemaphore);
                // The following flush blocks the GPU immediately instead of waiting for other
                // drawing ops. It seems dequeue_fence is not respected otherwise.
                // TODO: remove the flush after finding why backendSemaphore is not working.
                bufferInfo->skSurface->flush();
            }
        }
    }

    int bufferAge = (mSwapBehavior == SwapBehavior::Discard) ? 0 : surface->getCurrentBuffersAge();
    return Frame(surface->logicalWidth(), surface->logicalHeight(), bufferAge);
}

struct DestroySemaphoreInfo {
    PFN_vkDestroySemaphore mDestroyFunction;
    VkDevice mDevice;
    VkSemaphore mSemaphore;

    DestroySemaphoreInfo(PFN_vkDestroySemaphore destroyFunction, VkDevice device,
            VkSemaphore semaphore)
            : mDestroyFunction(destroyFunction), mDevice(device), mSemaphore(semaphore) {}
};

static void destroy_semaphore(void* context) {
    DestroySemaphoreInfo* info = reinterpret_cast<DestroySemaphoreInfo*>(context);
    info->mDestroyFunction(info->mDevice, info->mSemaphore, nullptr);
    delete info;
}

void VulkanManager::swapBuffers(VulkanSurface* surface, const SkRect& dirtyRect) {
    if (CC_UNLIKELY(Properties::waitForGpuCompletion)) {
        ATRACE_NAME("Finishing GPU work");
        mDeviceWaitIdle(mDevice);
    }

    VulkanSurface::NativeBufferInfo* bufferInfo = surface->getCurrentBufferInfo();
    if (!bufferInfo) {
        // If VulkanSurface::dequeueNativeBuffer failed earlier, then swapBuffers is a no-op.
        return;
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
    ALOGE_IF(VK_SUCCESS != err, "VulkanManager::swapBuffers(): Failed to create semaphore");

    GrBackendSemaphore backendSemaphore;
    backendSemaphore.initVulkan(semaphore);

    int fenceFd = -1;
    DestroySemaphoreInfo* destroyInfo = new DestroySemaphoreInfo(mDestroySemaphore, mDevice,
                                                                 semaphore);
    GrSemaphoresSubmitted submitted =
            bufferInfo->skSurface->flush(SkSurface::BackendSurfaceAccess::kPresent,
                                         kNone_GrFlushFlags, 1, &backendSemaphore,
                                         destroy_semaphore, destroyInfo);
    if (submitted == GrSemaphoresSubmitted::kYes) {
        VkSemaphoreGetFdInfoKHR getFdInfo;
        getFdInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_GET_FD_INFO_KHR;
        getFdInfo.pNext = nullptr;
        getFdInfo.semaphore = semaphore;
        getFdInfo.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;

        err = mGetSemaphoreFdKHR(mDevice, &getFdInfo, &fenceFd);
        ALOGE_IF(VK_SUCCESS != err, "VulkanManager::swapBuffers(): Failed to get semaphore Fd");
    } else {
        ALOGE("VulkanManager::swapBuffers(): Semaphore submission failed");
        mQueueWaitIdle(mGraphicsQueue);
    }

    surface->presentCurrentBuffer(dirtyRect, fenceFd);
}

void VulkanManager::destroySurface(VulkanSurface* surface) {
    // Make sure all submit commands have finished before starting to destroy objects.
    if (VK_NULL_HANDLE != mPresentQueue) {
        mQueueWaitIdle(mPresentQueue);
    }
    mDeviceWaitIdle(mDevice);

    delete surface;
}

VulkanSurface* VulkanManager::createSurface(ANativeWindow* window, ColorMode colorMode,
                                            sk_sp<SkColorSpace> surfaceColorSpace,
                                            SkColorType surfaceColorType, GrContext* grContext,
                                            uint32_t extraBuffers) {
    LOG_ALWAYS_FATAL_IF(!hasVkContext(), "Not initialized");
    if (!window) {
        return nullptr;
    }

    return VulkanSurface::Create(window, colorMode, surfaceColorType, surfaceColorSpace, grContext,
                                 *this, extraBuffers);
}

status_t VulkanManager::fenceWait(sp<Fence>& fence, GrContext* grContext) {
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
        mDestroySemaphore(mDevice, semaphore, nullptr);
        ALOGE("Failed to import semaphore, err: %d", err);
        return UNKNOWN_ERROR;
    }

    GrBackendSemaphore beSemaphore;
    beSemaphore.initVulkan(semaphore);

    // Skia takes ownership of the semaphore and will delete it once the wait has finished.
    grContext->wait(1, &beSemaphore);
    grContext->flush();

    return OK;
}

status_t VulkanManager::createReleaseFence(sp<Fence>& nativeFence, GrContext* grContext) {
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

    GrBackendSemaphore backendSemaphore;
    backendSemaphore.initVulkan(semaphore);

    DestroySemaphoreInfo* destroyInfo = new DestroySemaphoreInfo(mDestroySemaphore, mDevice,
                                                                 semaphore);
    GrSemaphoresSubmitted submitted =
            grContext->flush(kNone_GrFlushFlags, 1, &backendSemaphore,
                             destroy_semaphore, destroyInfo);

    if (submitted == GrSemaphoresSubmitted::kNo) {
        ALOGE("VulkanManager::createReleaseFence: Failed to submit semaphore");
        mDestroySemaphore(mDevice, semaphore, nullptr);
        return INVALID_OPERATION;
    }

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

    return OK;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
