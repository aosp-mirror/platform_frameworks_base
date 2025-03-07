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

#include <aidl/android/hardware/power/ChannelConfig.h>
#include <aidl/android/hardware/power/ChannelMessage.h>
#include <aidl/android/hardware/power/SessionConfig.h>
#include <aidl/android/hardware/power/SessionHint.h>
#include <aidl/android/hardware/power/SessionMode.h>
#include <aidl/android/hardware/power/SessionTag.h>
#include <aidl/android/hardware/power/WorkDuration.h>
#include <aidl/android/hardware/power/WorkDurationFixedV1.h>
#include <aidl/android/os/IHintManager.h>
#include <aidl/android/os/IHintSession.h>
#include <aidl/android/os/SessionCreationConfig.h>
#include <android-base/stringprintf.h>
#include <android-base/thread_annotations.h>
#include <android/binder_libbinder.h>
#include <android/binder_manager.h>
#include <android/binder_status.h>
#include <android/native_window.h>
#include <android/performance_hint.h>
#include <android/surface_control.h>
#include <android/trace.h>
#include <android_os.h>
#include <cutils/trace.h>
#include <fmq/AidlMessageQueue.h>
#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SurfaceControl.h>
#include <inttypes.h>
#include <jni_wrappers.h>
#include <performance_hint_private.h>
#include <utils/SystemClock.h>

#include <chrono>
#include <format>
#include <future>
#include <set>
#include <utility>
#include <vector>

using namespace android;
using namespace aidl::android::os;

using namespace std::chrono_literals;

// Namespace for AIDL types coming from the PowerHAL
namespace hal = aidl::android::hardware::power;

using ::aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using HalChannelMessageContents = hal::ChannelMessage::ChannelMessageContents;
using HalMessageQueue = ::android::AidlMessageQueue<hal::ChannelMessage, SynchronizedReadWrite>;
using HalFlagQueue = ::android::AidlMessageQueue<int8_t, SynchronizedReadWrite>;
using android::base::StringPrintf;

struct APerformanceHintSession;

constexpr int64_t SEND_HINT_TIMEOUT = std::chrono::nanoseconds(100ms).count();
struct AWorkDuration : public hal::WorkDuration {};
struct ASessionCreationConfig : public SessionCreationConfig {
    std::vector<wp<IBinder>> layers{};
    bool hasMode(hal::SessionMode&& mode) {
        return std::find(modesToEnable.begin(), modesToEnable.end(), mode) != modesToEnable.end();
    }
};

bool kForceGraphicsPipeline = false;

bool useGraphicsPipeline() {
    return android::os::adpf_graphics_pipeline() || kForceGraphicsPipeline;
}

// A pair of values that determine the behavior of the
// load hint rate limiter, to only allow "X hints every Y seconds"
constexpr double kLoadHintInterval = std::chrono::nanoseconds(2s).count();
constexpr double kMaxLoadHintsPerInterval = 20;
constexpr double kReplenishRate = kMaxLoadHintsPerInterval / kLoadHintInterval;
bool kForceNewHintBehavior = false;

template <class T>
constexpr int32_t enum_size() {
    return static_cast<int32_t>(*(ndk::enum_range<T>().end() - 1)) + 1;
}

bool useNewLoadHintBehavior() {
    return android::os::adpf_use_load_hints() || kForceNewHintBehavior;
}

// Shared lock for the whole PerformanceHintManager and sessions
static std::mutex sHintMutex = std::mutex{};
class FMQWrapper {
public:
    bool isActive();
    bool isSupported();
    bool startChannel(IHintManager* manager);
    void stopChannel(IHintManager* manager);
    // Number of elements the FMQ can hold
    bool reportActualWorkDurations(std::optional<hal::SessionConfig>& config,
                                   hal::WorkDuration* durations, size_t count) REQUIRES(sHintMutex);
    bool updateTargetWorkDuration(std::optional<hal::SessionConfig>& config,
                                  int64_t targetDurationNanos) REQUIRES(sHintMutex);
    bool sendHints(std::optional<hal::SessionConfig>& config, std::vector<hal::SessionHint>& hint,
                   int64_t now) REQUIRES(sHintMutex);
    bool setMode(std::optional<hal::SessionConfig>& config, hal::SessionMode, bool enabled)
            REQUIRES(sHintMutex);
    void setToken(ndk::SpAIBinder& token);
    void attemptWake();
    void setUnsupported();

private:
    template <HalChannelMessageContents::Tag T, bool urgent = false,
              class C = HalChannelMessageContents::_at<T>>
    bool sendMessages(std::optional<hal::SessionConfig>& config, C* message, size_t count = 1,
                      int64_t now = ::android::uptimeNanos()) REQUIRES(sHintMutex);
    template <HalChannelMessageContents::Tag T, class C = HalChannelMessageContents::_at<T>>
    void writeBuffer(C* message, hal::SessionConfig& config, size_t count, int64_t now)
            REQUIRES(sHintMutex);

    bool isActiveLocked() REQUIRES(sHintMutex);
    bool updatePersistentTransaction() REQUIRES(sHintMutex);
    std::shared_ptr<HalMessageQueue> mQueue GUARDED_BY(sHintMutex) = nullptr;
    std::shared_ptr<HalFlagQueue> mFlagQueue GUARDED_BY(sHintMutex) = nullptr;
    // android::hardware::EventFlag* mEventFlag GUARDED_BY(sHintMutex) = nullptr;
    android::hardware::EventFlag* mEventFlag = nullptr;
    int32_t mWriteMask;
    ndk::SpAIBinder mToken = nullptr;
    // Used to track if operating on the fmq consistently fails
    bool mCorrupted = false;
    // Used to keep a persistent transaction open with FMQ to reduce latency a bit
    size_t mAvailableSlots GUARDED_BY(sHintMutex) = 0;
    bool mHalSupported = true;
    HalMessageQueue::MemTransaction mFmqTransaction GUARDED_BY(sHintMutex);
    std::future<bool> mChannelCreationFinished;
};

struct APerformanceHintManager {
public:
    static APerformanceHintManager* getInstance();
    APerformanceHintManager(std::shared_ptr<IHintManager>& service, int64_t preferredRateNanos);
    APerformanceHintManager() = delete;
    ~APerformanceHintManager();

    APerformanceHintSession* createSession(const int32_t* threadIds, size_t size,
                                           int64_t initialTargetWorkDurationNanos,
                                           hal::SessionTag tag = hal::SessionTag::APP,
                                           bool isJava = false);
    APerformanceHintSession* getSessionFromJava(JNIEnv* _Nonnull env, jobject _Nonnull sessionObj);

    APerformanceHintSession* createSessionUsingConfig(ASessionCreationConfig* sessionCreationConfig,
                                                      hal::SessionTag tag = hal::SessionTag::APP,
                                                      bool isJava = false);
    int64_t getPreferredRateNanos() const;
    int32_t getMaxGraphicsPipelineThreadsCount();
    FMQWrapper& getFMQWrapper();
    bool canSendLoadHints(std::vector<hal::SessionHint>& hints, int64_t now) REQUIRES(sHintMutex);
    void initJava(JNIEnv* _Nonnull env);
    ndk::ScopedAIBinder_Weak x;
    template <class T>
    static void layersFromNativeSurfaces(ANativeWindow** windows, int numWindows,
                                         ASurfaceControl** controls, int numSurfaceControls,
                                         std::vector<T>& out);

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
    std::optional<int32_t> mMaxGraphicsPipelineThreadsCount;
    FMQWrapper mFMQWrapper;
    double mHintBudget = kMaxLoadHintsPerInterval;
    int64_t mLastBudgetReplenish = 0;
    bool mJavaInitialized = false;
    jclass mJavaSessionClazz;
    jfieldID mJavaSessionNativePtr;
};

struct APerformanceHintSession {
public:
    APerformanceHintSession(std::shared_ptr<IHintManager> hintManager,
                            std::shared_ptr<IHintSession> session, int64_t preferredRateNanos,
                            int64_t targetDurationNanos, bool isJava,
                            std::optional<hal::SessionConfig> sessionConfig);
    APerformanceHintSession() = delete;
    ~APerformanceHintSession();

    int updateTargetWorkDuration(int64_t targetDurationNanos);
    int reportActualWorkDuration(int64_t actualDurationNanos);
    int sendHints(std::vector<hal::SessionHint>& hints, int64_t now, const char* debugName);
    int notifyWorkloadIncrease(bool cpu, bool gpu, const char* debugName);
    int notifyWorkloadReset(bool cpu, bool gpu, const char* debugName);
    int notifyWorkloadSpike(bool cpu, bool gpu, const char* debugName);
    int setThreads(const int32_t* threadIds, size_t size);
    int getThreadIds(int32_t* const threadIds, size_t* size);
    int setPreferPowerEfficiency(bool enabled);
    int reportActualWorkDuration(AWorkDuration* workDuration);
    bool isJava();
    status_t setNativeSurfaces(ANativeWindow** windows, int numWindows, ASurfaceControl** controls,
                               int numSurfaceControls);

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
    // This is only used by the old rate limiter impl and is replaced
    // with the new rate limiter under a flag
    std::vector<int64_t> mLastHintSentTimestamp GUARDED_BY(sHintMutex);
    // Cached samples
    std::vector<hal::WorkDuration> mActualWorkDurations GUARDED_BY(sHintMutex);
    // Is this session backing an SDK wrapper object
    const bool mIsJava;
    std::string mSessionName;
    static int64_t sIDCounter GUARDED_BY(sHintMutex);
    // The most recent set of thread IDs
    std::vector<int32_t> mLastThreadIDs GUARDED_BY(sHintMutex);
    std::optional<hal::SessionConfig> mSessionConfig;
    // Tracing helpers
    void traceThreads(const std::vector<int32_t>& tids) REQUIRES(sHintMutex);
    void tracePowerEfficient(bool powerEfficient);
    void traceGraphicsPipeline(bool graphicsPipeline);
    void traceModes(const std::vector<hal::SessionMode>& modesToEnable);
    void traceActualDuration(int64_t actualDuration);
    void traceBatchSize(size_t batchSize);
    void traceTargetDuration(int64_t targetDuration);
};

static std::shared_ptr<IHintManager>* gIHintManagerForTesting = nullptr;
static std::shared_ptr<APerformanceHintManager> gHintManagerForTesting = nullptr;

static std::optional<bool> gForceFMQEnabled = std::nullopt;

// Start above the int32 range so we don't collide with config sessions
int64_t APerformanceHintSession::sIDCounter = INT32_MAX;

static FMQWrapper& getFMQ() {
    return APerformanceHintManager::getInstance()->getFMQWrapper();
}

// ===================================== APerformanceHintManager implementation
APerformanceHintManager::APerformanceHintManager(std::shared_ptr<IHintManager>& manager,
                                                 int64_t preferredRateNanos)
      : mHintManager(std::move(manager)), mPreferredRateNanos(preferredRateNanos) {
    static AIBinder_Class* tokenBinderClass =
            AIBinder_Class_define("phm_token", tokenStubOnCreate, tokenStubOnDestroy,
                                  tokenStubOnTransact);
    mToken = ndk::SpAIBinder(AIBinder_new(tokenBinderClass, nullptr));
    if (mFMQWrapper.isSupported()) {
        mFMQWrapper.setToken(mToken);
        mFMQWrapper.startChannel(mHintManager.get());
    }
}

APerformanceHintManager::~APerformanceHintManager() {
    mFMQWrapper.stopChannel(mHintManager.get());
}

APerformanceHintManager* APerformanceHintManager::getInstance() {
    static std::once_flag creationFlag;
    static APerformanceHintManager* instance = nullptr;
    if (gHintManagerForTesting) {
        return gHintManagerForTesting.get();
    }
    if (gIHintManagerForTesting) {
        gHintManagerForTesting =
                std::shared_ptr<APerformanceHintManager>(create(*gIHintManagerForTesting));
        return gHintManagerForTesting.get();
    }
    std::call_once(creationFlag, []() { instance = create(nullptr); });
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
    return new APerformanceHintManager(manager, preferredRateNanos);
}

bool APerformanceHintManager::canSendLoadHints(std::vector<hal::SessionHint>& hints, int64_t now) {
    mHintBudget =
            std::min(kMaxLoadHintsPerInterval,
                     mHintBudget +
                             static_cast<double>(now - mLastBudgetReplenish) * kReplenishRate);
    mLastBudgetReplenish = now;

    // If this youngest timestamp isn't older than the timeout time, we can't send
    if (hints.size() > mHintBudget) {
        return false;
    }
    mHintBudget -= hints.size();
    return true;
}

APerformanceHintSession* APerformanceHintManager::createSession(
        const int32_t* threadIds, size_t size, int64_t initialTargetWorkDurationNanos,
        hal::SessionTag tag, bool isJava) {
    ndk::ScopedAStatus ret;
    hal::SessionConfig sessionConfig{.id = -1};

    ASessionCreationConfig creationConfig{{
            .tids = std::vector<int32_t>(threadIds, threadIds + size),
            .targetWorkDurationNanos = initialTargetWorkDurationNanos,
    }};

    return APerformanceHintManager::createSessionUsingConfig(&creationConfig, tag, isJava);
}

APerformanceHintSession* APerformanceHintManager::createSessionUsingConfig(
        ASessionCreationConfig* sessionCreationConfig, hal::SessionTag tag, bool isJava) {
    std::shared_ptr<IHintSession> session;
    hal::SessionConfig sessionConfig{.id = -1};
    ndk::ScopedAStatus ret;

    // Hold the tokens weakly until we actually need them,
    // then promote them, then drop all strong refs after
    if (!sessionCreationConfig->layers.empty()) {
        for (auto&& layerIter = sessionCreationConfig->layers.begin();
             layerIter != sessionCreationConfig->layers.end();) {
            sp<IBinder> promoted = layerIter->promote();
            if (promoted == nullptr) {
                layerIter = sessionCreationConfig->layers.erase(layerIter);
            } else {
                sessionCreationConfig->layerTokens.push_back(
                        ndk::SpAIBinder(AIBinder_fromPlatformBinder(promoted.get())));
                ++layerIter;
            }
        }
    }

    ret = mHintManager->createHintSessionWithConfig(mToken, tag,
                                                    *static_cast<SessionCreationConfig*>(
                                                            sessionCreationConfig),
                                                    &sessionConfig, &session);

    sessionCreationConfig->layerTokens.clear();

    if (!ret.isOk() || !session) {
        ALOGE("%s: PerformanceHint cannot create session. %s", __FUNCTION__, ret.getMessage());
        return nullptr;
    }
    auto out = new APerformanceHintSession(mHintManager, std::move(session), mPreferredRateNanos,
                                           sessionCreationConfig->targetWorkDurationNanos, isJava,
                                           sessionConfig.id == -1
                                                   ? std::nullopt
                                                   : std::make_optional<hal::SessionConfig>(
                                                             std::move(sessionConfig)));
    std::scoped_lock lock(sHintMutex);
    out->traceThreads(sessionCreationConfig->tids);
    out->traceTargetDuration(sessionCreationConfig->targetWorkDurationNanos);
    out->traceModes(sessionCreationConfig->modesToEnable);

    return out;
}

APerformanceHintSession* APerformanceHintManager::getSessionFromJava(JNIEnv* env,
                                                                     jobject sessionObj) {
    initJava(env);
    LOG_ALWAYS_FATAL_IF(!env->IsInstanceOf(sessionObj, mJavaSessionClazz),
                        "Wrong java type passed to APerformanceHint_getSessionFromJava");
    APerformanceHintSession* out = reinterpret_cast<APerformanceHintSession*>(
            env->GetLongField(sessionObj, mJavaSessionNativePtr));
    LOG_ALWAYS_FATAL_IF(out == nullptr, "Java-wrapped native hint session is nullptr");
    LOG_ALWAYS_FATAL_IF(!out->isJava(), "Unmanaged native hint session returned from Java SDK");
    return out;
}

int64_t APerformanceHintManager::getPreferredRateNanos() const {
    return mPreferredRateNanos;
}

int32_t APerformanceHintManager::getMaxGraphicsPipelineThreadsCount() {
    if (!mMaxGraphicsPipelineThreadsCount.has_value()) {
        int32_t threadsCount = -1;
        ndk::ScopedAStatus ret = mHintManager->getMaxGraphicsPipelineThreadsCount(&threadsCount);
        if (!ret.isOk()) {
            ALOGE("%s: PerformanceHint cannot get max graphics pipeline threads count. %s",
                  __FUNCTION__, ret.getMessage());
            return -1;
        }
        if (threadsCount <= 0) {
            threadsCount = -1;
        }
        mMaxGraphicsPipelineThreadsCount.emplace(threadsCount);
    }
    return mMaxGraphicsPipelineThreadsCount.value();
}

FMQWrapper& APerformanceHintManager::getFMQWrapper() {
    return mFMQWrapper;
}

void APerformanceHintManager::initJava(JNIEnv* _Nonnull env) {
    if (mJavaInitialized) {
        return;
    }
    jclass sessionClazz = FindClassOrDie(env, "android/os/PerformanceHintManager$Session");
    mJavaSessionClazz = MakeGlobalRefOrDie(env, sessionClazz);
    mJavaSessionNativePtr = GetFieldIDOrDie(env, mJavaSessionClazz, "mNativeSessionPtr", "J");
    mJavaInitialized = true;
}

// ===================================== APerformanceHintSession implementation

constexpr int kNumEnums = enum_size<hal::SessionHint>();
APerformanceHintSession::APerformanceHintSession(std::shared_ptr<IHintManager> hintManager,
                                                 std::shared_ptr<IHintSession> session,
                                                 int64_t preferredRateNanos,
                                                 int64_t targetDurationNanos, bool isJava,
                                                 std::optional<hal::SessionConfig> sessionConfig)
      : mHintManager(hintManager),
        mHintSession(std::move(session)),
        mPreferredRateNanos(preferredRateNanos),
        mTargetDurationNanos(targetDurationNanos),
        mFirstTargetMetTimestamp(0),
        mLastTargetMetTimestamp(0),
        mLastHintSentTimestamp(std::vector<int64_t>(kNumEnums, 0)),
        mIsJava(isJava),
        mSessionConfig(sessionConfig) {
    if (sessionConfig->id > INT32_MAX) {
        ALOGE("Session ID too large, must fit 32-bit integer");
    }
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
    std::scoped_lock lock(sHintMutex);
    if (mTargetDurationNanos == targetDurationNanos) {
        return 0;
    }
    if (!getFMQ().updateTargetWorkDuration(mSessionConfig, targetDurationNanos)) {
        ndk::ScopedAStatus ret = mHintSession->updateTargetWorkDuration(targetDurationNanos);
        if (!ret.isOk()) {
            ALOGE("%s: HintSession updateTargetWorkDuration failed: %s", __FUNCTION__,
                  ret.getMessage());
            return EPIPE;
        }
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
    hal::WorkDuration workDuration{.durationNanos = actualDurationNanos,
                                   .workPeriodStartTimestampNanos = 0,
                                   .cpuDurationNanos = actualDurationNanos,
                                   .gpuDurationNanos = 0};

    return reportActualWorkDurationInternal(static_cast<AWorkDuration*>(&workDuration));
}

bool APerformanceHintSession::isJava() {
    return mIsJava;
}

int APerformanceHintSession::sendHints(std::vector<hal::SessionHint>& hints, int64_t now,
                                       const char*) {
    std::scoped_lock lock(sHintMutex);
    if (hints.empty()) {
        return EINVAL;
    }
    for (auto&& hint : hints) {
        if (static_cast<int32_t>(hint) < 0 || static_cast<int32_t>(hint) >= kNumEnums) {
            ALOGE("%s: invalid session hint %d", __FUNCTION__, hint);
            return EINVAL;
        }
    }

    if (useNewLoadHintBehavior()) {
        if (!APerformanceHintManager::getInstance()->canSendLoadHints(hints, now)) {
            return EBUSY;
        }
    }
    // keep old rate limiter behavior for legacy flag
    else {
        for (auto&& hint : hints) {
            if (now < (mLastHintSentTimestamp[static_cast<int32_t>(hint)] + SEND_HINT_TIMEOUT)) {
                return EBUSY;
            }
        }
    }

    if (!getFMQ().sendHints(mSessionConfig, hints, now)) {
        for (auto&& hint : hints) {
            ndk::ScopedAStatus ret = mHintSession->sendHint(static_cast<int32_t>(hint));

            if (!ret.isOk()) {
                ALOGE("%s: HintSession sendHint failed: %s", __FUNCTION__, ret.getMessage());
                return EPIPE;
            }
        }
    }

    if (!useNewLoadHintBehavior()) {
        for (auto&& hint : hints) {
            mLastHintSentTimestamp[static_cast<int32_t>(hint)] = now;
        }
    }

    if (ATrace_isEnabled()) {
        ATRACE_INSTANT("Sending load hint");
    }

    return 0;
}

int APerformanceHintSession::notifyWorkloadIncrease(bool cpu, bool gpu, const char* debugName) {
    std::vector<hal::SessionHint> hints(2);
    hints.clear();
    if (cpu) {
        hints.push_back(hal::SessionHint::CPU_LOAD_UP);
    }
    if (gpu) {
        hints.push_back(hal::SessionHint::GPU_LOAD_UP);
    }
    int64_t now = ::android::uptimeNanos();
    return sendHints(hints, now, debugName);
}

int APerformanceHintSession::notifyWorkloadReset(bool cpu, bool gpu, const char* debugName) {
    std::vector<hal::SessionHint> hints(2);
    hints.clear();
    if (cpu) {
        hints.push_back(hal::SessionHint::CPU_LOAD_RESET);
    }
    if (gpu) {
        hints.push_back(hal::SessionHint::GPU_LOAD_RESET);
    }
    int64_t now = ::android::uptimeNanos();
    return sendHints(hints, now, debugName);
}

int APerformanceHintSession::notifyWorkloadSpike(bool cpu, bool gpu, const char* debugName) {
    std::vector<hal::SessionHint> hints(2);
    hints.clear();
    if (cpu) {
        hints.push_back(hal::SessionHint::CPU_LOAD_SPIKE);
    }
    if (gpu) {
        hints.push_back(hal::SessionHint::GPU_LOAD_SPIKE);
    }
    int64_t now = ::android::uptimeNanos();
    return sendHints(hints, now, debugName);
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
    traceActualDuration(workDuration->durationNanos);
    int64_t now = uptimeNanos();
    workDuration->timeStampNanos = now;
    std::scoped_lock lock(sHintMutex);
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

    if (!getFMQ().reportActualWorkDurations(mSessionConfig, mActualWorkDurations.data(),
                                            mActualWorkDurations.size())) {
        ndk::ScopedAStatus ret = mHintSession->reportActualWorkDuration2(mActualWorkDurations);
        if (!ret.isOk()) {
            ALOGE("%s: HintSession reportActualWorkDuration failed: %s", __FUNCTION__,
                  ret.getMessage());
            mFirstTargetMetTimestamp = 0;
            mLastTargetMetTimestamp = 0;
            traceBatchSize(mActualWorkDurations.size());
            return ret.getExceptionCode() == EX_ILLEGAL_ARGUMENT ? EINVAL : EPIPE;
        }
    }

    mActualWorkDurations.clear();
    traceBatchSize(0);

    return 0;
}

status_t APerformanceHintSession::setNativeSurfaces(ANativeWindow** windows, int numWindows,
                                                    ASurfaceControl** controls,
                                                    int numSurfaceControls) {
    if (!mSessionConfig.has_value()) {
        return ENOTSUP;
    }

    std::vector<sp<IBinder>> layerHandles;
    APerformanceHintManager::layersFromNativeSurfaces<sp<IBinder>>(windows, numWindows, controls,
                                                                   numSurfaceControls,
                                                                   layerHandles);

    std::vector<ndk::SpAIBinder> ndkLayerHandles;
    for (auto&& handle : layerHandles) {
        ndkLayerHandles.emplace_back(ndk::SpAIBinder(AIBinder_fromPlatformBinder(handle)));
    }

    mHintSession->associateToLayers(ndkLayerHandles);
    return 0;
}

template <class T>
void APerformanceHintManager::layersFromNativeSurfaces(ANativeWindow** windows, int numWindows,
                                                       ASurfaceControl** controls,
                                                       int numSurfaceControls,
                                                       std::vector<T>& out) {
    std::scoped_lock lock(sHintMutex);
    if (windows != nullptr) {
        std::vector<ANativeWindow*> windowVec(windows, windows + numWindows);
        for (auto&& window : windowVec) {
            Surface* surface = static_cast<Surface*>(window);
            if (Surface::isValid(surface)) {
                const sp<IBinder>& handle = surface->getSurfaceControlHandle();
                if (handle != nullptr) {
                    out.push_back(handle);
                }
            }
        }
    }

    if (controls != nullptr) {
        std::vector<ASurfaceControl*> controlVec(controls, controls + numSurfaceControls);
        for (auto&& aSurfaceControl : controlVec) {
            SurfaceControl* control = reinterpret_cast<SurfaceControl*>(aSurfaceControl);
            if (control->isValid()) {
                out.push_back(control->getHandle());
            }
        }
    }
}

// ===================================== FMQ wrapper implementation

bool FMQWrapper::isActive() {
    std::scoped_lock lock{sHintMutex};
    return isActiveLocked();
}

bool FMQWrapper::isActiveLocked() {
    return mQueue != nullptr;
}

void FMQWrapper::setUnsupported() {
    mHalSupported = false;
}

bool FMQWrapper::isSupported() {
    if (!mHalSupported) {
        return false;
    }
    // Used for testing
    if (gForceFMQEnabled.has_value()) {
        return *gForceFMQEnabled;
    }
    return android::os::adpf_use_fmq_channel_fixed();
}

bool FMQWrapper::startChannel(IHintManager* manager) {
    if (isSupported() && !isActive() && manager->isRemote()) {
        mChannelCreationFinished = std::async(std::launch::async, [&, this, manager]() {
            std::optional<hal::ChannelConfig> config;
            auto ret = manager->getSessionChannel(mToken, &config);
            if (ret.isOk() && config.has_value()) {
                std::scoped_lock lock{sHintMutex};
                mQueue = std::make_shared<HalMessageQueue>(config->channelDescriptor, true);
                if (config->eventFlagDescriptor.has_value()) {
                    mFlagQueue = std::make_shared<HalFlagQueue>(*config->eventFlagDescriptor, true);
                    android::hardware::EventFlag::createEventFlag(mFlagQueue->getEventFlagWord(),
                                                                  &mEventFlag);
                    mWriteMask = config->writeFlagBitmask;
                }
                updatePersistentTransaction();
            } else if (ret.isOk() && !config.has_value()) {
                ALOGV("FMQ channel enabled but unsupported.");
                setUnsupported();
            } else {
                ALOGE("%s: FMQ channel initialization failed: %s", __FUNCTION__, ret.getMessage());
            }
            return true;
        });
    }
    return isActive();
}

void FMQWrapper::stopChannel(IHintManager* manager) {
    {
        std::scoped_lock lock{sHintMutex};
        if (!isActiveLocked()) {
            return;
        }
        mFlagQueue = nullptr;
        mQueue = nullptr;
    }
    manager->closeSessionChannel();
}

template <HalChannelMessageContents::Tag T, class C>
void FMQWrapper::writeBuffer(C* message, hal::SessionConfig& config, size_t count, int64_t now) {
    for (size_t i = 0; i < count; ++i) {
        new (mFmqTransaction.getSlot(i)) hal::ChannelMessage{
                .sessionID = static_cast<int32_t>(config.id),
                .timeStampNanos = now,
                .data = HalChannelMessageContents::make<T, C>(std::move(*(message + i))),
        };
    }
}

template <>
void FMQWrapper::writeBuffer<HalChannelMessageContents::workDuration>(hal::WorkDuration* messages,
                                                                      hal::SessionConfig& config,
                                                                      size_t count, int64_t now) {
    for (size_t i = 0; i < count; ++i) {
        hal::WorkDuration& message = messages[i];
        new (mFmqTransaction.getSlot(i)) hal::ChannelMessage{
                .sessionID = static_cast<int32_t>(config.id),
                .timeStampNanos = (i == count - 1) ? now : message.timeStampNanos,
                .data = HalChannelMessageContents::make<HalChannelMessageContents::workDuration,
                                                        hal::WorkDurationFixedV1>({
                        .durationNanos = message.cpuDurationNanos,
                        .workPeriodStartTimestampNanos = message.workPeriodStartTimestampNanos,
                        .cpuDurationNanos = message.cpuDurationNanos,
                        .gpuDurationNanos = message.gpuDurationNanos,
                }),
        };
    }
}

template <HalChannelMessageContents::Tag T, bool urgent, class C>
bool FMQWrapper::sendMessages(std::optional<hal::SessionConfig>& config, C* message, size_t count,
                              int64_t now) {
    if (!isActiveLocked() || !config.has_value() || mCorrupted) {
        return false;
    }
    // If we didn't reserve enough space, try re-creating the transaction
    if (count > mAvailableSlots) {
        if (!updatePersistentTransaction()) {
            return false;
        }
        // If we actually don't have enough space, give up
        if (count > mAvailableSlots) {
            return false;
        }
    }
    writeBuffer<T, C>(message, *config, count, now);
    mQueue->commitWrite(count);
    mEventFlag->wake(mWriteMask);
    // Re-create the persistent transaction after writing
    updatePersistentTransaction();
    return true;
}

void FMQWrapper::setToken(ndk::SpAIBinder& token) {
    mToken = token;
}

bool FMQWrapper::updatePersistentTransaction() {
    mAvailableSlots = mQueue->availableToWrite();
    if (mAvailableSlots > 0 && !mQueue->beginWrite(mAvailableSlots, &mFmqTransaction)) {
        ALOGE("ADPF FMQ became corrupted, falling back to binder calls!");
        mCorrupted = true;
        return false;
    }
    return true;
}

bool FMQWrapper::reportActualWorkDurations(std::optional<hal::SessionConfig>& config,
                                           hal::WorkDuration* durations, size_t count) {
    return sendMessages<HalChannelMessageContents::workDuration>(config, durations, count);
}

bool FMQWrapper::updateTargetWorkDuration(std::optional<hal::SessionConfig>& config,
                                          int64_t targetDurationNanos) {
    return sendMessages<HalChannelMessageContents::targetDuration>(config, &targetDurationNanos);
}

bool FMQWrapper::sendHints(std::optional<hal::SessionConfig>& config,
                           std::vector<hal::SessionHint>& hints, int64_t now) {
    return sendMessages<HalChannelMessageContents::hint>(config, hints.data(), hints.size(), now);
}

bool FMQWrapper::setMode(std::optional<hal::SessionConfig>& config, hal::SessionMode mode,
                         bool enabled) {
    hal::ChannelMessage::ChannelMessageContents::SessionModeSetter modeObj{.modeInt = mode,
                                                                           .enabled = enabled};
    return sendMessages<HalChannelMessageContents::mode, true>(config, &modeObj);
}

// ===================================== Tracing helpers

void APerformanceHintSession::traceThreads(const std::vector<int32_t>& tids) {
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

void APerformanceHintSession::traceGraphicsPipeline(bool graphicsPipeline) {
    ATrace_setCounter((mSessionName + " graphics pipeline mode").c_str(), graphicsPipeline);
}

void APerformanceHintSession::traceModes(const std::vector<hal::SessionMode>& modesToEnable) {
    // Iterate through all modes to trace, set to enable for all modes in modesToEnable,
    // and set to disable for those are not.
    for (hal::SessionMode mode :
         {hal::SessionMode::POWER_EFFICIENCY, hal::SessionMode::GRAPHICS_PIPELINE}) {
        bool isEnabled =
                find(modesToEnable.begin(), modesToEnable.end(), mode) != modesToEnable.end();
        switch (mode) {
            case hal::SessionMode::POWER_EFFICIENCY:
                tracePowerEfficient(isEnabled);
                break;
            case hal::SessionMode::GRAPHICS_PIPELINE:
                traceGraphicsPipeline(isEnabled);
                break;
            default:
                break;
        }
    }
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

APerformanceHintSession* APerformanceHint_createSessionUsingConfig(
        APerformanceHintManager* manager, ASessionCreationConfig* sessionCreationConfig) {
    VALIDATE_PTR(manager);
    VALIDATE_PTR(sessionCreationConfig);
    return manager->createSessionUsingConfig(sessionCreationConfig);
}

APerformanceHintSession* APerformanceHint_createSessionUsingConfigInternal(
        APerformanceHintManager* manager, ASessionCreationConfig* sessionCreationConfig,
        SessionTag tag) {
    VALIDATE_PTR(manager);
    VALIDATE_PTR(sessionCreationConfig);
    return manager->createSessionUsingConfig(sessionCreationConfig,
                                             static_cast<hal::SessionTag>(tag));
}

APerformanceHintSession* APerformanceHint_createSessionInternal(
        APerformanceHintManager* manager, const int32_t* threadIds, size_t size,
        int64_t initialTargetWorkDurationNanos, SessionTag tag) {
    VALIDATE_PTR(manager)
    VALIDATE_PTR(threadIds)
    return manager->createSession(threadIds, size, initialTargetWorkDurationNanos,
                                  static_cast<hal::SessionTag>(tag));
}

APerformanceHintSession* APerformanceHint_createSessionFromJava(
        APerformanceHintManager* manager, const int32_t* threadIds, size_t size,
        int64_t initialTargetWorkDurationNanos) {
    VALIDATE_PTR(manager)
    VALIDATE_PTR(threadIds)
    return manager->createSession(threadIds, size, initialTargetWorkDurationNanos,
                                  hal::SessionTag::APP, true);
}

APerformanceHintSession* APerformanceHint_borrowSessionFromJava(JNIEnv* env, jobject sessionObj) {
    VALIDATE_PTR(env)
    VALIDATE_PTR(sessionObj)
    return APerformanceHintManager::getInstance()->getSessionFromJava(env, sessionObj);
}

int64_t APerformanceHint_getPreferredUpdateRateNanos(APerformanceHintManager* manager) {
    VALIDATE_PTR(manager)
    return manager->getPreferredRateNanos();
}

int APerformanceHint_getMaxGraphicsPipelineThreadsCount(APerformanceHintManager* manager) {
    VALIDATE_PTR(manager);
    return manager->getMaxGraphicsPipelineThreadsCount();
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
    if (session->isJava()) {
        LOG_ALWAYS_FATAL("%s: Java-owned PerformanceHintSession cannot be closed in native",
                         __FUNCTION__);
        return;
    }
    delete session;
}

void APerformanceHint_closeSessionFromJava(APerformanceHintSession* session) {
    VALIDATE_PTR(session)
    delete session;
}

int APerformanceHint_sendHint(APerformanceHintSession* session, SessionHint hint) {
    VALIDATE_PTR(session)
    std::vector<hal::SessionHint> hints{static_cast<hal::SessionHint>(hint)};
    int64_t now = ::android::uptimeNanos();
    return session->sendHints(hints, now, "HWUI hint");
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

int APerformanceHint_notifyWorkloadIncrease(APerformanceHintSession* session, bool cpu, bool gpu,
                                            const char* debugName) {
    VALIDATE_PTR(session)
    VALIDATE_PTR(debugName)
    if (!useNewLoadHintBehavior()) {
        return ENOTSUP;
    }
    return session->notifyWorkloadIncrease(cpu, gpu, debugName);
}

int APerformanceHint_notifyWorkloadReset(APerformanceHintSession* session, bool cpu, bool gpu,
                                         const char* debugName) {
    VALIDATE_PTR(session)
    VALIDATE_PTR(debugName)
    if (!useNewLoadHintBehavior()) {
        return ENOTSUP;
    }
    return session->notifyWorkloadReset(cpu, gpu, debugName);
}

int APerformanceHint_notifyWorkloadSpike(APerformanceHintSession* session, bool cpu, bool gpu,
                                         const char* debugName) {
    VALIDATE_PTR(session)
    VALIDATE_PTR(debugName)
    if (!useNewLoadHintBehavior()) {
        return ENOTSUP;
    }
    return session->notifyWorkloadSpike(cpu, gpu, debugName);
}

int APerformanceHint_setNativeSurfaces(APerformanceHintSession* session,
                                       ANativeWindow** nativeWindows, int nativeWindowsSize,
                                       ASurfaceControl** surfaceControls, int surfaceControlsSize) {
    VALIDATE_PTR(session)
    return session->setNativeSurfaces(nativeWindows, nativeWindowsSize, surfaceControls,
                                      surfaceControlsSize);
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
    if (iManager == nullptr) {
        gHintManagerForTesting = nullptr;
    }
    gIHintManagerForTesting = static_cast<std::shared_ptr<IHintManager>*>(iManager);
}

void APerformanceHint_setUseFMQForTesting(bool enabled) {
    gForceFMQEnabled = enabled;
}

ASessionCreationConfig* ASessionCreationConfig_create() {
    return new ASessionCreationConfig();
}

void ASessionCreationConfig_release(ASessionCreationConfig* config) {
    VALIDATE_PTR(config)

    delete config;
}

int ASessionCreationConfig_setTids(ASessionCreationConfig* config, const pid_t* tids, size_t size) {
    VALIDATE_PTR(config)
    VALIDATE_PTR(tids)

    if (!useGraphicsPipeline()) {
        return ENOTSUP;
    }

    if (size <= 0) {
        LOG_ALWAYS_FATAL_IF(size <= 0,
                            "%s: Invalid value. Thread id list size should be greater than zero.",
                            __FUNCTION__);
        return EINVAL;
    }
    config->tids = std::vector<int32_t>(tids, tids + size);
    return 0;
}

int ASessionCreationConfig_setTargetWorkDurationNanos(ASessionCreationConfig* config,
                                                      int64_t targetWorkDurationNanos) {
    VALIDATE_PTR(config)
    VALIDATE_INT(targetWorkDurationNanos, >= 0)

    if (!useGraphicsPipeline()) {
        return ENOTSUP;
    }

    config->targetWorkDurationNanos = targetWorkDurationNanos;
    return 0;
}

int ASessionCreationConfig_setPreferPowerEfficiency(ASessionCreationConfig* config, bool enabled) {
    VALIDATE_PTR(config)

    if (!useGraphicsPipeline()) {
        return ENOTSUP;
    }

    if (enabled) {
        config->modesToEnable.push_back(hal::SessionMode::POWER_EFFICIENCY);
    } else {
        std::erase(config->modesToEnable, hal::SessionMode::POWER_EFFICIENCY);
    }
    return 0;
}

int ASessionCreationConfig_setGraphicsPipeline(ASessionCreationConfig* config, bool enabled) {
    VALIDATE_PTR(config)

    if (!useGraphicsPipeline()) {
        return ENOTSUP;
    }

    if (enabled) {
        config->modesToEnable.push_back(hal::SessionMode::GRAPHICS_PIPELINE);
    } else {
        std::erase(config->modesToEnable, hal::SessionMode::GRAPHICS_PIPELINE);

        // Remove automatic timing modes if we turn off GRAPHICS_PIPELINE,
        // as it is a strict pre-requisite for these to run
        std::erase(config->modesToEnable, hal::SessionMode::AUTO_CPU);
        std::erase(config->modesToEnable, hal::SessionMode::AUTO_GPU);
    }
    return 0;
}

void APerformanceHint_setUseGraphicsPipelineForTesting(bool enabled) {
    kForceGraphicsPipeline = enabled;
}

void APerformanceHint_getRateLimiterPropertiesForTesting(int32_t* maxLoadHintsPerInterval,
                                                         int64_t* loadHintInterval) {
    *maxLoadHintsPerInterval = kMaxLoadHintsPerInterval;
    *loadHintInterval = kLoadHintInterval;
}

void APerformanceHint_setUseNewLoadHintBehaviorForTesting(bool newBehavior) {
    kForceNewHintBehavior = newBehavior;
}

int ASessionCreationConfig_setNativeSurfaces(ASessionCreationConfig* config,
                                             ANativeWindow** nativeWindows, int nativeWindowsSize,
                                             ASurfaceControl** surfaceControls,
                                             int surfaceControlsSize) {
    VALIDATE_PTR(config)

    APerformanceHintManager::layersFromNativeSurfaces<wp<IBinder>>(nativeWindows, nativeWindowsSize,
                                                                   surfaceControls,
                                                                   surfaceControlsSize,
                                                                   config->layers);

    if (config->layers.empty()) {
        return EINVAL;
    }

    return 0;
}

int ASessionCreationConfig_setUseAutoTiming(ASessionCreationConfig* _Nonnull config, bool cpu,
                                            bool gpu) {
    VALIDATE_PTR(config)
    if ((cpu || gpu) && !config->hasMode(hal::SessionMode::GRAPHICS_PIPELINE)) {
        ALOGE("Automatic timing is not supported unless graphics pipeline mode is enabled first");
        return ENOTSUP;
    }

    if (config->hasMode(hal::SessionMode::AUTO_CPU)) {
        if (!cpu) {
            std::erase(config->modesToEnable, hal::SessionMode::AUTO_CPU);
        }
    } else if (cpu) {
        config->modesToEnable.push_back(static_cast<hal::SessionMode>(hal::SessionMode::AUTO_CPU));
    }

    if (config->hasMode(hal::SessionMode::AUTO_GPU)) {
        if (!gpu) {
            std::erase(config->modesToEnable, hal::SessionMode::AUTO_GPU);
        }
    } else if (gpu) {
        config->modesToEnable.push_back(static_cast<hal::SessionMode>(hal::SessionMode::AUTO_GPU));
    }

    return 0;
}
