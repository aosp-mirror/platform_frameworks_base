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

#include <android/WorkDuration.h>
#include <android/os/IHintManager.h>
#include <android/os/IHintSession.h>
#include <android/performance_hint.h>
#include <binder/IBinder.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <performance_hint_private.h>

#include <memory>
#include <vector>

using android::binder::Status;
using android::os::IHintManager;
using android::os::IHintSession;

using namespace android;
using namespace testing;

class MockIHintManager : public IHintManager {
public:
    MOCK_METHOD(Status, createHintSession,
                (const sp<IBinder>& token, const ::std::vector<int32_t>& tids,
                 int64_t durationNanos, ::android::sp<IHintSession>* _aidl_return),
                (override));
    MOCK_METHOD(Status, getHintSessionPreferredRate, (int64_t * _aidl_return), (override));
    MOCK_METHOD(Status, setHintSessionThreads,
                (const sp<IHintSession>& hintSession, const ::std::vector<int32_t>& tids),
                (override));
    MOCK_METHOD(Status, getHintSessionThreadIds,
                (const sp<IHintSession>& hintSession, ::std::vector<int32_t>* tids), (override));
    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
};

class MockIHintSession : public IHintSession {
public:
    MOCK_METHOD(Status, updateTargetWorkDuration, (int64_t targetDurationNanos), (override));
    MOCK_METHOD(Status, reportActualWorkDuration,
                (const ::std::vector<int64_t>& actualDurationNanos,
                 const ::std::vector<int64_t>& timeStampNanos),
                (override));
    MOCK_METHOD(Status, sendHint, (int32_t hint), (override));
    MOCK_METHOD(Status, setMode, (int32_t mode, bool enabled), (override));
    MOCK_METHOD(Status, close, (), (override));
    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
    MOCK_METHOD(Status, reportActualWorkDuration2,
                (const ::std::vector<android::os::WorkDuration>& workDurations), (override));
};

class PerformanceHintTest : public Test {
public:
    void SetUp() override {
        mMockIHintManager = new StrictMock<MockIHintManager>();
        APerformanceHint_setIHintManagerForTesting(mMockIHintManager);
    }

    void TearDown() override {
        mMockIHintManager = nullptr;
        // Destroys MockIHintManager.
        APerformanceHint_setIHintManagerForTesting(nullptr);
    }

    APerformanceHintManager* createManager() {
        EXPECT_CALL(*mMockIHintManager, getHintSessionPreferredRate(_))
                .Times(Exactly(1))
                .WillRepeatedly(DoAll(SetArgPointee<0>(123L), Return(Status())));
        return APerformanceHint_getManager();
    }

    StrictMock<MockIHintManager>* mMockIHintManager = nullptr;
};

TEST_F(PerformanceHintTest, TestGetPreferredUpdateRateNanos) {
    APerformanceHintManager* manager = createManager();
    int64_t preferredUpdateRateNanos = APerformanceHint_getPreferredUpdateRateNanos(manager);
    EXPECT_EQ(123L, preferredUpdateRateNanos);
}

TEST_F(PerformanceHintTest, TestSession) {
    APerformanceHintManager* manager = createManager();

    std::vector<int32_t> tids;
    tids.push_back(1);
    tids.push_back(2);
    int64_t targetDuration = 56789L;

    StrictMock<MockIHintSession>* iSession = new StrictMock<MockIHintSession>();
    sp<IHintSession> session_sp(iSession);

    EXPECT_CALL(*mMockIHintManager, createHintSession(_, Eq(tids), Eq(targetDuration), _))
            .Times(Exactly(1))
            .WillRepeatedly(DoAll(SetArgPointee<3>(std::move(session_sp)), Return(Status())));

    APerformanceHintSession* session =
            APerformanceHint_createSession(manager, tids.data(), tids.size(), targetDuration);
    ASSERT_TRUE(session);

    int64_t targetDurationNanos = 10;
    EXPECT_CALL(*iSession, updateTargetWorkDuration(Eq(targetDurationNanos))).Times(Exactly(1));
    int result = APerformanceHint_updateTargetWorkDuration(session, targetDurationNanos);
    EXPECT_EQ(0, result);

    usleep(2); // Sleep for longer than preferredUpdateRateNanos.
    int64_t actualDurationNanos = 20;
    std::vector<int64_t> actualDurations;
    actualDurations.push_back(20);
    EXPECT_CALL(*iSession, reportActualWorkDuration(Eq(actualDurations), _)).Times(Exactly(1));
    EXPECT_CALL(*iSession, reportActualWorkDuration2(_)).Times(Exactly(1));
    result = APerformanceHint_reportActualWorkDuration(session, actualDurationNanos);
    EXPECT_EQ(0, result);

    result = APerformanceHint_updateTargetWorkDuration(session, -1L);
    EXPECT_EQ(EINVAL, result);
    result = APerformanceHint_reportActualWorkDuration(session, -1L);
    EXPECT_EQ(EINVAL, result);

    SessionHint hintId = SessionHint::CPU_LOAD_RESET;
    EXPECT_CALL(*iSession, sendHint(Eq(hintId))).Times(Exactly(1));
    result = APerformanceHint_sendHint(session, hintId);
    EXPECT_EQ(0, result);
    usleep(110000); // Sleep for longer than the update timeout.
    EXPECT_CALL(*iSession, sendHint(Eq(hintId))).Times(Exactly(1));
    result = APerformanceHint_sendHint(session, hintId);
    EXPECT_EQ(0, result);
    // Expect to get rate limited if we try to send faster than the limiter allows
    EXPECT_CALL(*iSession, sendHint(Eq(hintId))).Times(Exactly(0));
    result = APerformanceHint_sendHint(session, hintId);
    EXPECT_EQ(0, result);

    result = APerformanceHint_sendHint(session, static_cast<SessionHint>(-1));
    EXPECT_EQ(EINVAL, result);

    EXPECT_CALL(*iSession, close()).Times(Exactly(1));
    APerformanceHint_closeSession(session);
}

TEST_F(PerformanceHintTest, SetThreads) {
    APerformanceHintManager* manager = createManager();

    std::vector<int32_t> tids;
    tids.push_back(1);
    tids.push_back(2);
    int64_t targetDuration = 56789L;

    StrictMock<MockIHintSession>* iSession = new StrictMock<MockIHintSession>();
    sp<IHintSession> session_sp(iSession);

    EXPECT_CALL(*mMockIHintManager, createHintSession(_, Eq(tids), Eq(targetDuration), _))
            .Times(Exactly(1))
            .WillRepeatedly(DoAll(SetArgPointee<3>(std::move(session_sp)), Return(Status())));

    APerformanceHintSession* session =
            APerformanceHint_createSession(manager, tids.data(), tids.size(), targetDuration);
    ASSERT_TRUE(session);

    std::vector<int32_t> emptyTids;
    int result = APerformanceHint_setThreads(session, emptyTids.data(), emptyTids.size());
    EXPECT_EQ(EINVAL, result);

    std::vector<int32_t> newTids;
    newTids.push_back(1);
    newTids.push_back(3);
    EXPECT_CALL(*mMockIHintManager, setHintSessionThreads(_, Eq(newTids)))
            .Times(Exactly(1))
            .WillOnce(Return(Status()));
    result = APerformanceHint_setThreads(session, newTids.data(), newTids.size());
    EXPECT_EQ(0, result);

    testing::Mock::VerifyAndClearExpectations(mMockIHintManager);
    std::vector<int32_t> invalidTids;
    auto status = Status::fromExceptionCode(binder::Status::Exception::EX_SECURITY);
    invalidTids.push_back(4);
    invalidTids.push_back(6);
    EXPECT_CALL(*mMockIHintManager, setHintSessionThreads(_, Eq(invalidTids)))
            .Times(Exactly(1))
            .WillOnce(Return(status));
    result = APerformanceHint_setThreads(session, invalidTids.data(), invalidTids.size());
    EXPECT_EQ(EPERM, result);
}

TEST_F(PerformanceHintTest, SetPowerEfficient) {
    APerformanceHintManager* manager = createManager();

    std::vector<int32_t> tids;
    tids.push_back(1);
    tids.push_back(2);
    int64_t targetDuration = 56789L;

    StrictMock<MockIHintSession>* iSession = new StrictMock<MockIHintSession>();
    sp<IHintSession> session_sp(iSession);

    EXPECT_CALL(*mMockIHintManager, createHintSession(_, Eq(tids), Eq(targetDuration), _))
            .Times(Exactly(1))
            .WillRepeatedly(DoAll(SetArgPointee<3>(std::move(session_sp)), Return(Status())));

    APerformanceHintSession* session =
            APerformanceHint_createSession(manager, tids.data(), tids.size(), targetDuration);
    ASSERT_TRUE(session);

    EXPECT_CALL(*iSession, setMode(_, Eq(true))).Times(Exactly(1));
    int result = APerformanceHint_setPreferPowerEfficiency(session, true);
    EXPECT_EQ(0, result);

    EXPECT_CALL(*iSession, setMode(_, Eq(false))).Times(Exactly(1));
    result = APerformanceHint_setPreferPowerEfficiency(session, false);
    EXPECT_EQ(0, result);
}

TEST_F(PerformanceHintTest, CreateZeroTargetDurationSession) {
    APerformanceHintManager* manager = createManager();

    std::vector<int32_t> tids;
    tids.push_back(1);
    tids.push_back(2);
    int64_t targetDuration = 0;

    StrictMock<MockIHintSession>* iSession = new StrictMock<MockIHintSession>();
    sp<IHintSession> session_sp(iSession);

    EXPECT_CALL(*mMockIHintManager, createHintSession(_, Eq(tids), Eq(targetDuration), _))
            .Times(Exactly(1))
            .WillRepeatedly(DoAll(SetArgPointee<3>(std::move(session_sp)), Return(Status())));

    APerformanceHintSession* session =
            APerformanceHint_createSession(manager, tids.data(), tids.size(), targetDuration);
    ASSERT_TRUE(session);
}

MATCHER_P(WorkDurationEq, expected, "") {
    if (arg.size() != expected.size()) {
        *result_listener << "WorkDuration vectors are different sizes. Expected: "
                         << expected.size() << ", Actual: " << arg.size();
        return false;
    }
    for (int i = 0; i < expected.size(); ++i) {
        android::os::WorkDuration expectedWorkDuration = expected[i];
        android::os::WorkDuration actualWorkDuration = arg[i];
        if (!expectedWorkDuration.equalsWithoutTimestamp(actualWorkDuration)) {
            *result_listener << "WorkDuration at [" << i << "] is different: "
                             << "Expected: " << expectedWorkDuration
                             << ", Actual: " << actualWorkDuration;
            return false;
        }
    }
    return true;
}

TEST_F(PerformanceHintTest, TestAPerformanceHint_reportActualWorkDuration2) {
    APerformanceHintManager* manager = createManager();

    std::vector<int32_t> tids;
    tids.push_back(1);
    tids.push_back(2);
    int64_t targetDuration = 56789L;

    StrictMock<MockIHintSession>* iSession = new StrictMock<MockIHintSession>();
    sp<IHintSession> session_sp(iSession);

    EXPECT_CALL(*mMockIHintManager, createHintSession(_, Eq(tids), Eq(targetDuration), _))
            .Times(Exactly(1))
            .WillRepeatedly(DoAll(SetArgPointee<3>(std::move(session_sp)), Return(Status())));

    APerformanceHintSession* session =
            APerformanceHint_createSession(manager, tids.data(), tids.size(), targetDuration);
    ASSERT_TRUE(session);

    int64_t targetDurationNanos = 10;
    EXPECT_CALL(*iSession, updateTargetWorkDuration(Eq(targetDurationNanos))).Times(Exactly(1));
    int result = APerformanceHint_updateTargetWorkDuration(session, targetDurationNanos);
    EXPECT_EQ(0, result);

    usleep(2); // Sleep for longer than preferredUpdateRateNanos.
    {
        std::vector<android::os::WorkDuration> actualWorkDurations;
        android::os::WorkDuration workDuration(1, 20, 13, 8);
        actualWorkDurations.push_back(workDuration);

        EXPECT_CALL(*iSession, reportActualWorkDuration2(WorkDurationEq(actualWorkDurations)))
                .Times(Exactly(1));
        result = APerformanceHint_reportActualWorkDuration2(session,
                                                            static_cast<AWorkDuration*>(
                                                                    &workDuration));
        EXPECT_EQ(0, result);
    }

    {
        std::vector<android::os::WorkDuration> actualWorkDurations;
        android::os::WorkDuration workDuration(-1, 20, 13, 8);
        actualWorkDurations.push_back(workDuration);

        EXPECT_CALL(*iSession, reportActualWorkDuration2(WorkDurationEq(actualWorkDurations)))
                .Times(Exactly(1));
        result = APerformanceHint_reportActualWorkDuration2(session,
                                                            static_cast<AWorkDuration*>(
                                                                    &workDuration));
        EXPECT_EQ(22, result);
    }
    {
        std::vector<android::os::WorkDuration> actualWorkDurations;
        android::os::WorkDuration workDuration(1, -20, 13, 8);
        actualWorkDurations.push_back(workDuration);

        EXPECT_CALL(*iSession, reportActualWorkDuration2(WorkDurationEq(actualWorkDurations)))
                .Times(Exactly(1));
        result = APerformanceHint_reportActualWorkDuration2(session,
                                                            static_cast<AWorkDuration*>(
                                                                    &workDuration));
        EXPECT_EQ(22, result);
    }
    {
        std::vector<android::os::WorkDuration> actualWorkDurations;
        android::os::WorkDuration workDuration(1, 20, -13, 8);
        actualWorkDurations.push_back(workDuration);

        EXPECT_CALL(*iSession, reportActualWorkDuration2(WorkDurationEq(actualWorkDurations)))
                .Times(Exactly(1));
        result = APerformanceHint_reportActualWorkDuration2(session,
                                                            static_cast<AWorkDuration*>(
                                                                    &workDuration));
        EXPECT_EQ(EINVAL, result);
    }
    {
        std::vector<android::os::WorkDuration> actualWorkDurations;
        android::os::WorkDuration workDuration(1, 20, 13, -8);
        actualWorkDurations.push_back(workDuration);

        EXPECT_CALL(*iSession, reportActualWorkDuration2(WorkDurationEq(actualWorkDurations)))
                .Times(Exactly(1));
        result = APerformanceHint_reportActualWorkDuration2(session,
                                                            static_cast<AWorkDuration*>(
                                                                    &workDuration));
        EXPECT_EQ(EINVAL, result);
    }

    EXPECT_CALL(*iSession, close()).Times(Exactly(1));
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
