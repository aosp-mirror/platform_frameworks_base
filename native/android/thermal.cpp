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

#include <cerrno>
#include <thread>

#include <android/thermal.h>
#include <android/os/BnThermalStatusListener.h>
#include <android/os/IThermalService.h>
#include <binder/IServiceManager.h>
#include <utils/Log.h>

using android::sp;

using namespace android;
using namespace android::os;

struct ThermalServiceListener : public BnThermalStatusListener {
    public:
        virtual binder::Status onStatusChange(int32_t status) override;
        ThermalServiceListener(AThermalManager *manager) {mMgr = manager;}
    private:
        AThermalManager *mMgr;
};

struct ListenerCallback {
    AThermal_StatusCallback callback;
    void* data;
};

struct AThermalManager {
   public:
        static AThermalManager* createAThermalManager();
        AThermalManager() = delete;
        ~AThermalManager();
        status_t notifyStateChange(int32_t status);
        status_t getCurrentThermalStatus(int32_t *status);
        status_t addListener(AThermal_StatusCallback, void *data);
        status_t removeListener(AThermal_StatusCallback, void *data);
   private:
       AThermalManager(sp<IThermalService> service);
       sp<IThermalService> mThermalSvc;
       sp<ThermalServiceListener> mServiceListener;
       std::vector<ListenerCallback> mListeners;
       std::mutex mMutex;
};

binder::Status ThermalServiceListener::onStatusChange(int32_t status) {
    if (mMgr != nullptr) {
        mMgr->notifyStateChange(status);
    }
    return binder::Status::ok();
}

AThermalManager* AThermalManager::createAThermalManager() {
    sp<IBinder> binder =
            defaultServiceManager()->checkService(String16("thermalservice"));

    if (binder == nullptr) {
        ALOGE("%s: Thermal service is not ready ", __FUNCTION__);
        return nullptr;
    }
    return new AThermalManager(interface_cast<IThermalService>(binder));
}

AThermalManager::AThermalManager(sp<IThermalService> service)
    : mThermalSvc(service),
      mServiceListener(nullptr) {
}

AThermalManager::~AThermalManager() {
    std::unique_lock<std::mutex> lock(mMutex);

    mListeners.clear();
    if (mServiceListener != nullptr) {
        bool success = false;
        mThermalSvc->unregisterThermalStatusListener(mServiceListener, &success);
        mServiceListener = nullptr;
    }
}

status_t AThermalManager::notifyStateChange(int32_t status) {
    std::unique_lock<std::mutex> lock(mMutex);
    AThermalStatus thermalStatus = static_cast<AThermalStatus>(status);

    for (auto listener : mListeners) {
        listener.callback(listener.data, thermalStatus);
    }
    return OK;
}

status_t AThermalManager::addListener(AThermal_StatusCallback callback, void *data) {
    std::unique_lock<std::mutex> lock(mMutex);

    if (callback == nullptr) {
        // Callback can not be nullptr
        return EINVAL;
    }
    for (const auto& cb : mListeners) {
        // Don't re-add callbacks.
        if (callback == cb.callback && data == cb.data) {
            return EINVAL;
        }
    }
    mListeners.emplace_back(ListenerCallback{callback, data});

    if (mServiceListener != nullptr) {
        return OK;
    }
    bool success = false;
    mServiceListener = new ThermalServiceListener(this);
    if (mServiceListener == nullptr) {
        return ENOMEM;
    }
    auto ret = mThermalSvc->registerThermalStatusListener(mServiceListener, &success);
    if (!success || !ret.isOk()) {
        ALOGE("Failed in registerThermalStatusListener %d", success);
        if (ret.exceptionCode() == binder::Status::EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }
    return OK;
}

status_t AThermalManager::removeListener(AThermal_StatusCallback callback, void *data) {
    std::unique_lock<std::mutex> lock(mMutex);

    auto it = std::remove_if(mListeners.begin(),
                             mListeners.end(),
                             [&](const ListenerCallback& cb) {
                                    return callback == cb.callback &&
                                           data == cb.data;
                             });
    if (it == mListeners.end()) {
        // If the listener and data pointer were not previously added.
        return EINVAL;
    }
    mListeners.erase(it, mListeners.end());

    if (!mListeners.empty()) {
        return OK;
    }
    if (mServiceListener == nullptr) {
        return OK;
    }
    bool success = false;
    auto ret = mThermalSvc->unregisterThermalStatusListener(mServiceListener, &success);
    if (!success || !ret.isOk()) {
        ALOGE("Failed in unregisterThermalStatusListener %d", success);
        if (ret.exceptionCode() == binder::Status::EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }
    mServiceListener = nullptr;
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

/**
  * Acquire an instance of the thermal manager. This must be freed using
  * {@link AThermal_releaseManager}.
  *
  * @return manager instance on success, nullptr on failure.
 */
AThermalManager* AThermal_acquireManager() {
    auto manager = AThermalManager::createAThermalManager();

    return manager;
}

/**
 * Release the thermal manager pointer acquired by
 * {@link AThermal_acquireManager}.
 *
 * @param manager The manager to be released.
 *
 */
void AThermal_releaseManager(AThermalManager *manager) {
    delete manager;
}

/**
  * Gets the current thermal status.
  *
  * @param manager The manager instance to use to query the thermal status,
  * acquired by {@link AThermal_acquireManager}.
  *
  * @return current thermal status, ATHERMAL_STATUS_ERROR on failure.
*/
AThermalStatus AThermal_getCurrentThermalStatus(AThermalManager *manager) {
    int32_t status = 0;
    status_t ret = manager->getCurrentThermalStatus(&status);
    if (ret != OK) {
        return AThermalStatus::ATHERMAL_STATUS_ERROR;
    }
    return static_cast<AThermalStatus>(status);
}

/**
 * Register the thermal status listener for thermal status change.
 *
 * @param manager The manager instance to use to register.
 * acquired by {@link AThermal_acquireManager}.
 * @param callback The callback function to be called when thermal status updated.
 * @param data The data pointer to be passed when callback is called.
 *
 * @return 0 on success
 *         EINVAL if the listener and data pointer were previously added and not removed.
 *         EPERM if the required permission is not held.
 *         EPIPE if communication with the system service has failed.
 */
int AThermal_registerThermalStatusListener(AThermalManager *manager,
        AThermal_StatusCallback callback, void *data) {
    return manager->addListener(callback, data);
}

/**
 * Unregister the thermal status listener previously resgistered.
 *
 * @param manager The manager instance to use to unregister.
 * acquired by {@link AThermal_acquireManager}.
 * @param callback The callback function to be called when thermal status updated.
 * @param data The data pointer to be passed when callback is called.
 *
 * @return 0 on success
 *         EINVAL if the listener and data pointer were not previously added.
 *         EPERM if the required permission is not held.
 *         EPIPE if communication with the system service has failed.
 */
int AThermal_unregisterThermalStatusListener(AThermalManager *manager,
        AThermal_StatusCallback callback, void *data) {
    return manager->removeListener(callback, data);
}
