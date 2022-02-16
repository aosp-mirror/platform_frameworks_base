/*
 * Copyright (C) 2021 The Android Open Source Project
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

#define LOG_TAG "perf_hint"

#include <utility>
#include <vector>

#include <android/os/IHintManager.h>
#include <android/os/IHintSession.h>
#include <binder/Binder.h>
#include <binder/IBinder.h>
#include <binder/IServiceManager.h>
#include <performance_hint_private.h>
#include <utils/SystemClock.h>

using namespace android;
using namespace android::os;

struct APerformanceHintSession;

struct APerformanceHintManager {
public:
    static APerformanceHintManager* getInstance();
    APerformanceHintManager(sp<IHintManager> service, int64_t preferredRateNanos);
    APerformanceHintManager() = delete;
    ~APerformanceHintManager() = default;

    APerformanceHintSession* createSession(const int32_t* threadIds, size_t size,
                                           int64_t initialTargetWorkDurationNanos);
    int64_t getPreferredRateNanos() const;

private:
    static APerformanceHintManager* create(sp<IHintManager> iHintManager);

    sp<IHintManager> mHintManager;
    const int64_t mPreferredRateNanos;
};

struct APerformanceHintSession {
public:
    APerformanceHintSession(sp<IHintSession> session, int64_t preferredRateNanos,
                            int64_t targetDurationNanos);
    APerformanceHintSession() = delete;
    ~APerformanceHintSession();

    int updateTargetWorkDuration(int64_t targetDurationNanos);
    int reportActualWorkDuration(int64_t actualDurationNanos);

private:
    friend struct APerformanceHintManager;

    sp<IHintSession> mHintSession;
    // HAL preferred update rate
    const int64_t mPreferredRateNanos;
    // Target duration for choosing update rate
    int64_t mTargetDurationNanos;
    // Last update timestamp
    int64_t mLastUpdateTimestamp;
    // Cached samples
    std::vector<int64_t> mActualDurationsNanos;
    std::vector<int64_t> mTimestampsNanos;
};

static IHintManager* gIHintManagerForTesting = nullptr;
static APerformanceHintManager* gHintManagerForTesting = nullptr;

// ===================================== APerformanceHintManager implementation
APerformanceHintManager::APerformanceHintManager(sp<IHintManager> manager,
                                                 int64_t preferredRateNanos)
      : mHintManager(std::move(manager)), mPreferredRateNanos(preferredRateNanos) {}

APerformanceHintManager* APerformanceHintManager::getInstance() {
    if (gHintManagerForTesting) return gHintManagerForTesting;
    if (gIHintManagerForTesting) {
        APerformanceHintManager* manager = create(gIHintManagerForTesting);
        gIHintManagerForTesting = nullptr;
        return manager;
    }
    static APerformanceHintManager* instance = create(nullptr);
    return instance;
}

APerformanceHintManager* APerformanceHintManager::create(sp<IHintManager> manager) {
    if (!manager) {
        manager = interface_cast<IHintManager>(
                defaultServiceManager()->checkService(String16("performance_hint")));
    }
    if (manager == nullptr) {
        ALOGE("%s: PerformanceHint service is not ready ", __FUNCTION__);
        return nullptr;
    }
    int64_t preferredRateNanos = -1L;
    binder::Status ret = manager->getHintSessionPreferredRate(&preferredRateNanos);
    if (!ret.isOk()) {
        ALOGE("%s: PerformanceHint cannot get preferred rate. %s", __FUNCTION__,
              ret.exceptionMessage().c_str());
        return nullptr;
    }
    if (preferredRateNanos <= 0) {
        preferredRateNanos = -1L;
    }
    return new APerformanceHintManager(std::move(manager), preferredRateNanos);
}

APerformanceHintSession* APerformanceHintManager::createSession(
        const int32_t* threadIds, size_t size, int64_t initialTargetWorkDurationNanos) {
    sp<IBinder> token = sp<BBinder>::make();
    std::vector<int32_t> tids(threadIds, threadIds + size);
    sp<IHintSession> session;
    binder::Status ret =
            mHintManager->createHintSession(token, tids, initialTargetWorkDurationNanos, &session);
    if (!ret.isOk() || !session) {
        return nullptr;
    }
    return new APerformanceHintSession(std::move(session), mPreferredRateNanos,
                                       initialTargetWorkDurationNanos);
}

int64_t APerformanceHintManager::getPreferredRateNanos() const {
    return mPreferredRateNanos;
}

// ===================================== APerformanceHintSession implementation

APerformanceHintSession::APerformanceHintSession(sp<IHintSession> session,
                                                 int64_t preferredRateNanos,
                                                 int64_t targetDurationNanos)
      : mHintSession(std::move(session)),
        mPreferredRateNanos(preferredRateNanos),
        mTargetDurationNanos(targetDurationNanos),
        mLastUpdateTimestamp(elapsedRealtimeNano()) {}

APerformanceHintSession::~APerformanceHintSession() {
    binder::Status ret = mHintSession->close();
    if (!ret.isOk()) {
        ALOGE("%s: HintSession close failed: %s", __FUNCTION__, ret.exceptionMessage().c_str());
    }
}

int APerformanceHintSession::updateTargetWorkDuration(int64_t targetDurationNanos) {
    if (targetDurationNanos <= 0) {
        ALOGE("%s: targetDurationNanos must be positive", __FUNCTION__);
        return EINVAL;
    }
    binder::Status ret = mHintSession->updateTargetWorkDuration(targetDurationNanos);
    if (!ret.isOk()) {
        ALOGE("%s: HintSessionn updateTargetWorkDuration failed: %s", __FUNCTION__,
              ret.exceptionMessage().c_str());
        return EPIPE;
    }
    mTargetDurationNanos = targetDurationNanos;
    /**
     * Most of the workload is target_duration dependent, so now clear the cached samples
     * as they are most likely obsolete.
     */
    mActualDurationsNanos.clear();
    mTimestampsNanos.clear();
    mLastUpdateTimestamp = elapsedRealtimeNano();
    return 0;
}

int APerformanceHintSession::reportActualWorkDuration(int64_t actualDurationNanos) {
    if (actualDurationNanos <= 0) {
        ALOGE("%s: actualDurationNanos must be positive", __FUNCTION__);
        return EINVAL;
    }
    int64_t now = elapsedRealtimeNano();
    mActualDurationsNanos.push_back(actualDurationNanos);
    mTimestampsNanos.push_back(now);

    /**
     * Use current sample to determine the rate limit. We can pick a shorter rate limit
     * if any sample underperformed, however, it could be the lower level system is slow
     * to react. So here we explicitly choose the rate limit with the latest sample.
     */
    int64_t rateLimit = actualDurationNanos > mTargetDurationNanos ? mPreferredRateNanos
                                                                   : 10 * mPreferredRateNanos;
    if (now - mLastUpdateTimestamp <= rateLimit) return 0;

    binder::Status ret =
            mHintSession->reportActualWorkDuration(mActualDurationsNanos, mTimestampsNanos);
    mActualDurationsNanos.clear();
    mTimestampsNanos.clear();
    if (!ret.isOk()) {
        ALOGE("%s: HintSession reportActualWorkDuration failed: %s", __FUNCTION__,
              ret.exceptionMessage().c_str());
        return EPIPE;
    }
    mLastUpdateTimestamp = now;
    return 0;
}

// ===================================== C API
APerformanceHintManager* APerformanceHint_getManager() {
    return APerformanceHintManager::getInstance();
}

APerformanceHintSession* APerformanceHint_createSession(APerformanceHintManager* manager,
                                                        const int32_t* threadIds, size_t size,
                                                        int64_t initialTargetWorkDurationNanos) {
    return manager->createSession(threadIds, size, initialTargetWorkDurationNanos);
}

int64_t APerformanceHint_getPreferredUpdateRateNanos(APerformanceHintManager* manager) {
    return manager->getPreferredRateNanos();
}

int APerformanceHint_updateTargetWorkDuration(APerformanceHintSession* session,
                                              int64_t targetDurationNanos) {
    return session->updateTargetWorkDuration(targetDurationNanos);
}

int APerformanceHint_reportActualWorkDuration(APerformanceHintSession* session,
                                              int64_t actualDurationNanos) {
    return session->reportActualWorkDuration(actualDurationNanos);
}

void APerformanceHint_closeSession(APerformanceHintSession* session) {
    delete session;
}

void APerformanceHint_setIHintManagerForTesting(void* iManager) {
    delete gHintManagerForTesting;
    gHintManagerForTesting = nullptr;
    gIHintManagerForTesting = static_cast<IHintManager*>(iManager);
}
