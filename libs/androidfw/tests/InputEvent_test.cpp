/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <androidfw/Input.h>
#include <gtest/gtest.h>
#include <binder/Parcel.h>

#include <math.h>
#include <core/SkMatrix.h>

namespace android {

class BaseTest : public testing::Test {
protected:
    virtual void SetUp() { }
    virtual void TearDown() { }
};

// --- PointerCoordsTest ---

class PointerCoordsTest : public BaseTest {
};

TEST_F(PointerCoordsTest, ClearSetsBitsToZero) {
    PointerCoords coords;
    coords.clear();

    ASSERT_EQ(0ULL, coords.bits);
}

TEST_F(PointerCoordsTest, AxisValues) {
    float* valuePtr;
    PointerCoords coords;
    coords.clear();

    // Check invariants when no axes are present.
    ASSERT_EQ(0, coords.getAxisValue(0))
            << "getAxisValue should return zero because axis is not present";
    ASSERT_EQ(0, coords.getAxisValue(1))
            << "getAxisValue should return zero because axis is not present";

    // Set first axis.
    ASSERT_EQ(OK, coords.setAxisValue(1, 5));
    ASSERT_EQ(0x00000002ULL, coords.bits);
    ASSERT_EQ(5, coords.values[0]);

    ASSERT_EQ(0, coords.getAxisValue(0))
            << "getAxisValue should return zero because axis is not present";
    ASSERT_EQ(5, coords.getAxisValue(1))
            << "getAxisValue should return value of axis";

    // Set an axis with a higher id than all others.  (appending value at the end)
    ASSERT_EQ(OK, coords.setAxisValue(3, 2));
    ASSERT_EQ(0x0000000aULL, coords.bits);
    ASSERT_EQ(5, coords.values[0]);
    ASSERT_EQ(2, coords.values[1]);

    ASSERT_EQ(0, coords.getAxisValue(0))
            << "getAxisValue should return zero because axis is not present";
    ASSERT_EQ(5, coords.getAxisValue(1))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(0, coords.getAxisValue(2))
            << "getAxisValue should return zero because axis is not present";
    ASSERT_EQ(2, coords.getAxisValue(3))
            << "getAxisValue should return value of axis";

    // Set an axis with an id lower than all others.  (prepending value at beginning)
    ASSERT_EQ(OK, coords.setAxisValue(0, 4));
    ASSERT_EQ(0x0000000bULL, coords.bits);
    ASSERT_EQ(4, coords.values[0]);
    ASSERT_EQ(5, coords.values[1]);
    ASSERT_EQ(2, coords.values[2]);

    ASSERT_EQ(4, coords.getAxisValue(0))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(5, coords.getAxisValue(1))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(0, coords.getAxisValue(2))
            << "getAxisValue should return zero because axis is not present";
    ASSERT_EQ(2, coords.getAxisValue(3))
            << "getAxisValue should return value of axis";

    // Set an axis with an id between the others.  (inserting value in the middle)
    ASSERT_EQ(OK, coords.setAxisValue(2, 1));
    ASSERT_EQ(0x0000000fULL, coords.bits);
    ASSERT_EQ(4, coords.values[0]);
    ASSERT_EQ(5, coords.values[1]);
    ASSERT_EQ(1, coords.values[2]);
    ASSERT_EQ(2, coords.values[3]);

    ASSERT_EQ(4, coords.getAxisValue(0))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(5, coords.getAxisValue(1))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(1, coords.getAxisValue(2))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(2, coords.getAxisValue(3))
            << "getAxisValue should return value of axis";

    // Set an existing axis value in place.
    ASSERT_EQ(OK, coords.setAxisValue(1, 6));
    ASSERT_EQ(0x0000000fULL, coords.bits);
    ASSERT_EQ(4, coords.values[0]);
    ASSERT_EQ(6, coords.values[1]);
    ASSERT_EQ(1, coords.values[2]);
    ASSERT_EQ(2, coords.values[3]);

    ASSERT_EQ(4, coords.getAxisValue(0))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(6, coords.getAxisValue(1))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(1, coords.getAxisValue(2))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(2, coords.getAxisValue(3))
            << "getAxisValue should return value of axis";

    // Set maximum number of axes.
    for (size_t axis = 4; axis < PointerCoords::MAX_AXES; axis++) {
        ASSERT_EQ(OK, coords.setAxisValue(axis, axis));
    }
    ASSERT_EQ(PointerCoords::MAX_AXES, __builtin_popcountll(coords.bits));

    // Try to set one more axis beyond maximum number.
    // Ensure bits are unchanged.
    ASSERT_EQ(NO_MEMORY, coords.setAxisValue(PointerCoords::MAX_AXES, 100));
    ASSERT_EQ(PointerCoords::MAX_AXES, __builtin_popcountll(coords.bits));
}

TEST_F(PointerCoordsTest, Parcel) {
    Parcel parcel;

    PointerCoords inCoords;
    inCoords.clear();
    PointerCoords outCoords;

    // Round trip with empty coords.
    inCoords.writeToParcel(&parcel);
    parcel.setDataPosition(0);
    outCoords.readFromParcel(&parcel);

    ASSERT_EQ(0ULL, outCoords.bits);

    // Round trip with some values.
    parcel.freeData();
    inCoords.setAxisValue(2, 5);
    inCoords.setAxisValue(5, 8);

    inCoords.writeToParcel(&parcel);
    parcel.setDataPosition(0);
    outCoords.readFromParcel(&parcel);

    ASSERT_EQ(outCoords.bits, inCoords.bits);
    ASSERT_EQ(outCoords.values[0], inCoords.values[0]);
    ASSERT_EQ(outCoords.values[1], inCoords.values[1]);
}


// --- KeyEventTest ---

class KeyEventTest : public BaseTest {
};

TEST_F(KeyEventTest, Properties) {
    KeyEvent event;

    // Initialize and get properties.
    const nsecs_t ARBITRARY_DOWN_TIME = 1;
    const nsecs_t ARBITRARY_EVENT_TIME = 2;
    event.initialize(2, AINPUT_SOURCE_GAMEPAD, AKEY_EVENT_ACTION_DOWN,
            AKEY_EVENT_FLAG_FROM_SYSTEM, AKEYCODE_BUTTON_X, 121,
            AMETA_ALT_ON, 1, ARBITRARY_DOWN_TIME, ARBITRARY_EVENT_TIME);

    ASSERT_EQ(AINPUT_EVENT_TYPE_KEY, event.getType());
    ASSERT_EQ(2, event.getDeviceId());
    ASSERT_EQ(AINPUT_SOURCE_GAMEPAD, event.getSource());
    ASSERT_EQ(AKEY_EVENT_ACTION_DOWN, event.getAction());
    ASSERT_EQ(AKEY_EVENT_FLAG_FROM_SYSTEM, event.getFlags());
    ASSERT_EQ(AKEYCODE_BUTTON_X, event.getKeyCode());
    ASSERT_EQ(121, event.getScanCode());
    ASSERT_EQ(AMETA_ALT_ON, event.getMetaState());
    ASSERT_EQ(1, event.getRepeatCount());
    ASSERT_EQ(ARBITRARY_DOWN_TIME, event.getDownTime());
    ASSERT_EQ(ARBITRARY_EVENT_TIME, event.getEventTime());

    // Set source.
    event.setSource(AINPUT_SOURCE_JOYSTICK);
    ASSERT_EQ(AINPUT_SOURCE_JOYSTICK, event.getSource());
}


// --- MotionEventTest ---

class MotionEventTest : public BaseTest {
protected:
    static const nsecs_t ARBITRARY_DOWN_TIME;
    static const nsecs_t ARBITRARY_EVENT_TIME;
    static const float X_OFFSET;
    static const float Y_OFFSET;

    void initializeEventWithHistory(MotionEvent* event);
    void assertEqualsEventWithHistory(const MotionEvent* event);
};

const nsecs_t MotionEventTest::ARBITRARY_DOWN_TIME = 1;
const nsecs_t MotionEventTest::ARBITRARY_EVENT_TIME = 2;
const float MotionEventTest::X_OFFSET = 1.0f;
const float MotionEventTest::Y_OFFSET = 1.1f;

void MotionEventTest::initializeEventWithHistory(MotionEvent* event) {
    PointerProperties pointerProperties[2];
    pointerProperties[0].clear();
    pointerProperties[0].id = 1;
    pointerProperties[0].toolType = AMOTION_EVENT_TOOL_TYPE_FINGER;
    pointerProperties[1].clear();
    pointerProperties[1].id = 2;
    pointerProperties[1].toolType = AMOTION_EVENT_TOOL_TYPE_STYLUS;

    PointerCoords pointerCoords[2];
    pointerCoords[0].clear();
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X, 10);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y, 11);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 12);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_SIZE, 13);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR, 14);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR, 15);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR, 16);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR, 17);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, 18);
    pointerCoords[1].clear();
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_X, 20);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_Y, 21);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 22);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_SIZE, 23);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR, 24);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR, 25);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR, 26);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR, 27);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, 28);
    event->initialize(2, AINPUT_SOURCE_TOUCHSCREEN, AMOTION_EVENT_ACTION_MOVE,
            AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED,
            AMOTION_EVENT_EDGE_FLAG_TOP, AMETA_ALT_ON, AMOTION_EVENT_BUTTON_PRIMARY,
            X_OFFSET, Y_OFFSET, 2.0f, 2.1f,
            ARBITRARY_DOWN_TIME, ARBITRARY_EVENT_TIME,
            2, pointerProperties, pointerCoords);

    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X, 110);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y, 111);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 112);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_SIZE, 113);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR, 114);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR, 115);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR, 116);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR, 117);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, 118);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_X, 120);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_Y, 121);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 122);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_SIZE, 123);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR, 124);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR, 125);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR, 126);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR, 127);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, 128);
    event->addSample(ARBITRARY_EVENT_TIME + 1, pointerCoords);

    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_X, 210);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_Y, 211);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 212);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_SIZE, 213);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR, 214);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR, 215);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR, 216);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR, 217);
    pointerCoords[0].setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, 218);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_X, 220);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_Y, 221);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 222);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_SIZE, 223);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR, 224);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR, 225);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR, 226);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR, 227);
    pointerCoords[1].setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, 228);
    event->addSample(ARBITRARY_EVENT_TIME + 2, pointerCoords);
}

void MotionEventTest::assertEqualsEventWithHistory(const MotionEvent* event) {
    // Check properties.
    ASSERT_EQ(AINPUT_EVENT_TYPE_MOTION, event->getType());
    ASSERT_EQ(2, event->getDeviceId());
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, event->getSource());
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, event->getAction());
    ASSERT_EQ(AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED, event->getFlags());
    ASSERT_EQ(AMOTION_EVENT_EDGE_FLAG_TOP, event->getEdgeFlags());
    ASSERT_EQ(AMETA_ALT_ON, event->getMetaState());
    ASSERT_EQ(AMOTION_EVENT_BUTTON_PRIMARY, event->getButtonState());
    ASSERT_EQ(X_OFFSET, event->getXOffset());
    ASSERT_EQ(Y_OFFSET, event->getYOffset());
    ASSERT_EQ(2.0f, event->getXPrecision());
    ASSERT_EQ(2.1f, event->getYPrecision());
    ASSERT_EQ(ARBITRARY_DOWN_TIME, event->getDownTime());

    ASSERT_EQ(2U, event->getPointerCount());
    ASSERT_EQ(1, event->getPointerId(0));
    ASSERT_EQ(AMOTION_EVENT_TOOL_TYPE_FINGER, event->getToolType(0));
    ASSERT_EQ(2, event->getPointerId(1));
    ASSERT_EQ(AMOTION_EVENT_TOOL_TYPE_STYLUS, event->getToolType(1));

    ASSERT_EQ(2U, event->getHistorySize());

    // Check data.
    ASSERT_EQ(ARBITRARY_EVENT_TIME, event->getHistoricalEventTime(0));
    ASSERT_EQ(ARBITRARY_EVENT_TIME + 1, event->getHistoricalEventTime(1));
    ASSERT_EQ(ARBITRARY_EVENT_TIME + 2, event->getEventTime());

    ASSERT_EQ(11, event->getHistoricalRawPointerCoords(0, 0)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));
    ASSERT_EQ(21, event->getHistoricalRawPointerCoords(1, 0)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));
    ASSERT_EQ(111, event->getHistoricalRawPointerCoords(0, 1)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));
    ASSERT_EQ(121, event->getHistoricalRawPointerCoords(1, 1)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));
    ASSERT_EQ(211, event->getRawPointerCoords(0)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));
    ASSERT_EQ(221, event->getRawPointerCoords(1)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));

    ASSERT_EQ(11, event->getHistoricalRawAxisValue(AMOTION_EVENT_AXIS_Y, 0, 0));
    ASSERT_EQ(21, event->getHistoricalRawAxisValue(AMOTION_EVENT_AXIS_Y, 1, 0));
    ASSERT_EQ(111, event->getHistoricalRawAxisValue(AMOTION_EVENT_AXIS_Y, 0, 1));
    ASSERT_EQ(121, event->getHistoricalRawAxisValue(AMOTION_EVENT_AXIS_Y, 1, 1));
    ASSERT_EQ(211, event->getRawAxisValue(AMOTION_EVENT_AXIS_Y, 0));
    ASSERT_EQ(221, event->getRawAxisValue(AMOTION_EVENT_AXIS_Y, 1));

    ASSERT_EQ(10, event->getHistoricalRawX(0, 0));
    ASSERT_EQ(20, event->getHistoricalRawX(1, 0));
    ASSERT_EQ(110, event->getHistoricalRawX(0, 1));
    ASSERT_EQ(120, event->getHistoricalRawX(1, 1));
    ASSERT_EQ(210, event->getRawX(0));
    ASSERT_EQ(220, event->getRawX(1));

    ASSERT_EQ(11, event->getHistoricalRawY(0, 0));
    ASSERT_EQ(21, event->getHistoricalRawY(1, 0));
    ASSERT_EQ(111, event->getHistoricalRawY(0, 1));
    ASSERT_EQ(121, event->getHistoricalRawY(1, 1));
    ASSERT_EQ(211, event->getRawY(0));
    ASSERT_EQ(221, event->getRawY(1));

    ASSERT_EQ(X_OFFSET + 10, event->getHistoricalX(0, 0));
    ASSERT_EQ(X_OFFSET + 20, event->getHistoricalX(1, 0));
    ASSERT_EQ(X_OFFSET + 110, event->getHistoricalX(0, 1));
    ASSERT_EQ(X_OFFSET + 120, event->getHistoricalX(1, 1));
    ASSERT_EQ(X_OFFSET + 210, event->getX(0));
    ASSERT_EQ(X_OFFSET + 220, event->getX(1));

    ASSERT_EQ(Y_OFFSET + 11, event->getHistoricalY(0, 0));
    ASSERT_EQ(Y_OFFSET + 21, event->getHistoricalY(1, 0));
    ASSERT_EQ(Y_OFFSET + 111, event->getHistoricalY(0, 1));
    ASSERT_EQ(Y_OFFSET + 121, event->getHistoricalY(1, 1));
    ASSERT_EQ(Y_OFFSET + 211, event->getY(0));
    ASSERT_EQ(Y_OFFSET + 221, event->getY(1));

    ASSERT_EQ(12, event->getHistoricalPressure(0, 0));
    ASSERT_EQ(22, event->getHistoricalPressure(1, 0));
    ASSERT_EQ(112, event->getHistoricalPressure(0, 1));
    ASSERT_EQ(122, event->getHistoricalPressure(1, 1));
    ASSERT_EQ(212, event->getPressure(0));
    ASSERT_EQ(222, event->getPressure(1));

    ASSERT_EQ(13, event->getHistoricalSize(0, 0));
    ASSERT_EQ(23, event->getHistoricalSize(1, 0));
    ASSERT_EQ(113, event->getHistoricalSize(0, 1));
    ASSERT_EQ(123, event->getHistoricalSize(1, 1));
    ASSERT_EQ(213, event->getSize(0));
    ASSERT_EQ(223, event->getSize(1));

    ASSERT_EQ(14, event->getHistoricalTouchMajor(0, 0));
    ASSERT_EQ(24, event->getHistoricalTouchMajor(1, 0));
    ASSERT_EQ(114, event->getHistoricalTouchMajor(0, 1));
    ASSERT_EQ(124, event->getHistoricalTouchMajor(1, 1));
    ASSERT_EQ(214, event->getTouchMajor(0));
    ASSERT_EQ(224, event->getTouchMajor(1));

    ASSERT_EQ(15, event->getHistoricalTouchMinor(0, 0));
    ASSERT_EQ(25, event->getHistoricalTouchMinor(1, 0));
    ASSERT_EQ(115, event->getHistoricalTouchMinor(0, 1));
    ASSERT_EQ(125, event->getHistoricalTouchMinor(1, 1));
    ASSERT_EQ(215, event->getTouchMinor(0));
    ASSERT_EQ(225, event->getTouchMinor(1));

    ASSERT_EQ(16, event->getHistoricalToolMajor(0, 0));
    ASSERT_EQ(26, event->getHistoricalToolMajor(1, 0));
    ASSERT_EQ(116, event->getHistoricalToolMajor(0, 1));
    ASSERT_EQ(126, event->getHistoricalToolMajor(1, 1));
    ASSERT_EQ(216, event->getToolMajor(0));
    ASSERT_EQ(226, event->getToolMajor(1));

    ASSERT_EQ(17, event->getHistoricalToolMinor(0, 0));
    ASSERT_EQ(27, event->getHistoricalToolMinor(1, 0));
    ASSERT_EQ(117, event->getHistoricalToolMinor(0, 1));
    ASSERT_EQ(127, event->getHistoricalToolMinor(1, 1));
    ASSERT_EQ(217, event->getToolMinor(0));
    ASSERT_EQ(227, event->getToolMinor(1));

    ASSERT_EQ(18, event->getHistoricalOrientation(0, 0));
    ASSERT_EQ(28, event->getHistoricalOrientation(1, 0));
    ASSERT_EQ(118, event->getHistoricalOrientation(0, 1));
    ASSERT_EQ(128, event->getHistoricalOrientation(1, 1));
    ASSERT_EQ(218, event->getOrientation(0));
    ASSERT_EQ(228, event->getOrientation(1));
}

TEST_F(MotionEventTest, Properties) {
    MotionEvent event;

    // Initialize, add samples and check properties.
    initializeEventWithHistory(&event);
    ASSERT_NO_FATAL_FAILURE(assertEqualsEventWithHistory(&event));

    // Set source.
    event.setSource(AINPUT_SOURCE_JOYSTICK);
    ASSERT_EQ(AINPUT_SOURCE_JOYSTICK, event.getSource());

    // Set action.
    event.setAction(AMOTION_EVENT_ACTION_CANCEL);
    ASSERT_EQ(AMOTION_EVENT_ACTION_CANCEL, event.getAction());

    // Set meta state.
    event.setMetaState(AMETA_CTRL_ON);
    ASSERT_EQ(AMETA_CTRL_ON, event.getMetaState());
}

TEST_F(MotionEventTest, CopyFrom_KeepHistory) {
    MotionEvent event;
    initializeEventWithHistory(&event);

    MotionEvent copy;
    copy.copyFrom(&event, true /*keepHistory*/);

    ASSERT_NO_FATAL_FAILURE(assertEqualsEventWithHistory(&event));
}

TEST_F(MotionEventTest, CopyFrom_DoNotKeepHistory) {
    MotionEvent event;
    initializeEventWithHistory(&event);

    MotionEvent copy;
    copy.copyFrom(&event, false /*keepHistory*/);

    ASSERT_EQ(event.getPointerCount(), copy.getPointerCount());
    ASSERT_EQ(0U, copy.getHistorySize());

    ASSERT_EQ(event.getPointerId(0), copy.getPointerId(0));
    ASSERT_EQ(event.getPointerId(1), copy.getPointerId(1));

    ASSERT_EQ(event.getEventTime(), copy.getEventTime());

    ASSERT_EQ(event.getX(0), copy.getX(0));
}

TEST_F(MotionEventTest, OffsetLocation) {
    MotionEvent event;
    initializeEventWithHistory(&event);

    event.offsetLocation(5.0f, -2.0f);

    ASSERT_EQ(X_OFFSET + 5.0f, event.getXOffset());
    ASSERT_EQ(Y_OFFSET - 2.0f, event.getYOffset());
}

TEST_F(MotionEventTest, Scale) {
    MotionEvent event;
    initializeEventWithHistory(&event);

    event.scale(2.0f);

    ASSERT_EQ(X_OFFSET * 2, event.getXOffset());
    ASSERT_EQ(Y_OFFSET * 2, event.getYOffset());

    ASSERT_EQ(210 * 2, event.getRawX(0));
    ASSERT_EQ(211 * 2, event.getRawY(0));
    ASSERT_EQ((X_OFFSET + 210) * 2, event.getX(0));
    ASSERT_EQ((Y_OFFSET + 211) * 2, event.getY(0));
    ASSERT_EQ(212, event.getPressure(0));
    ASSERT_EQ(213, event.getSize(0));
    ASSERT_EQ(214 * 2, event.getTouchMajor(0));
    ASSERT_EQ(215 * 2, event.getTouchMinor(0));
    ASSERT_EQ(216 * 2, event.getToolMajor(0));
    ASSERT_EQ(217 * 2, event.getToolMinor(0));
    ASSERT_EQ(218, event.getOrientation(0));
}

TEST_F(MotionEventTest, Parcel) {
    Parcel parcel;

    MotionEvent inEvent;
    initializeEventWithHistory(&inEvent);
    MotionEvent outEvent;

    // Round trip.
    inEvent.writeToParcel(&parcel);
    parcel.setDataPosition(0);
    outEvent.readFromParcel(&parcel);

    ASSERT_NO_FATAL_FAILURE(assertEqualsEventWithHistory(&outEvent));
}

TEST_F(MotionEventTest, Transform) {
    // Generate some points on a circle.
    // Each point 'i' is a point on a circle of radius ROTATION centered at (3,2) at an angle
    // of ARC * i degrees clockwise relative to the Y axis.
    // The geometrical representation is irrelevant to the test, it's just easy to generate
    // and check rotation.  We set the orientation to the same angle.
    // Coordinate system: down is increasing Y, right is increasing X.
    const float PI_180 = float(M_PI / 180);
    const float RADIUS = 10;
    const float ARC = 36;
    const float ROTATION = ARC * 2;

    const size_t pointerCount = 11;
    PointerProperties pointerProperties[pointerCount];
    PointerCoords pointerCoords[pointerCount];
    for (size_t i = 0; i < pointerCount; i++) {
        float angle = float(i * ARC * PI_180);
        pointerProperties[i].clear();
        pointerProperties[i].id = i;
        pointerCoords[i].clear();
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_X, sinf(angle) * RADIUS + 3);
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_Y, -cosf(angle) * RADIUS + 2);
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, angle);
    }
    MotionEvent event;
    event.initialize(0, 0, AMOTION_EVENT_ACTION_MOVE, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, pointerCount, pointerProperties, pointerCoords);
    float originalRawX = 0 + 3;
    float originalRawY = -RADIUS + 2;

    // Check original raw X and Y assumption.
    ASSERT_NEAR(originalRawX, event.getRawX(0), 0.001);
    ASSERT_NEAR(originalRawY, event.getRawY(0), 0.001);

    // Now translate the motion event so the circle's origin is at (0,0).
    event.offsetLocation(-3, -2);

    // Offsetting the location should preserve the raw X and Y of the first point.
    ASSERT_NEAR(originalRawX, event.getRawX(0), 0.001);
    ASSERT_NEAR(originalRawY, event.getRawY(0), 0.001);

    // Apply a rotation about the origin by ROTATION degrees clockwise.
    SkMatrix matrix;
    matrix.setRotate(ROTATION);
    event.transform(&matrix);

    // Check the points.
    for (size_t i = 0; i < pointerCount; i++) {
        float angle = float((i * ARC + ROTATION) * PI_180);
        ASSERT_NEAR(sinf(angle) * RADIUS, event.getX(i), 0.001);
        ASSERT_NEAR(-cosf(angle) * RADIUS, event.getY(i), 0.001);
        ASSERT_NEAR(tanf(angle), tanf(event.getOrientation(i)), 0.1);
    }

    // Applying the transformation should preserve the raw X and Y of the first point.
    ASSERT_NEAR(originalRawX, event.getRawX(0), 0.001);
    ASSERT_NEAR(originalRawY, event.getRawY(0), 0.001);
}

} // namespace android
