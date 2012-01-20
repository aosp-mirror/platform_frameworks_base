//
// Copyright 2010 The Android Open Source Project
//
// Provides a shared memory transport for input events.
//
#define LOG_TAG "InputTransport"

//#define LOG_NDEBUG 0

// Log debug messages about channel signalling (send signal, receive signal)
#define DEBUG_CHANNEL_SIGNALS 0

// Log debug messages whenever InputChannel objects are created/destroyed
#define DEBUG_CHANNEL_LIFECYCLE 0

// Log debug messages about transport actions (initialize, reset, publish, ...)
#define DEBUG_TRANSPORT_ACTIONS 0


#include <cutils/ashmem.h>
#include <cutils/log.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <ui/InputTransport.h>
#include <unistd.h>

namespace android {

#define ROUND_UP(value, boundary) (((value) + (boundary) - 1) & ~((boundary) - 1))
#define MIN_HISTORY_DEPTH 20

// Must be at least sizeof(InputMessage) + sufficient space for pointer data
static const int DEFAULT_MESSAGE_BUFFER_SIZE = ROUND_UP(
        sizeof(InputMessage) + MIN_HISTORY_DEPTH
                * (sizeof(InputMessage::SampleData) + MAX_POINTERS * sizeof(PointerCoords)),
        4096);

// Signal sent by the producer to the consumer to inform it that a new message is
// available to be consumed in the shared memory buffer.
static const char INPUT_SIGNAL_DISPATCH = 'D';

// Signal sent by the consumer to the producer to inform it that it has finished
// consuming the most recent message and it handled it.
static const char INPUT_SIGNAL_FINISHED_HANDLED = 'f';

// Signal sent by the consumer to the producer to inform it that it has finished
// consuming the most recent message but it did not handle it.
static const char INPUT_SIGNAL_FINISHED_UNHANDLED = 'u';


// --- InputChannel ---

InputChannel::InputChannel(const String8& name, int32_t ashmemFd, int32_t receivePipeFd,
        int32_t sendPipeFd) :
        mName(name), mAshmemFd(ashmemFd), mReceivePipeFd(receivePipeFd), mSendPipeFd(sendPipeFd) {
#if DEBUG_CHANNEL_LIFECYCLE
    ALOGD("Input channel constructed: name='%s', ashmemFd=%d, receivePipeFd=%d, sendPipeFd=%d",
            mName.string(), ashmemFd, receivePipeFd, sendPipeFd);
#endif

    int result = fcntl(mReceivePipeFd, F_SETFL, O_NONBLOCK);
    LOG_ALWAYS_FATAL_IF(result != 0, "channel '%s' ~ Could not make receive pipe "
            "non-blocking.  errno=%d", mName.string(), errno);

    result = fcntl(mSendPipeFd, F_SETFL, O_NONBLOCK);
    LOG_ALWAYS_FATAL_IF(result != 0, "channel '%s' ~ Could not make send pipe "
            "non-blocking.  errno=%d", mName.string(), errno);
}

InputChannel::~InputChannel() {
#if DEBUG_CHANNEL_LIFECYCLE
    ALOGD("Input channel destroyed: name='%s', ashmemFd=%d, receivePipeFd=%d, sendPipeFd=%d",
            mName.string(), mAshmemFd, mReceivePipeFd, mSendPipeFd);
#endif

    ::close(mAshmemFd);
    ::close(mReceivePipeFd);
    ::close(mSendPipeFd);
}

status_t InputChannel::openInputChannelPair(const String8& name,
        sp<InputChannel>& outServerChannel, sp<InputChannel>& outClientChannel) {
    status_t result;

    String8 ashmemName("InputChannel ");
    ashmemName.append(name);
    int serverAshmemFd = ashmem_create_region(ashmemName.string(), DEFAULT_MESSAGE_BUFFER_SIZE);
    if (serverAshmemFd < 0) {
        result = -errno;
        LOGE("channel '%s' ~ Could not create shared memory region. errno=%d",
                name.string(), errno);
    } else {
        result = ashmem_set_prot_region(serverAshmemFd, PROT_READ | PROT_WRITE);
        if (result < 0) {
            LOGE("channel '%s' ~ Error %d trying to set protection of ashmem fd %d.",
                    name.string(), result, serverAshmemFd);
        } else {
            // Dup the file descriptor because the server and client input channel objects that
            // are returned may have different lifetimes but they share the same shared memory region.
            int clientAshmemFd;
            clientAshmemFd = dup(serverAshmemFd);
            if (clientAshmemFd < 0) {
                result = -errno;
                LOGE("channel '%s' ~ Could not dup() shared memory region fd. errno=%d",
                        name.string(), errno);
            } else {
                int forward[2];
                if (pipe(forward)) {
                    result = -errno;
                    LOGE("channel '%s' ~ Could not create forward pipe.  errno=%d",
                            name.string(), errno);
                } else {
                    int reverse[2];
                    if (pipe(reverse)) {
                        result = -errno;
                        LOGE("channel '%s' ~ Could not create reverse pipe.  errno=%d",
                                name.string(), errno);
                    } else {
                        String8 serverChannelName = name;
                        serverChannelName.append(" (server)");
                        outServerChannel = new InputChannel(serverChannelName,
                                serverAshmemFd, reverse[0], forward[1]);

                        String8 clientChannelName = name;
                        clientChannelName.append(" (client)");
                        outClientChannel = new InputChannel(clientChannelName,
                                clientAshmemFd, forward[0], reverse[1]);
                        return OK;
                    }
                    ::close(forward[0]);
                    ::close(forward[1]);
                }
                ::close(clientAshmemFd);
            }
        }
        ::close(serverAshmemFd);
    }

    outServerChannel.clear();
    outClientChannel.clear();
    return result;
}

status_t InputChannel::sendSignal(char signal) {
    ssize_t nWrite;
    do {
        nWrite = ::write(mSendPipeFd, & signal, 1);
    } while (nWrite == -1 && errno == EINTR);

    if (nWrite == 1) {
#if DEBUG_CHANNEL_SIGNALS
        ALOGD("channel '%s' ~ sent signal '%c'", mName.string(), signal);
#endif
        return OK;
    }

#if DEBUG_CHANNEL_SIGNALS
    ALOGD("channel '%s' ~ error sending signal '%c', errno=%d", mName.string(), signal, errno);
#endif
    return -errno;
}

status_t InputChannel::receiveSignal(char* outSignal) {
    ssize_t nRead;
    do {
        nRead = ::read(mReceivePipeFd, outSignal, 1);
    } while (nRead == -1 && errno == EINTR);

    if (nRead == 1) {
#if DEBUG_CHANNEL_SIGNALS
        ALOGD("channel '%s' ~ received signal '%c'", mName.string(), *outSignal);
#endif
        return OK;
    }

    if (nRead == 0) { // check for EOF
#if DEBUG_CHANNEL_SIGNALS
        ALOGD("channel '%s' ~ receive signal failed because peer was closed", mName.string());
#endif
        return DEAD_OBJECT;
    }

    if (errno == EAGAIN) {
#if DEBUG_CHANNEL_SIGNALS
        ALOGD("channel '%s' ~ receive signal failed because no signal available", mName.string());
#endif
        return WOULD_BLOCK;
    }

#if DEBUG_CHANNEL_SIGNALS
    ALOGD("channel '%s' ~ receive signal failed, errno=%d", mName.string(), errno);
#endif
    return -errno;
}


// --- InputPublisher ---

InputPublisher::InputPublisher(const sp<InputChannel>& channel) :
        mChannel(channel), mSharedMessage(NULL),
        mPinned(false), mSemaphoreInitialized(false), mWasDispatched(false),
        mMotionEventSampleDataTail(NULL) {
}

InputPublisher::~InputPublisher() {
    reset();

    if (mSharedMessage) {
        munmap(mSharedMessage, mAshmemSize);
    }
}

status_t InputPublisher::initialize() {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' publisher ~ initialize",
            mChannel->getName().string());
#endif

    int ashmemFd = mChannel->getAshmemFd();
    int result = ashmem_get_size_region(ashmemFd);
    if (result < 0) {
        LOGE("channel '%s' publisher ~ Error %d getting size of ashmem fd %d.",
                mChannel->getName().string(), result, ashmemFd);
        return UNKNOWN_ERROR;
    }
    mAshmemSize = (size_t) result;

    mSharedMessage = static_cast<InputMessage*>(mmap(NULL, mAshmemSize,
            PROT_READ | PROT_WRITE, MAP_SHARED, ashmemFd, 0));
    if (! mSharedMessage) {
        LOGE("channel '%s' publisher ~ mmap failed on ashmem fd %d.",
                mChannel->getName().string(), ashmemFd);
        return NO_MEMORY;
    }

    mPinned = true;
    mSharedMessage->consumed = false;

    return reset();
}

status_t InputPublisher::reset() {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' publisher ~ reset",
        mChannel->getName().string());
#endif

    if (mPinned) {
        // Destroy the semaphore since we are about to unpin the memory region that contains it.
        int result;
        if (mSemaphoreInitialized) {
            if (mSharedMessage->consumed) {
                result = sem_post(& mSharedMessage->semaphore);
                if (result < 0) {
                    LOGE("channel '%s' publisher ~ Error %d in sem_post.",
                            mChannel->getName().string(), errno);
                    return UNKNOWN_ERROR;
                }
            }

            result = sem_destroy(& mSharedMessage->semaphore);
            if (result < 0) {
                LOGE("channel '%s' publisher ~ Error %d in sem_destroy.",
                        mChannel->getName().string(), errno);
                return UNKNOWN_ERROR;
            }

            mSemaphoreInitialized = false;
        }

        // Unpin the region since we no longer care about its contents.
        int ashmemFd = mChannel->getAshmemFd();
        result = ashmem_unpin_region(ashmemFd, 0, 0);
        if (result < 0) {
            LOGE("channel '%s' publisher ~ Error %d unpinning ashmem fd %d.",
                    mChannel->getName().string(), result, ashmemFd);
            return UNKNOWN_ERROR;
        }

        mPinned = false;
    }

    mMotionEventSampleDataTail = NULL;
    mWasDispatched = false;
    return OK;
}

status_t InputPublisher::publishInputEvent(
        int32_t type,
        int32_t deviceId,
        int32_t source) {
    if (mPinned) {
        LOGE("channel '%s' publisher ~ Attempted to publish a new event but publisher has "
                "not yet been reset.", mChannel->getName().string());
        return INVALID_OPERATION;
    }

    // Pin the region.
    // We do not check for ASHMEM_NOT_PURGED because we don't care about the previous
    // contents of the buffer so it does not matter whether it was purged in the meantime.
    int ashmemFd = mChannel->getAshmemFd();
    int result = ashmem_pin_region(ashmemFd, 0, 0);
    if (result < 0) {
        LOGE("channel '%s' publisher ~ Error %d pinning ashmem fd %d.",
                mChannel->getName().string(), result, ashmemFd);
        return UNKNOWN_ERROR;
    }

    mPinned = true;

    result = sem_init(& mSharedMessage->semaphore, 1, 1);
    if (result < 0) {
        LOGE("channel '%s' publisher ~ Error %d in sem_init.",
                mChannel->getName().string(), errno);
        return UNKNOWN_ERROR;
    }

    mSemaphoreInitialized = true;

    mSharedMessage->consumed = false;
    mSharedMessage->type = type;
    mSharedMessage->deviceId = deviceId;
    mSharedMessage->source = source;
    return OK;
}

status_t InputPublisher::publishKeyEvent(
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
    ALOGD("channel '%s' publisher ~ publishKeyEvent: deviceId=%d, source=0x%x, "
            "action=0x%x, flags=0x%x, keyCode=%d, scanCode=%d, metaState=0x%x, repeatCount=%d,"
            "downTime=%lld, eventTime=%lld",
            mChannel->getName().string(),
            deviceId, source, action, flags, keyCode, scanCode, metaState, repeatCount,
            downTime, eventTime);
#endif

    status_t result = publishInputEvent(AINPUT_EVENT_TYPE_KEY, deviceId, source);
    if (result < 0) {
        return result;
    }

    mSharedMessage->key.action = action;
    mSharedMessage->key.flags = flags;
    mSharedMessage->key.keyCode = keyCode;
    mSharedMessage->key.scanCode = scanCode;
    mSharedMessage->key.metaState = metaState;
    mSharedMessage->key.repeatCount = repeatCount;
    mSharedMessage->key.downTime = downTime;
    mSharedMessage->key.eventTime = eventTime;
    return OK;
}

status_t InputPublisher::publishMotionEvent(
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
    ALOGD("channel '%s' publisher ~ publishMotionEvent: deviceId=%d, source=0x%x, "
            "action=0x%x, flags=0x%x, edgeFlags=0x%x, metaState=0x%x, buttonState=0x%x, "
            "xOffset=%f, yOffset=%f, "
            "xPrecision=%f, yPrecision=%f, downTime=%lld, eventTime=%lld, "
            "pointerCount=%d",
            mChannel->getName().string(),
            deviceId, source, action, flags, edgeFlags, metaState, buttonState,
            xOffset, yOffset, xPrecision, yPrecision, downTime, eventTime, pointerCount);
#endif

    if (pointerCount > MAX_POINTERS || pointerCount < 1) {
        LOGE("channel '%s' publisher ~ Invalid number of pointers provided: %d.",
                mChannel->getName().string(), pointerCount);
        return BAD_VALUE;
    }

    status_t result = publishInputEvent(AINPUT_EVENT_TYPE_MOTION, deviceId, source);
    if (result < 0) {
        return result;
    }

    mSharedMessage->motion.action = action;
    mSharedMessage->motion.flags = flags;
    mSharedMessage->motion.edgeFlags = edgeFlags;
    mSharedMessage->motion.metaState = metaState;
    mSharedMessage->motion.buttonState = buttonState;
    mSharedMessage->motion.xOffset = xOffset;
    mSharedMessage->motion.yOffset = yOffset;
    mSharedMessage->motion.xPrecision = xPrecision;
    mSharedMessage->motion.yPrecision = yPrecision;
    mSharedMessage->motion.downTime = downTime;
    mSharedMessage->motion.pointerCount = pointerCount;

    mSharedMessage->motion.sampleCount = 1;
    mSharedMessage->motion.sampleData[0].eventTime = eventTime;

    for (size_t i = 0; i < pointerCount; i++) {
        mSharedMessage->motion.pointerProperties[i].copyFrom(pointerProperties[i]);
        mSharedMessage->motion.sampleData[0].coords[i].copyFrom(pointerCoords[i]);
    }

    // Cache essential information about the motion event to ensure that a malicious consumer
    // cannot confuse the publisher by modifying the contents of the shared memory buffer while
    // it is being updated.
    if (action == AMOTION_EVENT_ACTION_MOVE
            || action == AMOTION_EVENT_ACTION_HOVER_MOVE) {
        mMotionEventPointerCount = pointerCount;
        mMotionEventSampleDataStride = InputMessage::sampleDataStride(pointerCount);
        mMotionEventSampleDataTail = InputMessage::sampleDataPtrIncrement(
                mSharedMessage->motion.sampleData, mMotionEventSampleDataStride);
    } else {
        mMotionEventSampleDataTail = NULL;
    }
    return OK;
}

status_t InputPublisher::appendMotionSample(
        nsecs_t eventTime,
        const PointerCoords* pointerCoords) {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' publisher ~ appendMotionSample: eventTime=%lld",
            mChannel->getName().string(), eventTime);
#endif

    if (! mPinned || ! mMotionEventSampleDataTail) {
        LOGE("channel '%s' publisher ~ Cannot append motion sample because there is no current "
                "AMOTION_EVENT_ACTION_MOVE or AMOTION_EVENT_ACTION_HOVER_MOVE event.",
                mChannel->getName().string());
        return INVALID_OPERATION;
    }

    InputMessage::SampleData* newTail = InputMessage::sampleDataPtrIncrement(
            mMotionEventSampleDataTail, mMotionEventSampleDataStride);
    size_t newBytesUsed = reinterpret_cast<char*>(newTail) -
            reinterpret_cast<char*>(mSharedMessage);

    if (newBytesUsed > mAshmemSize) {
#if DEBUG_TRANSPORT_ACTIONS
        ALOGD("channel '%s' publisher ~ Cannot append motion sample because the shared memory "
                "buffer is full.  Buffer size: %d bytes, pointers: %d, samples: %d",
                mChannel->getName().string(),
                mAshmemSize, mMotionEventPointerCount, mSharedMessage->motion.sampleCount);
#endif
        return NO_MEMORY;
    }

    int result;
    if (mWasDispatched) {
        result = sem_trywait(& mSharedMessage->semaphore);
        if (result < 0) {
            if (errno == EAGAIN) {
                // Only possible source of contention is the consumer having consumed (or being in the
                // process of consuming) the message and left the semaphore count at 0.
#if DEBUG_TRANSPORT_ACTIONS
                ALOGD("channel '%s' publisher ~ Cannot append motion sample because the message has "
                        "already been consumed.", mChannel->getName().string());
#endif
                return FAILED_TRANSACTION;
            } else {
                LOGE("channel '%s' publisher ~ Error %d in sem_trywait.",
                        mChannel->getName().string(), errno);
                return UNKNOWN_ERROR;
            }
        }
    }

    mMotionEventSampleDataTail->eventTime = eventTime;
    for (size_t i = 0; i < mMotionEventPointerCount; i++) {
        mMotionEventSampleDataTail->coords[i].copyFrom(pointerCoords[i]);
    }
    mMotionEventSampleDataTail = newTail;

    mSharedMessage->motion.sampleCount += 1;

    if (mWasDispatched) {
        result = sem_post(& mSharedMessage->semaphore);
        if (result < 0) {
            LOGE("channel '%s' publisher ~ Error %d in sem_post.",
                    mChannel->getName().string(), errno);
            return UNKNOWN_ERROR;
        }
    }
    return OK;
}

status_t InputPublisher::sendDispatchSignal() {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' publisher ~ sendDispatchSignal",
            mChannel->getName().string());
#endif

    mWasDispatched = true;
    return mChannel->sendSignal(INPUT_SIGNAL_DISPATCH);
}

status_t InputPublisher::receiveFinishedSignal(bool* outHandled) {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' publisher ~ receiveFinishedSignal",
            mChannel->getName().string());
#endif

    char signal;
    status_t result = mChannel->receiveSignal(& signal);
    if (result) {
        *outHandled = false;
        return result;
    }
    if (signal == INPUT_SIGNAL_FINISHED_HANDLED) {
        *outHandled = true;
    } else if (signal == INPUT_SIGNAL_FINISHED_UNHANDLED) {
        *outHandled = false;
    } else {
        LOGE("channel '%s' publisher ~ Received unexpected signal '%c' from consumer",
                mChannel->getName().string(), signal);
        return UNKNOWN_ERROR;
    }
    return OK;
}

// --- InputConsumer ---

InputConsumer::InputConsumer(const sp<InputChannel>& channel) :
        mChannel(channel), mSharedMessage(NULL) {
}

InputConsumer::~InputConsumer() {
    if (mSharedMessage) {
        munmap(mSharedMessage, mAshmemSize);
    }
}

status_t InputConsumer::initialize() {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' consumer ~ initialize",
            mChannel->getName().string());
#endif

    int ashmemFd = mChannel->getAshmemFd();
    int result = ashmem_get_size_region(ashmemFd);
    if (result < 0) {
        LOGE("channel '%s' consumer ~ Error %d getting size of ashmem fd %d.",
                mChannel->getName().string(), result, ashmemFd);
        return UNKNOWN_ERROR;
    }

    mAshmemSize = (size_t) result;

    mSharedMessage = static_cast<InputMessage*>(mmap(NULL, mAshmemSize,
            PROT_READ | PROT_WRITE, MAP_SHARED, ashmemFd, 0));
    if (! mSharedMessage) {
        LOGE("channel '%s' consumer ~ mmap failed on ashmem fd %d.",
                mChannel->getName().string(), ashmemFd);
        return NO_MEMORY;
    }

    return OK;
}

status_t InputConsumer::consume(InputEventFactoryInterface* factory, InputEvent** outEvent) {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' consumer ~ consume",
            mChannel->getName().string());
#endif

    *outEvent = NULL;

    int ashmemFd = mChannel->getAshmemFd();
    int result = ashmem_pin_region(ashmemFd, 0, 0);
    if (result != ASHMEM_NOT_PURGED) {
        if (result == ASHMEM_WAS_PURGED) {
            LOGE("channel '%s' consumer ~ Error %d pinning ashmem fd %d because it was purged "
                    "which probably indicates that the publisher and consumer are out of sync.",
                    mChannel->getName().string(), result, ashmemFd);
            return INVALID_OPERATION;
        }

        LOGE("channel '%s' consumer ~ Error %d pinning ashmem fd %d.",
                mChannel->getName().string(), result, ashmemFd);
        return UNKNOWN_ERROR;
    }

    if (mSharedMessage->consumed) {
        LOGE("channel '%s' consumer ~ The current message has already been consumed.",
                mChannel->getName().string());
        return INVALID_OPERATION;
    }

    // Acquire but *never release* the semaphore.  Contention on the semaphore is used to signal
    // to the publisher that the message has been consumed (or is in the process of being
    // consumed).  Eventually the publisher will reinitialize the semaphore for the next message.
    result = sem_wait(& mSharedMessage->semaphore);
    if (result < 0) {
        LOGE("channel '%s' consumer ~ Error %d in sem_wait.",
                mChannel->getName().string(), errno);
        return UNKNOWN_ERROR;
    }

    mSharedMessage->consumed = true;

    switch (mSharedMessage->type) {
    case AINPUT_EVENT_TYPE_KEY: {
        KeyEvent* keyEvent = factory->createKeyEvent();
        if (! keyEvent) return NO_MEMORY;

        populateKeyEvent(keyEvent);

        *outEvent = keyEvent;
        break;
    }

    case AINPUT_EVENT_TYPE_MOTION: {
        MotionEvent* motionEvent = factory->createMotionEvent();
        if (! motionEvent) return NO_MEMORY;

        populateMotionEvent(motionEvent);

        *outEvent = motionEvent;
        break;
    }

    default:
        LOGE("channel '%s' consumer ~ Received message of unknown type %d",
                mChannel->getName().string(), mSharedMessage->type);
        return UNKNOWN_ERROR;
    }

    return OK;
}

status_t InputConsumer::sendFinishedSignal(bool handled) {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' consumer ~ sendFinishedSignal: handled=%d",
            mChannel->getName().string(), handled);
#endif

    return mChannel->sendSignal(handled
            ? INPUT_SIGNAL_FINISHED_HANDLED
            : INPUT_SIGNAL_FINISHED_UNHANDLED);
}

status_t InputConsumer::receiveDispatchSignal() {
#if DEBUG_TRANSPORT_ACTIONS
    ALOGD("channel '%s' consumer ~ receiveDispatchSignal",
            mChannel->getName().string());
#endif

    char signal;
    status_t result = mChannel->receiveSignal(& signal);
    if (result) {
        return result;
    }
    if (signal != INPUT_SIGNAL_DISPATCH) {
        LOGE("channel '%s' consumer ~ Received unexpected signal '%c' from publisher",
                mChannel->getName().string(), signal);
        return UNKNOWN_ERROR;
    }
    return OK;
}

void InputConsumer::populateKeyEvent(KeyEvent* keyEvent) const {
    keyEvent->initialize(
            mSharedMessage->deviceId,
            mSharedMessage->source,
            mSharedMessage->key.action,
            mSharedMessage->key.flags,
            mSharedMessage->key.keyCode,
            mSharedMessage->key.scanCode,
            mSharedMessage->key.metaState,
            mSharedMessage->key.repeatCount,
            mSharedMessage->key.downTime,
            mSharedMessage->key.eventTime);
}

void InputConsumer::populateMotionEvent(MotionEvent* motionEvent) const {
    motionEvent->initialize(
            mSharedMessage->deviceId,
            mSharedMessage->source,
            mSharedMessage->motion.action,
            mSharedMessage->motion.flags,
            mSharedMessage->motion.edgeFlags,
            mSharedMessage->motion.metaState,
            mSharedMessage->motion.buttonState,
            mSharedMessage->motion.xOffset,
            mSharedMessage->motion.yOffset,
            mSharedMessage->motion.xPrecision,
            mSharedMessage->motion.yPrecision,
            mSharedMessage->motion.downTime,
            mSharedMessage->motion.sampleData[0].eventTime,
            mSharedMessage->motion.pointerCount,
            mSharedMessage->motion.pointerProperties,
            mSharedMessage->motion.sampleData[0].coords);

    size_t sampleCount = mSharedMessage->motion.sampleCount;
    if (sampleCount > 1) {
        InputMessage::SampleData* sampleData = mSharedMessage->motion.sampleData;
        size_t sampleDataStride = InputMessage::sampleDataStride(
                mSharedMessage->motion.pointerCount);

        while (--sampleCount > 0) {
            sampleData = InputMessage::sampleDataPtrIncrement(sampleData, sampleDataStride);
            motionEvent->addSample(sampleData->eventTime, sampleData->coords);
        }
    }
}

} // namespace android
