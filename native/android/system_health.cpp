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

#include <aidl/android/hardware/power/CpuHeadroomParams.h>
#include <aidl/android/hardware/power/GpuHeadroomParams.h>
#include <aidl/android/os/CpuHeadroomParamsInternal.h>
#include <aidl/android/os/GpuHeadroomParamsInternal.h>
#include <aidl/android/os/IHintManager.h>
#include <android/binder_manager.h>
#include <android/system_health.h>
#include <binder/IServiceManager.h>
#include <binder/Status.h>

using namespace android;
using namespace aidl::android::os;
namespace hal = aidl::android::hardware::power;

struct ACpuHeadroomParams : public CpuHeadroomParamsInternal {};
struct AGpuHeadroomParams : public GpuHeadroomParamsInternal {};

const int CPU_HEADROOM_CALCULATION_WINDOW_MILLIS_MIN = 50;
const int CPU_HEADROOM_CALCULATION_WINDOW_MILLIS_MAX = 10000;
const int GPU_HEADROOM_CALCULATION_WINDOW_MILLIS_MIN = 50;
const int GPU_HEADROOM_CALCULATION_WINDOW_MILLIS_MAX = 10000;
const int CPU_HEADROOM_MAX_TID_COUNT = 5;

struct ASystemHealthManager {
public:
    static ASystemHealthManager* getInstance();
    ASystemHealthManager(std::shared_ptr<IHintManager>& hintManager);
    ASystemHealthManager() = delete;
    ~ASystemHealthManager();
    int getCpuHeadroom(const ACpuHeadroomParams* params, float* outHeadroom);
    int getGpuHeadroom(const AGpuHeadroomParams* params, float* outHeadroom);
    int getCpuHeadroomMinIntervalMillis(int64_t* outMinIntervalMillis);
    int getGpuHeadroomMinIntervalMillis(int64_t* outMinIntervalMillis);

private:
    static ASystemHealthManager* create(std::shared_ptr<IHintManager> hintManager);
    std::shared_ptr<IHintManager> mHintManager;
};

ASystemHealthManager* ASystemHealthManager::getInstance() {
    static std::once_flag creationFlag;
    static ASystemHealthManager* instance = nullptr;
    std::call_once(creationFlag, []() { instance = create(nullptr); });
    return instance;
}

ASystemHealthManager::ASystemHealthManager(std::shared_ptr<IHintManager>& hintManager)
      : mHintManager(std::move(hintManager)) {}

ASystemHealthManager::~ASystemHealthManager() {}

ASystemHealthManager* ASystemHealthManager::create(std::shared_ptr<IHintManager> hintManager) {
    if (!hintManager) {
        hintManager = IHintManager::fromBinder(
                ndk::SpAIBinder(AServiceManager_waitForService("performance_hint")));
    }
    if (hintManager == nullptr) {
        ALOGE("%s: PerformanceHint service is not ready ", __FUNCTION__);
        return nullptr;
    }
    return new ASystemHealthManager(hintManager);
}

ASystemHealthManager* ASystemHealth_acquireManager() {
    return ASystemHealthManager::getInstance();
}

int ASystemHealthManager::getCpuHeadroom(const ACpuHeadroomParams* params, float* outHeadroom) {
    std::optional<hal::CpuHeadroomResult> res;
    ::ndk::ScopedAStatus ret;
    CpuHeadroomParamsInternal internalParams;
    if (!params) {
        ret = mHintManager->getCpuHeadroom(internalParams, &res);
    } else {
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
    *outHeadroom = res->get<hal::CpuHeadroomResult::Tag::globalHeadroom>();
    return OK;
}

int ASystemHealthManager::getGpuHeadroom(const AGpuHeadroomParams* params, float* outHeadroom) {
    std::optional<hal::GpuHeadroomResult> res;
    ::ndk::ScopedAStatus ret;
    GpuHeadroomParamsInternal internalParams;
    if (!params) {
        ret = mHintManager->getGpuHeadroom(internalParams, &res);
    } else {
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
    *outHeadroom = res->get<hal::GpuHeadroomResult::Tag::globalHeadroom>();
    return OK;
}

int ASystemHealthManager::getCpuHeadroomMinIntervalMillis(int64_t* outMinIntervalMillis) {
    int64_t minIntervalMillis = 0;
    ::ndk::ScopedAStatus ret = mHintManager->getCpuHeadroomMinIntervalMillis(&minIntervalMillis);
    if (!ret.isOk()) {
        ALOGE("ASystemHealth_getCpuHeadroomMinIntervalMillis fails: %s", ret.getMessage());
        if (ret.getExceptionCode() == EX_UNSUPPORTED_OPERATION) {
            return ENOTSUP;
        }
        return EPIPE;
    }
    *outMinIntervalMillis = minIntervalMillis;
    return OK;
}

int ASystemHealthManager::getGpuHeadroomMinIntervalMillis(int64_t* outMinIntervalMillis) {
    int64_t minIntervalMillis = 0;
    ::ndk::ScopedAStatus ret = mHintManager->getGpuHeadroomMinIntervalMillis(&minIntervalMillis);
    if (!ret.isOk()) {
        ALOGE("ASystemHealth_getGpuHeadroomMinIntervalMillis fails: %s", ret.getMessage());
        if (ret.getExceptionCode() == EX_UNSUPPORTED_OPERATION) {
            return ENOTSUP;
        }
        return EPIPE;
    }
    *outMinIntervalMillis = minIntervalMillis;
    return OK;
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
    LOG_ALWAYS_FATAL_IF(windowMillis < CPU_HEADROOM_CALCULATION_WINDOW_MILLIS_MIN ||
                                windowMillis > CPU_HEADROOM_CALCULATION_WINDOW_MILLIS_MAX,
                        "%s: windowMillis should be in range [50, 10000] but got %d", __FUNCTION__,
                        windowMillis);
    params->calculationWindowMillis = windowMillis;
}

void AGpuHeadroomParams_setCalculationWindowMillis(AGpuHeadroomParams* _Nonnull params,
                                                   int windowMillis) {
    LOG_ALWAYS_FATAL_IF(windowMillis < GPU_HEADROOM_CALCULATION_WINDOW_MILLIS_MIN ||
                                windowMillis > GPU_HEADROOM_CALCULATION_WINDOW_MILLIS_MAX,
                        "%s: windowMillis should be in range [50, 10000] but got %d", __FUNCTION__,
                        windowMillis);
    params->calculationWindowMillis = windowMillis;
}

int ACpuHeadroomParams_getCalculationWindowMillis(ACpuHeadroomParams* _Nonnull params) {
    return params->calculationWindowMillis;
}

int AGpuHeadroomParams_getCalculationWindowMillis(AGpuHeadroomParams* _Nonnull params) {
    return params->calculationWindowMillis;
}

void ACpuHeadroomParams_setTids(ACpuHeadroomParams* _Nonnull params, const int* _Nonnull tids,
                                int tidsSize) {
    LOG_ALWAYS_FATAL_IF(tids == nullptr, "%s: tids should not be null", __FUNCTION__);
    LOG_ALWAYS_FATAL_IF(tidsSize > CPU_HEADROOM_MAX_TID_COUNT, "%s: tids size should not exceed 5",
                        __FUNCTION__);
    params->tids.resize(tidsSize);
    params->tids.clear();
    for (int i = 0; i < tidsSize; ++i) {
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

void ACpuHeadroomParams_destroy(ACpuHeadroomParams* _Nonnull params) {
    delete params;
}

void AGpuHeadroomParams_destroy(AGpuHeadroomParams* _Nonnull params) {
    delete params;
}
