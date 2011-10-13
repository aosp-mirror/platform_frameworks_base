/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include "../InputDispatcher.h"

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
    InputDispatcherConfiguration mConfig;

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
            const sp<InputWindowHandle>& inputWindowHandle) {
        return 0;
    }

    virtual void notifyInputChannelBroken(const sp<InputWindowHandle>& inputWindowHandle) {
    }

    virtual void getDispatcherConfiguration(InputDispatcherConfiguration* outConfig) {
        *outConfig = mConfig;
    }

    virtual bool isKeyRepeatEnabled() {
        return true;
    }

    virtual bool filterInputEvent(const InputEvent* inputEvent, uint32_t policyFlags) {
        return true;
    }

    virtual void interceptKeyBeforeQueueing(const KeyEvent* keyEvent, uint32_t& policyFlags) {
    }

    virtual void interceptMotionBeforeQueueing(nsecs_t when, uint32_t& policyFlags) {
    }

    virtual nsecs_t interceptKeyBeforeDispatching(const sp<InputWindowHandle>& inputWindowHandle,
            const KeyEvent* keyEvent, uint32_t policyFlags) {
        return 0;
    }

    virtual bool dispatchUnhandledKey(const sp<InputWindowHandle>& inputWindowHandle,
            const KeyEvent* keyEvent, uint32_t policyFlags, KeyEvent* outFallbackKeyEvent) {
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
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject key events with undefined action.";

    // Rejects ACTION_MULTIPLE since it is not supported despite being defined in the API.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_KEYBOARD,
            AKEY_EVENT_ACTION_MULTIPLE, 0,
            AKEYCODE_A, KEY_A, AMETA_NONE, 0, ARBITRARY_TIME, ARBITRARY_TIME);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject key events with ACTION_MULTIPLE.";
}

TEST_F(InputDispatcherTest, InjectInputEvent_ValidatesMotionEvents) {
    MotionEvent event;
    PointerProperties pointerProperties[MAX_POINTERS + 1];
    PointerCoords pointerCoords[MAX_POINTERS + 1];
    for (int i = 0; i <= MAX_POINTERS; i++) {
        pointerProperties[i].clear();
        pointerProperties[i].id = i;
        pointerCoords[i].clear();
    }

    // Rejects undefined motion actions.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            /*action*/ -1, 0, 0, AMETA_NONE, 0, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerProperties, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject motion events with undefined action.";

    // Rejects pointer down with invalid index.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_POINTER_DOWN | (1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            0, 0, AMETA_NONE, 0, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerProperties, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject motion events with pointer down index too large.";

    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_POINTER_DOWN | (-1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            0, 0, AMETA_NONE, 0, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerProperties, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject motion events with pointer down index too small.";

    // Rejects pointer up with invalid index.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_POINTER_UP | (1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            0, 0, AMETA_NONE, 0, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerProperties, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject motion events with pointer up index too large.";

    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_POINTER_UP | (-1 << AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT),
            0, 0, AMETA_NONE, 0, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerProperties, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject motion events with pointer up index too small.";

    // Rejects motion events with invalid number of pointers.
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_DOWN, 0, 0, AMETA_NONE, 0, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 0, pointerProperties, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject motion events with 0 pointers.";

    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_DOWN, 0, 0, AMETA_NONE, 0, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ MAX_POINTERS + 1, pointerProperties, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject motion events with more than MAX_POINTERS pointers.";

    // Rejects motion events with invalid pointer ids.
    pointerProperties[0].id = -1;
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_DOWN, 0, 0, AMETA_NONE, 0, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerProperties, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject motion events with pointer ids less than 0.";

    pointerProperties[0].id = MAX_POINTER_ID + 1;
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_DOWN, 0, 0, AMETA_NONE, 0, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 1, pointerProperties, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject motion events with pointer ids greater than MAX_POINTER_ID.";

    // Rejects motion events with duplicate pointer ids.
    pointerProperties[0].id = 1;
    pointerProperties[1].id = 1;
    event.initialize(DEVICE_ID, AINPUT_SOURCE_TOUCHSCREEN,
            AMOTION_EVENT_ACTION_DOWN, 0, 0, AMETA_NONE, 0, 0, 0, 0, 0,
            ARBITRARY_TIME, ARBITRARY_TIME,
            /*pointerCount*/ 2, pointerProperties, pointerCoords);
    ASSERT_EQ(INPUT_EVENT_INJECTION_FAILED, mDispatcher->injectInputEvent(&event,
            INJECTOR_PID, INJECTOR_UID, INPUT_EVENT_INJECTION_SYNC_NONE, 0, 0))
            << "Should reject motion events with duplicate pointer ids.";
}

} // namespace android
