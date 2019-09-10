/*
 * Copyright (C) 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <gtest/gtest.h>
#include "state/StateManager.h"
#include "state/StateTracker.h"
#include "state/StateListener.h"

#include "tests/statsd_test_util.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

/**
 * Mock StateListener class for testing.
 * Stores primary key and state pairs.
 */
class TestStateListener : public virtual StateListener {
public:
    TestStateListener(){};

    virtual ~TestStateListener(){};

    struct Update {
        Update(const HashableDimensionKey& key, int state) : mKey(key), mState(state){};
        HashableDimensionKey mKey;
        int mState;
    };

    std::vector<Update> updates;

    void onStateChanged(int stateAtomId, const HashableDimensionKey& primaryKey, int oldState,
                        int newState) {
        updates.emplace_back(primaryKey, newState);
    }
};

// START: build event functions.
// State with no primary fields - ScreenStateChanged
std::shared_ptr<LogEvent> buildScreenEvent(int state) {
    std::shared_ptr<LogEvent> event =
            std::make_shared<LogEvent>(android::util::SCREEN_STATE_CHANGED, 1000 /*timestamp*/);
    event->write((int32_t)state);
    event->init();
    return event;
}

// State with one primary field - UidProcessStateChanged
std::shared_ptr<LogEvent> buildUidProcessEvent(int uid, int state) {
    std::shared_ptr<LogEvent> event =
            std::make_shared<LogEvent>(android::util::UID_PROCESS_STATE_CHANGED, 1000 /*timestamp*/);
    event->write((int32_t)uid);
    event->write((int32_t)state);
    event->init();
    return event;
}

// State with multiple primary fields - OverlayStateChanged
std::shared_ptr<LogEvent> buildOverlayEvent(int uid, const std::string& packageName, int state) {
    std::shared_ptr<LogEvent> event =
            std::make_shared<LogEvent>(android::util::OVERLAY_STATE_CHANGED, 1000 /*timestamp*/);
    event->write((int32_t)uid);
    event->write(packageName);
    event->write(true);  // using_alert_window
    event->write((int32_t)state);
    event->init();
    return event;
}

std::shared_ptr<LogEvent> buildIncorrectOverlayEvent(int uid, const std::string& packageName, int state) {
    std::shared_ptr<LogEvent> event =
            std::make_shared<LogEvent>(android::util::OVERLAY_STATE_CHANGED, 1000 /*timestamp*/);
    event->write((int32_t)uid);
    event->write(packageName);
    event->write((int32_t)state);
    event->init();
    return event;
}
// END: build event functions.

// START: get primary key functions
void getUidProcessKey(int uid, HashableDimensionKey* key) {
    int pos1[] = {1, 0, 0};
    Field field1(27 /* atom id */, pos1, 0 /* depth */);
    Value value1((int32_t)uid);

    key->addValue(FieldValue(field1, value1));
}

void getOverlayKey(int uid, string packageName, HashableDimensionKey* key) {
    int pos1[] = {1, 0, 0};
    int pos2[] = {2, 0, 0};

    Field field1(59 /* atom id */, pos1, 0 /* depth */);
    Field field2(59 /* atom id */, pos2, 0 /* depth */);

    Value value1((int32_t)uid);
    Value value2(packageName);

    key->addValue(FieldValue(field1, value1));
    key->addValue(FieldValue(field2, value2));
}
// END: get primary key functions

TEST(StateListenerTest, TestStateListenerWeakPointer) {
    sp<TestStateListener> listener = new TestStateListener();
    wp<TestStateListener> wListener = listener;
    listener = nullptr;  // let go of listener
    EXPECT_TRUE(wListener.promote() == nullptr);
}

TEST(StateManagerTest, TestStateManagerGetInstance) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager& mgr = StateManager::getInstance();

    mgr.registerListener(android::util::SCREEN_STATE_CHANGED, listener1);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(1, StateManager::getInstance().getStateTrackersCount());
}

/**
 * Test registering listeners to StateTrackers
 *
 * - StateManager will create a new StateTracker if it doesn't already exist
 * and then register the listener to the StateTracker.
 * - If a listener is already registered to a StateTracker, it is not added again.
 * - StateTrackers are only created for atoms that are state atoms.
 */
TEST(StateTrackerTest, TestRegisterListener) {
    sp<TestStateListener> listener1 = new TestStateListener();
    sp<TestStateListener> listener2 = new TestStateListener();
    StateManager mgr;

    // Register listener to non-existing StateTracker
    EXPECT_EQ(0, mgr.getStateTrackersCount());
    mgr.registerListener(android::util::SCREEN_STATE_CHANGED, listener1);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(1, mgr.getListenersCount(android::util::SCREEN_STATE_CHANGED));

    // Register listener to existing StateTracker
    mgr.registerListener(android::util::SCREEN_STATE_CHANGED, listener2);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(2, mgr.getListenersCount(android::util::SCREEN_STATE_CHANGED));

    // Register already registered listener to existing StateTracker
    mgr.registerListener(android::util::SCREEN_STATE_CHANGED, listener2);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(2, mgr.getListenersCount(android::util::SCREEN_STATE_CHANGED));

    // Register listener to non-state atom
    mgr.registerListener(android::util::BATTERY_LEVEL_CHANGED, listener2);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
}

/**
 * Test unregistering listeners from StateTrackers
 *
 * - StateManager will unregister listeners from a StateTracker only if the
 * StateTracker exists and the listener is registered to the StateTracker.
 * - Once all listeners are removed from a StateTracker, the StateTracker
 * is also removed.
 */
TEST(StateTrackerTest, TestUnregisterListener) {
    sp<TestStateListener> listener1 = new TestStateListener();
    sp<TestStateListener> listener2 = new TestStateListener();
    StateManager mgr;

    // Unregister listener from non-existing StateTracker
    EXPECT_EQ(0, mgr.getStateTrackersCount());
    mgr.unregisterListener(android::util::SCREEN_STATE_CHANGED, listener1);
    EXPECT_EQ(0, mgr.getStateTrackersCount());
    EXPECT_EQ(-1, mgr.getListenersCount(android::util::SCREEN_STATE_CHANGED));

    // Unregister non-registered listener from existing StateTracker
    mgr.registerListener(android::util::SCREEN_STATE_CHANGED, listener1);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(1, mgr.getListenersCount(android::util::SCREEN_STATE_CHANGED));
    mgr.unregisterListener(android::util::SCREEN_STATE_CHANGED, listener2);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(1, mgr.getListenersCount(android::util::SCREEN_STATE_CHANGED));

    // Unregister second-to-last listener from StateTracker
    mgr.registerListener(android::util::SCREEN_STATE_CHANGED, listener2);
    mgr.unregisterListener(android::util::SCREEN_STATE_CHANGED, listener1);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(1, mgr.getListenersCount(android::util::SCREEN_STATE_CHANGED));

    // Unregister last listener from StateTracker
    mgr.unregisterListener(android::util::SCREEN_STATE_CHANGED, listener2);
    EXPECT_EQ(0, mgr.getStateTrackersCount());
    EXPECT_EQ(-1, mgr.getListenersCount(android::util::SCREEN_STATE_CHANGED));
}

/**
 * Test StateManager's onLogEvent and StateListener's onStateChanged correctly
 * updates listener for states without primary keys.
 */
TEST(StateTrackerTest, TestStateChangeNoPrimaryFields) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(android::util::SCREEN_STATE_CHANGED, listener1);

    // log event
    std::shared_ptr<LogEvent> event =
            buildScreenEvent(android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    mgr.onLogEvent(*event);

    // check listener was updated
    EXPECT_EQ(1, listener1->updates.size());
    EXPECT_EQ(DEFAULT_DIMENSION_KEY, listener1->updates[0].mKey);
    EXPECT_EQ(2, listener1->updates[0].mState);

    // check StateTracker was updated by querying for state
    HashableDimensionKey queryKey = DEFAULT_DIMENSION_KEY;
    EXPECT_EQ(2, mgr.getState(android::util::SCREEN_STATE_CHANGED, queryKey));
}

/**
 * Test StateManager's onLogEvent and StateListener's onStateChanged correctly
 * updates listener for states with primary keys.
 */
TEST(StateTrackerTest, TestStateChangeOnePrimaryField) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(android::util::UID_PROCESS_STATE_CHANGED, listener1);

    // log event
    std::shared_ptr<LogEvent> event = buildUidProcessEvent(
            1000,
            android::app::ProcessStateEnum::PROCESS_STATE_TOP);  //  state value: 1002
    mgr.onLogEvent(*event);

    // check listener was updated
    EXPECT_EQ(1, listener1->updates.size());
    EXPECT_EQ(1000, listener1->updates[0].mKey.getValues()[0].mValue.int_value);
    EXPECT_EQ(1002, listener1->updates[0].mState);

    // check StateTracker was updated by querying for state
    HashableDimensionKey queryKey;
    getUidProcessKey(1000, &queryKey);
    EXPECT_EQ(1002, mgr.getState(android::util::UID_PROCESS_STATE_CHANGED, queryKey));
}

TEST(StateTrackerTest, TestStateChangeMultiplePrimaryFields) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(android::util::OVERLAY_STATE_CHANGED, listener1);

    // log event
    std::shared_ptr<LogEvent> event = buildOverlayEvent(1000, "package1", 1);  // state: ENTERED
    mgr.onLogEvent(*event);

    // check listener update
    EXPECT_EQ(1, listener1->updates.size());
    EXPECT_EQ(1000, listener1->updates[0].mKey.getValues()[0].mValue.int_value);
    EXPECT_EQ(1, listener1->updates[0].mState);
}

/**
 * Test StateManager's onLogEvent and StateListener's onStateChanged
 * when there is an error extracting state from log event. Listener is not
 * updated of state change.
 */
TEST(StateTrackerTest, TestStateChangeEventError) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(android::util::OVERLAY_STATE_CHANGED, listener1);

    // log event
    std::shared_ptr<LogEvent> event =
            buildIncorrectOverlayEvent(1000, "package1", 1);  // state: ENTERED
    mgr.onLogEvent(*event);

    // check listener update
    EXPECT_EQ(0, listener1->updates.size());
}

TEST(StateTrackerTest, TestStateQuery) {
    sp<TestStateListener> listener1 = new TestStateListener();
    sp<TestStateListener> listener2 = new TestStateListener();
    sp<TestStateListener> listener3 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(android::util::SCREEN_STATE_CHANGED, listener1);
    mgr.registerListener(android::util::UID_PROCESS_STATE_CHANGED, listener2);
    mgr.registerListener(android::util::OVERLAY_STATE_CHANGED, listener3);

    std::shared_ptr<LogEvent> event1 = buildUidProcessEvent(
            1000,
            android::app::ProcessStateEnum::PROCESS_STATE_TOP);  // state value: 1002
    std::shared_ptr<LogEvent> event2 = buildUidProcessEvent(
            1001,
            android::app::ProcessStateEnum::PROCESS_STATE_FOREGROUND_SERVICE);  // state value: 1003
    std::shared_ptr<LogEvent> event3 = buildUidProcessEvent(
            1002,
            android::app::ProcessStateEnum::PROCESS_STATE_PERSISTENT);  // state value: 1000
    std::shared_ptr<LogEvent> event4 = buildUidProcessEvent(
            1001,
            android::app::ProcessStateEnum::PROCESS_STATE_TOP);  // state value: 1002
    std::shared_ptr<LogEvent> event5 =
            buildScreenEvent(android::view::DisplayStateEnum::DISPLAY_STATE_ON);  // state value:
    std::shared_ptr<LogEvent> event6 = buildOverlayEvent(1000, "package1", 1);
    std::shared_ptr<LogEvent> event7 = buildOverlayEvent(1000, "package2", 2);

    mgr.onLogEvent(*event1);
    mgr.onLogEvent(*event2);
    mgr.onLogEvent(*event3);
    mgr.onLogEvent(*event5);
    mgr.onLogEvent(*event5);
    mgr.onLogEvent(*event6);
    mgr.onLogEvent(*event7);

    // Query for UidProcessState of uid 1001
    HashableDimensionKey queryKey1;
    getUidProcessKey(1001, &queryKey1);
    EXPECT_EQ(1003, mgr.getState(android::util::UID_PROCESS_STATE_CHANGED, queryKey1));

    // Query for UidProcessState of uid 1004 - not in state map
    HashableDimensionKey queryKey2;
    getUidProcessKey(1004, &queryKey2);
    EXPECT_EQ(-1,
              mgr.getState(android::util::UID_PROCESS_STATE_CHANGED, queryKey2));  // default state

    // Query for UidProcessState of uid 1001 - after change in state
    mgr.onLogEvent(*event4);
    EXPECT_EQ(1002, mgr.getState(android::util::UID_PROCESS_STATE_CHANGED, queryKey1));

    // Query for ScreenState
    EXPECT_EQ(2, mgr.getState(android::util::SCREEN_STATE_CHANGED, DEFAULT_DIMENSION_KEY));

    // Query for OverlayState of uid 1000, package name "package2"
    HashableDimensionKey queryKey3;
    getOverlayKey(1000, "package2", &queryKey3);
    EXPECT_EQ(2, mgr.getState(android::util::OVERLAY_STATE_CHANGED, queryKey3));
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
