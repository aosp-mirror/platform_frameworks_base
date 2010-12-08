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

#ifndef _UI_INPUT_TRANSPORT_H
#define _UI_INPUT_TRANSPORT_H

/**
 * Native input transport.
 *
 * Uses anonymous shared memory as a whiteboard for sending input events from an
 * InputPublisher to an InputConsumer and ensuring appropriate synchronization.
 * One interesting feature is that published events can be updated in place as long as they
 * have not yet been consumed.
 *
 * The InputPublisher and InputConsumer only take care of transferring event data
 * over an InputChannel and sending synchronization signals.  The InputDispatcher and InputQueue
 * build on these abstractions to add multiplexing and queueing.
 */

#include <semaphore.h>
#include <ui/Input.h>
#include <utils/Errors.h>
#include <utils/Timers.h>
#include <utils/RefBase.h>
#include <utils/String8.h>

namespace android {

/*
 * An input channel consists of a shared memory buffer and a pair of pipes
 * used to send input messages from an InputPublisher to an InputConsumer
 * across processes.  Each channel has a descriptive name for debugging purposes.
 *
 * Each endpoint has its own InputChannel object that specifies its own file descriptors.
 *
 * The input channel is closed when all references to it are released.
 */
class InputChannel : public RefBase {
protected:
    virtual ~InputChannel();

public:
    InputChannel(const String8& name, int32_t ashmemFd, int32_t receivePipeFd,
            int32_t sendPipeFd);

    /* Creates a pair of input channels and their underlying shared memory buffers
     * and pipes.
     *
     * Returns OK on success.
     */
    static status_t openInputChannelPair(const String8& name,
            sp<InputChannel>& outServerChannel, sp<InputChannel>& outClientChannel);

    inline String8 getName() const { return mName; }
    inline int32_t getAshmemFd() const { return mAshmemFd; }
    inline int32_t getReceivePipeFd() const { return mReceivePipeFd; }
    inline int32_t getSendPipeFd() const { return mSendPipeFd; }

    /* Sends a signal to the other endpoint.
     *
     * Returns OK on success.
     * Returns DEAD_OBJECT if the channel's peer has been closed.
     * Other errors probably indicate that the channel is broken.
     */
    status_t sendSignal(char signal);

    /* Receives a signal send by the other endpoint.
     * (Should only call this after poll() indicates that the receivePipeFd has available input.)
     *
     * Returns OK on success.
     * Returns WOULD_BLOCK if there is no signal present.
     * Returns DEAD_OBJECT if the channel's peer has been closed.
     * Other errors probably indicate that the channel is broken.
     */
    status_t receiveSignal(char* outSignal);

private:
    String8 mName;
    int32_t mAshmemFd;
    int32_t mReceivePipeFd;
    int32_t mSendPipeFd;
};

/*
 * Private intermediate representation of input events as messages written into an
 * ashmem buffer.
 */
struct InputMessage {
    /* Semaphore count is set to 1 when the message is published.
     * It becomes 0 transiently while the publisher updates the message.
     * It becomes 0 permanently when the consumer consumes the message.
     */
    sem_t semaphore;

    /* Initialized to false by the publisher.
     * Set to true by the consumer when it consumes the message.
     */
    bool consumed;

    int32_t type;

    struct SampleData {
        nsecs_t eventTime;
        PointerCoords coords[0]; // variable length
    };

    int32_t deviceId;
    int32_t source;

    union {
        struct {
            int32_t action;
            int32_t flags;
            int32_t keyCode;
            int32_t scanCode;
            int32_t metaState;
            int32_t repeatCount;
            nsecs_t downTime;
            nsecs_t eventTime;
        } key;

        struct {
            int32_t action;
            int32_t flags;
            int32_t metaState;
            int32_t edgeFlags;
            nsecs_t downTime;
            float xOffset;
            float yOffset;
            float xPrecision;
            float yPrecision;
            size_t pointerCount;
            int32_t pointerIds[MAX_POINTERS];
            size_t sampleCount;
            SampleData sampleData[0]; // variable length
        } motion;
    };

    /* Gets the number of bytes to add to step to the next SampleData object in a motion
     * event message for a given number of pointers.
     */
    static inline size_t sampleDataStride(size_t pointerCount) {
        return sizeof(InputMessage::SampleData) + pointerCount * sizeof(PointerCoords);
    }

    /* Adds the SampleData stride to the given pointer. */
    static inline SampleData* sampleDataPtrIncrement(SampleData* ptr, size_t stride) {
        return reinterpret_cast<InputMessage::SampleData*>(reinterpret_cast<char*>(ptr) + stride);
    }
};

/*
 * Publishes input events to an anonymous shared memory buffer.
 * Uses atomic operations to coordinate shared access with a single concurrent consumer.
 */
class InputPublisher {
public:
    /* Creates a publisher associated with an input channel. */
    explicit InputPublisher(const sp<InputChannel>& channel);

    /* Destroys the publisher and releases its input channel. */
    ~InputPublisher();

    /* Gets the underlying input channel. */
    inline sp<InputChannel> getChannel() { return mChannel; }

    /* Prepares the publisher for use.  Must be called before it is used.
     * Returns OK on success.
     *
     * This method implicitly calls reset(). */
    status_t initialize();

    /* Resets the publisher to its initial state and unpins its ashmem buffer.
     * Returns OK on success.
     *
     * Should be called after an event has been consumed to release resources used by the
     * publisher until the next event is ready to be published.
     */
    status_t reset();

    /* Publishes a key event to the ashmem buffer.
     *
     * Returns OK on success.
     * Returns INVALID_OPERATION if the publisher has not been reset.
     */
    status_t publishKeyEvent(
            int32_t deviceId,
            int32_t source,
            int32_t action,
            int32_t flags,
            int32_t keyCode,
            int32_t scanCode,
            int32_t metaState,
            int32_t repeatCount,
            nsecs_t downTime,
            nsecs_t eventTime);

    /* Publishes a motion event to the ashmem buffer.
     *
     * Returns OK on success.
     * Returns INVALID_OPERATION if the publisher has not been reset.
     * Returns BAD_VALUE if pointerCount is less than 1 or greater than MAX_POINTERS.
     */
    status_t publishMotionEvent(
            int32_t deviceId,
            int32_t source,
            int32_t action,
            int32_t flags,
            int32_t edgeFlags,
            int32_t metaState,
            float xOffset,
            float yOffset,
            float xPrecision,
            float yPrecision,
            nsecs_t downTime,
            nsecs_t eventTime,
            size_t pointerCount,
            const int32_t* pointerIds,
            const PointerCoords* pointerCoords);

    /* Appends a motion sample to a motion event unless already consumed.
     *
     * Returns OK on success.
     * Returns INVALID_OPERATION if the current event is not a AMOTION_EVENT_ACTION_MOVE event.
     * Returns FAILED_TRANSACTION if the current event has already been consumed.
     * Returns NO_MEMORY if the buffer is full and no additional samples can be added.
     */
    status_t appendMotionSample(
            nsecs_t eventTime,
            const PointerCoords* pointerCoords);

    /* Sends a dispatch signal to the consumer to inform it that a new message is available.
     *
     * Returns OK on success.
     * Errors probably indicate that the channel is broken.
     */
    status_t sendDispatchSignal();

    /* Receives the finished signal from the consumer in reply to the original dispatch signal.
     * Returns whether the consumer handled the message.
     *
     * Returns OK on success.
     * Returns WOULD_BLOCK if there is no signal present.
     * Other errors probably indicate that the channel is broken.
     */
    status_t receiveFinishedSignal(bool* outHandled);

private:
    sp<InputChannel> mChannel;

    size_t mAshmemSize;
    InputMessage* mSharedMessage;
    bool mPinned;
    bool mSemaphoreInitialized;
    bool mWasDispatched;

    size_t mMotionEventPointerCount;
    InputMessage::SampleData* mMotionEventSampleDataTail;
    size_t mMotionEventSampleDataStride;

    status_t publishInputEvent(
            int32_t type,
            int32_t deviceId,
            int32_t source);
};

/*
 * Consumes input events from an anonymous shared memory buffer.
 * Uses atomic operations to coordinate shared access with a single concurrent publisher.
 */
class InputConsumer {
public:
    /* Creates a consumer associated with an input channel. */
    explicit InputConsumer(const sp<InputChannel>& channel);

    /* Destroys the consumer and releases its input channel. */
    ~InputConsumer();

    /* Gets the underlying input channel. */
    inline sp<InputChannel> getChannel() { return mChannel; }

    /* Prepares the consumer for use.  Must be called before it is used. */
    status_t initialize();

    /* Consumes the input event in the buffer and copies its contents into
     * an InputEvent object created using the specified factory.
     * This operation will block if the publisher is updating the event.
     *
     * Returns OK on success.
     * Returns INVALID_OPERATION if there is no currently published event.
     * Returns NO_MEMORY if the event could not be created.
     */
    status_t consume(InputEventFactoryInterface* factory, InputEvent** outEvent);

    /* Sends a finished signal to the publisher to inform it that the current message is
     * finished processing and specifies whether the message was handled by the consumer.
     *
     * Returns OK on success.
     * Errors probably indicate that the channel is broken.
     */
    status_t sendFinishedSignal(bool handled);

    /* Receives the dispatched signal from the publisher.
     *
     * Returns OK on success.
     * Returns WOULD_BLOCK if there is no signal present.
     * Other errors probably indicate that the channel is broken.
     */
    status_t receiveDispatchSignal();

private:
    sp<InputChannel> mChannel;

    size_t mAshmemSize;
    InputMessage* mSharedMessage;

    void populateKeyEvent(KeyEvent* keyEvent) const;
    void populateMotionEvent(MotionEvent* motionEvent) const;
};

} // namespace android

#endif // _UI_INPUT_TRANSPORT_H
