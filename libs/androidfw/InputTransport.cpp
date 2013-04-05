//
// Copyright 2010 The Android Open Source Project
//
// Provides a shared memory transport for input events.
//
#define LOG_TAG "InputTransport"

//#define LOG_NDEBUG 0

// Log debug messages about channel messages (send message, receive message)
#define DEBUG_CHANNEL_MESSAGES 0

// Log debug messages whenever InputChannel objects are created/destroyed
#define DEBUG_CHANNEL_LIFECYCLE 0

// Log debug messages about transport actions
#define DEBUG_TRANSPORT_ACTIONS 0

// Log debug messages about touch event resampling
#define DEBUG_RESAMPLING 0


#include <cutils/log.h>
#include <cutils/properties.h>
#include <errno.h>
#include <fcntl.h>
#include <androidfw/InputTransport.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <math.h>


namespace android {

// Socket buffer size.  The default is typically about 128KB, which is much larger than
// we really need.  So we make it smaller.  It just needs to be big enough to hold
// a few dozen large multi-finger motion events in the case where an application gets
// behind processing touches.
static const size_t SOCKET_BUFFER_SIZE = 32 * 1024;

// Nanoseconds per milliseconds.
static const nsecs_t NANOS_PER_MS = 1000000;

// Latency added during resampling.  A few milliseconds doesn't hurt much but
// reduces the impact of mispredicted touch positions.
static const nsecs_t RESAMPLE_LATENCY = 5 * NANOS_PER_MS;

// Minimum time difference between consecutive samples before attempting to resample.
static const nsecs_t RESAMPLE_MIN_DELTA = 2 * NANOS_PER_MS;

// Maximum time to predict forward from the last known state, to avoid predicting too
// far into the future.  This time is further bounded by 50% of the last time delta.
static const nsecs_t RESAMPLE_MAX_PREDICTION = 8 * NANOS_PER_MS;

template<typename T>
inline static T min(const T& a, const T& b) {
    return a < b ? a : b;
}

inline static float lerp(float a, float b, float alpha) {
    return a + alpha * (b - a);
}

// --- InputMessage ---

bool InputMessage::isValid(size_t actualSize) const {
    if (size() == actualSize) {
        switch (header.type) {
        case TYPE_KEY:
            return true;
        case TYPE_MOTION:
            return body.motion.pointerCount > 0
                    && body.motion.pointerCount <= MAX_POINTERS;
        case TYPE_FINISHED:
            return true;
        }
    }
    return false;
}

size_t InputMessage::size() const {
    switch (header.type) {
    case TYPE_KEY:
        return sizeof(Header) + body.key.size();
    case TYPE_MOTION:
        return sizeof(Header) + body.motion.size();
    case TYPE_FINISHED:
        return sizeof(Header) + body.finished.size();
    }
    return sizeof(Header);
}


// --- InputChannel ---

InputChannel::InputChannel(const String8& name, int fd) :
        mName(name), mFd(fd) {
#if DEBUG_CHANNEL_LIFECYCLE
    ALOGD("Input channel constructed: name='%s', fd=%d",
            mName.string(), fd);
#endif

    int result = fcntl(mFd, F_SETFL, O_NONBLOCK);
    LOG_ALWAYS_FATAL_IF(result != 0, "channel '%s' ~ Could not make socket "
            "non-blocking.  errno=%d", mName.string(), errno);
}

InputChannel::~InputChannel() {
#if DEBUG_CHANNEL_LIFECYCLE
    ALOGD("Input channel destroyed: name='%s', fd=%d",
            mName.string(), mFd);
#endif

    ::close(mFd);
}

status_t InputChannel::openInputChannelPair(const String8& name,
        sp<InputChannel>& outServerChannel, sp<InputChannel>& outClientChannel) {
    int sockets[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, sockets)) {
        status_t result = -errno;
        ALOGE("channel '%s' ~ Could not create socket pair.  errno=%d",
                name.string(), errno);
        outServerChannel.clear();
        outClientChannel.clear();
        return result;
    }

    int bufferSize = SOCKET_BUFFER_SIZE;
    setsockopt(sockets[0], SOL_SOCKET, SO_SNDBUF, &bufferSize, sizeof(bufferSize));
    setsockopt(sockets[0], SOL_SOCKET, SO_RCVBUF, &bufferSize, sizeof(bufferSize));
    setsockopt(sockets[1], SOL_SOCKET, SO_SNDBUF, &bufferSize, sizeof(bufferSize));
    setsockopt(sockets[1], SOL_SOCKET, SO_RCVBUF, &bufferSize, sizeof(bufferSize));

    String8 serverChannelName = name;
    serverChannelName.append(" (server)");
    outServerChannel = new InputChannel(serverChannelName, sockets[0]);

    String8 clientChannelName = name;
    clientChannelName.append(" (client)");
    outClientChannel = new InputChannel(clientChannelName, sockets[1]);
    return OK;
}

status_t InputChannel::sendMessage(const InputMessage* msg) {
    size_t msgLength = msg->size();
    ssize_t nWrite;
    do {
        nWrite = ::send(mFd, msg, msgLength, MSG_DONTWAIT | MSG_NOSIGNAL);
    } while (nWrite == -1 && errno == EINTR);

    if (nWrite < 0) {
        int error = errno;
#if DEBUG_CHANNEL_MESSAGES
        ALOGD("channel '%s' ~ error sending message of type %d, errno=%d", mName.string(),
                msg->header.type, error);
#endif
        if (error == EAGAIN || error == EWOULDBLOCK) {
            return WOULD_BLOCK;
        }
        if (error == EPIPE || error == ENOTCONN) {
            return DEAD_OBJECT;
        }
        return -error;
    }

    if (size_t(nWrite) != msgLength) {
#if DEBUG_CHANNEL_MESSAGES
        ALOGD("channel '%s' ~ error sending message type %d, send was incomplete",
                mName.string(), msg->header.type);
#endif
        return DEAD_OBJECT;
    }

#if DEBUG_CHANNEL_MESSAGES
    ALOGD("channel '%s' ~ sent message of type %d", mName.string(), msg->header.type);
#endif
    return OK;
}

status_t InputChannel::receiveMessage(InputMessage* msg) {
    ssize_t nRead;
    do {
        nRead = ::recv(mFd, msg, sizeof(InputMessage), MSG_DONTWAIT);
    } while (nRead == -1 && errno == EINTR);

    if (nRead < 0) {
        int error = errno;
#if DEBUG_CHANNEL_MESSAGES
        ALOGD("channel '%s' ~ receive message failed, errno=%d", mName.string(), errno);
#endif
        if (error == EAGAIN || error == EWOULDBLOCK) {
            return WOULD_BLOCK;
        }
        if (error == EPIPE || error == ENOTCONN) {
            return DEAD_OBJECT;
        }
        return -error;
    }

    if (nRead == 0) { // check for EOF
#if DEBUG_CHANNEL_MESSAGES
        ALOGD("channel '%s' ~ receive message failed because peer was closed", mName.string());
#endif
        return DEAD_OBJECT;
    }

    if (!msg->isValid(nRead)) {
#if DEBUG_CHANNEL_MESSAGES
        ALOGD("channel '%s' ~ received invalid message", mName.string());
#endif
        return BAD_VALUE;
    }

#if DEBUG_CHANNEL_MESSAGES
    ALOGD("channel '%s' ~ received message of type %d", mName.string(), msg->header.type);
#endif
    return OK;
}

sp<InputChannel> InputChannel::dup() const {
    int fd = ::dup(getFd());
    return fd >= 0 ? new InputChannel(getName(), fd) : NULL;
}


// --- InputPublisher ---

InputPublisher::InputPublisher(const sp<InputChannel>& channel) :
        mChannel(channel) {
}

InputPublisher::~InputPublisher() {
}

status_t InputPublisher::publishKeyEvent(
        uint32_t seq,
        int32_t deviceId,
        int32_t source,
        int32_t action,
        int32_t flags,
        int32_t keyCode,
        int32_t scanCode,
        int32_t metaState,
        int32_t repeatCount,
        nsecs_t downTime,
        nsecs_t eventTime) {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' publisher ~ publishKeyEvent: seq=%u, deviceId=%d, source=0x%x, "
            "action=0x%x, flags=0x%x, keyCode=%d, scanCode=%d, metaState=0x%x, repeatCount=%d,"
            "downTime=%lld, eventTime=%lld",
            mChannel->getName().string(), seq,
            deviceId, source, action, flags, keyCode, scanCode, metaState, repeatCount,
            downTime, eventTime);
#endif

    if (!seq) {
        ALOGE("Attempted to publish a key event with sequence number 0.");
        return BAD_VALUE;
    }

    InputMessage msg;
    msg.header.type = InputMessage::TYPE_KEY;
    msg.body.key.seq = seq;
    msg.body.key.deviceId = deviceId;
    msg.body.key.source = source;
    msg.body.key.action = action;
    msg.body.key.flags = flags;
    msg.body.key.keyCode = keyCode;
    msg.body.key.scanCode = scanCode;
    msg.body.key.metaState = metaState;
    msg.body.key.repeatCount = repeatCount;
    msg.body.key.downTime = downTime;
    msg.body.key.eventTime = eventTime;
    return mChannel->sendMessage(&msg);
}

status_t InputPublisher::publishMotionEvent(
        uint32_t seq,
        int32_t deviceId,
        int32_t source,
        int32_t action,
        int32_t flags,
        int32_t edgeFlags,
        int32_t metaState,
        int32_t buttonState,
        float xOffset,
        float yOffset,
        float xPrecision,
        float yPrecision,
        nsecs_t downTime,
        nsecs_t eventTime,
        size_t pointerCount,
        const PointerProperties* pointerProperties,
        const PointerCoords* pointerCoords) {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' publisher ~ publishMotionEvent: seq=%u, deviceId=%d, source=0x%x, "
            "action=0x%x, flags=0x%x, edgeFlags=0x%x, metaState=0x%x, buttonState=0x%x, "
            "xOffset=%f, yOffset=%f, "
            "xPrecision=%f, yPrecision=%f, downTime=%lld, eventTime=%lld, "
            "pointerCount=%d",
            mChannel->getName().string(), seq,
            deviceId, source, action, flags, edgeFlags, metaState, buttonState,
            xOffset, yOffset, xPrecision, yPrecision, downTime, eventTime, pointerCount);
#endif

    if (!seq) {
        ALOGE("Attempted to publish a motion event with sequence number 0.");
        return BAD_VALUE;
    }

    if (pointerCount > MAX_POINTERS || pointerCount < 1) {
        ALOGE("channel '%s' publisher ~ Invalid number of pointers provided: %d.",
                mChannel->getName().string(), pointerCount);
        return BAD_VALUE;
    }

    InputMessage msg;
    msg.header.type = InputMessage::TYPE_MOTION;
    msg.body.motion.seq = seq;
    msg.body.motion.deviceId = deviceId;
    msg.body.motion.source = source;
    msg.body.motion.action = action;
    msg.body.motion.flags = flags;
    msg.body.motion.edgeFlags = edgeFlags;
    msg.body.motion.metaState = metaState;
    msg.body.motion.buttonState = buttonState;
    msg.body.motion.xOffset = xOffset;
    msg.body.motion.yOffset = yOffset;
    msg.body.motion.xPrecision = xPrecision;
    msg.body.motion.yPrecision = yPrecision;
    msg.body.motion.downTime = downTime;
    msg.body.motion.eventTime = eventTime;
    msg.body.motion.pointerCount = pointerCount;
    for (size_t i = 0; i < pointerCount; i++) {
        msg.body.motion.pointers[i].properties.copyFrom(pointerProperties[i]);
        msg.body.motion.pointers[i].coords.copyFrom(pointerCoords[i]);
    }
    return mChannel->sendMessage(&msg);
}

status_t InputPublisher::receiveFinishedSignal(uint32_t* outSeq, bool* outHandled) {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' publisher ~ receiveFinishedSignal",
            mChannel->getName().string());
#endif

    InputMessage msg;
    status_t result = mChannel->receiveMessage(&msg);
    if (result) {
        *outSeq = 0;
        *outHandled = false;
        return result;
    }
    if (msg.header.type != InputMessage::TYPE_FINISHED) {
        ALOGE("channel '%s' publisher ~ Received unexpected message of type %d from consumer",
                mChannel->getName().string(), msg.header.type);
        return UNKNOWN_ERROR;
    }
    *outSeq = msg.body.finished.seq;
    *outHandled = msg.body.finished.handled;
    return OK;
}

// --- InputConsumer ---

InputConsumer::InputConsumer(const sp<InputChannel>& channel) :
        mResampleTouch(isTouchResamplingEnabled()),
        mChannel(channel), mMsgDeferred(false) {
}

InputConsumer::~InputConsumer() {
}

bool InputConsumer::isTouchResamplingEnabled() {
    char value[PROPERTY_VALUE_MAX];
    int length = property_get("debug.inputconsumer.resample", value, NULL);
    if (length > 0) {
        if (!strcmp("0", value)) {
            return false;
        }
        if (strcmp("1", value)) {
            ALOGD("Unrecognized property value for 'debug.inputconsumer.resample'.  "
                    "Use '1' or '0'.");
        }
    }
    return true;
}

status_t InputConsumer::consume(InputEventFactoryInterface* factory,
        bool consumeBatches, nsecs_t frameTime, uint32_t* outSeq, InputEvent** outEvent) {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' consumer ~ consume: consumeBatches=%s, frameTime=%lld",
            mChannel->getName().string(), consumeBatches ? "true" : "false", frameTime);
#endif

    *outSeq = 0;
    *outEvent = NULL;

    // Fetch the next input message.
    // Loop until an event can be returned or no additional events are received.
    while (!*outEvent) {
        if (mMsgDeferred) {
            // mMsg contains a valid input message from the previous call to consume
            // that has not yet been processed.
            mMsgDeferred = false;
        } else {
            // Receive a fresh message.
            status_t result = mChannel->receiveMessage(&mMsg);
            if (result) {
                // Consume the next batched event unless batches are being held for later.
                if (consumeBatches || result != WOULD_BLOCK) {
                    result = consumeBatch(factory, frameTime, outSeq, outEvent);
                    if (*outEvent) {
#if DEBUG_TRANSPORT_ACTIONS
                        ALOGD("channel '%s' consumer ~ consumed batch event, seq=%u",
                                mChannel->getName().string(), *outSeq);
#endif
                        break;
                    }
                }
                return result;
            }
        }

        switch (mMsg.header.type) {
        case InputMessage::TYPE_KEY: {
            KeyEvent* keyEvent = factory->createKeyEvent();
            if (!keyEvent) return NO_MEMORY;

            initializeKeyEvent(keyEvent, &mMsg);
            *outSeq = mMsg.body.key.seq;
            *outEvent = keyEvent;
#if DEBUG_TRANSPORT_ACTIONS
            ALOGD("channel '%s' consumer ~ consumed key event, seq=%u",
                    mChannel->getName().string(), *outSeq);
#endif
            break;
        }

        case AINPUT_EVENT_TYPE_MOTION: {
            ssize_t batchIndex = findBatch(mMsg.body.motion.deviceId, mMsg.body.motion.source);
            if (batchIndex >= 0) {
                Batch& batch = mBatches.editItemAt(batchIndex);
                if (canAddSample(batch, &mMsg)) {
                    batch.samples.push(mMsg);
#if DEBUG_TRANSPORT_ACTIONS
                    ALOGD("channel '%s' consumer ~ appended to batch event",
                            mChannel->getName().string());
#endif
                    break;
                } else {
                    // We cannot append to the batch in progress, so we need to consume
                    // the previous batch right now and defer the new message until later.
                    mMsgDeferred = true;
                    status_t result = consumeSamples(factory,
                            batch, batch.samples.size(), outSeq, outEvent);
                    mBatches.removeAt(batchIndex);
                    if (result) {
                        return result;
                    }
#if DEBUG_TRANSPORT_ACTIONS
                    ALOGD("channel '%s' consumer ~ consumed batch event and "
                            "deferred current event, seq=%u",
                            mChannel->getName().string(), *outSeq);
#endif
                    break;
                }
            }

            // Start a new batch if needed.
            if (mMsg.body.motion.action == AMOTION_EVENT_ACTION_MOVE
                    || mMsg.body.motion.action == AMOTION_EVENT_ACTION_HOVER_MOVE) {
                mBatches.push();
                Batch& batch = mBatches.editTop();
                batch.samples.push(mMsg);
#if DEBUG_TRANSPORT_ACTIONS
                ALOGD("channel '%s' consumer ~ started batch event",
                        mChannel->getName().string());
#endif
                break;
            }

            MotionEvent* motionEvent = factory->createMotionEvent();
            if (! motionEvent) return NO_MEMORY;

            updateTouchState(&mMsg);
            initializeMotionEvent(motionEvent, &mMsg);
            *outSeq = mMsg.body.motion.seq;
            *outEvent = motionEvent;
#if DEBUG_TRANSPORT_ACTIONS
            ALOGD("channel '%s' consumer ~ consumed motion event, seq=%u",
                    mChannel->getName().string(), *outSeq);
#endif
            break;
        }

        default:
            ALOGE("channel '%s' consumer ~ Received unexpected message of type %d",
                    mChannel->getName().string(), mMsg.header.type);
            return UNKNOWN_ERROR;
        }
    }
    return OK;
}

status_t InputConsumer::consumeBatch(InputEventFactoryInterface* factory,
        nsecs_t frameTime, uint32_t* outSeq, InputEvent** outEvent) {
    status_t result;
    for (size_t i = mBatches.size(); i-- > 0; ) {
        Batch& batch = mBatches.editItemAt(i);
        if (frameTime < 0) {
            result = consumeSamples(factory, batch, batch.samples.size(),
                    outSeq, outEvent);
            mBatches.removeAt(i);
            return result;
        }

        nsecs_t sampleTime = frameTime - RESAMPLE_LATENCY;
        ssize_t split = findSampleNoLaterThan(batch, sampleTime);
        if (split < 0) {
            continue;
        }

        result = consumeSamples(factory, batch, split + 1, outSeq, outEvent);
        const InputMessage* next;
        if (batch.samples.isEmpty()) {
            mBatches.removeAt(i);
            next = NULL;
        } else {
            next = &batch.samples.itemAt(0);
        }
        if (!result) {
            resampleTouchState(sampleTime, static_cast<MotionEvent*>(*outEvent), next);
        }
        return result;
    }

    return WOULD_BLOCK;
}

status_t InputConsumer::consumeSamples(InputEventFactoryInterface* factory,
        Batch& batch, size_t count, uint32_t* outSeq, InputEvent** outEvent) {
    MotionEvent* motionEvent = factory->createMotionEvent();
    if (! motionEvent) return NO_MEMORY;

    uint32_t chain = 0;
    for (size_t i = 0; i < count; i++) {
        InputMessage& msg = batch.samples.editItemAt(i);
        updateTouchState(&msg);
        if (i) {
            SeqChain seqChain;
            seqChain.seq = msg.body.motion.seq;
            seqChain.chain = chain;
            mSeqChains.push(seqChain);
            addSample(motionEvent, &msg);
        } else {
            initializeMotionEvent(motionEvent, &msg);
        }
        chain = msg.body.motion.seq;
    }
    batch.samples.removeItemsAt(0, count);

    *outSeq = chain;
    *outEvent = motionEvent;
    return OK;
}

void InputConsumer::updateTouchState(InputMessage* msg) {
    if (!mResampleTouch ||
            !(msg->body.motion.source & AINPUT_SOURCE_CLASS_POINTER)) {
        return;
    }

    int32_t deviceId = msg->body.motion.deviceId;
    int32_t source = msg->body.motion.source;
    nsecs_t eventTime = msg->body.motion.eventTime;

    // Update the touch state history to incorporate the new input message.
    // If the message is in the past relative to the most recently produced resampled
    // touch, then use the resampled time and coordinates instead.
    switch (msg->body.motion.action & AMOTION_EVENT_ACTION_MASK) {
    case AMOTION_EVENT_ACTION_DOWN: {
        ssize_t index = findTouchState(deviceId, source);
        if (index < 0) {
            mTouchStates.push();
            index = mTouchStates.size() - 1;
        }
        TouchState& touchState = mTouchStates.editItemAt(index);
        touchState.initialize(deviceId, source);
        touchState.addHistory(msg);
        break;
    }

    case AMOTION_EVENT_ACTION_MOVE: {
        ssize_t index = findTouchState(deviceId, source);
        if (index >= 0) {
            TouchState& touchState = mTouchStates.editItemAt(index);
            touchState.addHistory(msg);
            if (eventTime < touchState.lastResample.eventTime) {
                rewriteMessage(touchState, msg);
            } else {
                touchState.lastResample.idBits.clear();
            }
        }
        break;
    }

    case AMOTION_EVENT_ACTION_POINTER_DOWN: {
        ssize_t index = findTouchState(deviceId, source);
        if (index >= 0) {
            TouchState& touchState = mTouchStates.editItemAt(index);
            touchState.lastResample.idBits.clearBit(msg->body.motion.getActionId());
            rewriteMessage(touchState, msg);
        }
        break;
    }

    case AMOTION_EVENT_ACTION_POINTER_UP: {
        ssize_t index = findTouchState(deviceId, source);
        if (index >= 0) {
            TouchState& touchState = mTouchStates.editItemAt(index);
            rewriteMessage(touchState, msg);
            touchState.lastResample.idBits.clearBit(msg->body.motion.getActionId());
        }
        break;
    }

    case AMOTION_EVENT_ACTION_SCROLL: {
        ssize_t index = findTouchState(deviceId, source);
        if (index >= 0) {
            const TouchState& touchState = mTouchStates.itemAt(index);
            rewriteMessage(touchState, msg);
        }
        break;
    }

    case AMOTION_EVENT_ACTION_UP:
    case AMOTION_EVENT_ACTION_CANCEL: {
        ssize_t index = findTouchState(deviceId, source);
        if (index >= 0) {
            const TouchState& touchState = mTouchStates.itemAt(index);
            rewriteMessage(touchState, msg);
            mTouchStates.removeAt(index);
        }
        break;
    }
    }
}

void InputConsumer::rewriteMessage(const TouchState& state, InputMessage* msg) {
    for (size_t i = 0; i < msg->body.motion.pointerCount; i++) {
        uint32_t id = msg->body.motion.pointers[i].properties.id;
        if (state.lastResample.idBits.hasBit(id)) {
            PointerCoords& msgCoords = msg->body.motion.pointers[i].coords;
            const PointerCoords& resampleCoords = state.lastResample.getPointerById(id);
#if DEBUG_RESAMPLING
            ALOGD("[%d] - rewrite (%0.3f, %0.3f), old (%0.3f, %0.3f)", id,
                    resampleCoords.getAxisValue(AMOTION_EVENT_AXIS_X),
                    resampleCoords.getAxisValue(AMOTION_EVENT_AXIS_Y),
                    msgCoords.getAxisValue(AMOTION_EVENT_AXIS_X),
                    msgCoords.getAxisValue(AMOTION_EVENT_AXIS_Y));
#endif
            msgCoords.setAxisValue(AMOTION_EVENT_AXIS_X, resampleCoords.getX());
            msgCoords.setAxisValue(AMOTION_EVENT_AXIS_Y, resampleCoords.getY());
        }
    }
}

void InputConsumer::resampleTouchState(nsecs_t sampleTime, MotionEvent* event,
    const InputMessage* next) {
    if (!mResampleTouch
            || !(event->getSource() & AINPUT_SOURCE_CLASS_POINTER)
            || event->getAction() != AMOTION_EVENT_ACTION_MOVE) {
        return;
    }

    ssize_t index = findTouchState(event->getDeviceId(), event->getSource());
    if (index < 0) {
#if DEBUG_RESAMPLING
        ALOGD("Not resampled, no touch state for device.");
#endif
        return;
    }

    TouchState& touchState = mTouchStates.editItemAt(index);
    if (touchState.historySize < 1) {
#if DEBUG_RESAMPLING
        ALOGD("Not resampled, no history for device.");
#endif
        return;
    }

    // Ensure that the current sample has all of the pointers that need to be reported.
    const History* current = touchState.getHistory(0);
    size_t pointerCount = event->getPointerCount();
    for (size_t i = 0; i < pointerCount; i++) {
        uint32_t id = event->getPointerId(i);
        if (!current->idBits.hasBit(id)) {
#if DEBUG_RESAMPLING
            ALOGD("Not resampled, missing id %d", id);
#endif
            return;
        }
    }

    // Find the data to use for resampling.
    const History* other;
    History future;
    float alpha;
    if (next) {
        // Interpolate between current sample and future sample.
        // So current->eventTime <= sampleTime <= future.eventTime.
        future.initializeFrom(next);
        other = &future;
        nsecs_t delta = future.eventTime - current->eventTime;
        if (delta < RESAMPLE_MIN_DELTA) {
#if DEBUG_RESAMPLING
            ALOGD("Not resampled, delta time is %lld ns.", delta);
#endif
            return;
        }
        alpha = float(sampleTime - current->eventTime) / delta;
    } else if (touchState.historySize >= 2) {
        // Extrapolate future sample using current sample and past sample.
        // So other->eventTime <= current->eventTime <= sampleTime.
        other = touchState.getHistory(1);
        nsecs_t delta = current->eventTime - other->eventTime;
        if (delta < RESAMPLE_MIN_DELTA) {
#if DEBUG_RESAMPLING
            ALOGD("Not resampled, delta time is %lld ns.", delta);
#endif
            return;
        }
        nsecs_t maxPredict = current->eventTime + min(delta / 2, RESAMPLE_MAX_PREDICTION);
        if (sampleTime > maxPredict) {
#if DEBUG_RESAMPLING
            ALOGD("Sample time is too far in the future, adjusting prediction "
                    "from %lld to %lld ns.",
                    sampleTime - current->eventTime, maxPredict - current->eventTime);
#endif
            sampleTime = maxPredict;
        }
        alpha = float(current->eventTime - sampleTime) / delta;
    } else {
#if DEBUG_RESAMPLING
        ALOGD("Not resampled, insufficient data.");
#endif
        return;
    }

    // Resample touch coordinates.
    touchState.lastResample.eventTime = sampleTime;
    touchState.lastResample.idBits.clear();
    for (size_t i = 0; i < pointerCount; i++) {
        uint32_t id = event->getPointerId(i);
        touchState.lastResample.idToIndex[id] = i;
        touchState.lastResample.idBits.markBit(id);
        PointerCoords& resampledCoords = touchState.lastResample.pointers[i];
        const PointerCoords& currentCoords = current->getPointerById(id);
        if (other->idBits.hasBit(id)
                && shouldResampleTool(event->getToolType(i))) {
            const PointerCoords& otherCoords = other->getPointerById(id);
            resampledCoords.copyFrom(currentCoords);
            resampledCoords.setAxisValue(AMOTION_EVENT_AXIS_X,
                    lerp(currentCoords.getX(), otherCoords.getX(), alpha));
            resampledCoords.setAxisValue(AMOTION_EVENT_AXIS_Y,
                    lerp(currentCoords.getY(), otherCoords.getY(), alpha));
#if DEBUG_RESAMPLING
            ALOGD("[%d] - out (%0.3f, %0.3f), cur (%0.3f, %0.3f), "
                    "other (%0.3f, %0.3f), alpha %0.3f",
                    id, resampledCoords.getX(), resampledCoords.getY(),
                    currentCoords.getX(), currentCoords.getY(),
                    otherCoords.getX(), otherCoords.getY(),
                    alpha);
#endif
        } else {
            resampledCoords.copyFrom(currentCoords);
#if DEBUG_RESAMPLING
            ALOGD("[%d] - out (%0.3f, %0.3f), cur (%0.3f, %0.3f)",
                    id, resampledCoords.getX(), resampledCoords.getY(),
                    currentCoords.getX(), currentCoords.getY());
#endif
        }
    }

    event->addSample(sampleTime, touchState.lastResample.pointers);
}

bool InputConsumer::shouldResampleTool(int32_t toolType) {
    return toolType == AMOTION_EVENT_TOOL_TYPE_FINGER
            || toolType == AMOTION_EVENT_TOOL_TYPE_UNKNOWN;
}

status_t InputConsumer::sendFinishedSignal(uint32_t seq, bool handled) {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' consumer ~ sendFinishedSignal: seq=%u, handled=%s",
            mChannel->getName().string(), seq, handled ? "true" : "false");
#endif

    if (!seq) {
        ALOGE("Attempted to send a finished signal with sequence number 0.");
        return BAD_VALUE;
    }

    // Send finished signals for the batch sequence chain first.
    size_t seqChainCount = mSeqChains.size();
    if (seqChainCount) {
        uint32_t currentSeq = seq;
        uint32_t chainSeqs[seqChainCount];
        size_t chainIndex = 0;
        for (size_t i = seqChainCount; i-- > 0; ) {
             const SeqChain& seqChain = mSeqChains.itemAt(i);
             if (seqChain.seq == currentSeq) {
                 currentSeq = seqChain.chain;
                 chainSeqs[chainIndex++] = currentSeq;
                 mSeqChains.removeAt(i);
             }
        }
        status_t status = OK;
        while (!status && chainIndex-- > 0) {
            status = sendUnchainedFinishedSignal(chainSeqs[chainIndex], handled);
        }
        if (status) {
            // An error occurred so at least one signal was not sent, reconstruct the chain.
            do {
                SeqChain seqChain;
                seqChain.seq = chainIndex != 0 ? chainSeqs[chainIndex - 1] : seq;
                seqChain.chain = chainSeqs[chainIndex];
                mSeqChains.push(seqChain);
            } while (chainIndex-- > 0);
            return status;
        }
    }

    // Send finished signal for the last message in the batch.
    return sendUnchainedFinishedSignal(seq, handled);
}

status_t InputConsumer::sendUnchainedFinishedSignal(uint32_t seq, bool handled) {
    InputMessage msg;
    msg.header.type = InputMessage::TYPE_FINISHED;
    msg.body.finished.seq = seq;
    msg.body.finished.handled = handled;
    return mChannel->sendMessage(&msg);
}

bool InputConsumer::hasDeferredEvent() const {
    return mMsgDeferred;
}

bool InputConsumer::hasPendingBatch() const {
    return !mBatches.isEmpty();
}

ssize_t InputConsumer::findBatch(int32_t deviceId, int32_t source) const {
    for (size_t i = 0; i < mBatches.size(); i++) {
        const Batch& batch = mBatches.itemAt(i);
        const InputMessage& head = batch.samples.itemAt(0);
        if (head.body.motion.deviceId == deviceId && head.body.motion.source == source) {
            return i;
        }
    }
    return -1;
}

ssize_t InputConsumer::findTouchState(int32_t deviceId, int32_t source) const {
    for (size_t i = 0; i < mTouchStates.size(); i++) {
        const TouchState& touchState = mTouchStates.itemAt(i);
        if (touchState.deviceId == deviceId && touchState.source == source) {
            return i;
        }
    }
    return -1;
}

void InputConsumer::initializeKeyEvent(KeyEvent* event, const InputMessage* msg) {
    event->initialize(
            msg->body.key.deviceId,
            msg->body.key.source,
            msg->body.key.action,
            msg->body.key.flags,
            msg->body.key.keyCode,
            msg->body.key.scanCode,
            msg->body.key.metaState,
            msg->body.key.repeatCount,
            msg->body.key.downTime,
            msg->body.key.eventTime);
}

void InputConsumer::initializeMotionEvent(MotionEvent* event, const InputMessage* msg) {
    size_t pointerCount = msg->body.motion.pointerCount;
    PointerProperties pointerProperties[pointerCount];
    PointerCoords pointerCoords[pointerCount];
    for (size_t i = 0; i < pointerCount; i++) {
        pointerProperties[i].copyFrom(msg->body.motion.pointers[i].properties);
        pointerCoords[i].copyFrom(msg->body.motion.pointers[i].coords);
    }

    event->initialize(
            msg->body.motion.deviceId,
            msg->body.motion.source,
            msg->body.motion.action,
            msg->body.motion.flags,
            msg->body.motion.edgeFlags,
            msg->body.motion.metaState,
            msg->body.motion.buttonState,
            msg->body.motion.xOffset,
            msg->body.motion.yOffset,
            msg->body.motion.xPrecision,
            msg->body.motion.yPrecision,
            msg->body.motion.downTime,
            msg->body.motion.eventTime,
            pointerCount,
            pointerProperties,
            pointerCoords);
}

void InputConsumer::addSample(MotionEvent* event, const InputMessage* msg) {
    size_t pointerCount = msg->body.motion.pointerCount;
    PointerCoords pointerCoords[pointerCount];
    for (size_t i = 0; i < pointerCount; i++) {
        pointerCoords[i].copyFrom(msg->body.motion.pointers[i].coords);
    }

    event->setMetaState(event->getMetaState() | msg->body.motion.metaState);
    event->addSample(msg->body.motion.eventTime, pointerCoords);
}

bool InputConsumer::canAddSample(const Batch& batch, const InputMessage *msg) {
    const InputMessage& head = batch.samples.itemAt(0);
    size_t pointerCount = msg->body.motion.pointerCount;
    if (head.body.motion.pointerCount != pointerCount
            || head.body.motion.action != msg->body.motion.action) {
        return false;
    }
    for (size_t i = 0; i < pointerCount; i++) {
        if (head.body.motion.pointers[i].properties
                != msg->body.motion.pointers[i].properties) {
            return false;
        }
    }
    return true;
}

ssize_t InputConsumer::findSampleNoLaterThan(const Batch& batch, nsecs_t time) {
    size_t numSamples = batch.samples.size();
    size_t index = 0;
    while (index < numSamples
            && batch.samples.itemAt(index).body.motion.eventTime <= time) {
        index += 1;
    }
    return ssize_t(index) - 1;
}

} // namespace android
