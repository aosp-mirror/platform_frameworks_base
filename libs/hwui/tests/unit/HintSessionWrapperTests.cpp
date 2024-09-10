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
#include "tests/common/TestUtils.h"

using namespace testing;
using namespace std::chrono_literals;
using namespace android::uirenderer::renderthread;

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

    std::promise<int> blockDestroyCallUntil;
    std::promise<int> waitForDestroyFinished;

    class MockHintSessionBinding : public HintSessionWrapper::HintSessionBinding {
    public:
        void init() override;

        MOCK_METHOD(APerformanceHintManager*, fakeGetManager, ());
        MOCK_METHOD(APerformanceHintSession*, fakeCreateSessionInternal,
                    (APerformanceHintManager*, const int32_t*, size_t, int64_t, SessionTag));
        MOCK_METHOD(void, fakeCloseSession, (APerformanceHintSession*));
        MOCK_METHOD(void, fakeUpdateTargetWorkDuration, (APerformanceHintSession*, int64_t));
        MOCK_METHOD(void, fakeReportActualWorkDuration, (APerformanceHintSession*, int64_t));
        MOCK_METHOD(void, fakeSendHint, (APerformanceHintSession*, int32_t));
        MOCK_METHOD(int, fakeSetThreads, (APerformanceHintSession*, const std::vector<pid_t>&));
        // Needs to be on the binding so it can be accessed from static methods
        std::promise<int> allowCreationToFinish;
    };

    // Must be static so it can have function pointers we can point to with static methods
    static std::shared_ptr<MockHintSessionBinding> sMockBinding;

    static void allowCreationToFinish() { sMockBinding->allowCreationToFinish.set_value(1); }
    void allowDelayedDestructionToStart() { blockDestroyCallUntil.set_value(1); }
    void waitForDelayedDestructionToFinish() { waitForDestroyFinished.get_future().wait(); }

    // Must be static so we can point to them as normal fn pointers with HintSessionBinding
    static APerformanceHintManager* stubGetManager() { return sMockBinding->fakeGetManager(); };
    static APerformanceHintSession* stubCreateSessionInternal(APerformanceHintManager* manager,
                                                              const int32_t* ids, size_t idsSize,
                                                              int64_t initialTarget,
                                                              SessionTag tag) {
        return sMockBinding->fakeCreateSessionInternal(manager, ids, idsSize, initialTarget,
                                                       SessionTag::HWUI);
    }
    static APerformanceHintSession* stubManagedCreateSessionInternal(
            APerformanceHintManager* manager, const int32_t* ids, size_t idsSize,
            int64_t initialTarget, SessionTag tag) {
        sMockBinding->allowCreationToFinish.get_future().wait();
        return sMockBinding->fakeCreateSessionInternal(manager, ids, idsSize, initialTarget,
                                                       SessionTag::HWUI);
    }
    static APerformanceHintSession* stubSlowCreateSessionInternal(APerformanceHintManager* manager,
                                                                  const int32_t* ids,
                                                                  size_t idsSize,
                                                                  int64_t initialTarget,
                                                                  SessionTag tag) {
        std::this_thread::sleep_for(50ms);
        return sMockBinding->fakeCreateSessionInternal(manager, ids, idsSize, initialTarget,
                                                       SessionTag::HWUI);
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
    static int stubSetThreads(APerformanceHintSession* session, const pid_t* ids, size_t size) {
        std::vector<pid_t> tids(ids, ids + size);
        return sMockBinding->fakeSetThreads(session, tids);
    }
    void waitForWrapperReady() {
        if (mWrapper->mHintSessionFuture.has_value()) {
            mWrapper->mHintSessionFuture->wait();
        }
    }
    void waitForSetThreadsReady() {
        if (mWrapper->mSetThreadsFuture.has_value()) {
            mWrapper->mSetThreadsFuture->wait();
        }
    }
    void scheduleDelayedDestroyManaged() {
        TestUtils::runOnRenderThread([&](renderthread::RenderThread& rt) {
            // Guaranteed to be scheduled first, allows destruction to start
            rt.queue().postDelayed(0_ms, [&] { blockDestroyCallUntil.get_future().wait(); });
            // Guaranteed to be scheduled second, destroys the session
            mWrapper->delayedDestroy(rt, 1_ms, mWrapper);
            // This is guaranteed to be queued after the destroy, signals that destruction is done
            rt.queue().postDelayed(1_ms, [&] { waitForDestroyFinished.set_value(1); });
        });
    }
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
    ON_CALL(*sMockBinding, fakeCreateSessionInternal).WillByDefault(Return(sessionPtr));
    ON_CALL(*sMockBinding, fakeSetThreads).WillByDefault(Return(0));
}

void HintSessionWrapperTests::MockHintSessionBinding::init() {
    sMockBinding->getManager = &stubGetManager;
    if (sMockBinding->createSessionInternal == nullptr) {
        sMockBinding->createSessionInternal = &stubCreateSessionInternal;
    }
    sMockBinding->closeSession = &stubCloseSession;
    sMockBinding->updateTargetWorkDuration = &stubUpdateTargetWorkDuration;
    sMockBinding->reportActualWorkDuration = &stubReportActualWorkDuration;
    sMockBinding->sendHint = &stubSendHint;
    sMockBinding->setThreads = &stubSetThreads;
}

void HintSessionWrapperTests::TearDown() {
    // Ensure that anything running on RT is completely finished
    mWrapper = nullptr;
    sMockBinding = nullptr;
}

TEST_F(HintSessionWrapperTests, destructorClosesBackgroundSession) {
    EXPECT_CALL(*sMockBinding, fakeCloseSession(sessionPtr)).Times(1);
    sMockBinding->createSessionInternal = stubSlowCreateSessionInternal;
    mWrapper->init();
    mWrapper = nullptr;
    Mock::VerifyAndClearExpectations(sMockBinding.get());
}

TEST_F(HintSessionWrapperTests, sessionInitializesCorrectly) {
    EXPECT_CALL(*sMockBinding, fakeCreateSessionInternal(managerPtr, _, Gt(1), _, _)).Times(1);
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

TEST_F(HintSessionWrapperTests, delayedDeletionWorksCorrectlyAndOnlyClosesOnce) {
    EXPECT_CALL(*sMockBinding, fakeCloseSession(sessionPtr)).Times(1);
    mWrapper->init();
    waitForWrapperReady();
    // Init a second time just to ensure the wrapper grabs the promise value
    mWrapper->init();

    EXPECT_EQ(mWrapper->alive(), true);

    // Schedule delayed destruction, allow it to run, and check when it's done
    scheduleDelayedDestroyManaged();
    allowDelayedDestructionToStart();
    waitForDelayedDestructionToFinish();

    // Ensure it closed within the timeframe of the test
    Mock::VerifyAndClearExpectations(sMockBinding.get());
    EXPECT_EQ(mWrapper->alive(), false);
    // If we then delete the wrapper, it shouldn't close the session again
    EXPECT_CALL(*sMockBinding, fakeCloseSession(_)).Times(0);
    mWrapper = nullptr;
}

TEST_F(HintSessionWrapperTests, delayedDeletionResolvesBeforeAsyncCreationFinishes) {
    // Here we test whether queueing delayedDestroy works while creation is still happening, if
    // creation happens after
    EXPECT_CALL(*sMockBinding, fakeCloseSession(sessionPtr)).Times(1);
    sMockBinding->createSessionInternal = &stubManagedCreateSessionInternal;

    // Start creating the session and destroying it at the same time
    mWrapper->init();
    scheduleDelayedDestroyManaged();

    // Allow destruction to happen first
    allowDelayedDestructionToStart();

    // Make sure destruction has had time to happen
    std::this_thread::sleep_for(50ms);

    // Then, allow creation to finish after delayed destroy runs
    allowCreationToFinish();

    // Wait for destruction to finish
    waitForDelayedDestructionToFinish();

    // Ensure it closed within the timeframe of the test
    Mock::VerifyAndClearExpectations(sMockBinding.get());
    EXPECT_EQ(mWrapper->alive(), false);
}

TEST_F(HintSessionWrapperTests, delayedDeletionResolvesAfterAsyncCreationFinishes) {
    // Here we test whether queueing delayedDestroy works while creation is still happening, if
    // creation happens before
    EXPECT_CALL(*sMockBinding, fakeCloseSession(sessionPtr)).Times(1);
    sMockBinding->createSessionInternal = &stubManagedCreateSessionInternal;

    // Start creating the session and destroying it at the same time
    mWrapper->init();
    scheduleDelayedDestroyManaged();

    // Allow creation to happen first
    allowCreationToFinish();

    // Make sure creation has had time to happen
    waitForWrapperReady();

    // Then allow destruction to happen after creation is done
    allowDelayedDestructionToStart();

    // Wait for it to finish
    waitForDelayedDestructionToFinish();

    // Ensure it closed within the timeframe of the test
    Mock::VerifyAndClearExpectations(sMockBinding.get());
    EXPECT_EQ(mWrapper->alive(), false);
}

TEST_F(HintSessionWrapperTests, delayedDeletionDoesNotKillReusedSession) {
    EXPECT_CALL(*sMockBinding, fakeCloseSession(sessionPtr)).Times(0);
    EXPECT_CALL(*sMockBinding, fakeReportActualWorkDuration(sessionPtr, 5_ms)).Times(1);

    mWrapper->init();
    waitForWrapperReady();
    // Init a second time just to grab the wrapper from the promise
    mWrapper->init();
    EXPECT_EQ(mWrapper->alive(), true);

    // First schedule the deletion
    scheduleDelayedDestroyManaged();

    // Then, report an actual duration
    mWrapper->reportActualWorkDuration(5_ms);

    // Then, run the delayed deletion after sending the update
    allowDelayedDestructionToStart();
    waitForDelayedDestructionToFinish();

    // Ensure it didn't close within the timeframe of the test
    Mock::VerifyAndClearExpectations(sMockBinding.get());
    EXPECT_EQ(mWrapper->alive(), true);
}

TEST_F(HintSessionWrapperTests, loadUpDoesNotResetDeletionTimer) {
    EXPECT_CALL(*sMockBinding, fakeCloseSession(sessionPtr)).Times(1);
    EXPECT_CALL(*sMockBinding,
                fakeSendHint(sessionPtr, static_cast<int32_t>(SessionHint::CPU_LOAD_UP)))
            .Times(1);

    mWrapper->init();
    waitForWrapperReady();
    // Init a second time just to grab the wrapper from the promise
    mWrapper->init();
    EXPECT_EQ(mWrapper->alive(), true);

    // First schedule the deletion
    scheduleDelayedDestroyManaged();

    // Then, send a load_up hint
    mWrapper->sendLoadIncreaseHint();

    // Then, run the delayed deletion after sending the update
    allowDelayedDestructionToStart();
    waitForDelayedDestructionToFinish();

    // Ensure it closed within the timeframe of the test
    Mock::VerifyAndClearExpectations(sMockBinding.get());
    EXPECT_EQ(mWrapper->alive(), false);
}

TEST_F(HintSessionWrapperTests, manualSessionDestroyPlaysNiceWithDelayedDestruct) {
    EXPECT_CALL(*sMockBinding, fakeCloseSession(sessionPtr)).Times(1);

    mWrapper->init();
    waitForWrapperReady();
    // Init a second time just to grab the wrapper from the promise
    mWrapper->init();
    EXPECT_EQ(mWrapper->alive(), true);

    // First schedule the deletion
    scheduleDelayedDestroyManaged();

    // Then, kill the session
    mWrapper->destroy();

    // Verify it died
    Mock::VerifyAndClearExpectations(sMockBinding.get());
    EXPECT_EQ(mWrapper->alive(), false);

    EXPECT_CALL(*sMockBinding, fakeCloseSession(sessionPtr)).Times(0);

    // Then, run the delayed deletion after manually killing the session
    allowDelayedDestructionToStart();
    waitForDelayedDestructionToFinish();

    // Ensure it didn't close again and is still dead
    Mock::VerifyAndClearExpectations(sMockBinding.get());
    EXPECT_EQ(mWrapper->alive(), false);
}

TEST_F(HintSessionWrapperTests, setThreadsUpdatesSessionThreads) {
    EXPECT_CALL(*sMockBinding, fakeCreateSessionInternal(managerPtr, _, Gt(1), _, _)).Times(1);
    EXPECT_CALL(*sMockBinding, fakeSetThreads(sessionPtr, testing::IsSupersetOf({11, 22})))
            .Times(1);
    mWrapper->init();
    waitForWrapperReady();

    // This changes the overall set of threads in the session, so the session wrapper should call
    // setThreads.
    mWrapper->setActiveFunctorThreads({11, 22});
    waitForSetThreadsReady();

    // The set of threads doesn't change, so the session wrapper should not call setThreads this
    // time. The order of the threads shouldn't matter.
    mWrapper->setActiveFunctorThreads({22, 11});
    waitForSetThreadsReady();
}

TEST_F(HintSessionWrapperTests, setThreadsDoesntCrashAfterDestroy) {
    EXPECT_CALL(*sMockBinding, fakeCloseSession(sessionPtr)).Times(1);

    mWrapper->init();
    waitForWrapperReady();
    // Init a second time just to grab the wrapper from the promise
    mWrapper->init();
    EXPECT_EQ(mWrapper->alive(), true);

    // Then, kill the session
    mWrapper->destroy();

    // Verify it died
    Mock::VerifyAndClearExpectations(sMockBinding.get());
    EXPECT_EQ(mWrapper->alive(), false);

    // setActiveFunctorThreads shouldn't do anything, and shouldn't crash.
    EXPECT_CALL(*sMockBinding, fakeSetThreads(_, _)).Times(0);
    mWrapper->setActiveFunctorThreads({11, 22});
    waitForSetThreadsReady();
}

}  // namespace android::uirenderer::renderthread