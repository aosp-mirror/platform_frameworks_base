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

#include <androidfw/InputTransport.h>
#include <utils/Timers.h>
#include <utils/StopWatch.h>
#include <gtest/gtest.h>
#include <unistd.h>
#include <time.h>
#include <sys/mman.h>
#include <cutils/ashmem.h>

#include "TestHelpers.h"

namespace android {

class InputPublisherAndConsumerTest : public testing::Test {
protected:
    sp<InputChannel> serverChannel, clientChannel;
    InputPublisher* mPublisher;
    InputConsumer* mConsumer;
    PreallocatedInputEventFactory mEventFactory;

    virtual void SetUp() {
        status_t result = InputChannel::openInputChannelPair(String8("channel name"),
                serverChannel, clientChannel);

        mPublisher = new InputPublisher(serverChannel);
        mConsumer = new InputConsumer(clientChannel);
    }

    virtual void TearDown() {
        if (mPublisher) {
            delete mPublisher;
            mPublisher = NULL;
        }

        if (mConsumer) {
            delete mConsumer;
            mConsumer = NULL;
        }

        serverChannel.clear();
        clientChannel.clear();
    }

    void PublishAndConsumeKeyEvent();
    void PublishAndConsumeMotionEvent();
};

TEST_F(InputPublisherAndConsumerTest, GetChannel_ReturnsTheChannel) {
    EXPECT_EQ(serverChannel.get(), mPublisher->getChannel().get());
    EXPECT_EQ(clientChannel.get(), mConsumer->getChannel().get());
}

void InputPublisherAndConsumerTest::PublishAndConsumeKeyEvent() {
    status_t status;

    const uint32_t seq = 15;
    const int32_t deviceId = 1;
    const int32_t source = AINPUT_SOURCE_KEYBOARD;
    const int32_t action = AKEY_EVENT_ACTION_DOWN;
    const int32_t flags = AKEY_EVENT_FLAG_FROM_SYSTEM;
    const int32_t keyCode = AKEYCODE_ENTER;
    const int32_t scanCode = 13;
    const int32_t metaState = AMETA_ALT_LEFT_ON | AMETA_ALT_ON;
    const int32_t repeatCount = 1;
    const nsecs_t downTime = 3;
    const nsecs_t eventTime = 4;

    status = mPublisher->publishKeyEvent(seq, deviceId, source, action, flags,
            keyCode, scanCode, metaState, repeatCount, downTime, eventTime);
    ASSERT_EQ(OK, status)
            << "publisher publishKeyEvent should return OK";

    uint32_t consumeSeq;
    InputEvent* event;
    status = mConsumer->consume(&mEventFactory, true /*consumeBatches*/, -1, &consumeSeq, &event);
    ASSERT_EQ(OK, status)
            << "consumer consume should return OK";

    ASSERT_TRUE(event != NULL)
            << "consumer should have returned non-NULL event";
    ASSERT_EQ(AINPUT_EVENT_TYPE_KEY, event->getType())
            << "consumer should have returned a key event";

    KeyEvent* keyEvent = static_cast<KeyEvent*>(event);
    EXPECT_EQ(seq, consumeSeq);
    EXPECT_EQ(deviceId, keyEvent->getDeviceId());
    EXPECT_EQ(source, keyEvent->getSource());
    EXPECT_EQ(action, keyEvent->getAction());
    EXPECT_EQ(flags, keyEvent->getFlags());
    EXPECT_EQ(keyCode, keyEvent->getKeyCode());
    EXPECT_EQ(scanCode, keyEvent->getScanCode());
    EXPECT_EQ(metaState, keyEvent->getMetaState());
    EXPECT_EQ(repeatCount, keyEvent->getRepeatCount());
    EXPECT_EQ(downTime, keyEvent->getDownTime());
    EXPECT_EQ(eventTime, keyEvent->getEventTime());

    status = mConsumer->sendFinishedSignal(seq, true);
    ASSERT_EQ(OK, status)
            << "consumer sendFinishedSignal should return OK";

    uint32_t finishedSeq = 0;
    bool handled = false;
    status = mPublisher->receiveFinishedSignal(&finishedSeq, &handled);
    ASSERT_EQ(OK, status)
            << "publisher receiveFinishedSignal should return OK";
    ASSERT_EQ(seq, finishedSeq)
            << "publisher receiveFinishedSignal should have returned the original sequence number";
    ASSERT_TRUE(handled)
            << "publisher receiveFinishedSignal should have set handled to consumer's reply";
}

void InputPublisherAndConsumerTest::PublishAndConsumeMotionEvent() {
    status_t status;

    const uint32_t seq = 15;
    const int32_t deviceId = 1;
    const int32_t source = AINPUT_SOURCE_TOUCHSCREEN;
    const int32_t action = AMOTION_EVENT_ACTION_MOVE;
    const int32_t flags = AMOTION_EVENT_FLAG_WINDOW_IS_OBSCURED;
    const int32_t edgeFlags = AMOTION_EVENT_EDGE_FLAG_TOP;
    const int32_t metaState = AMETA_ALT_LEFT_ON | AMETA_ALT_ON;
    const int32_t buttonState = AMOTION_EVENT_BUTTON_PRIMARY;
    const float xOffset = -10;
    const float yOffset = -20;
    const float xPrecision = 0.25;
    const float yPrecision = 0.5;
    const nsecs_t downTime = 3;
    const size_t pointerCount = 3;
    const nsecs_t eventTime = 4;
    PointerProperties pointerProperties[pointerCount];
    PointerCoords pointerCoords[pointerCount];
    for (size_t i = 0; i < pointerCount; i++) {
        pointerProperties[i].clear();
        pointerProperties[i].id = (i + 2) % pointerCount;
        pointerProperties[i].toolType = AMOTION_EVENT_TOOL_TYPE_FINGER;

        pointerCoords[i].clear();
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_X, 100 * i);
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_Y, 200 * i);
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_PRESSURE, 0.5 * i);
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_SIZE, 0.7 * i);
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR, 1.5 * i);
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR, 1.7 * i);
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR, 2.5 * i);
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR, 2.7 * i);
        pointerCoords[i].setAxisValue(AMOTION_EVENT_AXIS_ORIENTATION, 3.5 * i);
    }

    status = mPublisher->publishMotionEvent(seq, deviceId, source, action, flags, edgeFlags,
            metaState, buttonState, xOffset, yOffset, xPrecision, yPrecision,
            downTime, eventTime, pointerCount,
            pointerProperties, pointerCoords);
    ASSERT_EQ(OK, status)
            << "publisher publishMotionEvent should return OK";

    uint32_t consumeSeq;
    InputEvent* event;
    status = mConsumer->consume(&mEventFactory, true /*consumeBatches*/, -1, &consumeSeq, &event);
    ASSERT_EQ(OK, status)
            << "consumer consume should return OK";

    ASSERT_TRUE(event != NULL)
            << "consumer should have returned non-NULL event";
    ASSERT_EQ(AINPUT_EVENT_TYPE_MOTION, event->getType())
            << "consumer should have returned a motion event";

    MotionEvent* motionEvent = static_cast<MotionEvent*>(event);
    EXPECT_EQ(seq, consumeSeq);
    EXPECT_EQ(deviceId, motionEvent->getDeviceId());
    EXPECT_EQ(source, motionEvent->getSource());
    EXPECT_EQ(action, motionEvent->getAction());
    EXPECT_EQ(flags, motionEvent->getFlags());
    EXPECT_EQ(edgeFlags, motionEvent->getEdgeFlags());
    EXPECT_EQ(metaState, motionEvent->getMetaState());
    EXPECT_EQ(buttonState, motionEvent->getButtonState());
    EXPECT_EQ(xPrecision, motionEvent->getXPrecision());
    EXPECT_EQ(yPrecision, motionEvent->getYPrecision());
    EXPECT_EQ(downTime, motionEvent->getDownTime());
    EXPECT_EQ(eventTime, motionEvent->getEventTime());
    EXPECT_EQ(pointerCount, motionEvent->getPointerCount());
    EXPECT_EQ(0U, motionEvent->getHistorySize());

    for (size_t i = 0; i < pointerCount; i++) {
        SCOPED_TRACE(i);
        EXPECT_EQ(pointerProperties[i].id, motionEvent->getPointerId(i));
        EXPECT_EQ(pointerProperties[i].toolType, motionEvent->getToolType(i));

        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_X),
                motionEvent->getRawX(i));
        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_Y),
                motionEvent->getRawY(i));
        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_X) + xOffset,
                motionEvent->getX(i));
        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_Y) + yOffset,
                motionEvent->getY(i));
        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_PRESSURE),
                motionEvent->getPressure(i));
        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_SIZE),
                motionEvent->getSize(i));
        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MAJOR),
                motionEvent->getTouchMajor(i));
        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOUCH_MINOR),
                motionEvent->getTouchMinor(i));
        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOOL_MAJOR),
                motionEvent->getToolMajor(i));
        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_TOOL_MINOR),
                motionEvent->getToolMinor(i));
        EXPECT_EQ(pointerCoords[i].getAxisValue(AMOTION_EVENT_AXIS_ORIENTATION),
                motionEvent->getOrientation(i));
    }

    status = mConsumer->sendFinishedSignal(seq, false);
    ASSERT_EQ(OK, status)
            << "consumer sendFinishedSignal should return OK";

    uint32_t finishedSeq = 0;
    bool handled = true;
    status = mPublisher->receiveFinishedSignal(&finishedSeq, &handled);
    ASSERT_EQ(OK, status)
            << "publisher receiveFinishedSignal should return OK";
    ASSERT_EQ(seq, finishedSeq)
            << "publisher receiveFinishedSignal should have returned the original sequence number";
    ASSERT_FALSE(handled)
            << "publisher receiveFinishedSignal should have set handled to consumer's reply";
}

TEST_F(InputPublisherAndConsumerTest, PublishKeyEvent_EndToEnd) {
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeKeyEvent());
}

TEST_F(InputPublisherAndConsumerTest, PublishMotionEvent_EndToEnd) {
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeMotionEvent());
}

TEST_F(InputPublisherAndConsumerTest, PublishMotionEvent_WhenPointerCountLessThan1_ReturnsError) {
    status_t status;
    const size_t pointerCount = 0;
    PointerProperties pointerProperties[pointerCount];
    PointerCoords pointerCoords[pointerCount];

    status = mPublisher->publishMotionEvent(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            pointerCount, pointerProperties, pointerCoords);
    ASSERT_EQ(BAD_VALUE, status)
            << "publisher publishMotionEvent should return BAD_VALUE";
}

TEST_F(InputPublisherAndConsumerTest, PublishMotionEvent_WhenPointerCountGreaterThanMax_ReturnsError) {
    status_t status;
    const size_t pointerCount = MAX_POINTERS + 1;
    PointerProperties pointerProperties[pointerCount];
    PointerCoords pointerCoords[pointerCount];
    for (size_t i = 0; i < pointerCount; i++) {
        pointerProperties[i].clear();
        pointerCoords[i].clear();
    }

    status = mPublisher->publishMotionEvent(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            pointerCount, pointerProperties, pointerCoords);
    ASSERT_EQ(BAD_VALUE, status)
            << "publisher publishMotionEvent should return BAD_VALUE";
}

TEST_F(InputPublisherAndConsumerTest, PublishMultipleEvents_EndToEnd) {
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeMotionEvent());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeKeyEvent());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeMotionEvent());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeMotionEvent());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeKeyEvent());
}

} // namespace android
