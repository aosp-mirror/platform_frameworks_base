/*
 * Copyright (C) 2020 The Android Open Source Project
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

#define LOG_TAG "thermal"

#include <android-base/thread_annotations.h>
#include <android/os/BnThermalHeadroomListener.h>
#include <android/os/BnThermalStatusListener.h>
#include <android/os/IThermalService.h>
#include <android/thermal.h>
#include <binder/IServiceManager.h>
#include <thermal_private.h>
#include <utils/Log.h>

#include <cerrno>
#include <limits>
#include <thread>

using android::sp;

using namespace android;
using namespace android::os;

struct ThermalServiceStatusListener : public BnThermalStatusListener {
public:
    virtual binder::Status onStatusChange(int32_t status) override;
    ThermalServiceStatusListener(AThermalManager *manager) {
        mMgr = manager;
    }

private:
    AThermalManager *mMgr;
};

struct ThermalServiceHeadroomListener : public BnThermalHeadroomListener {
public:
    virtual binder::Status onHeadroomChange(float headroom, float forecastHeadroom,
                                            int32_t forecastSeconds,
                                            const ::std::vector<float> &thresholds) override;
    ThermalServiceHeadroomListener(AThermalManager *manager) {
        mMgr = manager;
    }

private:
    AThermalManager *mMgr;
};

struct StatusListenerCallback {
    AThermal_StatusCallback callback;
    void* data;
};

struct HeadroomListenerCallback {
    AThermal_HeadroomCallback callback;
    void *data;
};

static IThermalService *gIThermalServiceForTesting = nullptr;

struct AThermalManager {
public:
    static AThermalManager *createAThermalManager();
    AThermalManager() = delete;
    ~AThermalManager();
    status_t notifyStateChange(int32_t status);
    status_t notifyHeadroomChange(float headroom, float forecastHeadroom, int32_t forecastSeconds,
                                  const ::std::vector<float> &thresholds);
    status_t getCurrentThermalStatus(int32_t *status);
    status_t addStatusListener(AThermal_StatusCallback, void *data);
    status_t removeStatusListener(AThermal_StatusCallback, void *data);
    status_t getThermalHeadroom(int32_t forecastSeconds, float *result);
    status_t getThermalHeadroomThresholds(const AThermalHeadroomThreshold **, size_t *size);
    status_t addHeadroomListener(AThermal_HeadroomCallback, void *data);
    status_t removeHeadroomListener(AThermal_HeadroomCallback, void *data);

private:
    AThermalManager(sp<IThermalService> service);
    sp<IThermalService> mThermalSvc;
    std::mutex mStatusListenerMutex;
    sp<ThermalServiceStatusListener> mServiceStatusListener GUARDED_BY(mStatusListenerMutex);
    std::vector<StatusListenerCallback> mStatusListeners GUARDED_BY(mStatusListenerMutex);

    std::mutex mHeadroomListenerMutex;
    sp<ThermalServiceHeadroomListener> mServiceHeadroomListener GUARDED_BY(mHeadroomListenerMutex);
    std::vector<HeadroomListenerCallback> mHeadroomListeners GUARDED_BY(mHeadroomListenerMutex);
};

binder::Status ThermalServiceStatusListener::onStatusChange(int32_t status) {
    if (mMgr != nullptr) {
        mMgr->notifyStateChange(status);
    }
    return binder::Status::ok();
}

binder::Status ThermalServiceHeadroomListener::onHeadroomChange(
        float headroom, float forecastHeadroom, int32_t forecastSeconds,
        const ::std::vector<float> &thresholds) {
    if (mMgr != nullptr) {
        mMgr->notifyHeadroomChange(headroom, forecastHeadroom, forecastSeconds, thresholds);
    }
    return binder::Status::ok();
}

AThermalManager* AThermalManager::createAThermalManager() {
    if (gIThermalServiceForTesting) {
        return new AThermalManager(gIThermalServiceForTesting);
    }
    sp<IBinder> binder =
            defaultServiceManager()->checkService(String16("thermalservice"));

    if (binder == nullptr) {
        ALOGE("%s: Thermal service is not ready ", __FUNCTION__);
        return nullptr;
    }
    return new AThermalManager(interface_cast<IThermalService>(binder));
}

AThermalManager::AThermalManager(sp<IThermalService> service)
      : mThermalSvc(std::move(service)),
        mServiceStatusListener(nullptr),
        mServiceHeadroomListener(nullptr) {}

AThermalManager::~AThermalManager() {
    {
        std::scoped_lock<std::mutex> listenerLock(mStatusListenerMutex);
        mStatusListeners.clear();
        if (mServiceStatusListener != nullptr) {
            bool success = false;
            mThermalSvc->unregisterThermalStatusListener(mServiceStatusListener, &success);
            mServiceStatusListener = nullptr;
        }
    }
    {
        std::scoped_lock<std::mutex> headroomListenerLock(mHeadroomListenerMutex);
        mHeadroomListeners.clear();
        if (mServiceHeadroomListener != nullptr) {
            bool success = false;
            mThermalSvc->unregisterThermalHeadroomListener(mServiceHeadroomListener, &success);
            mServiceHeadroomListener = nullptr;
        }
    }
}

status_t AThermalManager::notifyStateChange(int32_t status) {
    std::scoped_lock<std::mutex> lock(mStatusListenerMutex);
    AThermalStatus thermalStatus = static_cast<AThermalStatus>(status);

    for (auto listener : mStatusListeners) {
        listener.callback(listener.data, thermalStatus);
    }
    return OK;
}

status_t AThermalManager::notifyHeadroomChange(float headroom, float forecastHeadroom,
                                               int32_t forecastSeconds,
                                               const ::std::vector<float> &thresholds) {
    std::scoped_lock<std::mutex> lock(mHeadroomListenerMutex);
    size_t thresholdsCount = thresholds.size();
    auto t = new AThermalHeadroomThreshold[thresholdsCount];
    for (int i = 0; i < (int)thresholdsCount; i++) {
        t[i].headroom = thresholds[i];
        t[i].thermalStatus = static_cast<AThermalStatus>(i);
    }
    for (auto listener : mHeadroomListeners) {
        listener.callback(listener.data, headroom, forecastHeadroom, forecastSeconds, t,
                          thresholdsCount);
    }
    delete[] t;
    return OK;
}

status_t AThermalManager::addStatusListener(AThermal_StatusCallback callback, void *data) {
    std::scoped_lock<std::mutex> lock(mStatusListenerMutex);

    if (callback == nullptr) {
        // Callback can not be nullptr
        return EINVAL;
    }
    for (const auto &cb : mStatusListeners) {
        // Don't re-add callbacks.
        if (callback == cb.callback && data == cb.data) {
            return EINVAL;
        }
    }

    if (mServiceStatusListener != nullptr) {
        mStatusListeners.emplace_back(StatusListenerCallback{callback, data});
        return OK;
    }
    bool success = false;
    mServiceStatusListener = new ThermalServiceStatusListener(this);
    if (mServiceStatusListener == nullptr) {
        return ENOMEM;
    }
    auto ret = mThermalSvc->registerThermalStatusListener(mServiceStatusListener, &success);
    if (!success || !ret.isOk()) {
        mServiceStatusListener = nullptr;
        ALOGE("Failed in registerThermalStatusListener %d", success);
        if (ret.exceptionCode() == binder::Status::EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }
    mStatusListeners.emplace_back(StatusListenerCallback{callback, data});
    return OK;
}

status_t AThermalManager::removeStatusListener(AThermal_StatusCallback callback, void *data) {
    std::scoped_lock<std::mutex> lock(mStatusListenerMutex);

    auto it = std::remove_if(mStatusListeners.begin(), mStatusListeners.end(),
                             [&](const StatusListenerCallback &cb) {
                                 return callback == cb.callback && data == cb.data;
                             });
    if (it == mStatusListeners.end()) {
        // If the listener and data pointer were not previously added.
        return EINVAL;
    }
    if (mServiceStatusListener == nullptr || mStatusListeners.size() > 1) {
        mStatusListeners.erase(it, mStatusListeners.end());
        return OK;
    }

    bool success = false;
    auto ret = mThermalSvc->unregisterThermalStatusListener(mServiceStatusListener, &success);
    if (!success || !ret.isOk()) {
        ALOGE("Failed in unregisterThermalStatusListener %d", success);
        if (ret.exceptionCode() == binder::Status::EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }
    mServiceStatusListener = nullptr;
    mStatusListeners.erase(it, mStatusListeners.end());
    return OK;
}

status_t AThermalManager::addHeadroomListener(AThermal_HeadroomCallback callback, void *data) {
    std::scoped_lock<std::mutex> lock(mHeadroomListenerMutex);
    if (callback == nullptr) {
        return EINVAL;
    }
    for (const auto &cb : mHeadroomListeners) {
        if (callback == cb.callback && data == cb.data) {
            return EINVAL;
        }
    }

    if (mServiceHeadroomListener != nullptr) {
        mHeadroomListeners.emplace_back(HeadroomListenerCallback{callback, data});
        return OK;
    }
    bool success = false;
    mServiceHeadroomListener = new ThermalServiceHeadroomListener(this);
    if (mServiceHeadroomListener == nullptr) {
        return ENOMEM;
    }
    auto ret = mThermalSvc->registerThermalHeadroomListener(mServiceHeadroomListener, &success);
    if (!success || !ret.isOk()) {
        ALOGE("Failed in registerThermalHeadroomListener %d", success);
        mServiceHeadroomListener = nullptr;
        if (ret.exceptionCode() == binder::Status::EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }
    mHeadroomListeners.emplace_back(HeadroomListenerCallback{callback, data});
    return OK;
}

status_t AThermalManager::removeHeadroomListener(AThermal_HeadroomCallback callback, void *data) {
    std::scoped_lock<std::mutex> lock(mHeadroomListenerMutex);

    auto it = std::remove_if(mHeadroomListeners.begin(), mHeadroomListeners.end(),
                             [&](const HeadroomListenerCallback &cb) {
                                 return callback == cb.callback && data == cb.data;
                             });
    if (it == mHeadroomListeners.end()) {
        return EINVAL;
    }
    if (mServiceHeadroomListener == nullptr || mHeadroomListeners.size() > 1) {
        mHeadroomListeners.erase(it, mHeadroomListeners.end());
        return OK;
    }
    bool success = false;
    auto ret = mThermalSvc->unregisterThermalHeadroomListener(mServiceHeadroomListener, &success);
    if (!success || !ret.isOk()) {
        ALOGE("Failed in unregisterThermalHeadroomListener %d", success);
        if (ret.exceptionCode() == binder::Status::EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }
    mServiceHeadroomListener = nullptr;
    mHeadroomListeners.erase(it, mHeadroomListeners.end());
    return OK;
}

status_t AThermalManager::getCurrentThermalStatus(int32_t *status) {
    binder::Status ret = mThermalSvc->getCurrentThermalStatus(status);

    if (!ret.isOk()) {
        if (ret.exceptionCode() == binder::Status::EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }
    return OK;
}

status_t AThermalManager::getThermalHeadroom(int32_t forecastSeconds, float *result) {
    binder::Status ret = mThermalSvc->getThermalHeadroom(forecastSeconds, result);

    if (!ret.isOk()) {
        if (ret.exceptionCode() == binder::Status::EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }
    return OK;
}

status_t AThermalManager::getThermalHeadroomThresholds(const AThermalHeadroomThreshold **result,
                                                       size_t *size) {
    auto thresholds = std::make_unique<std::vector<float>>();
    binder::Status ret = mThermalSvc->getThermalHeadroomThresholds(thresholds.get());
    if (!ret.isOk()) {
        if (ret.exceptionCode() == binder::Status::EX_UNSUPPORTED_OPERATION) {
            // feature is not enabled
            return ENOSYS;
        }
        return EPIPE;
    }
    size_t thresholdsCount = thresholds->size();
    auto t = new AThermalHeadroomThreshold[thresholdsCount];
    for (int i = 0; i < (int)thresholdsCount; i++) {
        t[i].headroom = (*thresholds)[i];
        t[i].thermalStatus = static_cast<AThermalStatus>(i);
    }
    *size = thresholdsCount;
    *result = t;
    return OK;
}

AThermalManager* AThermal_acquireManager() {
    auto manager = AThermalManager::createAThermalManager();

    return manager;
}

void AThermal_releaseManager(AThermalManager *manager) {
    delete manager;
}

AThermalStatus AThermal_getCurrentThermalStatus(AThermalManager *manager) {
    int32_t status = 0;
    status_t ret = manager->getCurrentThermalStatus(&status);
    if (ret != OK) {
        return AThermalStatus::ATHERMAL_STATUS_ERROR;
    }
    return static_cast<AThermalStatus>(status);
}

int AThermal_registerThermalStatusListener(AThermalManager *manager,
                                           AThermal_StatusCallback callback, void *data) {
    return manager->addStatusListener(callback, data);
}

int AThermal_unregisterThermalStatusListener(AThermalManager *manager,
                                             AThermal_StatusCallback callback, void *data) {
    return manager->removeStatusListener(callback, data);
}

float AThermal_getThermalHeadroom(AThermalManager *manager, int forecastSeconds) {
    float result = 0.0f;
    status_t ret = manager->getThermalHeadroom(forecastSeconds, &result);
    if (ret != OK) {
        result = std::numeric_limits<float>::quiet_NaN();
    }
    return result;
}

int AThermal_getThermalHeadroomThresholds(AThermalManager *manager,
                                          const AThermalHeadroomThreshold **outThresholds,
                                          size_t *size) {
    if (outThresholds == nullptr || *outThresholds != nullptr || size == nullptr) {
        return EINVAL;
    }
    return manager->getThermalHeadroomThresholds(outThresholds, size);
}

void AThermal_setIThermalServiceForTesting(void *iThermalService) {
    gIThermalServiceForTesting = static_cast<IThermalService *>(iThermalService);
}

int AThermal_registerThermalHeadroomListener(AThermalManager *manager,
                                             AThermal_HeadroomCallback callback, void *data) {
    return manager->addHeadroomListener(callback, data);
}

int AThermal_unregisterThermalHeadroomListener(AThermalManager *manager,
                                               AThermal_HeadroomCallback callback, void *data) {
    return manager->removeHeadroomListener(callback, data);
}
