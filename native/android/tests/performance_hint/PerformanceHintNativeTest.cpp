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

#define LOG_TAG "PerformanceHintNativeTest"

#include <aidl/android/hardware/power/ChannelConfig.h>
#include <aidl/android/hardware/power/SessionConfig.h>
#include <aidl/android/hardware/power/SessionMode.h>
#include <aidl/android/hardware/power/SessionTag.h>
#include <aidl/android/hardware/power/WorkDuration.h>
#include <aidl/android/os/IHintManager.h>
#include <aidl/android/os/SessionCreationConfig.h>
#include <android/binder_manager.h>
#include <android/binder_status.h>
#include <android/performance_hint.h>
#include <fmq/AidlMessageQueue.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <performance_hint_private.h>

#include <memory>
#include <vector>

using namespace std::chrono_literals;
namespace hal = aidl::android::hardware::power;
using aidl::android::os::IHintManager;
using aidl::android::os::IHintSession;
using aidl::android::os::SessionCreationConfig;
using ndk::ScopedAStatus;
using ndk::SpAIBinder;
using HalChannelMessageContents = hal::ChannelMessage::ChannelMessageContents;

using ::aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using HalFlagQueue = ::android::AidlMessageQueue<int8_t, SynchronizedReadWrite>;

using namespace android;
using namespace testing;

constexpr int64_t DEFAULT_TARGET_NS = 16666666L;

template <class T, void (*D)(T*)>
std::shared_ptr<T> wrapSP(T* incoming) {
    return incoming == nullptr ? nullptr : std::shared_ptr<T>(incoming, [](T* ptr) { D(ptr); });
}
constexpr auto&& wrapSession = wrapSP<APerformanceHintSession, APerformanceHint_closeSession>;
constexpr auto&& wrapConfig = wrapSP<ASessionCreationConfig, ASessionCreationConfig_release>;
constexpr auto&& wrapWorkDuration = wrapSP<AWorkDuration, AWorkDuration_release>;

std::shared_ptr<ASessionCreationConfig> createConfig() {
    return wrapConfig(ASessionCreationConfig_create());
}

struct ConfigCreator {
    std::vector<int32_t> tids{1, 2};
    int64_t targetDuration = DEFAULT_TARGET_NS;
    bool powerEfficient = false;
    bool graphicsPipeline = false;
    std::vector<ANativeWindow*> nativeWindows{};
    std::vector<ASurfaceControl*> surfaceControls{};
    bool autoCpu = false;
    bool autoGpu = false;
};

struct SupportHelper {
    bool hintSessions : 1;
    bool powerEfficiency : 1;
    bool bindToSurface : 1;
    bool graphicsPipeline : 1;
    bool autoCpu : 1;
    bool autoGpu : 1;
};

SupportHelper getSupportHelper() {
    return {
            .hintSessions = APerformanceHint_isFeatureSupported(APERF_HINT_SESSIONS),
            .powerEfficiency = APerformanceHint_isFeatureSupported(APERF_HINT_POWER_EFFICIENCY),
            .bindToSurface = APerformanceHint_isFeatureSupported(APERF_HINT_SURFACE_BINDING),
            .graphicsPipeline = APerformanceHint_isFeatureSupported(APERF_HINT_GRAPHICS_PIPELINE),
            .autoCpu = APerformanceHint_isFeatureSupported(APERF_HINT_AUTO_CPU),
            .autoGpu = APerformanceHint_isFeatureSupported(APERF_HINT_AUTO_GPU),
    };
}

SupportHelper getFullySupportedSupportHelper() {
    return {
            .hintSessions = true,
            .powerEfficiency = true,
            .graphicsPipeline = true,
            .autoCpu = true,
            .autoGpu = true,
    };
}

std::shared_ptr<ASessionCreationConfig> configFromCreator(ConfigCreator&& creator) {
    auto config = createConfig();

    ASessionCreationConfig_setTids(config.get(), creator.tids.data(), creator.tids.size());
    ASessionCreationConfig_setTargetWorkDurationNanos(config.get(), creator.targetDuration);
    ASessionCreationConfig_setPreferPowerEfficiency(config.get(), creator.powerEfficient);
    ASessionCreationConfig_setGraphicsPipeline(config.get(), creator.graphicsPipeline);
    ASessionCreationConfig_setNativeSurfaces(config.get(),
                                             creator.nativeWindows.size() > 0
                                                     ? creator.nativeWindows.data()
                                                     : nullptr,
                                             creator.nativeWindows.size(),
                                             creator.surfaceControls.size() > 0
                                                     ? creator.surfaceControls.data()
                                                     : nullptr,
                                             creator.surfaceControls.size());
    ASessionCreationConfig_setUseAutoTiming(config.get(), creator.autoCpu, creator.autoGpu);
    return config;
}

class MockIHintManager : public IHintManager {
public:
    MOCK_METHOD(ScopedAStatus, createHintSessionWithConfig,
                (const SpAIBinder& token, hal::SessionTag tag,
                 const SessionCreationConfig& creationConfig, hal::SessionConfig* config,
                 IHintManager::SessionCreationReturn* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, setHintSessionThreads,
                (const std::shared_ptr<IHintSession>& hintSession,
                 const ::std::vector<int32_t>& tids),
                (override));
    MOCK_METHOD(ScopedAStatus, getHintSessionThreadIds,
                (const std::shared_ptr<IHintSession>& hintSession, ::std::vector<int32_t>* tids),
                (override));
    MOCK_METHOD(ScopedAStatus, getSessionChannel,
                (const ::ndk::SpAIBinder& in_token,
                 std::optional<hal::ChannelConfig>* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, closeSessionChannel, (), (override));
    MOCK_METHOD(ScopedAStatus, getCpuHeadroom,
                (const ::aidl::android::os::CpuHeadroomParamsInternal& in_params,
                 std::optional<hal::CpuHeadroomResult>* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, getCpuHeadroomMinIntervalMillis, (int64_t* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, getGpuHeadroom,
                (const ::aidl::android::os::GpuHeadroomParamsInternal& in_params,
                 std::optional<hal::GpuHeadroomResult>* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, getGpuHeadroomMinIntervalMillis, (int64_t* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, passSessionManagerBinder, (const SpAIBinder& sessionManager));
    MOCK_METHOD(ScopedAStatus, registerClient,
                (const std::shared_ptr<::aidl::android::os::IHintManager::IHintManagerClient>&
                         clientDataIn,
                 ::aidl::android::os::IHintManager::HintManagerClientData* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, getClientData,
                (::aidl::android::os::IHintManager::HintManagerClientData * _aidl_return),
                (override));
    MOCK_METHOD(SpAIBinder, asBinder, (), (override));
    MOCK_METHOD(bool, isRemote, (), (override));
};

class MockIHintSession : public IHintSession {
public:
    MOCK_METHOD(ScopedAStatus, updateTargetWorkDuration, (int64_t targetDurationNanos), (override));
    MOCK_METHOD(ScopedAStatus, reportActualWorkDuration,
                (const ::std::vector<int64_t>& actualDurationNanos,
                 const ::std::vector<int64_t>& timeStampNanos),
                (override));
    MOCK_METHOD(ScopedAStatus, sendHint, (int32_t hint), (override));
    MOCK_METHOD(ScopedAStatus, setMode, (int32_t mode, bool enabled), (override));
    MOCK_METHOD(ScopedAStatus, close, (), (override));
    MOCK_METHOD(ScopedAStatus, reportActualWorkDuration2,
                (const ::std::vector<hal::WorkDuration>& workDurations), (override));
    MOCK_METHOD(ScopedAStatus, associateToLayers,
                (const std::vector<::ndk::SpAIBinder>& in_layerTokens), (override));
    MOCK_METHOD(SpAIBinder, asBinder, (), (override));
    MOCK_METHOD(bool, isRemote, (), (override));
};

class PerformanceHintTest : public Test {
public:
    void SetUp() override {
        mMockIHintManager = ndk::SharedRefBase::make<NiceMock<MockIHintManager>>();
        APerformanceHint_getRateLimiterPropertiesForTesting(&mMaxLoadHintsPerInterval,
                                                            &mLoadHintInterval);
        APerformanceHint_setIHintManagerForTesting(&mMockIHintManager);
        APerformanceHint_setUseNewLoadHintBehaviorForTesting(true);
        mTids.push_back(1);
        mTids.push_back(2);
    }

    void TearDown() override {
        mMockIHintManager = nullptr;
        // Destroys MockIHintManager.
        APerformanceHint_setIHintManagerForTesting(nullptr);
    }

    APerformanceHintManager* createManager() {
        APerformanceHint_setUseFMQForTesting(mUsingFMQ);
        ON_CALL(*mMockIHintManager, registerClient(_, _))
                .WillByDefault(
                        DoAll(SetArgPointee<1>(mClientData), [] { return ScopedAStatus::ok(); }));
        ON_CALL(*mMockIHintManager, isRemote()).WillByDefault(Return(true));
        return APerformanceHint_getManager();
    }

    void prepareSessionMock() {
        mMockSession = ndk::SharedRefBase::make<NiceMock<MockIHintSession>>();
        const int64_t sessionId = 123;

        mSessionCreationReturn = IHintManager::SessionCreationReturn{
                .session = mMockSession,
                .pipelineThreadLimitExceeded = false,
        };

        ON_CALL(*mMockIHintManager, createHintSessionWithConfig(_, _, _, _, _))
                .WillByDefault(DoAll(SetArgPointee<3>(hal::SessionConfig({.id = sessionId})),
                                     SetArgPointee<4>(mSessionCreationReturn),
                                     [] { return ScopedAStatus::ok(); }));

        ON_CALL(*mMockIHintManager, setHintSessionThreads(_, _)).WillByDefault([] {
            return ScopedAStatus::ok();
        });
        ON_CALL(*mMockSession, sendHint(_)).WillByDefault([] { return ScopedAStatus::ok(); });
        ON_CALL(*mMockSession, setMode(_, _)).WillByDefault([] { return ScopedAStatus::ok(); });
        ON_CALL(*mMockSession, close()).WillByDefault([] { return ScopedAStatus::ok(); });
        ON_CALL(*mMockSession, updateTargetWorkDuration(_)).WillByDefault([] {
            return ScopedAStatus::ok();
        });
        ON_CALL(*mMockSession, reportActualWorkDuration(_, _)).WillByDefault([] {
            return ScopedAStatus::ok();
        });
        ON_CALL(*mMockSession, reportActualWorkDuration2(_)).WillByDefault([] {
            return ScopedAStatus::ok();
        });
    }

    std::shared_ptr<APerformanceHintSession> createSession(APerformanceHintManager* manager,
                                                           int64_t targetDuration = 56789L,
                                                           bool isHwui = false) {
        prepareSessionMock();
        if (isHwui) {
            return wrapSession(APerformanceHint_createSessionInternal(manager, mTids.data(),
                                                                      mTids.size(), targetDuration,
                                                                      SessionTag::HWUI));
        }
        return wrapSession(APerformanceHint_createSession(manager, mTids.data(), mTids.size(),
                                                          targetDuration));
    }

    std::shared_ptr<APerformanceHintSession> createSessionUsingConfig(
            APerformanceHintManager* manager, std::shared_ptr<ASessionCreationConfig>& config,
            bool isHwui = false) {
        prepareSessionMock();
        APerformanceHintSession* session;
        int out = 0;
        if (isHwui) {
            out = APerformanceHint_createSessionUsingConfigInternal(manager, config.get(), &session,
                                                                    SessionTag::HWUI);
        }

        out = APerformanceHint_createSessionUsingConfig(manager, config.get(), &session);
        EXPECT_EQ(out, 0);

        return wrapSession(session);
    }

    void setFMQEnabled(bool enabled) {
        mUsingFMQ = enabled;
        if (enabled) {
            mMockFMQ = std::make_shared<
                    AidlMessageQueue<hal::ChannelMessage, SynchronizedReadWrite>>(kMockQueueSize,
                                                                                  true);
            mMockFlagQueue =
                    std::make_shared<AidlMessageQueue<int8_t, SynchronizedReadWrite>>(1, true);
            hardware::EventFlag::createEventFlag(mMockFlagQueue->getEventFlagWord(), &mEventFlag);

            ON_CALL(*mMockIHintManager, getSessionChannel(_, _))
                    .WillByDefault([&](ndk::SpAIBinder, std::optional<hal::ChannelConfig>* config) {
                        config->emplace(
                                hal::ChannelConfig{.channelDescriptor = mMockFMQ->dupeDesc(),
                                                   .eventFlagDescriptor =
                                                           mMockFlagQueue->dupeDesc(),
                                                   .readFlagBitmask =
                                                           static_cast<int32_t>(mReadBits),
                                                   .writeFlagBitmask =
                                                           static_cast<int32_t>(mWriteBits)});
                        return ::ndk::ScopedAStatus::ok();
                    });
        }
    }
    uint32_t mReadBits = 0x00000001;
    uint32_t mWriteBits = 0x00000002;
    std::shared_ptr<NiceMock<MockIHintManager>> mMockIHintManager = nullptr;
    std::shared_ptr<NiceMock<MockIHintSession>> mMockSession = nullptr;
    IHintManager::SessionCreationReturn mSessionCreationReturn;
    std::shared_ptr<AidlMessageQueue<hal::ChannelMessage, SynchronizedReadWrite>> mMockFMQ;
    std::shared_ptr<AidlMessageQueue<int8_t, SynchronizedReadWrite>> mMockFlagQueue;
    hardware::EventFlag* mEventFlag;
    int kMockQueueSize = 20;
    bool mUsingFMQ = false;
    std::vector<int> mTids;

    IHintManager::HintManagerClientData mClientData{
            .powerHalVersion = 6,
            .maxGraphicsPipelineThreads = 5,
            .preferredRateNanos = 123L,
            .supportInfo{
                    .usesSessions = true,
                    .boosts = 0,
                    .modes = 0,
                    .sessionHints = -1,
                    .sessionModes = -1,
                    .sessionTags = -1,
            },
    };

    int32_t mMaxLoadHintsPerInterval;
    int64_t mLoadHintInterval;

    template <HalChannelMessageContents::Tag T, class C = HalChannelMessageContents::_at<T>>
    void expectToReadFromFmq(C expected) {
        hal::ChannelMessage readData;
        mMockFMQ->readBlocking(&readData, 1, mReadBits, mWriteBits, 1000000000, mEventFlag);
        C got = static_cast<C>(readData.data.get<T>());
        ASSERT_EQ(got, expected);
    }
};

bool equalsWithoutTimestamp(hal::WorkDuration lhs, hal::WorkDuration rhs) {
    return lhs.workPeriodStartTimestampNanos == rhs.workPeriodStartTimestampNanos &&
            lhs.cpuDurationNanos == rhs.cpuDurationNanos &&
            lhs.gpuDurationNanos == rhs.gpuDurationNanos && lhs.durationNanos == rhs.durationNanos;
}

TEST_F(PerformanceHintTest, TestSession) {
    APerformanceHintManager* manager = createManager();
    auto&& session = createSession(manager);
    ASSERT_TRUE(session);

    int64_t targetDurationNanos = 10;
    EXPECT_CALL(*mMockSession, updateTargetWorkDuration(Eq(targetDurationNanos))).Times(Exactly(1));
    int result = APerformanceHint_updateTargetWorkDuration(session.get(), targetDurationNanos);
    EXPECT_EQ(0, result);

    // subsequent call with same target should be ignored but return no error
    result = APerformanceHint_updateTargetWorkDuration(session.get(), targetDurationNanos);
    EXPECT_EQ(0, result);

    Mock::VerifyAndClearExpectations(mMockSession.get());

    usleep(2); // Sleep for longer than preferredUpdateRateNanos.
    int64_t actualDurationNanos = 20;
    std::vector<int64_t> actualDurations;
    actualDurations.push_back(20);
    EXPECT_CALL(*mMockSession, reportActualWorkDuration2(_)).Times(Exactly(1));
    EXPECT_CALL(*mMockSession, updateTargetWorkDuration(_)).Times(Exactly(1));
    result = APerformanceHint_reportActualWorkDuration(session.get(), actualDurationNanos);
    EXPECT_EQ(0, result);
    result = APerformanceHint_reportActualWorkDuration(session.get(), -1L);
    EXPECT_EQ(EINVAL, result);
    result = APerformanceHint_updateTargetWorkDuration(session.get(), 0);
    EXPECT_EQ(0, result);
    result = APerformanceHint_updateTargetWorkDuration(session.get(), -2);
    EXPECT_EQ(EINVAL, result);
    result = APerformanceHint_reportActualWorkDuration(session.get(), 12L);
    EXPECT_EQ(EINVAL, result);

    SessionHint hintId = SessionHint::CPU_LOAD_RESET;
    EXPECT_CALL(*mMockSession, sendHint(Eq(hintId))).Times(Exactly(1));
    result = APerformanceHint_sendHint(session.get(), hintId);
    EXPECT_EQ(0, result);
    EXPECT_CALL(*mMockSession, sendHint(Eq(SessionHint::CPU_LOAD_UP))).Times(Exactly(1));
    result = APerformanceHint_notifyWorkloadIncrease(session.get(), true, false, "Test hint");
    EXPECT_EQ(0, result);
    EXPECT_CALL(*mMockSession, sendHint(Eq(SessionHint::CPU_LOAD_RESET))).Times(Exactly(1));
    EXPECT_CALL(*mMockSession, sendHint(Eq(SessionHint::GPU_LOAD_RESET))).Times(Exactly(1));
    result = APerformanceHint_notifyWorkloadReset(session.get(), true, true, "Test hint");
    EXPECT_EQ(0, result);
    EXPECT_CALL(*mMockSession, sendHint(Eq(SessionHint::CPU_LOAD_SPIKE))).Times(Exactly(1));
    EXPECT_CALL(*mMockSession, sendHint(Eq(SessionHint::GPU_LOAD_SPIKE))).Times(Exactly(1));
    result = APerformanceHint_notifyWorkloadSpike(session.get(), true, true, "Test hint");
    EXPECT_EQ(0, result);

    EXPECT_DEATH(
            { APerformanceHint_sendHint(session.get(), static_cast<SessionHint>(-1)); },
            "invalid session hint");

    Mock::VerifyAndClearExpectations(mMockSession.get());
    for (int i = 0; i < mMaxLoadHintsPerInterval; ++i) {
        APerformanceHint_sendHint(session.get(), hintId);
    }

    // Expect to get rate limited if we try to send faster than the limiter allows
    EXPECT_CALL(*mMockSession, sendHint(_)).Times(Exactly(0));
    result = APerformanceHint_notifyWorkloadIncrease(session.get(), true, true, "Test hint");
    EXPECT_EQ(result, EBUSY);
    EXPECT_CALL(*mMockSession, sendHint(_)).Times(Exactly(0));
    result = APerformanceHint_notifyWorkloadReset(session.get(), true, true, "Test hint");
    EXPECT_CALL(*mMockSession, close()).Times(Exactly(1));
}

TEST_F(PerformanceHintTest, TestUpdatedSessionCreation) {
    EXPECT_CALL(*mMockIHintManager, createHintSessionWithConfig(_, _, _, _, _)).Times(1);
    APerformanceHintManager* manager = createManager();
    auto&& session = createSession(manager);
    ASSERT_TRUE(session);
}

TEST_F(PerformanceHintTest, TestSessionCreationUsingConfig) {
    EXPECT_CALL(*mMockIHintManager, createHintSessionWithConfig(_, _, _, _, _)).Times(1);
    auto&& config = configFromCreator({.tids = mTids});
    APerformanceHintManager* manager = createManager();
    auto&& session = createSessionUsingConfig(manager, config);
    ASSERT_TRUE(session);
}

TEST_F(PerformanceHintTest, TestHwuiSessionCreation) {
    EXPECT_CALL(*mMockIHintManager, createHintSessionWithConfig(_, hal::SessionTag::HWUI, _, _, _))
            .Times(1);
    APerformanceHintManager* manager = createManager();
    auto&& session = createSession(manager, 56789L, true);
    ASSERT_TRUE(session);
}

TEST_F(PerformanceHintTest, SetThreads) {
    APerformanceHintManager* manager = createManager();

    auto&& session = createSession(manager);
    ASSERT_TRUE(session);

    int32_t emptyTids[2];
    int result = APerformanceHint_setThreads(session.get(), emptyTids, 0);
    EXPECT_EQ(EINVAL, result);

    std::vector<int32_t> newTids;
    newTids.push_back(1);
    newTids.push_back(3);
    EXPECT_CALL(*mMockIHintManager, setHintSessionThreads(_, Eq(newTids))).Times(Exactly(1));
    result = APerformanceHint_setThreads(session.get(), newTids.data(), newTids.size());
    EXPECT_EQ(0, result);

    testing::Mock::VerifyAndClearExpectations(mMockIHintManager.get());
    std::vector<int32_t> invalidTids;
    invalidTids.push_back(4);
    invalidTids.push_back(6);
    EXPECT_CALL(*mMockIHintManager, setHintSessionThreads(_, Eq(invalidTids)))
            .Times(Exactly(1))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCode(EX_SECURITY))));
    result = APerformanceHint_setThreads(session.get(), invalidTids.data(), invalidTids.size());
    EXPECT_EQ(EPERM, result);
}

TEST_F(PerformanceHintTest, SetPowerEfficient) {
    APerformanceHintManager* manager = createManager();
    auto&& session = createSession(manager);
    ASSERT_TRUE(session);

    EXPECT_CALL(*mMockSession, setMode(_, Eq(true))).Times(Exactly(1));
    int result = APerformanceHint_setPreferPowerEfficiency(session.get(), true);
    EXPECT_EQ(0, result);

    EXPECT_CALL(*mMockSession, setMode(_, Eq(false))).Times(Exactly(1));
    result = APerformanceHint_setPreferPowerEfficiency(session.get(), false);
    EXPECT_EQ(0, result);
}

TEST_F(PerformanceHintTest, CreateZeroTargetDurationSession) {
    APerformanceHintManager* manager = createManager();
    auto&& session = createSession(manager, 0);
    ASSERT_TRUE(session);
}

MATCHER_P(WorkDurationEq, expected, "") {
    if (arg.size() != expected.size()) {
        *result_listener << "WorkDuration vectors are different sizes. Expected: "
                         << expected.size() << ", Actual: " << arg.size();
        return false;
    }
    for (int i = 0; i < expected.size(); ++i) {
        hal::WorkDuration expectedWorkDuration = expected[i];
        hal::WorkDuration actualWorkDuration = arg[i];
        if (!equalsWithoutTimestamp(expectedWorkDuration, actualWorkDuration)) {
            *result_listener << "WorkDuration at [" << i << "] is different: "
                             << "Expected: " << expectedWorkDuration.toString()
                             << ", Actual: " << actualWorkDuration.toString();
            return false;
        }
    }
    return true;
}

TEST_F(PerformanceHintTest, TestAPerformanceHint_reportActualWorkDuration2) {
    APerformanceHintManager* manager = createManager();
    auto&& session = createSession(manager);
    ASSERT_TRUE(session);

    int64_t targetDurationNanos = 10;
    EXPECT_CALL(*mMockSession, updateTargetWorkDuration(Eq(targetDurationNanos))).Times(Exactly(1));
    int result = APerformanceHint_updateTargetWorkDuration(session.get(), targetDurationNanos);
    EXPECT_EQ(0, result);

    usleep(2); // Sleep for longer than preferredUpdateRateNanos.
    struct TestPair {
        hal::WorkDuration duration;
        int expectedResult;
    };
    std::vector<TestPair> testPairs{
            {{1, 20, 1, 13, 8}, OK},       {{1, -20, 1, 13, 8}, EINVAL},
            {{1, 20, -1, 13, 8}, EINVAL},  {{1, -20, 1, -13, 8}, EINVAL},
            {{1, -20, 1, 13, -8}, EINVAL},
    };
    for (auto&& pair : testPairs) {
        std::vector<hal::WorkDuration> actualWorkDurations;
        actualWorkDurations.push_back(pair.duration);

        EXPECT_CALL(*mMockSession, reportActualWorkDuration2(WorkDurationEq(actualWorkDurations)))
                .Times(Exactly(pair.expectedResult == OK));
        result = APerformanceHint_reportActualWorkDuration2(session.get(),
                                                            reinterpret_cast<AWorkDuration*>(
                                                                    &pair.duration));
        EXPECT_EQ(pair.expectedResult, result);
    }

    EXPECT_CALL(*mMockSession, close()).Times(Exactly(1));
}

TEST_F(PerformanceHintTest, TestAWorkDuration) {
    // AWorkDuration* aWorkDuration = AWorkDuration_create();
    auto&& aWorkDuration = wrapWorkDuration(AWorkDuration_create());
    ASSERT_NE(aWorkDuration, nullptr);

    AWorkDuration_setWorkPeriodStartTimestampNanos(aWorkDuration.get(), 1);
    AWorkDuration_setActualTotalDurationNanos(aWorkDuration.get(), 20);
    AWorkDuration_setActualCpuDurationNanos(aWorkDuration.get(), 13);
    AWorkDuration_setActualGpuDurationNanos(aWorkDuration.get(), 8);
}

TEST_F(PerformanceHintTest, TestCreateUsingFMQ) {
    setFMQEnabled(true);
    APerformanceHintManager* manager = createManager();
    auto&& session = createSession(manager);
    ASSERT_TRUE(session);
}

TEST_F(PerformanceHintTest, TestUpdateTargetWorkDurationUsingFMQ) {
    setFMQEnabled(true);
    APerformanceHintManager* manager = createManager();
    auto&& session = createSession(manager);
    APerformanceHint_updateTargetWorkDuration(session.get(), 456);
    expectToReadFromFmq<HalChannelMessageContents::Tag::targetDuration>(456);
}

TEST_F(PerformanceHintTest, TestSendHintUsingFMQ) {
    setFMQEnabled(true);
    APerformanceHintManager* manager = createManager();
    auto&& session = createSession(manager);
    APerformanceHint_sendHint(session.get(), SessionHint::CPU_LOAD_UP);
    expectToReadFromFmq<HalChannelMessageContents::Tag::hint>(hal::SessionHint::CPU_LOAD_UP);
}

TEST_F(PerformanceHintTest, TestReportActualUsingFMQ) {
    setFMQEnabled(true);
    APerformanceHintManager* manager = createManager();
    auto&& session = createSession(manager);
    hal::WorkDuration duration{.timeStampNanos = 3,
                               .durationNanos = 999999,
                               .workPeriodStartTimestampNanos = 1,
                               .cpuDurationNanos = 999999,
                               .gpuDurationNanos = 999999};

    hal::WorkDurationFixedV1 durationExpected{
            .durationNanos = duration.durationNanos,
            .workPeriodStartTimestampNanos = duration.workPeriodStartTimestampNanos,
            .cpuDurationNanos = duration.cpuDurationNanos,
            .gpuDurationNanos = duration.gpuDurationNanos,
    };

    APerformanceHint_reportActualWorkDuration2(session.get(),
                                               reinterpret_cast<AWorkDuration*>(&duration));
    expectToReadFromFmq<HalChannelMessageContents::Tag::workDuration>(durationExpected);
}

TEST_F(PerformanceHintTest, TestASessionCreationConfig) {
    auto&& config = configFromCreator({
            .tids = mTids,
            .targetDuration = 20,
            .powerEfficient = true,
            .graphicsPipeline = true,
    });

    APerformanceHintManager* manager = createManager();
    auto&& session = createSessionUsingConfig(manager, config);

    ASSERT_NE(session, nullptr);
    ASSERT_NE(config, nullptr);
}

TEST_F(PerformanceHintTest, TestSupportObject) {
    // Disable GPU and Power Efficiency support to test partial enabling
    mClientData.supportInfo.sessionModes &= ~(1 << (int)hal::SessionMode::AUTO_GPU);
    mClientData.supportInfo.sessionHints &= ~(1 << (int)hal::SessionHint::GPU_LOAD_UP);
    mClientData.supportInfo.sessionHints &= ~(1 << (int)hal::SessionHint::POWER_EFFICIENCY);

    APerformanceHintManager* manager = createManager();

    union {
        int expectedSupportInt;
        SupportHelper expectedSupport;
    };

    union {
        int actualSupportInt;
        SupportHelper actualSupport;
    };

    expectedSupport = getFullySupportedSupportHelper();
    actualSupport = getSupportHelper();

    expectedSupport.autoGpu = false;

    EXPECT_EQ(expectedSupportInt, actualSupportInt);
}

TEST_F(PerformanceHintTest, TestCreatingAutoSession) {
    // Disable GPU capability for testing
    mClientData.supportInfo.sessionModes &= ~(1 << (int)hal::SessionMode::AUTO_GPU);
    APerformanceHintManager* manager = createManager();

    auto&& invalidConfig = configFromCreator({
            .tids = mTids,
            .targetDuration = 20,
            .graphicsPipeline = false,
            .autoCpu = true,
            .autoGpu = true,
    });

    EXPECT_DEATH({ createSessionUsingConfig(manager, invalidConfig); }, "");

    auto&& unsupportedConfig = configFromCreator({
            .tids = mTids,
            .targetDuration = 20,
            .graphicsPipeline = true,
            .autoCpu = true,
            .autoGpu = true,
    });

    APerformanceHintSession* unsupportedSession = nullptr;

    // Creating a session with auto timing but no graphics pipeline should fail
    int out = APerformanceHint_createSessionUsingConfig(manager, unsupportedConfig.get(),
                                                        &unsupportedSession);
    EXPECT_EQ(out, ENOTSUP);
    EXPECT_EQ(wrapSession(unsupportedSession), nullptr);

    auto&& validConfig = configFromCreator({
            .tids = mTids,
            .targetDuration = 20,
            .graphicsPipeline = true,
            .autoCpu = true,
            .autoGpu = false,
    });

    auto&& validSession = createSessionUsingConfig(manager, validConfig);
    EXPECT_NE(validSession, nullptr);
}
