/*
 * Copyright (C) 2024 The Android Open Source Project
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

#define LOG_TAG "system_health"

#include <aidl/android/hardware/power/CpuHeadroomParams.h>
#include <aidl/android/hardware/power/GpuHeadroomParams.h>
#include <aidl/android/os/CpuHeadroomParamsInternal.h>
#include <aidl/android/os/GpuHeadroomParamsInternal.h>
#include <aidl/android/os/IHintManager.h>
#include <android/binder_manager.h>
#include <android/system_health.h>
#include <binder/IServiceManager.h>
#include <binder/Status.h>
#include <system_health_private.h>

#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <utility>

#include "android-base/thread_annotations.h"
#include "utils/SystemClock.h"

using namespace android;
using namespace aidl::android::os;
namespace hal = aidl::android::hardware::power;

struct ACpuHeadroomParams : public CpuHeadroomParamsInternal {};
struct AGpuHeadroomParams : public GpuHeadroomParamsInternal {};

struct ASystemHealthManager {
public:
    static ASystemHealthManager* getInstance();

    ASystemHealthManager(std::shared_ptr<IHintManager>& hintManager,
                         IHintManager::HintManagerClientData&& clientData);
    ASystemHealthManager() = delete;
    ~ASystemHealthManager();
    int getCpuHeadroom(const ACpuHeadroomParams* params, float* outHeadroom);
    int getGpuHeadroom(const AGpuHeadroomParams* params, float* outHeadroom);
    int getCpuHeadroomMinIntervalMillis(int64_t* outMinIntervalMillis);
    int getGpuHeadroomMinIntervalMillis(int64_t* outMinIntervalMillis);
    int getMaxCpuHeadroomTidsSize(size_t* outSize);
    int getCpuHeadroomCalculationWindowRange(int32_t* _Nonnull outMinMillis,
                                             int32_t* _Nonnull outMaxMillis);
    int getGpuHeadroomCalculationWindowRange(int32_t* _Nonnull outMinMillis,
                                             int32_t* _Nonnull outMaxMillis);

private:
    static ASystemHealthManager* create(std::shared_ptr<IHintManager> hintManager);
    std::shared_ptr<IHintManager> mHintManager;
    IHintManager::HintManagerClientData mClientData;
};

static std::shared_ptr<IHintManager>* gIHintManagerForTesting = nullptr;
static std::shared_ptr<ASystemHealthManager> gSystemHealthManagerForTesting = nullptr;

ASystemHealthManager* ASystemHealthManager::getInstance() {
    static std::once_flag creationFlag;
    static ASystemHealthManager* instance = nullptr;
    if (gSystemHealthManagerForTesting) {
        return gSystemHealthManagerForTesting.get();
    }
    if (gIHintManagerForTesting) {
        gSystemHealthManagerForTesting =
                std::shared_ptr<ASystemHealthManager>(create(*gIHintManagerForTesting));
        return gSystemHealthManagerForTesting.get();
    }
    std::call_once(creationFlag, []() { instance = create(nullptr); });
    return instance;
}

ASystemHealthManager::ASystemHealthManager(std::shared_ptr<IHintManager>& hintManager,
                                           IHintManager::HintManagerClientData&& clientData)
      : mHintManager(std::move(hintManager)), mClientData(clientData) {}

ASystemHealthManager::~ASystemHealthManager() = default;

ASystemHealthManager* ASystemHealthManager::create(std::shared_ptr<IHintManager> hintManager) {
    if (!hintManager) {
        hintManager = IHintManager::fromBinder(
                ndk::SpAIBinder(AServiceManager_waitForService("performance_hint")));
    }
    if (hintManager == nullptr) {
        ALOGE("%s: PerformanceHint service is not ready ", __FUNCTION__);
        return nullptr;
    }
    IHintManager::HintManagerClientData clientData;
    ndk::ScopedAStatus ret = hintManager->getClientData(&clientData);
    if (!ret.isOk()) {
        ALOGE("%s: PerformanceHint service is not initialized %s", __FUNCTION__, ret.getMessage());
        return nullptr;
    }
    return new ASystemHealthManager(hintManager, std::move(clientData));
}

int ASystemHealthManager::getCpuHeadroom(const ACpuHeadroomParams* params, float* outHeadroom) {
    if (!mClientData.supportInfo.headroom.isCpuSupported) return ENOTSUP;
    std::optional<hal::CpuHeadroomResult> res;
    ::ndk::ScopedAStatus ret;
    CpuHeadroomParamsInternal internalParams;
    if (!params) {
        ret = mHintManager->getCpuHeadroom(internalParams, &res);
    } else {
        LOG_ALWAYS_FATAL_IF((int)params->tids.size() > mClientData.maxCpuHeadroomThreads,
                            "%s: tids size should not exceed %d", __FUNCTION__,
                            mClientData.maxCpuHeadroomThreads);
        LOG_ALWAYS_FATAL_IF(params->calculationWindowMillis <
                                            mClientData.supportInfo.headroom
                                                    .cpuMinCalculationWindowMillis ||
                                    params->calculationWindowMillis >
                                            mClientData.supportInfo.headroom
                                                    .cpuMaxCalculationWindowMillis,
                            "%s: calculationWindowMillis should be in range [%d, %d] but got %d",
                            __FUNCTION__,
                            mClientData.supportInfo.headroom.cpuMinCalculationWindowMillis,
                            mClientData.supportInfo.headroom.cpuMaxCalculationWindowMillis,
                            params->calculationWindowMillis);
        ret = mHintManager->getCpuHeadroom(*params, &res);
    }
    if (!ret.isOk()) {
        LOG_ALWAYS_FATAL_IF(ret.getExceptionCode() == EX_ILLEGAL_ARGUMENT,
                            "Invalid ACpuHeadroomParams: %s", ret.getMessage());
        ALOGE("ASystemHealth_getCpuHeadroom fails: %s", ret.getMessage());
        if (ret.getExceptionCode() == EX_UNSUPPORTED_OPERATION) {
            return ENOTSUP;
        } else if (ret.getExceptionCode() == EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }
    *outHeadroom = res ? res->get<hal::CpuHeadroomResult::Tag::globalHeadroom>()
                       : std::numeric_limits<float>::quiet_NaN();
    return OK;
}

int ASystemHealthManager::getGpuHeadroom(const AGpuHeadroomParams* params, float* outHeadroom) {
    if (!mClientData.supportInfo.headroom.isGpuSupported) return ENOTSUP;
    std::optional<hal::GpuHeadroomResult> res;
    ::ndk::ScopedAStatus ret;
    GpuHeadroomParamsInternal internalParams;
    if (!params) {
        ret = mHintManager->getGpuHeadroom(internalParams, &res);
    } else {
        LOG_ALWAYS_FATAL_IF(params->calculationWindowMillis <
                                            mClientData.supportInfo.headroom
                                                    .gpuMinCalculationWindowMillis ||
                                    params->calculationWindowMillis >
                                            mClientData.supportInfo.headroom
                                                    .gpuMaxCalculationWindowMillis,
                            "%s: calculationWindowMillis should be in range [%d, %d] but got %d",
                            __FUNCTION__,
                            mClientData.supportInfo.headroom.gpuMinCalculationWindowMillis,
                            mClientData.supportInfo.headroom.gpuMaxCalculationWindowMillis,
                            params->calculationWindowMillis);
        ret = mHintManager->getGpuHeadroom(*params, &res);
    }
    if (!ret.isOk()) {
        LOG_ALWAYS_FATAL_IF(ret.getExceptionCode() == EX_ILLEGAL_ARGUMENT,
                            "Invalid AGpuHeadroomParams: %s", ret.getMessage());
        ALOGE("ASystemHealth_getGpuHeadroom fails: %s", ret.getMessage());
        if (ret.getExceptionCode() == EX_UNSUPPORTED_OPERATION) {
            return ENOTSUP;
        }
        return EPIPE;
    }
    *outHeadroom = res ? res->get<hal::GpuHeadroomResult::Tag::globalHeadroom>()
                       : std::numeric_limits<float>::quiet_NaN();
    return OK;
}

int ASystemHealthManager::getCpuHeadroomMinIntervalMillis(int64_t* outMinIntervalMillis) {
    if (!mClientData.supportInfo.headroom.isCpuSupported) return ENOTSUP;
    *outMinIntervalMillis = mClientData.supportInfo.headroom.cpuMinIntervalMillis;
    return OK;
}

int ASystemHealthManager::getGpuHeadroomMinIntervalMillis(int64_t* outMinIntervalMillis) {
    if (!mClientData.supportInfo.headroom.isGpuSupported) return ENOTSUP;
    *outMinIntervalMillis = mClientData.supportInfo.headroom.gpuMinIntervalMillis;
    return OK;
}

int ASystemHealthManager::getMaxCpuHeadroomTidsSize(size_t* outSize) {
    if (!mClientData.supportInfo.headroom.isGpuSupported) return ENOTSUP;
    *outSize = mClientData.maxCpuHeadroomThreads;
    return OK;
}

int ASystemHealthManager::getCpuHeadroomCalculationWindowRange(int32_t* _Nonnull outMinMillis,
                                                               int32_t* _Nonnull outMaxMillis) {
    if (!mClientData.supportInfo.headroom.isCpuSupported) return ENOTSUP;
    *outMinMillis = mClientData.supportInfo.headroom.cpuMinCalculationWindowMillis;
    *outMaxMillis = mClientData.supportInfo.headroom.cpuMaxCalculationWindowMillis;
    return OK;
}

int ASystemHealthManager::getGpuHeadroomCalculationWindowRange(int32_t* _Nonnull outMinMillis,
                                                               int32_t* _Nonnull outMaxMillis) {
    if (!mClientData.supportInfo.headroom.isGpuSupported) return ENOTSUP;
    *outMinMillis = mClientData.supportInfo.headroom.gpuMinCalculationWindowMillis;
    *outMaxMillis = mClientData.supportInfo.headroom.gpuMaxCalculationWindowMillis;
    return OK;
}

int ASystemHealth_getMaxCpuHeadroomTidsSize(size_t* _Nonnull outSize) {
    LOG_ALWAYS_FATAL_IF(outSize == nullptr, "%s: outSize should not be null", __FUNCTION__);
    auto manager = ASystemHealthManager::getInstance();
    if (manager == nullptr) return ENOTSUP;
    return manager->getMaxCpuHeadroomTidsSize(outSize);
}

int ASystemHealth_getCpuHeadroomCalculationWindowRange(int32_t* _Nonnull outMinMillis,
                                                       int32_t* _Nonnull outMaxMillis) {
    LOG_ALWAYS_FATAL_IF(outMinMillis == nullptr, "%s: outMinMillis should not be null",
                        __FUNCTION__);
    LOG_ALWAYS_FATAL_IF(outMaxMillis == nullptr, "%s: outMaxMillis should not be null",
                        __FUNCTION__);
    auto manager = ASystemHealthManager::getInstance();
    if (manager == nullptr) return ENOTSUP;
    return manager->getCpuHeadroomCalculationWindowRange(outMinMillis, outMaxMillis);
}

int ASystemHealth_getGpuHeadroomCalculationWindowRange(int32_t* _Nonnull outMinMillis,
                                                       int32_t* _Nonnull outMaxMillis) {
    LOG_ALWAYS_FATAL_IF(outMinMillis == nullptr, "%s: outMinMillis should not be null",
                        __FUNCTION__);
    LOG_ALWAYS_FATAL_IF(outMaxMillis == nullptr, "%s: outMaxMillis should not be null",
                        __FUNCTION__);
    auto manager = ASystemHealthManager::getInstance();
    if (manager == nullptr) return ENOTSUP;
    return manager->getGpuHeadroomCalculationWindowRange(outMinMillis, outMaxMillis);
}

int ASystemHealth_getCpuHeadroom(const ACpuHeadroomParams* _Nullable params,
                                 float* _Nonnull outHeadroom) {
    LOG_ALWAYS_FATAL_IF(outHeadroom == nullptr, "%s: outHeadroom should not be null", __FUNCTION__);
    auto manager = ASystemHealthManager::getInstance();
    if (manager == nullptr) return ENOTSUP;
    return manager->getCpuHeadroom(params, outHeadroom);
}

int ASystemHealth_getGpuHeadroom(const AGpuHeadroomParams* _Nullable params,
                                 float* _Nonnull outHeadroom) {
    LOG_ALWAYS_FATAL_IF(outHeadroom == nullptr, "%s: outHeadroom should not be null", __FUNCTION__);
    auto manager = ASystemHealthManager::getInstance();
    if (manager == nullptr) return ENOTSUP;
    return manager->getGpuHeadroom(params, outHeadroom);
}

int ASystemHealth_getCpuHeadroomMinIntervalMillis(int64_t* _Nonnull outMinIntervalMillis) {
    LOG_ALWAYS_FATAL_IF(outMinIntervalMillis == nullptr,
                        "%s: outMinIntervalMillis should not be null", __FUNCTION__);
    auto manager = ASystemHealthManager::getInstance();
    if (manager == nullptr) return ENOTSUP;
    return manager->getCpuHeadroomMinIntervalMillis(outMinIntervalMillis);
}

int ASystemHealth_getGpuHeadroomMinIntervalMillis(int64_t* _Nonnull outMinIntervalMillis) {
    LOG_ALWAYS_FATAL_IF(outMinIntervalMillis == nullptr,
                        "%s: outMinIntervalMillis should not be null", __FUNCTION__);
    auto manager = ASystemHealthManager::getInstance();
    if (manager == nullptr) return ENOTSUP;
    return manager->getGpuHeadroomMinIntervalMillis(outMinIntervalMillis);
}

void ACpuHeadroomParams_setCalculationWindowMillis(ACpuHeadroomParams* _Nonnull params,
                                                   int windowMillis) {
    LOG_ALWAYS_FATAL_IF(windowMillis <= 0, "%s: windowMillis should be positive but got %d",
                        __FUNCTION__, windowMillis);
    params->calculationWindowMillis = windowMillis;
}

void AGpuHeadroomParams_setCalculationWindowMillis(AGpuHeadroomParams* _Nonnull params,
                                                   int windowMillis) {
    LOG_ALWAYS_FATAL_IF(windowMillis <= 0, "%s: windowMillis should be positive but got %d",
                        __FUNCTION__, windowMillis);
    params->calculationWindowMillis = windowMillis;
}

int ACpuHeadroomParams_getCalculationWindowMillis(ACpuHeadroomParams* _Nonnull params) {
    return params->calculationWindowMillis;
}

int AGpuHeadroomParams_getCalculationWindowMillis(AGpuHeadroomParams* _Nonnull params) {
    return params->calculationWindowMillis;
}

void ACpuHeadroomParams_setTids(ACpuHeadroomParams* _Nonnull params, const int* _Nonnull tids,
                                size_t tidsSize) {
    LOG_ALWAYS_FATAL_IF(tids == nullptr, "%s: tids should not be null", __FUNCTION__);
    params->tids.resize(tidsSize);
    for (int i = 0; i < (int)tidsSize; ++i) {
        LOG_ALWAYS_FATAL_IF(tids[i] <= 0, "ACpuHeadroomParams_setTids: Invalid non-positive tid %d",
                            tids[i]);
        params->tids[i] = tids[i];
    }
}

void ACpuHeadroomParams_setCalculationType(ACpuHeadroomParams* _Nonnull params,
                                           ACpuHeadroomCalculationType calculationType) {
    LOG_ALWAYS_FATAL_IF(calculationType < ACpuHeadroomCalculationType::
                                                  ACPU_HEADROOM_CALCULATION_TYPE_MIN ||
                                calculationType > ACpuHeadroomCalculationType::
                                                          ACPU_HEADROOM_CALCULATION_TYPE_AVERAGE,
                        "%s: calculationType should be one of ACpuHeadroomCalculationType values "
                        "but got %d",
                        __FUNCTION__, calculationType);
    params->calculationType = static_cast<hal::CpuHeadroomParams::CalculationType>(calculationType);
}

ACpuHeadroomCalculationType ACpuHeadroomParams_getCalculationType(
        ACpuHeadroomParams* _Nonnull params) {
    return static_cast<ACpuHeadroomCalculationType>(params->calculationType);
}

void AGpuHeadroomParams_setCalculationType(AGpuHeadroomParams* _Nonnull params,
                                           AGpuHeadroomCalculationType calculationType) {
    LOG_ALWAYS_FATAL_IF(calculationType < AGpuHeadroomCalculationType::
                                                  AGPU_HEADROOM_CALCULATION_TYPE_MIN ||
                                calculationType > AGpuHeadroomCalculationType::
                                                          AGPU_HEADROOM_CALCULATION_TYPE_AVERAGE,
                        "%s: calculationType should be one of AGpuHeadroomCalculationType values "
                        "but got %d",
                        __FUNCTION__, calculationType);
    params->calculationType = static_cast<hal::GpuHeadroomParams::CalculationType>(calculationType);
}

AGpuHeadroomCalculationType AGpuHeadroomParams_getCalculationType(
        AGpuHeadroomParams* _Nonnull params) {
    return static_cast<AGpuHeadroomCalculationType>(params->calculationType);
}

ACpuHeadroomParams* _Nonnull ACpuHeadroomParams_create() {
    return new ACpuHeadroomParams();
}

AGpuHeadroomParams* _Nonnull AGpuHeadroomParams_create() {
    return new AGpuHeadroomParams();
}

void ACpuHeadroomParams_destroy(ACpuHeadroomParams* _Nullable params) {
    delete params;
}

void AGpuHeadroomParams_destroy(AGpuHeadroomParams* _Nullable params) {
    delete params;
}

void ASystemHealth_setIHintManagerForTesting(void* iManager) {
    if (iManager == nullptr) {
        gSystemHealthManagerForTesting = nullptr;
    }
    gIHintManagerForTesting = static_cast<std::shared_ptr<IHintManager>*>(iManager);
}
