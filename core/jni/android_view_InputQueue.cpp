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

#define LOG_TAG "InputQueue-JNI"

//#define LOG_NDEBUG 0

// Log debug messages about the dispatch cycle.
#define DEBUG_DISPATCH_CYCLE 0

// Log debug messages about registrations.
#define DEBUG_REGISTRATION 0


#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/KeyedVector.h>
#include <utils/threads.h>
#include <ui/InputTransport.h>
#include "android_os_MessageQueue.h"
#include "android_view_InputChannel.h"
#include "android_view_KeyEvent.h"
#include "android_view_MotionEvent.h"

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jmethodID dispatchKeyEvent;
    jmethodID dispatchMotionEvent;
} gInputQueueClassInfo;

// ----------------------------------------------------------------------------

class NativeInputQueue {
public:
    NativeInputQueue();
    ~NativeInputQueue();

    status_t registerInputChannel(JNIEnv* env, jobject inputChannelObj,
            jobject inputHandlerObj, jobject messageQueueObj);

    status_t unregisterInputChannel(JNIEnv* env, jobject inputChannelObj);

    status_t finished(JNIEnv* env, jlong finishedToken, bool handled, bool ignoreSpuriousFinish);

private:
    class Connection : public RefBase {
    protected:
        virtual ~Connection();

    public:
        enum Status {
            // Everything is peachy.
            STATUS_NORMAL,
            // The input channel has been unregistered.
            STATUS_ZOMBIE
        };

        Connection(uint16_t id,
                const sp<InputChannel>& inputChannel, const sp<Looper>& looper);

        inline const char* getInputChannelName() const { return inputChannel->getName().string(); }

        // A unique id for this connection.
        uint16_t id;

        Status status;

        sp<InputChannel> inputChannel;
        InputConsumer inputConsumer;
        sp<Looper> looper;
        jobject inputHandlerObjGlobal;
        PreallocatedInputEventFactory inputEventFactory;

        // The sequence number of the current event being dispatched.
        // This is used as part of the finished token as a way to determine whether the finished
        // token is still valid before sending a finished signal back to the publisher.
        uint16_t messageSeqNum;

        // True if a message has been received from the publisher but not yet finished.
        bool messageInProgress;
    };

    Mutex mLock;
    uint16_t mNextConnectionId;
    KeyedVector<int32_t, sp<Connection> > mConnectionsByReceiveFd;

    ssize_t getConnectionIndex(const sp<InputChannel>& inputChannel);

    static void handleInputChannelDisposed(JNIEnv* env,
            jobject inputChannelObj, const sp<InputChannel>& inputChannel, void* data);

    static int handleReceiveCallback(int receiveFd, int events, void* data);

    static jlong generateFinishedToken(int32_t receiveFd,
            uint16_t connectionId, uint16_t messageSeqNum);

    static void parseFinishedToken(jlong finishedToken,
            int32_t* outReceiveFd, uint16_t* outConnectionId, uint16_t* outMessageIndex);
};

// ----------------------------------------------------------------------------

NativeInputQueue::NativeInputQueue() :
        mNextConnectionId(0) {
}

NativeInputQueue::~NativeInputQueue() {
}

status_t NativeInputQueue::registerInputChannel(JNIEnv* env, jobject inputChannelObj,
        jobject inputHandlerObj, jobject messageQueueObj) {
    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == NULL) {
        LOGW("Input channel is not initialized.");
        return BAD_VALUE;
    }

#if DEBUG_REGISTRATION
    LOGD("channel '%s' - Registered", inputChannel->getName().string());
#endif

    sp<Looper> looper = android_os_MessageQueue_getLooper(env, messageQueueObj);

    { // acquire lock
        AutoMutex _l(mLock);

        if (getConnectionIndex(inputChannel) >= 0) {
            LOGW("Attempted to register already registered input channel '%s'",
                    inputChannel->getName().string());
            return BAD_VALUE;
        }

        uint16_t connectionId = mNextConnectionId++;
        sp<Connection> connection = new Connection(connectionId, inputChannel, looper);
        status_t result = connection->inputConsumer.initialize();
        if (result) {
            LOGW("Failed to initialize input consumer for input channel '%s', status=%d",
                    inputChannel->getName().string(), result);
            return result;
        }

        connection->inputHandlerObjGlobal = env->NewGlobalRef(inputHandlerObj);

        int32_t receiveFd = inputChannel->getReceivePipeFd();
        mConnectionsByReceiveFd.add(receiveFd, connection);

        looper->addFd(receiveFd, 0, ALOOPER_EVENT_INPUT, handleReceiveCallback, this);
    } // release lock

    android_view_InputChannel_setDisposeCallback(env, inputChannelObj,
            handleInputChannelDisposed, this);
    return OK;
}

status_t NativeInputQueue::unregisterInputChannel(JNIEnv* env, jobject inputChannelObj) {
    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == NULL) {
        LOGW("Input channel is not initialized.");
        return BAD_VALUE;
    }

#if DEBUG_REGISTRATION
    LOGD("channel '%s' - Unregistered", inputChannel->getName().string());
#endif

    { // acquire lock
        AutoMutex _l(mLock);

        ssize_t connectionIndex = getConnectionIndex(inputChannel);
        if (connectionIndex < 0) {
            LOGW("Attempted to unregister already unregistered input channel '%s'",
                    inputChannel->getName().string());
            return BAD_VALUE;
        }

        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        mConnectionsByReceiveFd.removeItemsAt(connectionIndex);

        connection->status = Connection::STATUS_ZOMBIE;

        connection->looper->removeFd(inputChannel->getReceivePipeFd());

        env->DeleteGlobalRef(connection->inputHandlerObjGlobal);
        connection->inputHandlerObjGlobal = NULL;

        if (connection->messageInProgress) {
            LOGI("Sending finished signal for input channel '%s' since it is being unregistered "
                    "while an input message is still in progress.",
                    connection->getInputChannelName());
            connection->messageInProgress = false;
            connection->inputConsumer.sendFinishedSignal(false); // ignoring result
        }
    } // release lock

    android_view_InputChannel_setDisposeCallback(env, inputChannelObj, NULL, NULL);
    return OK;
}

ssize_t NativeInputQueue::getConnectionIndex(const sp<InputChannel>& inputChannel) {
    ssize_t connectionIndex = mConnectionsByReceiveFd.indexOfKey(inputChannel->getReceivePipeFd());
    if (connectionIndex >= 0) {
        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        if (connection->inputChannel.get() == inputChannel.get()) {
            return connectionIndex;
        }
    }

    return -1;
}

status_t NativeInputQueue::finished(JNIEnv* env, jlong finishedToken,
        bool handled, bool ignoreSpuriousFinish) {
    int32_t receiveFd;
    uint16_t connectionId;
    uint16_t messageSeqNum;
    parseFinishedToken(finishedToken, &receiveFd, &connectionId, &messageSeqNum);

    { // acquire lock
        AutoMutex _l(mLock);

        ssize_t connectionIndex = mConnectionsByReceiveFd.indexOfKey(receiveFd);
        if (connectionIndex < 0) {
            if (! ignoreSpuriousFinish) {
                LOGI("Ignoring finish signal on channel that is no longer registered.");
            }
            return DEAD_OBJECT;
        }

        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        if (connectionId != connection->id) {
            if (! ignoreSpuriousFinish) {
                LOGI("Ignoring finish signal on channel that is no longer registered.");
            }
            return DEAD_OBJECT;
        }

        if (messageSeqNum != connection->messageSeqNum || ! connection->messageInProgress) {
            if (! ignoreSpuriousFinish) {
                LOGW("Attempted to finish input twice on channel '%s'.  "
                        "finished messageSeqNum=%d, current messageSeqNum=%d, messageInProgress=%d",
                        connection->getInputChannelName(),
                        messageSeqNum, connection->messageSeqNum, connection->messageInProgress);
            }
            return INVALID_OPERATION;
        }

        connection->messageInProgress = false;

        status_t status = connection->inputConsumer.sendFinishedSignal(handled);
        if (status) {
            LOGW("Failed to send finished signal on channel '%s'.  status=%d",
                    connection->getInputChannelName(), status);
            return status;
        }

#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Finished event.",
                connection->getInputChannelName());
#endif
    } // release lock

    return OK;
}

void NativeInputQueue::handleInputChannelDisposed(JNIEnv* env,
        jobject inputChannelObj, const sp<InputChannel>& inputChannel, void* data) {
    LOGW("Input channel object '%s' was disposed without first being unregistered with "
            "the input queue!", inputChannel->getName().string());

    NativeInputQueue* q = static_cast<NativeInputQueue*>(data);
    q->unregisterInputChannel(env, inputChannelObj);
}

int NativeInputQueue::handleReceiveCallback(int receiveFd, int events, void* data) {
    NativeInputQueue* q = static_cast<NativeInputQueue*>(data);
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    sp<Connection> connection;
    InputEvent* inputEvent;
    jobject inputHandlerObjLocal;
    jlong finishedToken;
    { // acquire lock
        AutoMutex _l(q->mLock);

        ssize_t connectionIndex = q->mConnectionsByReceiveFd.indexOfKey(receiveFd);
        if (connectionIndex < 0) {
            LOGE("Received spurious receive callback for unknown input channel.  "
                    "fd=%d, events=0x%x", receiveFd, events);
            return 0; // remove the callback
        }

        connection = q->mConnectionsByReceiveFd.valueAt(connectionIndex);
        if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
            LOGE("channel '%s' ~ Publisher closed input channel or an error occurred.  "
                    "events=0x%x", connection->getInputChannelName(), events);
            return 0; // remove the callback
        }

        if (! (events & ALOOPER_EVENT_INPUT)) {
            LOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  "
                    "events=0x%x", connection->getInputChannelName(), events);
            return 1;
        }

        status_t status = connection->inputConsumer.receiveDispatchSignal();
        if (status) {
            LOGE("channel '%s' ~ Failed to receive dispatch signal.  status=%d",
                    connection->getInputChannelName(), status);
            return 0; // remove the callback
        }

        if (connection->messageInProgress) {
            LOGW("channel '%s' ~ Publisher sent spurious dispatch signal.",
                    connection->getInputChannelName());
            return 1;
        }

        status = connection->inputConsumer.consume(& connection->inputEventFactory, & inputEvent);
        if (status) {
            LOGW("channel '%s' ~ Failed to consume input event.  status=%d",
                    connection->getInputChannelName(), status);
            connection->inputConsumer.sendFinishedSignal(false);
            return 1;
        }

        connection->messageInProgress = true;
        connection->messageSeqNum += 1;

        finishedToken = generateFinishedToken(receiveFd, connection->id, connection->messageSeqNum);

        inputHandlerObjLocal = env->NewLocalRef(connection->inputHandlerObjGlobal);
    } // release lock

    // Invoke the handler outside of the lock.
    //
    // Note: inputEvent is stored in a field of the connection object which could potentially
    //       become disposed due to the input channel being unregistered concurrently.
    //       For this reason, we explicitly keep the connection object alive by holding
    //       a strong pointer to it within this scope.  We also grabbed a local reference to
    //       the input handler object itself for the same reason.

    int32_t inputEventType = inputEvent->getType();

    jobject inputEventObj;
    jmethodID dispatchMethodId;
    switch (inputEventType) {
    case AINPUT_EVENT_TYPE_KEY:
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Received key event.", connection->getInputChannelName());
#endif
        inputEventObj = android_view_KeyEvent_fromNative(env,
                static_cast<KeyEvent*>(inputEvent));
        dispatchMethodId = gInputQueueClassInfo.dispatchKeyEvent;
        break;

    case AINPUT_EVENT_TYPE_MOTION:
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Received motion event.", connection->getInputChannelName());
#endif
        inputEventObj = android_view_MotionEvent_obtainAsCopy(env,
                static_cast<MotionEvent*>(inputEvent));
        dispatchMethodId = gInputQueueClassInfo.dispatchMotionEvent;
        break;

    default:
        assert(false); // InputConsumer should prevent this from ever happening
        inputEventObj = NULL;
    }

    if (! inputEventObj) {
        LOGW("channel '%s' ~ Failed to obtain DVM event object.",
                connection->getInputChannelName());
        env->DeleteLocalRef(inputHandlerObjLocal);
        q->finished(env, finishedToken, false, false);
        return 1;
    }

#if DEBUG_DISPATCH_CYCLE
    LOGD("Invoking input handler.");
#endif
    env->CallStaticVoidMethod(gInputQueueClassInfo.clazz,
            dispatchMethodId, inputHandlerObjLocal, inputEventObj,
            jlong(finishedToken));
#if DEBUG_DISPATCH_CYCLE
    LOGD("Returned from input handler.");
#endif

    if (env->ExceptionCheck()) {
        LOGE("An exception occurred while invoking the input handler for an event.");
        LOGE_EX(env);
        env->ExceptionClear();

        q->finished(env, finishedToken, false, true /*ignoreSpuriousFinish*/);
    }

    env->DeleteLocalRef(inputEventObj);
    env->DeleteLocalRef(inputHandlerObjLocal);
    return 1;
}

jlong NativeInputQueue::generateFinishedToken(int32_t receiveFd, uint16_t connectionId,
        uint16_t messageSeqNum) {
    return (jlong(receiveFd) << 32) | (jlong(connectionId) << 16) | jlong(messageSeqNum);
}

void NativeInputQueue::parseFinishedToken(jlong finishedToken,
        int32_t* outReceiveFd, uint16_t* outConnectionId, uint16_t* outMessageIndex) {
    *outReceiveFd = int32_t(finishedToken >> 32);
    *outConnectionId = uint16_t(finishedToken >> 16);
    *outMessageIndex = uint16_t(finishedToken);
}

// ----------------------------------------------------------------------------

NativeInputQueue::Connection::Connection(uint16_t id,
        const sp<InputChannel>& inputChannel, const sp<Looper>& looper) :
    id(id), status(STATUS_NORMAL), inputChannel(inputChannel), inputConsumer(inputChannel),
    looper(looper), inputHandlerObjGlobal(NULL),
    messageSeqNum(0), messageInProgress(false) {
}

NativeInputQueue::Connection::~Connection() {
}

// ----------------------------------------------------------------------------

static NativeInputQueue gNativeInputQueue;

static void android_view_InputQueue_nativeRegisterInputChannel(JNIEnv* env, jclass clazz,
        jobject inputChannelObj, jobject inputHandlerObj, jobject messageQueueObj) {
    status_t status = gNativeInputQueue.registerInputChannel(
            env, inputChannelObj, inputHandlerObj, messageQueueObj);

    if (status) {
        String8 message;
        message.appendFormat("Failed to register input channel.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
    }
}

static void android_view_InputQueue_nativeUnregisterInputChannel(JNIEnv* env, jclass clazz,
        jobject inputChannelObj) {
    status_t status = gNativeInputQueue.unregisterInputChannel(env, inputChannelObj);

    if (status) {
        String8 message;
        message.appendFormat("Failed to unregister input channel.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
    }
}

static void android_view_InputQueue_nativeFinished(JNIEnv* env, jclass clazz,
        jlong finishedToken, bool handled) {
    status_t status = gNativeInputQueue.finished(
            env, finishedToken, handled, false /*ignoreSpuriousFinish*/);

    // We ignore the case where an event could not be finished because the input channel
    // was no longer registered (DEAD_OBJECT) since it is a common race that can occur
    // during application shutdown.  The input dispatcher recovers gracefully anyways.
    if (status != OK && status != DEAD_OBJECT) {
        String8 message;
        message.appendFormat("Failed to finish input event.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
    }
}

// ----------------------------------------------------------------------------

static JNINativeMethod gInputQueueMethods[] = {
    /* name, signature, funcPtr */
    { "nativeRegisterInputChannel",
            "(Landroid/view/InputChannel;Landroid/view/InputHandler;Landroid/os/MessageQueue;)V",
            (void*)android_view_InputQueue_nativeRegisterInputChannel },
    { "nativeUnregisterInputChannel",
            "(Landroid/view/InputChannel;)V",
            (void*)android_view_InputQueue_nativeUnregisterInputChannel },
    { "nativeFinished", "(JZ)V",
            (void*)android_view_InputQueue_nativeFinished }
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_STATIC_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetStaticMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find static method " methodName);

int register_android_view_InputQueue(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/InputQueue",
            gInputQueueMethods, NELEM(gInputQueueMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    FIND_CLASS(gInputQueueClassInfo.clazz, "android/view/InputQueue");

    GET_STATIC_METHOD_ID(gInputQueueClassInfo.dispatchKeyEvent, gInputQueueClassInfo.clazz,
            "dispatchKeyEvent",
            "(Landroid/view/InputHandler;Landroid/view/KeyEvent;J)V");

    GET_STATIC_METHOD_ID(gInputQueueClassInfo.dispatchMotionEvent, gInputQueueClassInfo.clazz,
            "dispatchMotionEvent",
            "(Landroid/view/InputHandler;Landroid/view/MotionEvent;J)V");
    return 0;
}

} // namespace android
