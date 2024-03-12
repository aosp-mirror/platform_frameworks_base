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

#include <aidl/android/hardware/power/SessionHint.h>
#include <aidl/android/hardware/power/SessionMode.h>
#include <android/WorkDuration.h>
#include <android/os/IHintManager.h>
#include <android/os/IHintSession.h>
#include <android/performance_hint.h>
#include <binder/Binder.h>
#include <binder/IBinder.h>
#include <binder/IServiceManager.h>
#include <inttypes.h>
#include <performance_hint_private.h>
#include <utils/SystemClock.h>

#include <chrono>
#include <utility>
#include <vector>

using namespace android;
using namespace android::os;

using namespace std::chrono_literals;

using AidlSessionHint = aidl::android::hardware::power::SessionHint;
using AidlSessionMode = aidl::android::hardware::power::SessionMode;

struct APerformanceHintSession;

constexpr int64_t SEND_HINT_TIMEOUT = std::chrono::nanoseconds(100ms).count();

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
    const sp<IBinder> mToken = sp<BBinder>::make();
    const int64_t mPreferredRateNanos;
};

struct APerformanceHintSession {
public:
    APerformanceHintSession(sp<IHintManager> hintManager, sp<IHintSession> session,
                            int64_t preferredRateNanos, int64_t targetDurationNanos);
    APerformanceHintSession() = delete;
    ~APerformanceHintSession();

    int updateTargetWorkDuration(int64_t targetDurationNanos);
    int reportActualWorkDuration(int64_t actualDurationNanos);
    int sendHint(SessionHint hint);
    int setThreads(const int32_t* threadIds, size_t size);
    int getThreadIds(int32_t* const threadIds, size_t* size);
    int setPreferPowerEfficiency(bool enabled);
    int reportActualWorkDuration(AWorkDuration* workDuration);

private:
    friend struct APerformanceHintManager;

    int reportActualWorkDurationInternal(WorkDuration* workDuration);

    sp<IHintManager> mHintManager;
    sp<IHintSession> mHintSession;
    // HAL preferred update rate
    const int64_t mPreferredRateNanos;
    // Target duration for choosing update rate
    int64_t mTargetDurationNanos;
    // First target hit timestamp
    int64_t mFirstTargetMetTimestamp;
    // Last target hit timestamp
    int64_t mLastTargetMetTimestamp;
    // Last hint reported from sendHint indexed by hint value
    std::vector<int64_t> mLastHintSentTimestamp;
    // Cached samples
    std::vector<WorkDuration> mActualWorkDurations;
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
    std::vector<int32_t> tids(threadIds, threadIds + size);
    sp<IHintSession> session;
    binder::Status ret =
            mHintManager->createHintSession(mToken, tids, initialTargetWorkDurationNanos, &session);
    if (!ret.isOk() || !session) {
        return nullptr;
    }
    return new APerformanceHintSession(mHintManager, std::move(session), mPreferredRateNanos,
                                       initialTargetWorkDurationNanos);
}

int64_t APerformanceHintManager::getPreferredRateNanos() const {
    return mPreferredRateNanos;
}

// ===================================== APerformanceHintSession implementation

APerformanceHintSession::APerformanceHintSession(sp<IHintManager> hintManager,
                                                 sp<IHintSession> session,
                                                 int64_t preferredRateNanos,
                                                 int64_t targetDurationNanos)
      : mHintManager(hintManager),
        mHintSession(std::move(session)),
        mPreferredRateNanos(preferredRateNanos),
        mTargetDurationNanos(targetDurationNanos),
        mFirstTargetMetTimestamp(0),
        mLastTargetMetTimestamp(0) {
    const std::vector<AidlSessionHint> sessionHintRange{ndk::enum_range<AidlSessionHint>().begin(),
                                                        ndk::enum_range<AidlSessionHint>().end()};

    mLastHintSentTimestamp = std::vector<int64_t>(sessionHintRange.size(), 0);
}

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
        ALOGE("%s: HintSession updateTargetWorkDuration failed: %s", __FUNCTION__,
              ret.exceptionMessage().c_str());
        return EPIPE;
    }
    mTargetDurationNanos = targetDurationNanos;
    /**
     * Most of the workload is target_duration dependent, so now clear the cached samples
     * as they are most likely obsolete.
     */
    mActualWorkDurations.clear();
    mFirstTargetMetTimestamp = 0;
    mLastTargetMetTimestamp = 0;
    return 0;
}

int APerformanceHintSession::reportActualWorkDuration(int64_t actualDurationNanos) {
    if (actualDurationNanos <= 0) {
        ALOGE("%s: actualDurationNanos must be positive", __FUNCTION__);
        return EINVAL;
    }

    WorkDuration workDuration(0, actualDurationNanos, actualDurationNanos, 0);

    return reportActualWorkDurationInternal(&workDuration);
}

int APerformanceHintSession::sendHint(SessionHint hint) {
    if (hint < 0 || hint >= static_cast<int32_t>(mLastHintSentTimestamp.size())) {
        ALOGE("%s: invalid session hint %d", __FUNCTION__, hint);
        return EINVAL;
    }
    int64_t now = elapsedRealtimeNano();

    // Limit sendHint to a pre-detemined rate for safety
    if (now < (mLastHintSentTimestamp[hint] + SEND_HINT_TIMEOUT)) {
        return 0;
    }

    binder::Status ret = mHintSession->sendHint(hint);

    if (!ret.isOk()) {
        ALOGE("%s: HintSession sendHint failed: %s", __FUNCTION__, ret.exceptionMessage().c_str());
        return EPIPE;
    }
    mLastHintSentTimestamp[hint] = now;
    return 0;
}

int APerformanceHintSession::setThreads(const int32_t* threadIds, size_t size) {
    if (size == 0) {
        ALOGE("%s: the list of thread ids must not be empty.", __FUNCTION__);
        return EINVAL;
    }
    std::vector<int32_t> tids(threadIds, threadIds + size);
    binder::Status ret = mHintManager->setHintSessionThreads(mHintSession, tids);
    if (!ret.isOk()) {
        ALOGE("%s: failed: %s", __FUNCTION__, ret.exceptionMessage().c_str());
        if (ret.exceptionCode() == binder::Status::Exception::EX_ILLEGAL_ARGUMENT) {
            return EINVAL;
        } else if (ret.exceptionCode() == binder::Status::Exception::EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }
    return 0;
}

int APerformanceHintSession::getThreadIds(int32_t* const threadIds, size_t* size) {
    std::vector<int32_t> tids;
    binder::Status ret = mHintManager->getHintSessionThreadIds(mHintSession, &tids);
    if (!ret.isOk()) {
        ALOGE("%s: failed: %s", __FUNCTION__, ret.exceptionMessage().c_str());
        return EPIPE;
    }

    // When threadIds is nullptr, this is the first call to determine the size
    // of the thread ids list.
    if (threadIds == nullptr) {
        *size = tids.size();
        return 0;
    }

    // Second call to return the actual list of thread ids.
    *size = tids.size();
    for (size_t i = 0; i < *size; ++i) {
        threadIds[i] = tids[i];
    }
    return 0;
}

int APerformanceHintSession::setPreferPowerEfficiency(bool enabled) {
    binder::Status ret =
            mHintSession->setMode(static_cast<int32_t>(AidlSessionMode::POWER_EFFICIENCY), enabled);

    if (!ret.isOk()) {
        ALOGE("%s: HintSession setPreferPowerEfficiency failed: %s", __FUNCTION__,
              ret.exceptionMessage().c_str());
        return EPIPE;
    }
    return OK;
}

int APerformanceHintSession::reportActualWorkDuration(AWorkDuration* aWorkDuration) {
    WorkDuration* workDuration = static_cast<WorkDuration*>(aWorkDuration);
    if (workDuration->workPeriodStartTimestampNanos <= 0) {
        ALOGE("%s: workPeriodStartTimestampNanos must be positive", __FUNCTION__);
        return EINVAL;
    }
    if (workDuration->actualTotalDurationNanos <= 0) {
        ALOGE("%s: actualDurationNanos must be positive", __FUNCTION__);
        return EINVAL;
    }
    if (workDuration->actualCpuDurationNanos <= 0) {
        ALOGE("%s: cpuDurationNanos must be positive", __FUNCTION__);
        return EINVAL;
    }
    if (workDuration->actualGpuDurationNanos < 0) {
        ALOGE("%s: gpuDurationNanos must be non negative", __FUNCTION__);
        return EINVAL;
    }

    return reportActualWorkDurationInternal(workDuration);
}

int APerformanceHintSession::reportActualWorkDurationInternal(WorkDuration* workDuration) {
    int64_t actualTotalDurationNanos = workDuration->actualTotalDurationNanos;
    int64_t now = uptimeNanos();
    workDuration->timestampNanos = now;
    mActualWorkDurations.push_back(std::move(*workDuration));

    if (actualTotalDurationNanos >= mTargetDurationNanos) {
        // Reset timestamps if we are equal or over the target.
        mFirstTargetMetTimestamp = 0;
    } else {
        // Set mFirstTargetMetTimestamp for first time meeting target.
        if (!mFirstTargetMetTimestamp || !mLastTargetMetTimestamp ||
            (now - mLastTargetMetTimestamp > 2 * mPreferredRateNanos)) {
            mFirstTargetMetTimestamp = now;
        }
        /**
         * Rate limit the change if the update is over mPreferredRateNanos since first
         * meeting target and less than mPreferredRateNanos since last meeting target.
         */
        if (now - mFirstTargetMetTimestamp > mPreferredRateNanos &&
            now - mLastTargetMetTimestamp <= mPreferredRateNanos) {
            return 0;
        }
        mLastTargetMetTimestamp = now;
    }

    binder::Status ret = mHintSession->reportActualWorkDuration2(mActualWorkDurations);
    if (!ret.isOk()) {
        ALOGE("%s: HintSession reportActualWorkDuration failed: %s", __FUNCTION__,
              ret.exceptionMessage().c_str());
        mFirstTargetMetTimestamp = 0;
        mLastTargetMetTimestamp = 0;
        return ret.exceptionCode() == binder::Status::EX_ILLEGAL_ARGUMENT ? EINVAL : EPIPE;
    }
    mActualWorkDurations.clear();

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

int APerformanceHint_sendHint(void* session, SessionHint hint) {
    return reinterpret_cast<APerformanceHintSession*>(session)->sendHint(hint);
}

int APerformanceHint_setThreads(APerformanceHintSession* session, const pid_t* threadIds,
                                size_t size) {
    if (session == nullptr) {
        return EINVAL;
    }
    return session->setThreads(threadIds, size);
}

int APerformanceHint_getThreadIds(void* aPerformanceHintSession, int32_t* const threadIds,
                                  size_t* const size) {
    if (aPerformanceHintSession == nullptr) {
        return EINVAL;
    }
    return static_cast<APerformanceHintSession*>(aPerformanceHintSession)
            ->getThreadIds(threadIds, size);
}

int APerformanceHint_setPreferPowerEfficiency(APerformanceHintSession* session, bool enabled) {
    return session->setPreferPowerEfficiency(enabled);
}

int APerformanceHint_reportActualWorkDuration2(APerformanceHintSession* session,
                                               AWorkDuration* workDuration) {
    if (session == nullptr || workDuration == nullptr) {
        ALOGE("Invalid value: (session %p, workDuration %p)", session, workDuration);
        return EINVAL;
    }
    return session->reportActualWorkDuration(workDuration);
}

AWorkDuration* AWorkDuration_create() {
    WorkDuration* workDuration = new WorkDuration();
    return static_cast<AWorkDuration*>(workDuration);
}

void AWorkDuration_release(AWorkDuration* aWorkDuration) {
    if (aWorkDuration == nullptr) {
        ALOGE("%s: aWorkDuration is nullptr", __FUNCTION__);
    }
    delete aWorkDuration;
}

void AWorkDuration_setWorkPeriodStartTimestampNanos(AWorkDuration* aWorkDuration,
                                                    int64_t workPeriodStartTimestampNanos) {
    if (aWorkDuration == nullptr || workPeriodStartTimestampNanos <= 0) {
        ALOGE("%s: Invalid value. (AWorkDuration: %p, workPeriodStartTimestampNanos: %" PRIi64 ")",
              __FUNCTION__, aWorkDuration, workPeriodStartTimestampNanos);
    }
    static_cast<WorkDuration*>(aWorkDuration)->workPeriodStartTimestampNanos =
            workPeriodStartTimestampNanos;
}

void AWorkDuration_setActualTotalDurationNanos(AWorkDuration* aWorkDuration,
                                               int64_t actualTotalDurationNanos) {
    if (aWorkDuration == nullptr || actualTotalDurationNanos <= 0) {
        ALOGE("%s: Invalid value. (AWorkDuration: %p, actualTotalDurationNanos: %" PRIi64 ")",
              __FUNCTION__, aWorkDuration, actualTotalDurationNanos);
    }
    static_cast<WorkDuration*>(aWorkDuration)->actualTotalDurationNanos = actualTotalDurationNanos;
}

void AWorkDuration_setActualCpuDurationNanos(AWorkDuration* aWorkDuration,
                                             int64_t actualCpuDurationNanos) {
    if (aWorkDuration == nullptr || actualCpuDurationNanos <= 0) {
        ALOGE("%s: Invalid value. (AWorkDuration: %p, actualCpuDurationNanos: %" PRIi64 ")",
              __FUNCTION__, aWorkDuration, actualCpuDurationNanos);
    }
    static_cast<WorkDuration*>(aWorkDuration)->actualCpuDurationNanos = actualCpuDurationNanos;
}

void AWorkDuration_setActualGpuDurationNanos(AWorkDuration* aWorkDuration,
                                             int64_t actualGpuDurationNanos) {
    if (aWorkDuration == nullptr || actualGpuDurationNanos < 0) {
        ALOGE("%s: Invalid value. (AWorkDuration: %p, actualGpuDurationNanos: %" PRIi64 ")",
              __FUNCTION__, aWorkDuration, actualGpuDurationNanos);
    }
    static_cast<WorkDuration*>(aWorkDuration)->actualGpuDurationNanos = actualGpuDurationNanos;
}

void APerformanceHint_setIHintManagerForTesting(void* iManager) {
    delete gHintManagerForTesting;
    gHintManagerForTesting = nullptr;
    gIHintManagerForTesting = static_cast<IHintManager*>(iManager);
}
