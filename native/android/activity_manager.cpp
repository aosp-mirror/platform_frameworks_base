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

#define LOG_NDEBUG 0
#define LOG_TAG "AActivityManager"
#include <utils/Log.h>

#include <android/activity_manager.h>
#include <binder/ActivityManager.h>

namespace android {
namespace activitymanager {

// Global instance of ActivityManager, service is obtained only on first use.
static ActivityManager gAm;
// String tag used with ActivityManager.
static const String16& getTag() {
    static String16 tag("libandroid");
    return tag;
}

struct UidObserver : public BnUidObserver, public virtual IBinder::DeathRecipient {
    explicit UidObserver(const AActivityManager_onUidImportance& cb,
                         int32_t cutpoint, void* cookie)
          : mCallback(cb), mImportanceCutpoint(cutpoint), mCookie(cookie), mRegistered(false) {}
    bool registerSelf();
    void unregisterSelf();

    // IUidObserver
    void onUidGone(uid_t uid, bool disabled) override;
    void onUidActive(uid_t uid) override;
    void onUidIdle(uid_t uid, bool disabled) override;
    void onUidStateChanged(uid_t uid, int32_t procState, int64_t procStateSeq,
                           int32_t capability) override;
    void onUidProcAdjChanged(uid_t uid) override;

    // IBinder::DeathRecipient implementation
    void binderDied(const wp<IBinder>& who) override;

    static int32_t procStateToImportance(int32_t procState);
    static int32_t importanceToProcState(int32_t importance);

    AActivityManager_onUidImportance mCallback;
    int32_t mImportanceCutpoint;
    void* mCookie;
    std::mutex mRegisteredLock;
    bool mRegistered GUARDED_BY(mRegisteredLock);
};

//static
int32_t UidObserver::procStateToImportance(int32_t procState) {
    // TODO: remove this after adding Importance to onUidStateChanged callback.
    if (procState == ActivityManager::PROCESS_STATE_NONEXISTENT) {
        return AACTIVITYMANAGER_IMPORTANCE_GONE;
    } else if (procState >= ActivityManager::PROCESS_STATE_HOME) {
        return AACTIVITYMANAGER_IMPORTANCE_CACHED;
    } else if (procState == ActivityManager::PROCESS_STATE_HEAVY_WEIGHT) {
        return AACTIVITYMANAGER_IMPORTANCE_CANT_SAVE_STATE;
    } else if (procState >= ActivityManager::PROCESS_STATE_TOP_SLEEPING) {
        return AACTIVITYMANAGER_IMPORTANCE_TOP_SLEEPING;
    } else if (procState >= ActivityManager::PROCESS_STATE_SERVICE) {
        return AACTIVITYMANAGER_IMPORTANCE_SERVICE;
    } else if (procState >= ActivityManager::PROCESS_STATE_TRANSIENT_BACKGROUND) {
        return AACTIVITYMANAGER_IMPORTANCE_PERCEPTIBLE;
    } else if (procState >= ActivityManager::PROCESS_STATE_IMPORTANT_FOREGROUND) {
        return AACTIVITYMANAGER_IMPORTANCE_VISIBLE;
    } else if (procState >= ActivityManager::PROCESS_STATE_FOREGROUND_SERVICE) {
        return AACTIVITYMANAGER_IMPORTANCE_FOREGROUND_SERVICE;
    } else {
        return AACTIVITYMANAGER_IMPORTANCE_FOREGROUND;
    }
}

//static
int32_t UidObserver::importanceToProcState(int32_t importance) {
    // TODO: remove this after adding Importance to onUidStateChanged callback.
    if (importance == AACTIVITYMANAGER_IMPORTANCE_GONE) {
        return ActivityManager::PROCESS_STATE_NONEXISTENT;
    } else if (importance >= AACTIVITYMANAGER_IMPORTANCE_CACHED) {
        return ActivityManager::PROCESS_STATE_HOME;
    } else if (importance >= AACTIVITYMANAGER_IMPORTANCE_CANT_SAVE_STATE) {
        return ActivityManager::PROCESS_STATE_HEAVY_WEIGHT;
    } else if (importance >= AACTIVITYMANAGER_IMPORTANCE_TOP_SLEEPING) {
        return ActivityManager::PROCESS_STATE_TOP_SLEEPING;
    } else if (importance >= AACTIVITYMANAGER_IMPORTANCE_SERVICE) {
        return ActivityManager::PROCESS_STATE_SERVICE;
    } else if (importance >= AACTIVITYMANAGER_IMPORTANCE_PERCEPTIBLE) {
        return ActivityManager::PROCESS_STATE_TRANSIENT_BACKGROUND;
    } else if (importance >= AACTIVITYMANAGER_IMPORTANCE_VISIBLE) {
        return ActivityManager::PROCESS_STATE_IMPORTANT_FOREGROUND;
    } else if (importance >= AACTIVITYMANAGER_IMPORTANCE_FOREGROUND_SERVICE) {
        return ActivityManager::PROCESS_STATE_FOREGROUND_SERVICE;
    } else {
        return ActivityManager::PROCESS_STATE_TOP;
    }
}


void UidObserver::onUidGone(uid_t uid, bool disabled __unused) {
    std::scoped_lock lock{mRegisteredLock};

    if (mRegistered && mCallback) {
        mCallback(uid, AACTIVITYMANAGER_IMPORTANCE_GONE, mCookie);
    }
}

void UidObserver::onUidActive(uid_t uid __unused) {}

void UidObserver::onUidIdle(uid_t uid __unused, bool disabled __unused) {}

void UidObserver::onUidProcAdjChanged(uid_t uid __unused) {}

void UidObserver::onUidStateChanged(uid_t uid, int32_t procState,
                                    int64_t procStateSeq __unused,
                                    int32_t capability __unused) {
    std::scoped_lock lock{mRegisteredLock};

    if (mRegistered && mCallback) {
        mCallback(uid, procStateToImportance(procState), mCookie);
    }
}

void UidObserver::binderDied(const wp<IBinder>& /*who*/) {
    // ActivityManager is dead, try to re-register.
    {
        std::scoped_lock lock{mRegisteredLock};
        // If client already unregistered, don't try to re-register.
        if (!mRegistered) {
            return;
        }
        // Clear mRegistered to re-register.
        mRegistered = false;
    }
    registerSelf();
}

bool UidObserver::registerSelf() {
    std::scoped_lock lock{mRegisteredLock};
    if (mRegistered) {
        return true;
    }

    status_t res = gAm.linkToDeath(this);
    if (res != OK) {
        ALOGE("UidObserver: Failed to linkToDeath with ActivityManager (err %d)", res);
        return false;
    }

    // TODO: it seems only way to get all changes is to set cutoff to PROCESS_STATE_UNKNOWN.
    // But there is no equivalent of PROCESS_STATE_UNKNOWN in the UidImportance.
    // If mImportanceCutpoint is < 0, use PROCESS_STATE_UNKNOWN instead.
    res = gAm.registerUidObserver(
            this,
            ActivityManager::UID_OBSERVER_GONE | ActivityManager::UID_OBSERVER_PROCSTATE,
            (mImportanceCutpoint < 0) ? ActivityManager::PROCESS_STATE_UNKNOWN
                                      : importanceToProcState(mImportanceCutpoint),
            getTag());
    if (res != OK) {
        ALOGE("UidObserver: Failed to register with ActivityManager (err %d)", res);
        gAm.unlinkToDeath(this);
        return false;
    }

    mRegistered = true;
    ALOGV("UidObserver: Registered with ActivityManager");
    return true;
}

void UidObserver::unregisterSelf() {
    std::scoped_lock lock{mRegisteredLock};

    if (mRegistered) {
        gAm.unregisterUidObserver(this);
        gAm.unlinkToDeath(this);
        mRegistered = false;
    }

    ALOGV("UidObserver: Unregistered with ActivityManager");
}

} // activitymanager
} // android

using namespace android;
using namespace activitymanager;

struct AActivityManager_UidImportanceListener : public UidObserver {
};

AActivityManager_UidImportanceListener* AActivityManager_addUidImportanceListener(
        AActivityManager_onUidImportance onUidImportance, int32_t importanceCutpoint, void* cookie) {
    sp<UidObserver> observer(new UidObserver(onUidImportance, importanceCutpoint, cookie));
    if (observer == nullptr || !observer->registerSelf()) {
        return nullptr;
    }
    observer->incStrong((void *)AActivityManager_addUidImportanceListener);
    return static_cast<AActivityManager_UidImportanceListener*>(observer.get());
}

void AActivityManager_removeUidImportanceListener(
        AActivityManager_UidImportanceListener* listener) {
    if (listener != nullptr) {
        UidObserver* observer = static_cast<UidObserver*>(listener);
        observer->unregisterSelf();
        observer->decStrong((void *)AActivityManager_addUidImportanceListener);
    }
}

bool AActivityManager_isUidActive(uid_t uid) {
    return gAm.isUidActive(uid, getTag());
}

int32_t AActivityManager_getUidImportance(uid_t uid) {
    return UidObserver::procStateToImportance(gAm.getUidProcessState(uid, getTag()));
}

