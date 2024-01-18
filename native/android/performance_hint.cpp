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
#include <android-base/stringprintf.h>
#include <android/WorkDuration.h>
#include <android/os/IHintManager.h>
#include <android/os/IHintSession.h>
#include <android/performance_hint.h>
#include <android/trace.h>
#include <binder/Binder.h>
#include <binder/IBinder.h>
#include <binder/IServiceManager.h>
#include <inttypes.h>
#include <performance_hint_private.h>
#include <utils/SystemClock.h>

#include <chrono>
#include <set>
#include <utility>
#include <vector>

using namespace android;
using namespace android::os;

using namespace std::chrono_literals;

using AidlSessionHint = aidl::android::hardware::power::SessionHint;
using AidlSessionMode = aidl::android::hardware::power::SessionMode;
using android::base::StringPrintf;

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
    std::string mSessionName;
    static int32_t sIDCounter;
    // The most recent set of thread IDs
    std::vector<int32_t> mLastThreadIDs;
    // Tracing helpers
    void traceThreads(std::vector<int32_t>& tids);
    void tracePowerEfficient(bool powerEfficient);
    void traceActualDuration(int64_t actualDuration);
    void traceBatchSize(size_t batchSize);
    void traceTargetDuration(int64_t targetDuration);
};

static IHintManager* gIHintManagerForTesting = nullptr;
static APerformanceHintManager* gHintManagerForTesting = nullptr;
int32_t APerformanceHintSession::sIDCounter = 0;

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
    auto out = new APerformanceHintSession(mHintManager, std::move(session), mPreferredRateNanos,
                                           initialTargetWorkDurationNanos);
    out->traceThreads(tids);
    out->traceTargetDuration(initialTargetWorkDurationNanos);
    out->tracePowerEfficient(false);
    return out;
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
    mSessionName = android::base::StringPrintf("ADPF Session %" PRId32, ++sIDCounter);
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
    traceBatchSize(0);
    traceTargetDuration(targetDurationNanos);
    mFirstTargetMetTimestamp = 0;
    mLastTargetMetTimestamp = 0;
    return 0;
}

int APerformanceHintSession::reportActualWorkDuration(int64_t actualDurationNanos) {
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

    traceThreads(tids);

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
    tracePowerEfficient(enabled);
    return OK;
}

int APerformanceHintSession::reportActualWorkDuration(AWorkDuration* aWorkDuration) {
    WorkDuration* workDuration = static_cast<WorkDuration*>(aWorkDuration);
    return reportActualWorkDurationInternal(workDuration);
}

int APerformanceHintSession::reportActualWorkDurationInternal(WorkDuration* workDuration) {
    int64_t actualTotalDurationNanos = workDuration->actualTotalDurationNanos;
    int64_t now = uptimeNanos();
    workDuration->timestampNanos = now;
    traceActualDuration(workDuration->actualTotalDurationNanos);
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
            traceBatchSize(mActualWorkDurations.size());
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
        traceBatchSize(mActualWorkDurations.size());
        return ret.exceptionCode() == binder::Status::EX_ILLEGAL_ARGUMENT ? EINVAL : EPIPE;
    }
    mActualWorkDurations.clear();
    traceBatchSize(0);

    return 0;
}
// ===================================== Tracing helpers

void APerformanceHintSession::traceThreads(std::vector<int32_t>& tids) {
    std::set<int32_t> tidSet{tids.begin(), tids.end()};

    // Disable old TID tracing
    for (int32_t tid : mLastThreadIDs) {
        if (!tidSet.count(tid)) {
            std::string traceName =
                    android::base::StringPrintf("%s TID: %" PRId32, mSessionName.c_str(), tid);
            ATrace_setCounter(traceName.c_str(), 0);
        }
    }

    // Add new TID tracing
    for (int32_t tid : tids) {
        std::string traceName =
                android::base::StringPrintf("%s TID: %" PRId32, mSessionName.c_str(), tid);
        ATrace_setCounter(traceName.c_str(), 1);
    }

    mLastThreadIDs = std::move(tids);
}

void APerformanceHintSession::tracePowerEfficient(bool powerEfficient) {
    ATrace_setCounter((mSessionName + " power efficiency mode").c_str(), powerEfficient);
}

void APerformanceHintSession::traceActualDuration(int64_t actualDuration) {
    ATrace_setCounter((mSessionName + " actual duration").c_str(), actualDuration);
}

void APerformanceHintSession::traceBatchSize(size_t batchSize) {
    std::string traceName = StringPrintf("%s batch size", mSessionName.c_str());
    ATrace_setCounter((mSessionName + " batch size").c_str(), batchSize);
}

void APerformanceHintSession::traceTargetDuration(int64_t targetDuration) {
    ATrace_setCounter((mSessionName + " target duration").c_str(), targetDuration);
}

// ===================================== C API
APerformanceHintManager* APerformanceHint_getManager() {
    return APerformanceHintManager::getInstance();
}

#define VALIDATE_PTR(ptr) \
    LOG_ALWAYS_FATAL_IF(ptr == nullptr, "%s: " #ptr " is nullptr", __FUNCTION__);

#define VALIDATE_INT(value, cmp)                                                             \
    if (!(value cmp)) {                                                                      \
        ALOGE("%s: Invalid value. Check failed: (" #value " " #cmp ") with value: %" PRIi64, \
              __FUNCTION__, value);                                                          \
        return EINVAL;                                                                       \
    }

#define WARN_INT(value, cmp)                                                                 \
    if (!(value cmp)) {                                                                      \
        ALOGE("%s: Invalid value. Check failed: (" #value " " #cmp ") with value: %" PRIi64, \
              __FUNCTION__, value);                                                          \
    }

APerformanceHintSession* APerformanceHint_createSession(APerformanceHintManager* manager,
                                                        const int32_t* threadIds, size_t size,
                                                        int64_t initialTargetWorkDurationNanos) {
    VALIDATE_PTR(manager)
    VALIDATE_PTR(threadIds)
    return manager->createSession(threadIds, size, initialTargetWorkDurationNanos);
}

int64_t APerformanceHint_getPreferredUpdateRateNanos(APerformanceHintManager* manager) {
    VALIDATE_PTR(manager)
    return manager->getPreferredRateNanos();
}

int APerformanceHint_updateTargetWorkDuration(APerformanceHintSession* session,
                                              int64_t targetDurationNanos) {
    VALIDATE_PTR(session)
    return session->updateTargetWorkDuration(targetDurationNanos);
}

int APerformanceHint_reportActualWorkDuration(APerformanceHintSession* session,
                                              int64_t actualDurationNanos) {
    VALIDATE_PTR(session)
    VALIDATE_INT(actualDurationNanos, > 0)
    return session->reportActualWorkDuration(actualDurationNanos);
}

void APerformanceHint_closeSession(APerformanceHintSession* session) {
    VALIDATE_PTR(session)
    delete session;
}

int APerformanceHint_sendHint(void* session, SessionHint hint) {
    VALIDATE_PTR(session)
    return reinterpret_cast<APerformanceHintSession*>(session)->sendHint(hint);
}

int APerformanceHint_setThreads(APerformanceHintSession* session, const pid_t* threadIds,
                                size_t size) {
    VALIDATE_PTR(session)
    VALIDATE_PTR(threadIds)
    return session->setThreads(threadIds, size);
}

int APerformanceHint_getThreadIds(void* aPerformanceHintSession, int32_t* const threadIds,
                                  size_t* const size) {
    VALIDATE_PTR(aPerformanceHintSession)
    return static_cast<APerformanceHintSession*>(aPerformanceHintSession)
            ->getThreadIds(threadIds, size);
}

int APerformanceHint_setPreferPowerEfficiency(APerformanceHintSession* session, bool enabled) {
    VALIDATE_PTR(session)
    return session->setPreferPowerEfficiency(enabled);
}

int APerformanceHint_reportActualWorkDuration2(APerformanceHintSession* session,
                                               AWorkDuration* workDurationPtr) {
    VALIDATE_PTR(session)
    VALIDATE_PTR(workDurationPtr)
    WorkDuration& workDuration = *static_cast<WorkDuration*>(workDurationPtr);
    VALIDATE_INT(workDuration.workPeriodStartTimestampNanos, > 0)
    VALIDATE_INT(workDuration.actualTotalDurationNanos, > 0)
    VALIDATE_INT(workDuration.actualCpuDurationNanos, > 0)
    VALIDATE_INT(workDuration.actualGpuDurationNanos, >= 0)
    return session->reportActualWorkDuration(workDurationPtr);
}

AWorkDuration* AWorkDuration_create() {
    WorkDuration* workDuration = new WorkDuration();
    return static_cast<AWorkDuration*>(workDuration);
}

void AWorkDuration_release(AWorkDuration* aWorkDuration) {
    VALIDATE_PTR(aWorkDuration)
    delete aWorkDuration;
}

void AWorkDuration_setWorkPeriodStartTimestampNanos(AWorkDuration* aWorkDuration,
                                                    int64_t workPeriodStartTimestampNanos) {
    VALIDATE_PTR(aWorkDuration)
    WARN_INT(workPeriodStartTimestampNanos, > 0)
    static_cast<WorkDuration*>(aWorkDuration)->workPeriodStartTimestampNanos =
            workPeriodStartTimestampNanos;
}

void AWorkDuration_setActualTotalDurationNanos(AWorkDuration* aWorkDuration,
                                               int64_t actualTotalDurationNanos) {
    VALIDATE_PTR(aWorkDuration)
    WARN_INT(actualTotalDurationNanos, > 0)
    static_cast<WorkDuration*>(aWorkDuration)->actualTotalDurationNanos = actualTotalDurationNanos;
}

void AWorkDuration_setActualCpuDurationNanos(AWorkDuration* aWorkDuration,
                                             int64_t actualCpuDurationNanos) {
    VALIDATE_PTR(aWorkDuration)
    WARN_INT(actualCpuDurationNanos, > 0)
    static_cast<WorkDuration*>(aWorkDuration)->actualCpuDurationNanos = actualCpuDurationNanos;
}

void AWorkDuration_setActualGpuDurationNanos(AWorkDuration* aWorkDuration,
                                             int64_t actualGpuDurationNanos) {
    VALIDATE_PTR(aWorkDuration)
    WARN_INT(actualGpuDurationNanos, >= 0)
    static_cast<WorkDuration*>(aWorkDuration)->actualGpuDurationNanos = actualGpuDurationNanos;
}

void APerformanceHint_setIHintManagerForTesting(void* iManager) {
    delete gHintManagerForTesting;
    gHintManagerForTesting = nullptr;
    gIHintManagerForTesting = static_cast<IHintManager*>(iManager);
}
