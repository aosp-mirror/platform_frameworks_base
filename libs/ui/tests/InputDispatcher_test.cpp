//
// Copyright 2010 The Android Open Source Project
//

#include <ui/InputDispatcher.h>
#include <gtest/gtest.h>
#include <linux/input.h>

namespace android {

// An arbitrary time value.
static const nsecs_t ARBITRARY_TIME = 1234;

// An arbitrary device id.
static const int32_t DEVICE_ID = 1;

// An arbitrary injector pid / uid pair that has permission to inject events.
static const int32_t INJECTOR_PID = 999;
static const int32_t INJECTOR_UID = 1001;


// --- FakeInputDispatcherPolicy ---

class FakeInputDispatcherPolicy : public InputDispatcherPolicyInterface {
protected:
    virtual ~FakeInputDispatcherPolicy() {
    }

public:
    FakeInputDispatcherPolicy() {
    }

private:
    virtual void notifyConfigurationChanged(nsecs_t when) {
    }

    virtual nsecs_t notifyANR(const sp<InputApplicationHandle>& inputApplicationHandle,
            const sp<InputChannel>& inputChannel) {
        return 0;
    }

    virtual void notifyInputChannelBroken(const sp<InputChannel>& inputChannel) {
    }

    virtual nsecs_t getKeyRepeatTimeout() {
        return 500 * 1000000LL;
    }

    virtual nsecs_t getKeyRepeatDelay() {
        return 50 * 1000000LL;
    }

    virtual int32_t getMaxEventsPerSecond() {
        return 60;
    }

    virtual void interceptKeyBeforeQueueing(nsecs_t when, int32_t deviceId,
            int32_t action, int32_t& flags, int32_t keyCode, int32_t scanCode,
            uint32_t& policyFlags) {
    }

    virtual void interceptGenericBeforeQueueing(nsecs_t when, uint32_t& policyFlags) {
    }

    virtual bool interceptKeyBeforeDispatching(const sp<InputChannel>& inputChannel,
            const KeyEvent* keyEvent, uint32_t policyFlags) {
        return false;
    }

    virtual void notifySwitch(nsecs_t when,
            int32_t switchCode, int32_t switchValue, uint32_t policyFlags) {
    }

    virtual void pokeUserActivity(nsecs_t eventTime, int32_t eventType) {
    }

    virtual bool checkInjectEventsPermissionNonReentrant(
            int32_t injectorPid, int32_t injectorUid) {
        return false;
    }
};


// --- InputDispatcherTest ---

class InputDispatcherTest : public testing::Test {
protected:
    sp<FakeInputDispatcherPolicy> mFakePolicy;
    sp<InputDispatcher> mDispatcher;

    virtual void SetUp() {
        mFakePolicy = new FakeInputDispatcherPolicy();
        mDispatcher = new InputDispatcher(mFakePolicy);
    }

    virtual void TearDown() {
        mFakePolicy.clear();
        mDispatcher.clear();
    }
};


TEST_F(InputDispatcherTest, InjectInputEvent_ValidatesKeyEvents) {
    KeyEvent event;

    // Rejects undefined key actions.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_KEYBOARD,
            /*action*/ -1, 0,
            AKEYCODE_A, KEY_A, AMETA_NONE, 0, ARBITRARY_TIME, ARBITRARY_TIME);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject key events with undefined action.";

    // Rejects ACTION_MULTIPLE since it is not supported despite being defined in the API.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_KEYBOARD,
            AKEY_EVENT_ACTION_MULTIPLE, 0,
            AKEYCODE_A, KEY_A, AMETA_NONE, 0, ARBITRARY_TIME, ARBITRARY_TIME);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject key events with ACTION_MULTIPLE.";
}

TEST_F(InputDispatcherTest, InjectInputEvent_ValidatesMotionEvents) {
    MotionEvent event;
    int32_t pointerIds[MAX_POINTERS + 1];
    PointerCoords pointerCoords[MAX_POINTERS + 1];
    for (int i = 0; i <= MAX_POINTERS; i++) {
        pointerIds[i] = i;
    }

    // Rejects undefined motion actions.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            /*action*/ -1, 0, 0, AMETA_NONE, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerIds, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject motion events with undefined action.";

    // Rejects pointer down with invalid index.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_POINTER_DOWN | (1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            0, 0, AMETA_NONE, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerIds, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject motion events with pointer down index too large.";

    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_POINTER_DOWN | (-1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            0, 0, AMETA_NONE, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerIds, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject motion events with pointer down index too small.";

    // Rejects pointer up with invalid index.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_POINTER_UP | (1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            0, 0, AMETA_NONE, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerIds, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject motion events with pointer up index too large.";

    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_POINTER_UP | (-1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            0, 0, AMETA_NONE, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerIds, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject motion events with pointer up index too small.";

    // Rejects motion events with invalid number of pointers.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_DOWN, 0, 0, AMETA_NONE, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 0, pointerIds, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject motion events with 0 pointers.";

    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_DOWN, 0, 0, AMETA_NONE, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ MAX_POINTERS + 1, pointerIds, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject motion events with more than MAX_POINTERS pointers.";

    // Rejects motion events with invalid pointer ids.
    pointerIds[0] = -1;
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_DOWN, 0, 0, AMETA_NONE, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerIds, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject motion events with pointer ids less than 0.";

    pointerIds[0] = MAX_POINTER_ID + 1;
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_DOWN, 0, 0, AMETA_NONE, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerIds, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject motion events with pointer ids greater than MAX_POINTER_ID.";

    // Rejects motion events with duplicate pointer ids.
    pointerIds[0] = 1;
    pointerIds[1] = 1;
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_DOWN, 0, 0, AMETA_NONE, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 2, pointerIds, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0))
            << "Should reject motion events with duplicate pointer ids.";
}

} // namespace android
