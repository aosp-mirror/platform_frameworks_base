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
#include <aidl/android/hardware/power/SessionTag.h>
#include <aidl/android/hardware/power/WorkDuration.h>
#include <aidl/android/os/IHintManager.h>
#include <aidl/android/os/IHintSession.h>
#include <android-base/stringprintf.h>
#include <android-base/thread_annotations.h>
#include <android/binder_manager.h>
#include <android/binder_status.h>
#include <android/performance_hint.h>
#include <android/trace.h>
#include <inttypes.h>
#include <performance_hint_private.h>
#include <utils/SystemClock.h>

#include <chrono>
#include <set>
#include <utility>
#include <vector>

using namespace android;
using namespace aidl::android::os;

using namespace std::chrono_literals;

// Namespace for AIDL types coming from the PowerHAL
namespace hal = aidl::android::hardware::power;

using android::base::StringPrintf;

struct APerformanceHintSession;

constexpr int64_t SEND_HINT_TIMEOUT = std::chrono::nanoseconds(100ms).count();
struct AWorkDuration : public hal::WorkDuration {};

// Shared lock for the whole PerformanceHintManager and sessions
static std::mutex sHintMutex = std::mutex{};

struct APerformanceHintManager {
public:
    static APerformanceHintManager* getInstance();
    APerformanceHintManager(std::shared_ptr<IHintManager> service, int64_t preferredRateNanos);
    APerformanceHintManager() = delete;
    ~APerformanceHintManager() = default;

    APerformanceHintSession* createSession(const int32_t* threadIds, size_t size,
                                           int64_t initialTargetWorkDurationNanos,
                                           hal::SessionTag tag = hal::SessionTag::APP);
    int64_t getPreferredRateNanos() const;

private:
    // Necessary to create an empty binder object
    static void* tokenStubOnCreate(void*) {
        return nullptr;
    }
    static void tokenStubOnDestroy(void*) {}
    static binder_status_t tokenStubOnTransact(AIBinder*, transaction_code_t, const AParcel*,
                                               AParcel*) {
        return STATUS_OK;
    }

    static APerformanceHintManager* create(std::shared_ptr<IHintManager> iHintManager);

    std::shared_ptr<IHintManager> mHintManager;
    ndk::SpAIBinder mToken;
    const int64_t mPreferredRateNanos;
};

struct APerformanceHintSession {
public:
    APerformanceHintSession(std::shared_ptr<IHintManager> hintManager,
                            std::shared_ptr<IHintSession> session, int64_t preferredRateNanos,
                            int64_t targetDurationNanos,
                            std::optional<hal::SessionConfig> sessionConfig);
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

    int reportActualWorkDurationInternal(AWorkDuration* workDuration);

    std::shared_ptr<IHintManager> mHintManager;
    std::shared_ptr<IHintSession> mHintSession;
    // HAL preferred update rate
    const int64_t mPreferredRateNanos;
    // Target duration for choosing update rate
    int64_t mTargetDurationNanos GUARDED_BY(sHintMutex);
    // First target hit timestamp
    int64_t mFirstTargetMetTimestamp GUARDED_BY(sHintMutex);
    // Last target hit timestamp
    int64_t mLastTargetMetTimestamp GUARDED_BY(sHintMutex);
    // Last hint reported from sendHint indexed by hint value
    std::vector<int64_t> mLastHintSentTimestamp GUARDED_BY(sHintMutex);
    // Cached samples
    std::vector<hal::WorkDuration> mActualWorkDurations GUARDED_BY(sHintMutex);
    std::string mSessionName GUARDED_BY(sHintMutex);
    static int64_t sIDCounter GUARDED_BY(sHintMutex);
    // The most recent set of thread IDs
    std::vector<int32_t> mLastThreadIDs GUARDED_BY(sHintMutex);
    std::optional<hal::SessionConfig> mSessionConfig GUARDED_BY(sHintMutex);
    // Tracing helpers
    void traceThreads(std::vector<int32_t>& tids) REQUIRES(sHintMutex);
    void tracePowerEfficient(bool powerEfficient) REQUIRES(sHintMutex);
    void traceActualDuration(int64_t actualDuration) REQUIRES(sHintMutex);
    void traceBatchSize(size_t batchSize) REQUIRES(sHintMutex);
    void traceTargetDuration(int64_t targetDuration) REQUIRES(sHintMutex);
};

static std::shared_ptr<IHintManager>* gIHintManagerForTesting = nullptr;
static APerformanceHintManager* gHintManagerForTesting = nullptr;
// Start above the int32 range so we don't collide with config sessions
int64_t APerformanceHintSession::sIDCounter = INT32_MAX;

// ===================================== APerformanceHintManager implementation
APerformanceHintManager::APerformanceHintManager(std::shared_ptr<IHintManager> manager,
                                                 int64_t preferredRateNanos)
      : mHintManager(std::move(manager)), mPreferredRateNanos(preferredRateNanos) {
    static AIBinder_Class* tokenBinderClass =
            AIBinder_Class_define("phm_token", tokenStubOnCreate, tokenStubOnDestroy,
                                  tokenStubOnTransact);
    mToken = ndk::SpAIBinder(AIBinder_new(tokenBinderClass, nullptr));
}

APerformanceHintManager* APerformanceHintManager::getInstance() {
    if (gHintManagerForTesting) return gHintManagerForTesting;
    if (gIHintManagerForTesting) {
        APerformanceHintManager* manager = create(*gIHintManagerForTesting);
        gIHintManagerForTesting = nullptr;
        return manager;
    }
    static APerformanceHintManager* instance = create(nullptr);
    return instance;
}

APerformanceHintManager* APerformanceHintManager::create(std::shared_ptr<IHintManager> manager) {
    if (!manager) {
        manager = IHintManager::fromBinder(
                ndk::SpAIBinder(AServiceManager_waitForService("performance_hint")));
    }
    if (manager == nullptr) {
        ALOGE("%s: PerformanceHint service is not ready ", __FUNCTION__);
        return nullptr;
    }
    int64_t preferredRateNanos = -1L;
    ndk::ScopedAStatus ret = manager->getHintSessionPreferredRate(&preferredRateNanos);
    if (!ret.isOk()) {
        ALOGE("%s: PerformanceHint cannot get preferred rate. %s", __FUNCTION__, ret.getMessage());
        return nullptr;
    }
    if (preferredRateNanos <= 0) {
        preferredRateNanos = -1L;
    }
    return new APerformanceHintManager(std::move(manager), preferredRateNanos);
}

APerformanceHintSession* APerformanceHintManager::createSession(
        const int32_t* threadIds, size_t size, int64_t initialTargetWorkDurationNanos,
        hal::SessionTag tag) {
    std::vector<int32_t> tids(threadIds, threadIds + size);
    std::shared_ptr<IHintSession> session;
    ndk::ScopedAStatus ret;
    std::optional<hal::SessionConfig> sessionConfig;
    ret = mHintManager->createHintSessionWithConfig(mToken, tids, initialTargetWorkDurationNanos,
                                                    tag, &sessionConfig, &session);

    if (!ret.isOk() || !session) {
        return nullptr;
    }
    auto out = new APerformanceHintSession(mHintManager, std::move(session), mPreferredRateNanos,
                                           initialTargetWorkDurationNanos, sessionConfig);
    std::scoped_lock lock(sHintMutex);
    out->traceThreads(tids);
    out->traceTargetDuration(initialTargetWorkDurationNanos);
    out->tracePowerEfficient(false);
    return out;
}

int64_t APerformanceHintManager::getPreferredRateNanos() const {
    return mPreferredRateNanos;
}

// ===================================== APerformanceHintSession implementation

APerformanceHintSession::APerformanceHintSession(std::shared_ptr<IHintManager> hintManager,
                                                 std::shared_ptr<IHintSession> session,
                                                 int64_t preferredRateNanos,
                                                 int64_t targetDurationNanos,
                                                 std::optional<hal::SessionConfig> sessionConfig)
      : mHintManager(hintManager),
        mHintSession(std::move(session)),
        mPreferredRateNanos(preferredRateNanos),
        mTargetDurationNanos(targetDurationNanos),
        mFirstTargetMetTimestamp(0),
        mLastTargetMetTimestamp(0),
        mSessionConfig(sessionConfig) {
    if (sessionConfig->id > INT32_MAX) {
        ALOGE("Session ID too large, must fit 32-bit integer");
    }
    std::scoped_lock lock(sHintMutex);
    constexpr int numEnums =
            ndk::enum_range<hal::SessionHint>().end() - ndk::enum_range<hal::SessionHint>().begin();
    mLastHintSentTimestamp = std::vector<int64_t>(numEnums, 0);
    int64_t traceId = sessionConfig.has_value() ? sessionConfig->id : ++sIDCounter;
    mSessionName = android::base::StringPrintf("ADPF Session %" PRId64, traceId);
}

APerformanceHintSession::~APerformanceHintSession() {
    ndk::ScopedAStatus ret = mHintSession->close();
    if (!ret.isOk()) {
        ALOGE("%s: HintSession close failed: %s", __FUNCTION__, ret.getMessage());
    }
}

int APerformanceHintSession::updateTargetWorkDuration(int64_t targetDurationNanos) {
    if (targetDurationNanos <= 0) {
        ALOGE("%s: targetDurationNanos must be positive", __FUNCTION__);
        return EINVAL;
    }
    {
        std::scoped_lock lock(sHintMutex);
        if (mTargetDurationNanos == targetDurationNanos) {
            return 0;
        }
    }
    ndk::ScopedAStatus ret = mHintSession->updateTargetWorkDuration(targetDurationNanos);
    if (!ret.isOk()) {
        ALOGE("%s: HintSession updateTargetWorkDuration failed: %s", __FUNCTION__,
              ret.getMessage());
        return EPIPE;
    }
    std::scoped_lock lock(sHintMutex);
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
    hal::WorkDuration workDuration{.durationNanos = actualDurationNanos,
                                   .workPeriodStartTimestampNanos = 0,
                                   .cpuDurationNanos = actualDurationNanos,
                                   .gpuDurationNanos = 0};

    return reportActualWorkDurationInternal(static_cast<AWorkDuration*>(&workDuration));
}

int APerformanceHintSession::sendHint(SessionHint hint) {
    std::scoped_lock lock(sHintMutex);
    if (hint < 0 || hint >= static_cast<int32_t>(mLastHintSentTimestamp.size())) {
        ALOGE("%s: invalid session hint %d", __FUNCTION__, hint);
        return EINVAL;
    }
    int64_t now = uptimeNanos();

    // Limit sendHint to a pre-detemined rate for safety
    if (now < (mLastHintSentTimestamp[hint] + SEND_HINT_TIMEOUT)) {
        return 0;
    }

    ndk::ScopedAStatus ret = mHintSession->sendHint(hint);

    if (!ret.isOk()) {
        ALOGE("%s: HintSession sendHint failed: %s", __FUNCTION__, ret.getMessage());
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
    ndk::ScopedAStatus ret = mHintManager->setHintSessionThreads(mHintSession, tids);
    if (!ret.isOk()) {
        ALOGE("%s: failed: %s", __FUNCTION__, ret.getMessage());
        if (ret.getExceptionCode() == EX_ILLEGAL_ARGUMENT) {
            return EINVAL;
        } else if (ret.getExceptionCode() == EX_SECURITY) {
            return EPERM;
        }
        return EPIPE;
    }

    std::scoped_lock lock(sHintMutex);
    traceThreads(tids);

    return 0;
}

int APerformanceHintSession::getThreadIds(int32_t* const threadIds, size_t* size) {
    std::vector<int32_t> tids;
    ndk::ScopedAStatus ret = mHintManager->getHintSessionThreadIds(mHintSession, &tids);
    if (!ret.isOk()) {
        ALOGE("%s: failed: %s", __FUNCTION__, ret.getMessage());
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
    ndk::ScopedAStatus ret =
            mHintSession->setMode(static_cast<int32_t>(hal::SessionMode::POWER_EFFICIENCY),
                                  enabled);

    if (!ret.isOk()) {
        ALOGE("%s: HintSession setPreferPowerEfficiency failed: %s", __FUNCTION__,
              ret.getMessage());
        return EPIPE;
    }
    std::scoped_lock lock(sHintMutex);
    tracePowerEfficient(enabled);
    return OK;
}

int APerformanceHintSession::reportActualWorkDuration(AWorkDuration* workDuration) {
    return reportActualWorkDurationInternal(workDuration);
}

int APerformanceHintSession::reportActualWorkDurationInternal(AWorkDuration* workDuration) {
    int64_t actualTotalDurationNanos = workDuration->durationNanos;
    int64_t now = uptimeNanos();
    workDuration->timeStampNanos = now;
    std::scoped_lock lock(sHintMutex);
    traceActualDuration(workDuration->durationNanos);
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

    ndk::ScopedAStatus ret = mHintSession->reportActualWorkDuration2(mActualWorkDurations);
    if (!ret.isOk()) {
        ALOGE("%s: HintSession reportActualWorkDuration failed: %s", __FUNCTION__,
              ret.getMessage());
        mFirstTargetMetTimestamp = 0;
        mLastTargetMetTimestamp = 0;
        traceBatchSize(mActualWorkDurations.size());
        return ret.getExceptionCode() == EX_ILLEGAL_ARGUMENT ? EINVAL : EPIPE;
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

APerformanceHintSession* APerformanceHint_createSessionInternal(
        APerformanceHintManager* manager, const int32_t* threadIds, size_t size,
        int64_t initialTargetWorkDurationNanos, SessionTag tag) {
    VALIDATE_PTR(manager)
    VALIDATE_PTR(threadIds)
    return manager->createSession(threadIds, size, initialTargetWorkDurationNanos,
                                  static_cast<hal::SessionTag>(tag));
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

int APerformanceHint_sendHint(APerformanceHintSession* session, SessionHint hint) {
    VALIDATE_PTR(session)
    return session->sendHint(hint);
}

int APerformanceHint_setThreads(APerformanceHintSession* session, const pid_t* threadIds,
                                size_t size) {
    VALIDATE_PTR(session)
    VALIDATE_PTR(threadIds)
    return session->setThreads(threadIds, size);
}

int APerformanceHint_getThreadIds(APerformanceHintSession* session, int32_t* const threadIds,
                                  size_t* const size) {
    VALIDATE_PTR(session)
    return session->getThreadIds(threadIds, size);
}

int APerformanceHint_setPreferPowerEfficiency(APerformanceHintSession* session, bool enabled) {
    VALIDATE_PTR(session)
    return session->setPreferPowerEfficiency(enabled);
}

int APerformanceHint_reportActualWorkDuration2(APerformanceHintSession* session,
                                               AWorkDuration* workDurationPtr) {
    VALIDATE_PTR(session)
    VALIDATE_PTR(workDurationPtr)
    VALIDATE_INT(workDurationPtr->durationNanos, > 0)
    VALIDATE_INT(workDurationPtr->workPeriodStartTimestampNanos, > 0)
    VALIDATE_INT(workDurationPtr->cpuDurationNanos, >= 0)
    VALIDATE_INT(workDurationPtr->gpuDurationNanos, >= 0)
    VALIDATE_INT(workDurationPtr->gpuDurationNanos + workDurationPtr->cpuDurationNanos, > 0)
    return session->reportActualWorkDuration(workDurationPtr);
}

AWorkDuration* AWorkDuration_create() {
    return new AWorkDuration();
}

void AWorkDuration_release(AWorkDuration* aWorkDuration) {
    VALIDATE_PTR(aWorkDuration)
    delete aWorkDuration;
}

void AWorkDuration_setActualTotalDurationNanos(AWorkDuration* aWorkDuration,
                                               int64_t actualTotalDurationNanos) {
    VALIDATE_PTR(aWorkDuration)
    WARN_INT(actualTotalDurationNanos, > 0)
    aWorkDuration->durationNanos = actualTotalDurationNanos;
}

void AWorkDuration_setWorkPeriodStartTimestampNanos(AWorkDuration* aWorkDuration,
                                                    int64_t workPeriodStartTimestampNanos) {
    VALIDATE_PTR(aWorkDuration)
    WARN_INT(workPeriodStartTimestampNanos, > 0)
    aWorkDuration->workPeriodStartTimestampNanos = workPeriodStartTimestampNanos;
}

void AWorkDuration_setActualCpuDurationNanos(AWorkDuration* aWorkDuration,
                                             int64_t actualCpuDurationNanos) {
    VALIDATE_PTR(aWorkDuration)
    WARN_INT(actualCpuDurationNanos, >= 0)
    aWorkDuration->cpuDurationNanos = actualCpuDurationNanos;
}

void AWorkDuration_setActualGpuDurationNanos(AWorkDuration* aWorkDuration,
                                             int64_t actualGpuDurationNanos) {
    VALIDATE_PTR(aWorkDuration)
    WARN_INT(actualGpuDurationNanos, >= 0)
    aWorkDuration->gpuDurationNanos = actualGpuDurationNanos;
}

void APerformanceHint_setIHintManagerForTesting(void* iManager) {
    delete gHintManagerForTesting;
    gHintManagerForTesting = nullptr;
    gIHintManagerForTesting = static_cast<std::shared_ptr<IHintManager>*>(iManager);
}
