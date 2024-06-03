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
#include <aidl/android/hardware/power/SessionTag.h>
#include <aidl/android/hardware/power/WorkDuration.h>
#include <aidl/android/os/IHintManager.h>
#include <android/binder_manager.h>
#include <android/binder_status.h>
#include <android/performance_hint.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <performance_hint_private.h>

#include <memory>
#include <vector>

namespace hal = aidl::android::hardware::power;
using aidl::android::os::IHintManager;
using aidl::android::os::IHintSession;
using ndk::ScopedAStatus;
using ndk::SpAIBinder;

using namespace android;
using namespace testing;

class MockIHintManager : public IHintManager {
public:
    MOCK_METHOD(ScopedAStatus, createHintSessionWithConfig,
                (const SpAIBinder& token, const ::std::vector<int32_t>& tids, int64_t durationNanos,
                 hal::SessionTag tag, std::optional<hal::SessionConfig>* config,
                 std::shared_ptr<IHintSession>* _aidl_return),
                (override));
    MOCK_METHOD(ScopedAStatus, getHintSessionPreferredRate, (int64_t * _aidl_return), (override));
    MOCK_METHOD(ScopedAStatus, setHintSessionThreads,
                (const std::shared_ptr<IHintSession>& hintSession,
                 const ::std::vector<int32_t>& tids),
                (override));
    MOCK_METHOD(ScopedAStatus, getHintSessionThreadIds,
                (const std::shared_ptr<IHintSession>& hintSession, ::std::vector<int32_t>* tids),
                (override));
    MOCK_METHOD(ScopedAStatus, getSessionChannel,
                (const ::ndk::SpAIBinder& in_token, hal::ChannelConfig* _aidl_return), (override));
    MOCK_METHOD(ScopedAStatus, closeSessionChannel, (), (override));
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
    MOCK_METHOD(SpAIBinder, asBinder, (), (override));
    MOCK_METHOD(bool, isRemote, (), (override));
};

class PerformanceHintTest : public Test {
public:
    void SetUp() override {
        mMockIHintManager = ndk::SharedRefBase::make<NiceMock<MockIHintManager>>();
        APerformanceHint_setIHintManagerForTesting(&mMockIHintManager);
    }

    void TearDown() override {
        mMockIHintManager = nullptr;
        // Destroys MockIHintManager.
        APerformanceHint_setIHintManagerForTesting(nullptr);
    }

    APerformanceHintManager* createManager() {
        ON_CALL(*mMockIHintManager, getHintSessionPreferredRate(_))
                .WillByDefault(DoAll(SetArgPointee<0>(123L), [] { return ScopedAStatus::ok(); }));
        return APerformanceHint_getManager();
    }
    APerformanceHintSession* createSession(APerformanceHintManager* manager,
                                           int64_t targetDuration = 56789L, bool isHwui = false) {
        mMockSession = ndk::SharedRefBase::make<NiceMock<MockIHintSession>>();
        int64_t sessionId = 123;
        std::vector<int32_t> tids;
        tids.push_back(1);
        tids.push_back(2);

        ON_CALL(*mMockIHintManager,
                createHintSessionWithConfig(_, Eq(tids), Eq(targetDuration), _, _, _))
                .WillByDefault(DoAll(SetArgPointee<4>(std::make_optional<hal::SessionConfig>(
                                             {.id = sessionId})),
                                     SetArgPointee<5>(std::shared_ptr<IHintSession>(mMockSession)),
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
        if (isHwui) {
            return APerformanceHint_createSessionInternal(manager, tids.data(), tids.size(),
                                                          targetDuration, SessionTag::HWUI);
        }
        return APerformanceHint_createSession(manager, tids.data(), tids.size(), targetDuration);
    }

    std::shared_ptr<NiceMock<MockIHintManager>> mMockIHintManager = nullptr;
    std::shared_ptr<NiceMock<MockIHintSession>> mMockSession = nullptr;
};

bool equalsWithoutTimestamp(hal::WorkDuration lhs, hal::WorkDuration rhs) {
    return lhs.workPeriodStartTimestampNanos == rhs.workPeriodStartTimestampNanos &&
            lhs.cpuDurationNanos == rhs.cpuDurationNanos &&
            lhs.gpuDurationNanos == rhs.gpuDurationNanos && lhs.durationNanos == rhs.durationNanos;
}

TEST_F(PerformanceHintTest, TestGetPreferredUpdateRateNanos) {
    APerformanceHintManager* manager = createManager();
    int64_t preferredUpdateRateNanos = APerformanceHint_getPreferredUpdateRateNanos(manager);
    EXPECT_EQ(123L, preferredUpdateRateNanos);
}

TEST_F(PerformanceHintTest, TestSession) {
    APerformanceHintManager* manager = createManager();
    APerformanceHintSession* session = createSession(manager);
    ASSERT_TRUE(session);

    int64_t targetDurationNanos = 10;
    EXPECT_CALL(*mMockSession, updateTargetWorkDuration(Eq(targetDurationNanos))).Times(Exactly(1));
    int result = APerformanceHint_updateTargetWorkDuration(session, targetDurationNanos);
    EXPECT_EQ(0, result);

    usleep(2); // Sleep for longer than preferredUpdateRateNanos.
    int64_t actualDurationNanos = 20;
    std::vector<int64_t> actualDurations;
    actualDurations.push_back(20);
    EXPECT_CALL(*mMockSession, reportActualWorkDuration2(_)).Times(Exactly(1));
    result = APerformanceHint_reportActualWorkDuration(session, actualDurationNanos);
    EXPECT_EQ(0, result);

    result = APerformanceHint_updateTargetWorkDuration(session, -1L);
    EXPECT_EQ(EINVAL, result);
    result = APerformanceHint_reportActualWorkDuration(session, -1L);
    EXPECT_EQ(EINVAL, result);

    SessionHint hintId = SessionHint::CPU_LOAD_RESET;
    EXPECT_CALL(*mMockSession, sendHint(Eq(hintId))).Times(Exactly(1));
    result = APerformanceHint_sendHint(session, hintId);
    EXPECT_EQ(0, result);
    usleep(110000); // Sleep for longer than the update timeout.
    EXPECT_CALL(*mMockSession, sendHint(Eq(hintId))).Times(Exactly(1));
    result = APerformanceHint_sendHint(session, hintId);
    EXPECT_EQ(0, result);
    // Expect to get rate limited if we try to send faster than the limiter allows
    EXPECT_CALL(*mMockSession, sendHint(Eq(hintId))).Times(Exactly(0));
    result = APerformanceHint_sendHint(session, hintId);
    EXPECT_EQ(0, result);

    result = APerformanceHint_sendHint(session, static_cast<SessionHint>(-1));
    EXPECT_EQ(EINVAL, result);

    EXPECT_CALL(*mMockSession, close()).Times(Exactly(1));
    APerformanceHint_closeSession(session);
}

TEST_F(PerformanceHintTest, TestUpdatedSessionCreation) {
    EXPECT_CALL(*mMockIHintManager, createHintSessionWithConfig(_, _, _, _, _, _)).Times(1);
    APerformanceHintManager* manager = createManager();
    APerformanceHintSession* session = createSession(manager);
    ASSERT_TRUE(session);
    APerformanceHint_closeSession(session);
}

TEST_F(PerformanceHintTest, TestHwuiSessionCreation) {
    EXPECT_CALL(*mMockIHintManager,
                createHintSessionWithConfig(_, _, _, hal::SessionTag::HWUI, _, _))
            .Times(1);
    APerformanceHintManager* manager = createManager();
    APerformanceHintSession* session = createSession(manager, 56789L, true);
    ASSERT_TRUE(session);
    APerformanceHint_closeSession(session);
}

TEST_F(PerformanceHintTest, SetThreads) {
    APerformanceHintManager* manager = createManager();

    APerformanceHintSession* session = createSession(manager);
    ASSERT_TRUE(session);

    int32_t emptyTids[2];
    int result = APerformanceHint_setThreads(session, emptyTids, 0);
    EXPECT_EQ(EINVAL, result);

    std::vector<int32_t> newTids;
    newTids.push_back(1);
    newTids.push_back(3);
    EXPECT_CALL(*mMockIHintManager, setHintSessionThreads(_, Eq(newTids))).Times(Exactly(1));
    result = APerformanceHint_setThreads(session, newTids.data(), newTids.size());
    EXPECT_EQ(0, result);

    testing::Mock::VerifyAndClearExpectations(mMockIHintManager.get());
    std::vector<int32_t> invalidTids;
    invalidTids.push_back(4);
    invalidTids.push_back(6);
    EXPECT_CALL(*mMockIHintManager, setHintSessionThreads(_, Eq(invalidTids)))
            .Times(Exactly(1))
            .WillOnce(Return(ByMove(ScopedAStatus::fromExceptionCode(EX_SECURITY))));
    result = APerformanceHint_setThreads(session, invalidTids.data(), invalidTids.size());
    EXPECT_EQ(EPERM, result);
}

TEST_F(PerformanceHintTest, SetPowerEfficient) {
    APerformanceHintManager* manager = createManager();
    APerformanceHintSession* session = createSession(manager);
    ASSERT_TRUE(session);

    EXPECT_CALL(*mMockSession, setMode(_, Eq(true))).Times(Exactly(1));
    int result = APerformanceHint_setPreferPowerEfficiency(session, true);
    EXPECT_EQ(0, result);

    EXPECT_CALL(*mMockSession, setMode(_, Eq(false))).Times(Exactly(1));
    result = APerformanceHint_setPreferPowerEfficiency(session, false);
    EXPECT_EQ(0, result);
}

TEST_F(PerformanceHintTest, CreateZeroTargetDurationSession) {
    APerformanceHintManager* manager = createManager();
    APerformanceHintSession* session = createSession(manager, 0);
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
    APerformanceHintSession* session = createSession(manager);
    ASSERT_TRUE(session);

    int64_t targetDurationNanos = 10;
    EXPECT_CALL(*mMockSession, updateTargetWorkDuration(Eq(targetDurationNanos))).Times(Exactly(1));
    int result = APerformanceHint_updateTargetWorkDuration(session, targetDurationNanos);
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
                .Times(Exactly(1));
        result = APerformanceHint_reportActualWorkDuration2(session,
                                                            reinterpret_cast<AWorkDuration*>(
                                                                    &pair.duration));
        EXPECT_EQ(pair.expectedResult, result);
    }

    EXPECT_CALL(*mMockSession, close()).Times(Exactly(1));
    APerformanceHint_closeSession(session);
}

TEST_F(PerformanceHintTest, TestAWorkDuration) {
    AWorkDuration* aWorkDuration = AWorkDuration_create();
    ASSERT_NE(aWorkDuration, nullptr);

    AWorkDuration_setWorkPeriodStartTimestampNanos(aWorkDuration, 1);
    AWorkDuration_setActualTotalDurationNanos(aWorkDuration, 20);
    AWorkDuration_setActualCpuDurationNanos(aWorkDuration, 13);
    AWorkDuration_setActualGpuDurationNanos(aWorkDuration, 8);
    AWorkDuration_release(aWorkDuration);
}
