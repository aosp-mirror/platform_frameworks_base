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

#include <android/os/IHintManager.h>
#include <android/os/IHintSession.h>
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
                (const ::android::sp<::android::IBinder>& token, const ::std::vector<int32_t>& tids,
                 int64_t durationNanos, ::android::sp<::android::os::IHintSession>* _aidl_return),
                (override));
    MOCK_METHOD(Status, getHintSessionPreferredRate, (int64_t * _aidl_return), (override));
    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
};

class MockIHintSession : public IHintSession {
public:
    MOCK_METHOD(Status, updateTargetWorkDuration, (int64_t targetDurationNanos), (override));
    MOCK_METHOD(Status, reportActualWorkDuration,
                (const ::std::vector<int64_t>& actualDurationNanos,
                 const ::std::vector<int64_t>& timeStampNanos),
                (override));
    MOCK_METHOD(Status, close, (), (override));
    MOCK_METHOD(IBinder*, onAsBinder, (), (override));
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
    result = APerformanceHint_reportActualWorkDuration(session, actualDurationNanos);
    EXPECT_EQ(0, result);

    result = APerformanceHint_updateTargetWorkDuration(session, -1L);
    EXPECT_EQ(EINVAL, result);
    result = APerformanceHint_reportActualWorkDuration(session, -1L);
    EXPECT_EQ(EINVAL, result);

    EXPECT_CALL(*iSession, close()).Times(Exactly(1));
    APerformanceHint_closeSession(session);
}
