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
#define DEBUG_DISPATCH_CYCLE 1

// Log debug messages about registrations.
#define DEBUG_REGISTRATION 1


#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/PollLoop.h>
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

    status_t finished(JNIEnv* env, jlong finishedToken, bool ignoreSpuriousFinish);

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

        Connection(const sp<InputChannel>& inputChannel, const sp<PollLoop>& pollLoop);

        inline const char* getInputChannelName() const { return inputChannel->getName().string(); }

        Status status;

        sp<InputChannel> inputChannel;
        InputConsumer inputConsumer;
        sp<PollLoop> pollLoop;
        jobject inputHandlerObjGlobal;
        PreallocatedInputEventFactory inputEventFactory;

        // The sequence number of the current event being dispatched.
        // This is used as part of the finished token as a way to determine whether the finished
        // token is still valid before sending a finished signal back to the publisher.
        uint32_t messageSeqNum;

        // True if a message has been received from the publisher but not yet finished.
        bool messageInProgress;
    };

    Mutex mLock;
    KeyedVector<int32_t, sp<Connection> > mConnectionsByReceiveFd;

    static void handleInputChannelDisposed(JNIEnv* env,
            jobject inputChannelObj, const sp<InputChannel>& inputChannel, void* data);

    static bool handleReceiveCallback(int receiveFd, int events, void* data);

    static jlong generateFinishedToken(int32_t receiveFd, int32_t messageSeqNum);

    static void parseFinishedToken(jlong finishedToken,
            int32_t* outReceiveFd, uint32_t* outMessageIndex);
};

// ----------------------------------------------------------------------------

NativeInputQueue::NativeInputQueue() {
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

    sp<PollLoop> pollLoop = android_os_MessageQueue_getPollLoop(env, messageQueueObj);

    int receiveFd;
    { // acquire lock
        AutoMutex _l(mLock);

        receiveFd = inputChannel->getReceivePipeFd();
        if (mConnectionsByReceiveFd.indexOfKey(receiveFd) >= 0) {
            LOGW("Attempted to register already registered input channel '%s'",
                    inputChannel->getName().string());
            return BAD_VALUE;
        }

        sp<Connection> connection = new Connection(inputChannel, pollLoop);
        status_t result = connection->inputConsumer.initialize();
        if (result) {
            LOGW("Failed to initialize input consumer for input channel '%s', status=%d",
                    inputChannel->getName().string(), result);
            return result;
        }

        connection->inputHandlerObjGlobal = env->NewGlobalRef(inputHandlerObj);

        mConnectionsByReceiveFd.add(receiveFd, connection);
    } // release lock

    android_view_InputChannel_setDisposeCallback(env, inputChannelObj,
            handleInputChannelDisposed, this);

    pollLoop->setCallback(receiveFd, POLLIN, handleReceiveCallback, this);
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

    int32_t receiveFd;
    sp<Connection> connection;
    { // acquire lock
        AutoMutex _l(mLock);

        receiveFd = inputChannel->getReceivePipeFd();
        ssize_t connectionIndex = mConnectionsByReceiveFd.indexOfKey(receiveFd);
        if (connectionIndex < 0) {
            LOGW("Attempted to unregister already unregistered input channel '%s'",
                    inputChannel->getName().string());
            return BAD_VALUE;
        }

        connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        mConnectionsByReceiveFd.removeItemsAt(connectionIndex);

        connection->status = Connection::STATUS_ZOMBIE;

        env->DeleteGlobalRef(connection->inputHandlerObjGlobal);
        connection->inputHandlerObjGlobal = NULL;
    } // release lock

    android_view_InputChannel_setDisposeCallback(env, inputChannelObj, NULL, NULL);

    connection->pollLoop->removeCallback(receiveFd);
    return OK;
}

status_t NativeInputQueue::finished(JNIEnv* env, jlong finishedToken, bool ignoreSpuriousFinish) {
    int32_t receiveFd;
    uint32_t messageSeqNum;
    parseFinishedToken(finishedToken, &receiveFd, &messageSeqNum);

    { // acquire lock
        AutoMutex _l(mLock);

        ssize_t connectionIndex = mConnectionsByReceiveFd.indexOfKey(receiveFd);
        if (connectionIndex < 0) {
            if (! ignoreSpuriousFinish) {
                LOGW("Attempted to finish input on channel that is no longer registered.");
            }
            return DEAD_OBJECT;
        }

        sp<Connection> connection = mConnectionsByReceiveFd.valueAt(connectionIndex);
        if (messageSeqNum != connection->messageSeqNum || ! connection->messageInProgress) {
            if (! ignoreSpuriousFinish) {
                LOGW("Attempted to finish input twice on channel '%s'.",
                        connection->getInputChannelName());
            }
            return INVALID_OPERATION;
        }

        connection->messageInProgress = false;

        status_t status = connection->inputConsumer.sendFinishedSignal();
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

bool NativeInputQueue::handleReceiveCallback(int receiveFd, int events, void* data) {
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
            return false; // remove the callback
        }

        connection = q->mConnectionsByReceiveFd.valueAt(connectionIndex);
        if (events & (POLLERR | POLLHUP | POLLNVAL)) {
            LOGE("channel '%s' ~ Publisher closed input channel or an error occurred.  "
                    "events=0x%x", connection->getInputChannelName(), events);
            return false; // remove the callback
        }

        if (! (events & POLLIN)) {
            LOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  "
                    "events=0x%x", connection->getInputChannelName(), events);
            return true;
        }

        status_t status = connection->inputConsumer.receiveDispatchSignal();
        if (status) {
            LOGE("channel '%s' ~ Failed to receive dispatch signal.  status=%d",
                    connection->getInputChannelName(), status);
            return false; // remove the callback
        }

        if (connection->messageInProgress) {
            LOGW("channel '%s' ~ Publisher sent spurious dispatch signal.",
                    connection->getInputChannelName());
            return true;
        }

        status = connection->inputConsumer.consume(& connection->inputEventFactory, & inputEvent);
        if (status) {
            LOGW("channel '%s' ~ Failed to consume input event.  status=%d",
                    connection->getInputChannelName(), status);
            connection->inputConsumer.sendFinishedSignal();
            return true;
        }

        connection->messageInProgress = true;
        connection->messageSeqNum += 1;

        finishedToken = generateFinishedToken(receiveFd, connection->messageSeqNum);

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
    int32_t inputEventNature = inputEvent->getNature();

    jobject inputEventObj;
    jmethodID dispatchMethodId;
    switch (inputEventType) {
    case INPUT_EVENT_TYPE_KEY:
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Received key event.", connection->getInputChannelName());
#endif
        inputEventObj = android_view_KeyEvent_fromNative(env,
                static_cast<KeyEvent*>(inputEvent));
        dispatchMethodId = gInputQueueClassInfo.dispatchKeyEvent;
        break;

    case INPUT_EVENT_TYPE_MOTION:
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Received motion event.", connection->getInputChannelName());
#endif
        inputEventObj = android_view_MotionEvent_fromNative(env,
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
        q->finished(env, finishedToken, false);
        return true;
    }

#if DEBUG_DISPATCH_CYCLE
    LOGD("Invoking input handler.");
#endif
    env->CallStaticVoidMethod(gInputQueueClassInfo.clazz,
            dispatchMethodId, inputHandlerObjLocal, inputEventObj,
            jint(inputEventNature), jlong(finishedToken));
#if DEBUG_DISPATCH_CYCLE
    LOGD("Returned from input handler.");
#endif

    if (env->ExceptionCheck()) {
        LOGE("An exception occurred while invoking the input handler for an event.");
        LOGE_EX(env);
        env->ExceptionClear();

        q->finished(env, finishedToken, true /*ignoreSpuriousFinish*/);
    }

    env->DeleteLocalRef(inputEventObj);
    env->DeleteLocalRef(inputHandlerObjLocal);
    return true;
}

jlong NativeInputQueue::generateFinishedToken(int32_t receiveFd, int32_t messageSeqNum) {
    return (jlong(receiveFd) << 32) | jlong(messageSeqNum);
}

void NativeInputQueue::parseFinishedToken(jlong finishedToken,
        int32_t* outReceiveFd, uint32_t* outMessageIndex) {
    *outReceiveFd = int32_t(finishedToken >> 32);
    *outMessageIndex = uint32_t(finishedToken & 0xffffffff);
}

// ----------------------------------------------------------------------------

NativeInputQueue::Connection::Connection(const sp<InputChannel>& inputChannel, const sp<PollLoop>& pollLoop) :
    status(STATUS_NORMAL), inputChannel(inputChannel), inputConsumer(inputChannel),
    pollLoop(pollLoop), inputHandlerObjGlobal(NULL),
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
        jniThrowRuntimeException(env, "Failed to register input channel.  "
                "Check logs for details.");
    }
}

static void android_view_InputQueue_nativeUnregisterInputChannel(JNIEnv* env, jclass clazz,
        jobject inputChannelObj) {
    status_t status = gNativeInputQueue.unregisterInputChannel(env, inputChannelObj);
    if (status) {
        jniThrowRuntimeException(env, "Failed to unregister input channel.  "
                "Check logs for details.");
    }
}

static void android_view_InputQueue_nativeFinished(JNIEnv* env, jclass clazz,
        jlong finishedToken) {
    status_t status = gNativeInputQueue.finished(
            env, finishedToken, false /*ignoreSpuriousFinish*/);
    if (status) {
        jniThrowRuntimeException(env, "Failed to finish input event.  "
                "Check logs for details.");
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
    { "nativeFinished", "(J)V",
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
            "(Landroid/view/InputHandler;Landroid/view/KeyEvent;IJ)V");

    GET_STATIC_METHOD_ID(gInputQueueClassInfo.dispatchMotionEvent, gInputQueueClassInfo.clazz,
            "dispatchMotionEvent",
            "(Landroid/view/InputHandler;Landroid/view/MotionEvent;IJ)V");
    return 0;
}

} // namespace android
