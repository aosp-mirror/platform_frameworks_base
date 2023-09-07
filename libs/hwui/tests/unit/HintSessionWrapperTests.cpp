/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <private/performance_hint_private.h>
#include <renderthread/HintSessionWrapper.h>
#include <utils/Log.h>

#include <chrono>

#include "Properties.h"

using namespace testing;
using namespace std::chrono_literals;

APerformanceHintManager* managerPtr = reinterpret_cast<APerformanceHintManager*>(123);
APerformanceHintSession* sessionPtr = reinterpret_cast<APerformanceHintSession*>(456);
int uiThreadId = 1;
int renderThreadId = 2;

namespace android::uirenderer::renderthread {

class HintSessionWrapperTests : public testing::Test {
public:
    void SetUp() override;
    void TearDown() override;

protected:
    std::shared_ptr<HintSessionWrapper> mWrapper;

    class MockHintSessionBinding : public HintSessionWrapper::HintSessionBinding {
    public:
        void init() override;

        MOCK_METHOD(APerformanceHintManager*, fakeGetManager, ());
        MOCK_METHOD(APerformanceHintSession*, fakeCreateSession,
                    (APerformanceHintManager*, const int32_t*, size_t, int64_t));
        MOCK_METHOD(void, fakeCloseSession, (APerformanceHintSession*));
        MOCK_METHOD(void, fakeUpdateTargetWorkDuration, (APerformanceHintSession*, int64_t));
        MOCK_METHOD(void, fakeReportActualWorkDuration, (APerformanceHintSession*, int64_t));
        MOCK_METHOD(void, fakeSendHint, (APerformanceHintSession*, int32_t));
    };

    // Must be static so it can have function pointers we can point to with static methods
    static std::shared_ptr<MockHintSessionBinding> sMockBinding;

    // Must be static so we can point to them as normal fn pointers with HintSessionBinding
    static APerformanceHintManager* stubGetManager() { return sMockBinding->fakeGetManager(); };
    static APerformanceHintSession* stubCreateSession(APerformanceHintManager* manager,
                                                      const int32_t* ids, size_t idsSize,
                                                      int64_t initialTarget) {
        return sMockBinding->fakeCreateSession(manager, ids, idsSize, initialTarget);
    }
    static APerformanceHintSession* stubSlowCreateSession(APerformanceHintManager* manager,
                                                          const int32_t* ids, size_t idsSize,
                                                          int64_t initialTarget) {
        std::this_thread::sleep_for(50ms);
        return sMockBinding->fakeCreateSession(manager, ids, idsSize, initialTarget);
    }
    static void stubCloseSession(APerformanceHintSession* session) {
        sMockBinding->fakeCloseSession(session);
    };
    static void stubUpdateTargetWorkDuration(APerformanceHintSession* session,
                                             int64_t workDuration) {
        sMockBinding->fakeUpdateTargetWorkDuration(session, workDuration);
    }
    static void stubReportActualWorkDuration(APerformanceHintSession* session,
                                             int64_t workDuration) {
        sMockBinding->fakeReportActualWorkDuration(session, workDuration);
    }
    static void stubSendHint(APerformanceHintSession* session, int32_t hintId) {
        sMockBinding->fakeSendHint(session, hintId);
    };
    void waitForWrapperReady() { mWrapper->mHintSessionFuture.wait(); }
};

std::shared_ptr<HintSessionWrapperTests::MockHintSessionBinding>
        HintSessionWrapperTests::sMockBinding;

void HintSessionWrapperTests::SetUp() {
    // Pretend it's supported even if we're in an emulator
    Properties::useHintManager = true;
    sMockBinding = std::make_shared<NiceMock<MockHintSessionBinding>>();
    mWrapper = std::make_shared<HintSessionWrapper>(uiThreadId, renderThreadId);
    mWrapper->mBinding = sMockBinding;
    EXPECT_CALL(*sMockBinding, fakeGetManager).WillOnce(Return(managerPtr));
    ON_CALL(*sMockBinding, fakeCreateSession).WillByDefault(Return(sessionPtr));
}

void HintSessionWrapperTests::MockHintSessionBinding::init() {
    sMockBinding->getManager = &stubGetManager;
    if (sMockBinding->createSession == nullptr) {
        sMockBinding->createSession = &stubCreateSession;
    }
    sMockBinding->closeSession = &stubCloseSession;
    sMockBinding->updateTargetWorkDuration = &stubUpdateTargetWorkDuration;
    sMockBinding->reportActualWorkDuration = &stubReportActualWorkDuration;
    sMockBinding->sendHint = &stubSendHint;
}

void HintSessionWrapperTests::TearDown() {
    mWrapper = nullptr;
    sMockBinding = nullptr;
}

TEST_F(HintSessionWrapperTests, destructorClosesBackgroundSession) {
    EXPECT_CALL(*sMockBinding, fakeCloseSession(sessionPtr)).Times(1);
    sMockBinding->createSession = stubSlowCreateSession;
    mWrapper->init();
    mWrapper = nullptr;
}

TEST_F(HintSessionWrapperTests, sessionInitializesCorrectly) {
    EXPECT_CALL(*sMockBinding, fakeCreateSession(managerPtr, _, Gt(1), _)).Times(1);
    mWrapper->init();
    waitForWrapperReady();
}

TEST_F(HintSessionWrapperTests, loadUpHintsSendCorrectly) {
    EXPECT_CALL(*sMockBinding,
                fakeSendHint(sessionPtr, static_cast<int32_t>(SessionHint::CPU_LOAD_UP)))
            .Times(1);
    mWrapper->init();
    waitForWrapperReady();
    mWrapper->sendLoadIncreaseHint();
}

TEST_F(HintSessionWrapperTests, loadResetHintsSendCorrectly) {
    EXPECT_CALL(*sMockBinding,
                fakeSendHint(sessionPtr, static_cast<int32_t>(SessionHint::CPU_LOAD_RESET)))
            .Times(1);
    mWrapper->init();
    waitForWrapperReady();
    mWrapper->sendLoadResetHint();
}

}  // namespace android::uirenderer::renderthread