//
// Copyright 2010 The Android Open Source Project
//

#include <ui/InputTransport.h>
#include <utils/Timers.h>
#include <utils/StopWatch.h>
#include <gtest/gtest.h>
#include <unistd.h>
#include <time.h>
#include <sys/mman.h>
#include <cutils/ashmem.h>

#include "../../utils/tests/TestHelpers.h"

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

    void Initialize();
    void PublishAndConsumeKeyEvent();
    void PublishAndConsumeMotionEvent(
            size_t samplesToAppendBeforeDispatch = 0,
            size_t samplesToAppendAfterDispatch = 0);
};

TEST_F(InputPublisherAndConsumerTest, GetChannel_ReturnsTheChannel) {
    EXPECT_EQ(serverChannel.get(), mPublisher->getChannel().get());
    EXPECT_EQ(clientChannel.get(), mConsumer->getChannel().get());
}

void InputPublisherAndConsumerTest::Initialize() {
    status_t status;

    status = mPublisher->initialize();
    ASSERT_EQ(OK, status)
            << "publisher initialize should return OK";

    status = mConsumer->initialize();
    ASSERT_EQ(OK, status)
            << "consumer initialize should return OK";
}

void InputPublisherAndConsumerTest::PublishAndConsumeKeyEvent() {
    status_t status;

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

    status = mPublisher->publishKeyEvent(deviceId, source, action, flags,
            keyCode, scanCode, metaState, repeatCount, downTime, eventTime);
    ASSERT_EQ(OK, status)
            << "publisher publishKeyEvent should return OK";

    status = mPublisher->sendDispatchSignal();
    ASSERT_EQ(OK, status)
            << "publisher sendDispatchSignal should return OK";

    status = mConsumer->receiveDispatchSignal();
    ASSERT_EQ(OK, status)
            << "consumer receiveDispatchSignal should return OK";

    InputEvent* event;
    status = mConsumer->consume(& mEventFactory, & event);
    ASSERT_EQ(OK, status)
            << "consumer consume should return OK";

    ASSERT_TRUE(event != NULL)
            << "consumer should have returned non-NULL event";
    ASSERT_EQ(AINPUT_EVENT_TYPE_KEY, event->getType())
            << "consumer should have returned a key event";

    KeyEvent* keyEvent = static_cast<KeyEvent*>(event);
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

    status = mConsumer->sendFinishedSignal();
    ASSERT_EQ(OK, status)
            << "consumer sendFinishedSignal should return OK";

    status = mPublisher->receiveFinishedSignal();
    ASSERT_EQ(OK, status)
            << "publisher receiveFinishedSignal should return OK";

    status = mPublisher->reset();
    ASSERT_EQ(OK, status)
            << "publisher reset should return OK";
}

void InputPublisherAndConsumerTest::PublishAndConsumeMotionEvent(
        size_t samplesToAppendBeforeDispatch, size_t samplesToAppendAfterDispatch) {
    status_t status;

    const int32_t deviceId = 1;
    const int32_t source = AINPUT_SOURCE_TOUCHSCREEN;
    const int32_t action = AMOTION_EVENT_ACTION_MOVE;
    const int32_t edgeFlags = AMOTION_EVENT_EDGE_FLAG_TOP;
    const int32_t metaState = AMETA_ALT_LEFT_ON | AMETA_ALT_ON;
    const float xOffset = -10;
    const float yOffset = -20;
    const float xPrecision = 0.25;
    const float yPrecision = 0.5;
    const nsecs_t downTime = 3;
    const size_t pointerCount = 3;
    const int32_t pointerIds[pointerCount] = { 2, 0, 1 };

    Vector<nsecs_t> sampleEventTimes;
    Vector<PointerCoords> samplePointerCoords;

    for (size_t i = 0; i <= samplesToAppendAfterDispatch + samplesToAppendBeforeDispatch; i++) {
        sampleEventTimes.push(i + 10);
        for (size_t j = 0; j < pointerCount; j++) {
            samplePointerCoords.push();
            samplePointerCoords.editTop().x = 100 * i + j;
            samplePointerCoords.editTop().y = 200 * i + j;
            samplePointerCoords.editTop().pressure = 0.5 * i + j;
            samplePointerCoords.editTop().size = 0.7 * i + j;
            samplePointerCoords.editTop().touchMajor = 1.5 * i + j;
            samplePointerCoords.editTop().touchMinor = 1.7 * i + j;
            samplePointerCoords.editTop().toolMajor = 2.5 * i + j;
            samplePointerCoords.editTop().toolMinor = 2.7 * i + j;
            samplePointerCoords.editTop().orientation = 3.5 * i + j;
        }
    }

    status = mPublisher->publishMotionEvent(deviceId, source, action, edgeFlags,
            metaState, xOffset, yOffset, xPrecision, yPrecision,
            downTime, sampleEventTimes[0], pointerCount, pointerIds, samplePointerCoords.array());
    ASSERT_EQ(OK, status)
            << "publisher publishMotionEvent should return OK";

    for (size_t i = 0; i < samplesToAppendBeforeDispatch; i++) {
        size_t sampleIndex = i + 1;
        status = mPublisher->appendMotionSample(sampleEventTimes[sampleIndex],
                samplePointerCoords.array() + sampleIndex * pointerCount);
        ASSERT_EQ(OK, status)
                << "publisher appendMotionEvent should return OK";
    }

    status = mPublisher->sendDispatchSignal();
    ASSERT_EQ(OK, status)
            << "publisher sendDispatchSignal should return OK";

    for (size_t i = 0; i < samplesToAppendAfterDispatch; i++) {
        size_t sampleIndex = i + 1 + samplesToAppendBeforeDispatch;
        status = mPublisher->appendMotionSample(sampleEventTimes[sampleIndex],
                samplePointerCoords.array() + sampleIndex * pointerCount);
        ASSERT_EQ(OK, status)
                << "publisher appendMotionEvent should return OK";
    }

    status = mConsumer->receiveDispatchSignal();
    ASSERT_EQ(OK, status)
            << "consumer receiveDispatchSignal should return OK";

    InputEvent* event;
    status = mConsumer->consume(& mEventFactory, & event);
    ASSERT_EQ(OK, status)
            << "consumer consume should return OK";

    ASSERT_TRUE(event != NULL)
            << "consumer should have returned non-NULL event";
    ASSERT_EQ(AINPUT_EVENT_TYPE_MOTION, event->getType())
            << "consumer should have returned a motion event";

    size_t lastSampleIndex = samplesToAppendBeforeDispatch + samplesToAppendAfterDispatch;

    MotionEvent* motionEvent = static_cast<MotionEvent*>(event);
    EXPECT_EQ(deviceId, motionEvent->getDeviceId());
    EXPECT_EQ(source, motionEvent->getSource());
    EXPECT_EQ(action, motionEvent->getAction());
    EXPECT_EQ(edgeFlags, motionEvent->getEdgeFlags());
    EXPECT_EQ(metaState, motionEvent->getMetaState());
    EXPECT_EQ(xPrecision, motionEvent->getXPrecision());
    EXPECT_EQ(yPrecision, motionEvent->getYPrecision());
    EXPECT_EQ(downTime, motionEvent->getDownTime());
    EXPECT_EQ(sampleEventTimes[lastSampleIndex], motionEvent->getEventTime());
    EXPECT_EQ(pointerCount, motionEvent->getPointerCount());
    EXPECT_EQ(lastSampleIndex, motionEvent->getHistorySize());

    for (size_t i = 0; i < pointerCount; i++) {
        SCOPED_TRACE(i);
        EXPECT_EQ(pointerIds[i], motionEvent->getPointerId(i));
    }

    for (size_t sampleIndex = 0; sampleIndex < lastSampleIndex; sampleIndex++) {
        SCOPED_TRACE(sampleIndex);
        EXPECT_EQ(sampleEventTimes[sampleIndex],
                motionEvent->getHistoricalEventTime(sampleIndex));
        for (size_t i = 0; i < pointerCount; i++) {
            SCOPED_TRACE(i);
            size_t offset = sampleIndex * pointerCount + i;
            EXPECT_EQ(samplePointerCoords[offset].x,
                    motionEvent->getHistoricalRawX(i, sampleIndex));
            EXPECT_EQ(samplePointerCoords[offset].y,
                    motionEvent->getHistoricalRawY(i, sampleIndex));
            EXPECT_EQ(samplePointerCoords[offset].x + xOffset,
                    motionEvent->getHistoricalX(i, sampleIndex));
            EXPECT_EQ(samplePointerCoords[offset].y + yOffset,
                    motionEvent->getHistoricalY(i, sampleIndex));
            EXPECT_EQ(samplePointerCoords[offset].pressure,
                    motionEvent->getHistoricalPressure(i, sampleIndex));
            EXPECT_EQ(samplePointerCoords[offset].size,
                    motionEvent->getHistoricalSize(i, sampleIndex));
            EXPECT_EQ(samplePointerCoords[offset].touchMajor,
                    motionEvent->getHistoricalTouchMajor(i, sampleIndex));
            EXPECT_EQ(samplePointerCoords[offset].touchMinor,
                    motionEvent->getHistoricalTouchMinor(i, sampleIndex));
            EXPECT_EQ(samplePointerCoords[offset].toolMajor,
                    motionEvent->getHistoricalToolMajor(i, sampleIndex));
            EXPECT_EQ(samplePointerCoords[offset].toolMinor,
                    motionEvent->getHistoricalToolMinor(i, sampleIndex));
            EXPECT_EQ(samplePointerCoords[offset].orientation,
                    motionEvent->getHistoricalOrientation(i, sampleIndex));
        }
    }

    SCOPED_TRACE(lastSampleIndex);
    EXPECT_EQ(sampleEventTimes[lastSampleIndex], motionEvent->getEventTime());
    for (size_t i = 0; i < pointerCount; i++) {
        SCOPED_TRACE(i);
        size_t offset = lastSampleIndex * pointerCount + i;
        EXPECT_EQ(samplePointerCoords[offset].x, motionEvent->getRawX(i));
        EXPECT_EQ(samplePointerCoords[offset].y, motionEvent->getRawY(i));
        EXPECT_EQ(samplePointerCoords[offset].x + xOffset, motionEvent->getX(i));
        EXPECT_EQ(samplePointerCoords[offset].y + yOffset, motionEvent->getY(i));
        EXPECT_EQ(samplePointerCoords[offset].pressure, motionEvent->getPressure(i));
        EXPECT_EQ(samplePointerCoords[offset].size, motionEvent->getSize(i));
        EXPECT_EQ(samplePointerCoords[offset].touchMajor, motionEvent->getTouchMajor(i));
        EXPECT_EQ(samplePointerCoords[offset].touchMinor, motionEvent->getTouchMinor(i));
        EXPECT_EQ(samplePointerCoords[offset].toolMajor, motionEvent->getToolMajor(i));
        EXPECT_EQ(samplePointerCoords[offset].toolMinor, motionEvent->getToolMinor(i));
        EXPECT_EQ(samplePointerCoords[offset].orientation, motionEvent->getOrientation(i));
    }

    status = mConsumer->sendFinishedSignal();
    ASSERT_EQ(OK, status)
            << "consumer sendFinishedSignal should return OK";

    status = mPublisher->receiveFinishedSignal();
    ASSERT_EQ(OK, status)
            << "publisher receiveFinishedSignal should return OK";

    status = mPublisher->reset();
    ASSERT_EQ(OK, status)
            << "publisher reset should return OK";
}

TEST_F(InputPublisherAndConsumerTest, PublishKeyEvent_EndToEnd) {
    ASSERT_NO_FATAL_FAILURE(Initialize());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeKeyEvent());
}

TEST_F(InputPublisherAndConsumerTest, PublishKeyEvent_WhenNotReset_ReturnsError) {
    status_t status;
    ASSERT_NO_FATAL_FAILURE(Initialize());

    status = mPublisher->publishKeyEvent(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    ASSERT_EQ(OK, status)
            << "publisher publishKeyEvent should return OK first time";

    status = mPublisher->publishKeyEvent(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    ASSERT_EQ(INVALID_OPERATION, status)
            << "publisher publishKeyEvent should return INVALID_OPERATION because "
                    "the publisher was not reset";
}

TEST_F(InputPublisherAndConsumerTest, PublishMotionEvent_EndToEnd) {
    ASSERT_NO_FATAL_FAILURE(Initialize());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeMotionEvent());
}

TEST_F(InputPublisherAndConsumerTest, PublishMotionEvent_WhenNotReset_ReturnsError) {
    status_t status;
    ASSERT_NO_FATAL_FAILURE(Initialize());

    const size_t pointerCount = 1;
    int32_t pointerIds[pointerCount] = { 0 };
    PointerCoords pointerCoords[pointerCount] = { { 0, 0, 0, 0, 0, 0, 0, 0, 0 } };

    status = mPublisher->publishMotionEvent(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            pointerCount, pointerIds, pointerCoords);
    ASSERT_EQ(OK, status)
            << "publisher publishMotionEvent should return OK";

    status = mPublisher->publishMotionEvent(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            pointerCount, pointerIds, pointerCoords);
    ASSERT_EQ(INVALID_OPERATION, status)
            << "publisher publishMotionEvent should return INVALID_OPERATION because ";
                    "the publisher was not reset";
}

TEST_F(InputPublisherAndConsumerTest, PublishMotionEvent_WhenPointerCountLessThan1_ReturnsError) {
    status_t status;
    ASSERT_NO_FATAL_FAILURE(Initialize());

    const size_t pointerCount = 0;
    int32_t pointerIds[pointerCount];
    PointerCoords pointerCoords[pointerCount];

    status = mPublisher->publishMotionEvent(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            pointerCount, pointerIds, pointerCoords);
    ASSERT_EQ(BAD_VALUE, status)
            << "publisher publishMotionEvent should return BAD_VALUE";
}

TEST_F(InputPublisherAndConsumerTest, PublishMotionEvent_WhenPointerCountGreaterThanMax_ReturnsError) {
    status_t status;
    ASSERT_NO_FATAL_FAILURE(Initialize());

    const size_t pointerCount = MAX_POINTERS + 1;
    int32_t pointerIds[pointerCount];
    PointerCoords pointerCoords[pointerCount];

    status = mPublisher->publishMotionEvent(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            pointerCount, pointerIds, pointerCoords);
    ASSERT_EQ(BAD_VALUE, status)
            << "publisher publishMotionEvent should return BAD_VALUE";
}

TEST_F(InputPublisherAndConsumerTest, PublishMultipleEvents_EndToEnd) {
    ASSERT_NO_FATAL_FAILURE(Initialize());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeMotionEvent());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeKeyEvent());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeMotionEvent());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeMotionEvent());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeKeyEvent());
}

TEST_F(InputPublisherAndConsumerTest, AppendMotionSample_WhenCalledBeforeDispatchSignal_AppendsSamples) {
    status_t status;
    ASSERT_NO_FATAL_FAILURE(Initialize());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeMotionEvent(3, 0));
}

TEST_F(InputPublisherAndConsumerTest, AppendMotionSample_WhenCalledAfterDispatchSignalAndNotConsumed_AppendsSamples) {
    status_t status;
    ASSERT_NO_FATAL_FAILURE(Initialize());
    ASSERT_NO_FATAL_FAILURE(PublishAndConsumeMotionEvent(0, 4));
}

TEST_F(InputPublisherAndConsumerTest, AppendMotionSample_WhenNoMotionEventPublished_ReturnsError) {
    status_t status;
    ASSERT_NO_FATAL_FAILURE(Initialize());

    PointerCoords pointerCoords[1];
    status = mPublisher->appendMotionSample(0, pointerCoords);
    ASSERT_EQ(INVALID_OPERATION, status)
            << "publisher appendMotionSample should return INVALID_OPERATION";
}

TEST_F(InputPublisherAndConsumerTest, AppendMotionSample_WhenPublishedMotionEventIsNotAMove_ReturnsError) {
    status_t status;
    ASSERT_NO_FATAL_FAILURE(Initialize());

    const size_t pointerCount = MAX_POINTERS;
    int32_t pointerIds[pointerCount];
    PointerCoords pointerCoords[pointerCount];

    status = mPublisher->publishMotionEvent(0, 0, AMOTION_EVENT_ACTION_DOWN,
            0, 0, 0, 0, 0, 0, 0, 0, pointerCount, pointerIds, pointerCoords);
    ASSERT_EQ(OK, status);

    status = mPublisher->appendMotionSample(0, pointerCoords);
    ASSERT_EQ(INVALID_OPERATION, status)
            << "publisher appendMotionSample should return INVALID_OPERATION";
}

TEST_F(InputPublisherAndConsumerTest, AppendMotionSample_WhenAlreadyConsumed_ReturnsError) {
    status_t status;
    ASSERT_NO_FATAL_FAILURE(Initialize());

    const size_t pointerCount = MAX_POINTERS;
    int32_t pointerIds[pointerCount];
    PointerCoords pointerCoords[pointerCount];

    status = mPublisher->publishMotionEvent(0, 0, AMOTION_EVENT_ACTION_MOVE,
            0, 0, 0, 0, 0, 0, 0, 0, pointerCount, pointerIds, pointerCoords);
    ASSERT_EQ(OK, status);

    status = mPublisher->sendDispatchSignal();
    ASSERT_EQ(OK, status);

    status = mConsumer->receiveDispatchSignal();
    ASSERT_EQ(OK, status);

    InputEvent* event;
    status = mConsumer->consume(& mEventFactory, & event);
    ASSERT_EQ(OK, status);

    status = mPublisher->appendMotionSample(0, pointerCoords);
    ASSERT_EQ(status_t(FAILED_TRANSACTION), status)
            << "publisher appendMotionSample should return FAILED_TRANSACTION";
}

TEST_F(InputPublisherAndConsumerTest, AppendMotionSample_WhenBufferFull_ReturnsError) {
    status_t status;
    ASSERT_NO_FATAL_FAILURE(Initialize());

    const size_t pointerCount = MAX_POINTERS;
    int32_t pointerIds[pointerCount];
    PointerCoords pointerCoords[pointerCount];

    status = mPublisher->publishMotionEvent(0, 0, AMOTION_EVENT_ACTION_MOVE,
            0, 0, 0, 0, 0, 0, 0, 0, pointerCount, pointerIds, pointerCoords);
    ASSERT_EQ(OK, status);

    for (int count = 1;; count++) {
        ASSERT_LT(count, 100000) << "should eventually reach OOM";

        status = mPublisher->appendMotionSample(0, pointerCoords);
        if (status != OK) {
            ASSERT_GT(count, 12) << "should be able to add at least a dozen samples";
            ASSERT_EQ(NO_MEMORY, status)
                    << "publisher appendMotionSample should return NO_MEMORY when buffer is full";
            break;
        }
    }

    status = mPublisher->appendMotionSample(0, pointerCoords);
    ASSERT_EQ(NO_MEMORY, status)
            << "publisher appendMotionSample should return NO_MEMORY persistently until reset";
}

} // namespace android
