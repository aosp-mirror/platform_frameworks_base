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

#include <ui/Input.h>
#include <gtest/gtest.h>
#include <binder/Parcel.h>

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

    ASSERT_EQ(0U, coords.bits);
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

    ASSERT_EQ(NULL, coords.editAxisValue(0))
            << "editAxisValue should return null because axis is not present";

    // Set first axis.
    ASSERT_EQ(OK, coords.setAxisValue(1, 5));
    ASSERT_EQ(0x00000002U, coords.bits);
    ASSERT_EQ(5, coords.values[0]);

    ASSERT_EQ(0, coords.getAxisValue(0))
            << "getAxisValue should return zero because axis is not present";
    ASSERT_EQ(5, coords.getAxisValue(1))
            << "getAxisValue should return value of axis";

    // Set an axis with a higher id than all others.  (appending value at the end)
    ASSERT_EQ(OK, coords.setAxisValue(3, 2));
    ASSERT_EQ(0x0000000aU, coords.bits);
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
    ASSERT_EQ(0x0000000bU, coords.bits);
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

    // Edit an existing axis value in place.
    valuePtr = coords.editAxisValue(1);
    ASSERT_EQ(5, *valuePtr)
            << "editAxisValue should return pointer to axis value";

    *valuePtr = 7;
    ASSERT_EQ(7, coords.getAxisValue(1))
            << "getAxisValue should return value of axis";

    // Set an axis with an id between the others.  (inserting value in the middle)
    ASSERT_EQ(OK, coords.setAxisValue(2, 1));
    ASSERT_EQ(0x0000000fU, coords.bits);
    ASSERT_EQ(4, coords.values[0]);
    ASSERT_EQ(7, coords.values[1]);
    ASSERT_EQ(1, coords.values[2]);
    ASSERT_EQ(2, coords.values[3]);

    ASSERT_EQ(4, coords.getAxisValue(0))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(7, coords.getAxisValue(1))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(1, coords.getAxisValue(2))
            << "getAxisValue should return value of axis";
    ASSERT_EQ(2, coords.getAxisValue(3))
            << "getAxisValue should return value of axis";

    // Set an existing axis value in place.
    ASSERT_EQ(OK, coords.setAxisValue(1, 6));
    ASSERT_EQ(0x0000000fU, coords.bits);
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
    ASSERT_EQ(PointerCoords::MAX_AXES, __builtin_popcount(coords.bits));

    // Try to set one more axis beyond maximum number.
    // Ensure bits are unchanged.
    ASSERT_EQ(NO_MEMORY, coords.setAxisValue(PointerCoords::MAX_AXES, 100));
    ASSERT_EQ(PointerCoords::MAX_AXES, __builtin_popcount(coords.bits));
}

TEST_F(PointerCoordsTest, ReadAndWriteParcel) {
    Parcel parcel;

    PointerCoords inCoords;
    inCoords.clear();
    PointerCoords outCoords;

    // Round trip with empty coords.
    inCoords.writeToParcel(&parcel);
    parcel.setDataPosition(0);
    outCoords.readFromParcel(&parcel);

    ASSERT_EQ(0U, outCoords.bits);

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
};

TEST_F(MotionEventTest, Properties) {
    MotionEvent event;

    // Initialize, add samples and get properties.
    const nsecs_t ARBITRARY_DOWN_TIME = 1;
    const nsecs_t ARBITRARY_EVENT_TIME = 2;
    const float X_OFFSET = 1.0f;
    const float Y_OFFSET = 1.1f;
    int32_t pointerIds[] = { 1, 2 };
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
    event.initialize(2, AINPUT_SOURCE_TOUCHSCREEN, AMOTION_EVENT_ACTION_MOVE,
            AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED,
            AMOTION_EVENT_EDGE_FLAG_TOP, AMETA_ALT_ON,
            X_OFFSET, Y_OFFSET, 2.0f, 2.1f,
            ARBITRARY_DOWN_TIME, ARBITRARY_EVENT_TIME,
            2, pointerIds, pointerCoords);

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
    event.addSample(ARBITRARY_EVENT_TIME + 1, pointerCoords);

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
    event.addSample(ARBITRARY_EVENT_TIME + 2, pointerCoords);

    ASSERT_EQ(AINPUT_EVENT_TYPE_MOTION, event.getType());
    ASSERT_EQ(2, event.getDeviceId());
    ASSERT_EQ(AINPUT_SOURCE_TOUCHSCREEN, event.getSource());
    ASSERT_EQ(AMOTION_EVENT_ACTION_MOVE, event.getAction());
    ASSERT_EQ(AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED, event.getFlags());
    ASSERT_EQ(AMOTION_EVENT_EDGE_FLAG_TOP, event.getEdgeFlags());
    ASSERT_EQ(AMETA_ALT_ON, event.getMetaState());
    ASSERT_EQ(X_OFFSET, event.getXOffset());
    ASSERT_EQ(Y_OFFSET, event.getYOffset());
    ASSERT_EQ(2.0f, event.getXPrecision());
    ASSERT_EQ(2.1f, event.getYPrecision());
    ASSERT_EQ(ARBITRARY_DOWN_TIME, event.getDownTime());

    ASSERT_EQ(2U, event.getPointerCount());
    ASSERT_EQ(1, event.getPointerId(0));
    ASSERT_EQ(2, event.getPointerId(1));

    ASSERT_EQ(2U, event.getHistorySize());

    // Get data.
    ASSERT_EQ(ARBITRARY_EVENT_TIME, event.getHistoricalEventTime(0));
    ASSERT_EQ(ARBITRARY_EVENT_TIME + 1, event.getHistoricalEventTime(1));
    ASSERT_EQ(ARBITRARY_EVENT_TIME + 2, event.getEventTime());

    ASSERT_EQ(11, event.getHistoricalRawPointerCoords(0, 0)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));
    ASSERT_EQ(21, event.getHistoricalRawPointerCoords(1, 0)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));
    ASSERT_EQ(111, event.getHistoricalRawPointerCoords(0, 1)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));
    ASSERT_EQ(121, event.getHistoricalRawPointerCoords(1, 1)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));
    ASSERT_EQ(211, event.getRawPointerCoords(0)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));
    ASSERT_EQ(221, event.getRawPointerCoords(1)->
            getAxisValue(AMOTION_EVENT_AXIS_Y));

    ASSERT_EQ(11, event.getHistoricalRawAxisValue(AMOTION_EVENT_AXIS_Y, 0, 0));
    ASSERT_EQ(21, event.getHistoricalRawAxisValue(AMOTION_EVENT_AXIS_Y, 1, 0));
    ASSERT_EQ(111, event.getHistoricalRawAxisValue(AMOTION_EVENT_AXIS_Y, 0, 1));
    ASSERT_EQ(121, event.getHistoricalRawAxisValue(AMOTION_EVENT_AXIS_Y, 1, 1));
    ASSERT_EQ(211, event.getRawAxisValue(AMOTION_EVENT_AXIS_Y, 0));
    ASSERT_EQ(221, event.getRawAxisValue(AMOTION_EVENT_AXIS_Y, 1));

    ASSERT_EQ(10, event.getHistoricalRawX(0, 0));
    ASSERT_EQ(20, event.getHistoricalRawX(1, 0));
    ASSERT_EQ(110, event.getHistoricalRawX(0, 1));
    ASSERT_EQ(120, event.getHistoricalRawX(1, 1));
    ASSERT_EQ(210, event.getRawX(0));
    ASSERT_EQ(220, event.getRawX(1));

    ASSERT_EQ(11, event.getHistoricalRawY(0, 0));
    ASSERT_EQ(21, event.getHistoricalRawY(1, 0));
    ASSERT_EQ(111, event.getHistoricalRawY(0, 1));
    ASSERT_EQ(121, event.getHistoricalRawY(1, 1));
    ASSERT_EQ(211, event.getRawY(0));
    ASSERT_EQ(221, event.getRawY(1));

    ASSERT_EQ(X_OFFSET + 10, event.getHistoricalX(0, 0));
    ASSERT_EQ(X_OFFSET + 20, event.getHistoricalX(1, 0));
    ASSERT_EQ(X_OFFSET + 110, event.getHistoricalX(0, 1));
    ASSERT_EQ(X_OFFSET + 120, event.getHistoricalX(1, 1));
    ASSERT_EQ(X_OFFSET + 210, event.getX(0));
    ASSERT_EQ(X_OFFSET + 220, event.getX(1));

    ASSERT_EQ(Y_OFFSET + 11, event.getHistoricalY(0, 0));
    ASSERT_EQ(Y_OFFSET + 21, event.getHistoricalY(1, 0));
    ASSERT_EQ(Y_OFFSET + 111, event.getHistoricalY(0, 1));
    ASSERT_EQ(Y_OFFSET + 121, event.getHistoricalY(1, 1));
    ASSERT_EQ(Y_OFFSET + 211, event.getY(0));
    ASSERT_EQ(Y_OFFSET + 221, event.getY(1));

    ASSERT_EQ(12, event.getHistoricalPressure(0, 0));
    ASSERT_EQ(22, event.getHistoricalPressure(1, 0));
    ASSERT_EQ(112, event.getHistoricalPressure(0, 1));
    ASSERT_EQ(122, event.getHistoricalPressure(1, 1));
    ASSERT_EQ(212, event.getPressure(0));
    ASSERT_EQ(222, event.getPressure(1));

    ASSERT_EQ(13, event.getHistoricalSize(0, 0));
    ASSERT_EQ(23, event.getHistoricalSize(1, 0));
    ASSERT_EQ(113, event.getHistoricalSize(0, 1));
    ASSERT_EQ(123, event.getHistoricalSize(1, 1));
    ASSERT_EQ(213, event.getSize(0));
    ASSERT_EQ(223, event.getSize(1));

    ASSERT_EQ(14, event.getHistoricalTouchMajor(0, 0));
    ASSERT_EQ(24, event.getHistoricalTouchMajor(1, 0));
    ASSERT_EQ(114, event.getHistoricalTouchMajor(0, 1));
    ASSERT_EQ(124, event.getHistoricalTouchMajor(1, 1));
    ASSERT_EQ(214, event.getTouchMajor(0));
    ASSERT_EQ(224, event.getTouchMajor(1));

    ASSERT_EQ(15, event.getHistoricalTouchMinor(0, 0));
    ASSERT_EQ(25, event.getHistoricalTouchMinor(1, 0));
    ASSERT_EQ(115, event.getHistoricalTouchMinor(0, 1));
    ASSERT_EQ(125, event.getHistoricalTouchMinor(1, 1));
    ASSERT_EQ(215, event.getTouchMinor(0));
    ASSERT_EQ(225, event.getTouchMinor(1));

    ASSERT_EQ(16, event.getHistoricalToolMajor(0, 0));
    ASSERT_EQ(26, event.getHistoricalToolMajor(1, 0));
    ASSERT_EQ(116, event.getHistoricalToolMajor(0, 1));
    ASSERT_EQ(126, event.getHistoricalToolMajor(1, 1));
    ASSERT_EQ(216, event.getToolMajor(0));
    ASSERT_EQ(226, event.getToolMajor(1));

    ASSERT_EQ(17, event.getHistoricalToolMinor(0, 0));
    ASSERT_EQ(27, event.getHistoricalToolMinor(1, 0));
    ASSERT_EQ(117, event.getHistoricalToolMinor(0, 1));
    ASSERT_EQ(127, event.getHistoricalToolMinor(1, 1));
    ASSERT_EQ(217, event.getToolMinor(0));
    ASSERT_EQ(227, event.getToolMinor(1));

    ASSERT_EQ(18, event.getHistoricalOrientation(0, 0));
    ASSERT_EQ(28, event.getHistoricalOrientation(1, 0));
    ASSERT_EQ(118, event.getHistoricalOrientation(0, 1));
    ASSERT_EQ(128, event.getHistoricalOrientation(1, 1));
    ASSERT_EQ(218, event.getOrientation(0));
    ASSERT_EQ(228, event.getOrientation(1));
}

} // namespace android
