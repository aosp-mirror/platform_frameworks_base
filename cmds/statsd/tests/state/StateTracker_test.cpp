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
#include "state/StateTracker.h"

#include <gtest/gtest.h>

#include "state/StateListener.h"
#include "state/StateManager.h"
#include "state/StateTracker.h"
#include "stats_event.h"
#include "tests/statsd_test_util.h"

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

const int32_t timestampNs = 1000;

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

    void onStateChanged(const int64_t eventTimeNs, const int32_t atomId,
                        const HashableDimensionKey& primaryKey, int oldState, int newState) {
        updates.emplace_back(primaryKey, newState);
    }
};

int getStateInt(StateManager& mgr, int atomId, const HashableDimensionKey& queryKey) {
    FieldValue output;
    mgr.getStateValue(atomId, queryKey, &output);
    return output.mValue.int_value;
}

// START: build event functions.
// Incorrect event - missing fields
std::unique_ptr<LogEvent> buildIncorrectOverlayEvent(int uid, const std::string& packageName,
                                                     int state) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::OVERLAY_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, 1000);

    AStatsEvent_writeInt32(statsEvent, uid);
    AStatsEvent_writeString(statsEvent, packageName.c_str());
    // Missing field 3 - using_alert_window.
    AStatsEvent_writeInt32(statsEvent, state);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    parseStatsEventToLogEvent(statsEvent, logEvent.get());
    return logEvent;
}

// Incorrect event - exclusive state has wrong type
std::unique_ptr<LogEvent> buildOverlayEventBadStateType(int uid, const std::string& packageName) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, util::OVERLAY_STATE_CHANGED);
    AStatsEvent_overwriteTimestamp(statsEvent, 1000);

    AStatsEvent_writeInt32(statsEvent, uid);
    AStatsEvent_writeString(statsEvent, packageName.c_str());
    AStatsEvent_writeInt32(statsEvent, true);       // using_alert_window
    AStatsEvent_writeString(statsEvent, "string");  // exclusive state: string instead of int

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    parseStatsEventToLogEvent(statsEvent, logEvent.get());
    return logEvent;
}
// END: build event functions.

TEST(StateListenerTest, TestStateListenerWeakPointer) {
    sp<TestStateListener> listener = new TestStateListener();
    wp<TestStateListener> wListener = listener;
    listener = nullptr;  // let go of listener
    EXPECT_TRUE(wListener.promote() == nullptr);
}

TEST(StateManagerTest, TestStateManagerGetInstance) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager& mgr = StateManager::getInstance();
    mgr.clear();

    mgr.registerListener(util::SCREEN_STATE_CHANGED, listener1);
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
    mgr.registerListener(util::SCREEN_STATE_CHANGED, listener1);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(1, mgr.getListenersCount(util::SCREEN_STATE_CHANGED));

    // Register listener to existing StateTracker
    mgr.registerListener(util::SCREEN_STATE_CHANGED, listener2);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(2, mgr.getListenersCount(util::SCREEN_STATE_CHANGED));

    // Register already registered listener to existing StateTracker
    mgr.registerListener(util::SCREEN_STATE_CHANGED, listener2);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(2, mgr.getListenersCount(util::SCREEN_STATE_CHANGED));

    // Register listener to non-state atom
    mgr.registerListener(util::BATTERY_LEVEL_CHANGED, listener2);
    EXPECT_EQ(2, mgr.getStateTrackersCount());
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
    mgr.unregisterListener(util::SCREEN_STATE_CHANGED, listener1);
    EXPECT_EQ(0, mgr.getStateTrackersCount());
    EXPECT_EQ(-1, mgr.getListenersCount(util::SCREEN_STATE_CHANGED));

    // Unregister non-registered listener from existing StateTracker
    mgr.registerListener(util::SCREEN_STATE_CHANGED, listener1);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(1, mgr.getListenersCount(util::SCREEN_STATE_CHANGED));
    mgr.unregisterListener(util::SCREEN_STATE_CHANGED, listener2);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(1, mgr.getListenersCount(util::SCREEN_STATE_CHANGED));

    // Unregister second-to-last listener from StateTracker
    mgr.registerListener(util::SCREEN_STATE_CHANGED, listener2);
    mgr.unregisterListener(util::SCREEN_STATE_CHANGED, listener1);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(1, mgr.getListenersCount(util::SCREEN_STATE_CHANGED));

    // Unregister last listener from StateTracker
    mgr.unregisterListener(util::SCREEN_STATE_CHANGED, listener2);
    EXPECT_EQ(0, mgr.getStateTrackersCount());
    EXPECT_EQ(-1, mgr.getListenersCount(util::SCREEN_STATE_CHANGED));
}

/**
 * Test a binary state atom with nested counting.
 *
 * To go from an "ON" state to an "OFF" state with nested counting, we must see
 * an equal number of "OFF" events as "ON" events.
 * For example, ACQUIRE, ACQUIRE, RELEASE will still be in the ACQUIRE state.
 * ACQUIRE, ACQUIRE, RELEASE, RELEASE will be in the RELEASE state.
 */
TEST(StateTrackerTest, TestStateChangeNested) {
    sp<TestStateListener> listener = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(util::WAKELOCK_STATE_CHANGED, listener);

    std::vector<int> attributionUids1 = {1000};
    std::vector<string> attributionTags1 = {"tag"};

    std::unique_ptr<LogEvent> event1 = CreateAcquireWakelockEvent(timestampNs, attributionUids1,
                                                                  attributionTags1, "wakelockName");
    mgr.onLogEvent(*event1);
    EXPECT_EQ(1, listener->updates.size());
    EXPECT_EQ(1000, listener->updates[0].mKey.getValues()[0].mValue.int_value);
    EXPECT_EQ(1, listener->updates[0].mState);
    listener->updates.clear();

    std::unique_ptr<LogEvent> event2 = CreateAcquireWakelockEvent(
            timestampNs + 1000, attributionUids1, attributionTags1, "wakelockName");
    mgr.onLogEvent(*event2);
    EXPECT_EQ(0, listener->updates.size());

    std::unique_ptr<LogEvent> event3 = CreateReleaseWakelockEvent(
            timestampNs + 2000, attributionUids1, attributionTags1, "wakelockName");
    mgr.onLogEvent(*event3);
    EXPECT_EQ(0, listener->updates.size());

    std::unique_ptr<LogEvent> event4 = CreateReleaseWakelockEvent(
            timestampNs + 3000, attributionUids1, attributionTags1, "wakelockName");
    mgr.onLogEvent(*event4);
    EXPECT_EQ(1, listener->updates.size());
    EXPECT_EQ(1000, listener->updates[0].mKey.getValues()[0].mValue.int_value);
    EXPECT_EQ(0, listener->updates[0].mState);
}

/**
 * Test a state atom with a reset state.
 *
 * If the reset state value is seen, every state in the map is set to the default
 * state and every listener is notified.
 */
TEST(StateTrackerTest, TestStateChangeReset) {
    sp<TestStateListener> listener = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(util::BLE_SCAN_STATE_CHANGED, listener);

    std::vector<int> attributionUids1 = {1000};
    std::vector<string> attributionTags1 = {"tag1"};
    std::vector<int> attributionUids2 = {2000};

    std::unique_ptr<LogEvent> event1 =
            CreateBleScanStateChangedEvent(timestampNs, attributionUids1, attributionTags1,
                                           BleScanStateChanged::ON, false, false, false);
    mgr.onLogEvent(*event1);
    EXPECT_EQ(1, listener->updates.size());
    EXPECT_EQ(1000, listener->updates[0].mKey.getValues()[0].mValue.int_value);
    EXPECT_EQ(BleScanStateChanged::ON, listener->updates[0].mState);
    FieldValue stateFieldValue;
    mgr.getStateValue(util::BLE_SCAN_STATE_CHANGED, listener->updates[0].mKey, &stateFieldValue);
    EXPECT_EQ(BleScanStateChanged::ON, stateFieldValue.mValue.int_value);
    listener->updates.clear();

    std::unique_ptr<LogEvent> event2 =
            CreateBleScanStateChangedEvent(timestampNs + 1000, attributionUids2, attributionTags1,
                                           BleScanStateChanged::ON, false, false, false);
    mgr.onLogEvent(*event2);
    EXPECT_EQ(1, listener->updates.size());
    EXPECT_EQ(2000, listener->updates[0].mKey.getValues()[0].mValue.int_value);
    EXPECT_EQ(BleScanStateChanged::ON, listener->updates[0].mState);
    mgr.getStateValue(util::BLE_SCAN_STATE_CHANGED, listener->updates[0].mKey, &stateFieldValue);
    EXPECT_EQ(BleScanStateChanged::ON, stateFieldValue.mValue.int_value);
    listener->updates.clear();

    std::unique_ptr<LogEvent> event3 =
            CreateBleScanStateChangedEvent(timestampNs + 2000, attributionUids2, attributionTags1,
                                           BleScanStateChanged::RESET, false, false, false);
    mgr.onLogEvent(*event3);
    EXPECT_EQ(2, listener->updates.size());
    for (const TestStateListener::Update& update : listener->updates) {
        EXPECT_EQ(BleScanStateChanged::OFF, update.mState);

        mgr.getStateValue(util::BLE_SCAN_STATE_CHANGED, update.mKey, &stateFieldValue);
        EXPECT_EQ(BleScanStateChanged::OFF, stateFieldValue.mValue.int_value);
    }
}

/**
 * Test StateManager's onLogEvent and StateListener's onStateChanged correctly
 * updates listener for states without primary keys.
 */
TEST(StateTrackerTest, TestStateChangeNoPrimaryFields) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(util::SCREEN_STATE_CHANGED, listener1);

    // log event
    std::unique_ptr<LogEvent> event = CreateScreenStateChangedEvent(
            timestampNs, android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    mgr.onLogEvent(*event);

    // check listener was updated
    EXPECT_EQ(1, listener1->updates.size());
    EXPECT_EQ(DEFAULT_DIMENSION_KEY, listener1->updates[0].mKey);
    EXPECT_EQ(2, listener1->updates[0].mState);

    // check StateTracker was updated by querying for state
    HashableDimensionKey queryKey = DEFAULT_DIMENSION_KEY;
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON,
              getStateInt(mgr, util::SCREEN_STATE_CHANGED, queryKey));
}

/**
 * Test StateManager's onLogEvent and StateListener's onStateChanged correctly
 * updates listener for states with one primary key.
 */
TEST(StateTrackerTest, TestStateChangeOnePrimaryField) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(util::UID_PROCESS_STATE_CHANGED, listener1);

    // log event
    std::unique_ptr<LogEvent> event = CreateUidProcessStateChangedEvent(
            timestampNs, 1000 /*uid*/, android::app::ProcessStateEnum::PROCESS_STATE_TOP);
    mgr.onLogEvent(*event);

    // check listener was updated
    EXPECT_EQ(1, listener1->updates.size());
    EXPECT_EQ(1000, listener1->updates[0].mKey.getValues()[0].mValue.int_value);
    EXPECT_EQ(1002, listener1->updates[0].mState);

    // check StateTracker was updated by querying for state
    HashableDimensionKey queryKey;
    getUidProcessKey(1000 /* uid */, &queryKey);
    EXPECT_EQ(android::app::ProcessStateEnum::PROCESS_STATE_TOP,
              getStateInt(mgr, util::UID_PROCESS_STATE_CHANGED, queryKey));
}

TEST(StateTrackerTest, TestStateChangePrimaryFieldAttrChain) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(util::WAKELOCK_STATE_CHANGED, listener1);

    // Log event.
    std::vector<int> attributionUids = {1001};
    std::vector<string> attributionTags = {"tag1"};

    std::unique_ptr<LogEvent> event = CreateAcquireWakelockEvent(timestampNs, attributionUids,
                                                                 attributionTags, "wakelockName");
    mgr.onLogEvent(*event);
    EXPECT_EQ(1, mgr.getStateTrackersCount());
    EXPECT_EQ(1, mgr.getListenersCount(util::WAKELOCK_STATE_CHANGED));

    // Check listener was updated.
    EXPECT_EQ(1, listener1->updates.size());
    EXPECT_EQ(3, listener1->updates[0].mKey.getValues().size());
    EXPECT_EQ(1001, listener1->updates[0].mKey.getValues()[0].mValue.int_value);
    EXPECT_EQ(1, listener1->updates[0].mKey.getValues()[1].mValue.int_value);
    EXPECT_EQ("wakelockName", listener1->updates[0].mKey.getValues()[2].mValue.str_value);
    EXPECT_EQ(WakelockStateChanged::ACQUIRE, listener1->updates[0].mState);

    // Check StateTracker was updated by querying for state.
    HashableDimensionKey queryKey;
    getPartialWakelockKey(1001 /* uid */, "wakelockName", &queryKey);
    EXPECT_EQ(WakelockStateChanged::ACQUIRE,
              getStateInt(mgr, util::WAKELOCK_STATE_CHANGED, queryKey));

    // No state stored for this query key.
    HashableDimensionKey queryKey2;
    getPartialWakelockKey(1002 /* uid */, "tag1", &queryKey2);
    EXPECT_EQ(-1 /*StateTracker::kStateUnknown*/,
              getStateInt(mgr, util::WAKELOCK_STATE_CHANGED, queryKey2));

    // Partial query fails.
    HashableDimensionKey queryKey3;
    getPartialWakelockKey(1001 /* uid */, &queryKey3);
    EXPECT_EQ(-1 /*StateTracker::kStateUnknown*/,
              getStateInt(mgr, util::WAKELOCK_STATE_CHANGED, queryKey3));
}

/**
 * Test StateManager's onLogEvent and StateListener's onStateChanged correctly
 * updates listener for states with multiple primary keys.
 */
TEST(StateTrackerTest, TestStateChangeMultiplePrimaryFields) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(util::OVERLAY_STATE_CHANGED, listener1);

    // log event
    std::unique_ptr<LogEvent> event = CreateOverlayStateChangedEvent(
            timestampNs, 1000 /* uid */, "package1", true /*using_alert_window*/,
            OverlayStateChanged::ENTERED);
    mgr.onLogEvent(*event);

    // check listener was updated
    EXPECT_EQ(1, listener1->updates.size());
    EXPECT_EQ(1000, listener1->updates[0].mKey.getValues()[0].mValue.int_value);
    EXPECT_EQ(1, listener1->updates[0].mState);

    // check StateTracker was updated by querying for state
    HashableDimensionKey queryKey;
    getOverlayKey(1000 /* uid */, "package1", &queryKey);
    EXPECT_EQ(OverlayStateChanged::ENTERED,
              getStateInt(mgr, util::OVERLAY_STATE_CHANGED, queryKey));
}

/**
 * Test StateManager's onLogEvent and StateListener's onStateChanged
 * when there is an error extracting state from log event. Listener is not
 * updated of state change.
 */
TEST(StateTrackerTest, TestStateChangeEventError) {
    sp<TestStateListener> listener1 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(util::OVERLAY_STATE_CHANGED, listener1);

    // log event
    std::shared_ptr<LogEvent> event1 =
            buildIncorrectOverlayEvent(1000 /* uid */, "package1", 1 /* state */);
    std::shared_ptr<LogEvent> event2 = buildOverlayEventBadStateType(1001 /* uid */, "package2");

    // check listener was updated
    mgr.onLogEvent(*event1);
    EXPECT_EQ(0, listener1->updates.size());
    mgr.onLogEvent(*event2);
    EXPECT_EQ(0, listener1->updates.size());
}

TEST(StateTrackerTest, TestStateQuery) {
    sp<TestStateListener> listener1 = new TestStateListener();
    sp<TestStateListener> listener2 = new TestStateListener();
    sp<TestStateListener> listener3 = new TestStateListener();
    sp<TestStateListener> listener4 = new TestStateListener();
    StateManager mgr;
    mgr.registerListener(util::SCREEN_STATE_CHANGED, listener1);
    mgr.registerListener(util::UID_PROCESS_STATE_CHANGED, listener2);
    mgr.registerListener(util::OVERLAY_STATE_CHANGED, listener3);
    mgr.registerListener(util::WAKELOCK_STATE_CHANGED, listener4);

    std::unique_ptr<LogEvent> event1 = CreateUidProcessStateChangedEvent(
            timestampNs, 1000 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_TOP);  //  state value: 1002
    std::unique_ptr<LogEvent> event2 = CreateUidProcessStateChangedEvent(
            timestampNs + 1000, 1001 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_FOREGROUND_SERVICE);  //  state value:
                                                                                //  1003
    std::unique_ptr<LogEvent> event3 = CreateUidProcessStateChangedEvent(
            timestampNs + 2000, 1002 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_PERSISTENT);  //  state value: 1000
    std::unique_ptr<LogEvent> event4 = CreateUidProcessStateChangedEvent(
            timestampNs + 3000, 1001 /*uid*/,
            android::app::ProcessStateEnum::PROCESS_STATE_TOP);  //  state value: 1002
    std::unique_ptr<LogEvent> event5 = CreateScreenStateChangedEvent(
            timestampNs + 4000, android::view::DisplayStateEnum::DISPLAY_STATE_ON);
    std::unique_ptr<LogEvent> event6 = CreateOverlayStateChangedEvent(
            timestampNs + 5000, 1000 /*uid*/, "package1", true /*using_alert_window*/,
            OverlayStateChanged::ENTERED);
    std::unique_ptr<LogEvent> event7 = CreateOverlayStateChangedEvent(
            timestampNs + 6000, 1000 /*uid*/, "package2", true /*using_alert_window*/,
            OverlayStateChanged::EXITED);

    std::vector<int> attributionUids = {1005};
    std::vector<string> attributionTags = {"tag"};

    std::unique_ptr<LogEvent> event8 = CreateAcquireWakelockEvent(
            timestampNs + 7000, attributionUids, attributionTags, "wakelock1");
    std::unique_ptr<LogEvent> event9 = CreateReleaseWakelockEvent(
            timestampNs + 8000, attributionUids, attributionTags, "wakelock2");

    mgr.onLogEvent(*event1);
    mgr.onLogEvent(*event2);
    mgr.onLogEvent(*event3);
    mgr.onLogEvent(*event5);
    mgr.onLogEvent(*event5);
    mgr.onLogEvent(*event6);
    mgr.onLogEvent(*event7);
    mgr.onLogEvent(*event8);
    mgr.onLogEvent(*event9);

    // Query for UidProcessState of uid 1001
    HashableDimensionKey queryKey1;
    getUidProcessKey(1001, &queryKey1);
    EXPECT_EQ(android::app::ProcessStateEnum::PROCESS_STATE_FOREGROUND_SERVICE,
              getStateInt(mgr, util::UID_PROCESS_STATE_CHANGED, queryKey1));

    // Query for UidProcessState of uid 1004 - not in state map
    HashableDimensionKey queryKey2;
    getUidProcessKey(1004, &queryKey2);
    EXPECT_EQ(-1, getStateInt(mgr, util::UID_PROCESS_STATE_CHANGED,
                              queryKey2));  // default state

    // Query for UidProcessState of uid 1001 - after change in state
    mgr.onLogEvent(*event4);
    EXPECT_EQ(android::app::ProcessStateEnum::PROCESS_STATE_TOP,
              getStateInt(mgr, util::UID_PROCESS_STATE_CHANGED, queryKey1));

    // Query for ScreenState
    EXPECT_EQ(android::view::DisplayStateEnum::DISPLAY_STATE_ON,
              getStateInt(mgr, util::SCREEN_STATE_CHANGED, DEFAULT_DIMENSION_KEY));

    // Query for OverlayState of uid 1000, package name "package2"
    HashableDimensionKey queryKey3;
    getOverlayKey(1000, "package2", &queryKey3);
    EXPECT_EQ(OverlayStateChanged::EXITED,
              getStateInt(mgr, util::OVERLAY_STATE_CHANGED, queryKey3));

    // Query for WakelockState of uid 1005, tag 2
    HashableDimensionKey queryKey4;
    getPartialWakelockKey(1005, "wakelock2", &queryKey4);
    EXPECT_EQ(WakelockStateChanged::RELEASE,
              getStateInt(mgr, util::WAKELOCK_STATE_CHANGED, queryKey4));

    // Query for WakelockState of uid 1005, tag 1
    HashableDimensionKey queryKey5;
    getPartialWakelockKey(1005, "wakelock1", &queryKey5);
    EXPECT_EQ(WakelockStateChanged::ACQUIRE,
              getStateInt(mgr, util::WAKELOCK_STATE_CHANGED, queryKey5));
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
