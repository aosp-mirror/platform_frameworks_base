/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "InputEventSender"

//#define LOG_NDEBUG 0

// Log debug messages about the dispatch cycle.
#define DEBUG_DISPATCH_CYCLE 0


#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/threads.h>
#include <utils/KeyedVector.h>
#include <input/InputTransport.h>
#include "android_os_MessageQueue.h"
#include "android_view_InputChannel.h"
#include "android_view_KeyEvent.h"
#include "android_view_MotionEvent.h"

#include <ScopedLocalRef.h>

namespace android {

static struct {
    jclass clazz;

    jmethodID dispatchInputEventFinished;
} gInputEventSenderClassInfo;


class NativeInputEventSender : public LooperCallback {
public:
    NativeInputEventSender(JNIEnv* env,
            jobject senderWeak, const sp<InputChannel>& inputChannel,
            const sp<MessageQueue>& messageQueue);

    status_t initialize();
    void dispose();
    status_t sendKeyEvent(uint32_t seq, const KeyEvent* event);
    status_t sendMotionEvent(uint32_t seq, const MotionEvent* event);

protected:
    virtual ~NativeInputEventSender();

private:
    jobject mSenderWeakGlobal;
    InputPublisher mInputPublisher;
    sp<MessageQueue> mMessageQueue;
    KeyedVector<uint32_t, uint32_t> mPublishedSeqMap;
    uint32_t mNextPublishedSeq;

    const char* getInputChannelName() {
        return mInputPublisher.getChannel()->getName().string();
    }

    virtual int handleEvent(int receiveFd, int events, void* data);
    status_t receiveFinishedSignals(JNIEnv* env);
};


NativeInputEventSender::NativeInputEventSender(JNIEnv* env,
        jobject senderWeak, const sp<InputChannel>& inputChannel,
        const sp<MessageQueue>& messageQueue) :
        mSenderWeakGlobal(env->NewGlobalRef(senderWeak)),
        mInputPublisher(inputChannel), mMessageQueue(messageQueue),
        mNextPublishedSeq(1) {
#if DEBUG_DISPATCH_CYCLE
    ALOGD("channel '%s' ~ Initializing input event sender.", getInputChannelName());
#endif
}

NativeInputEventSender::~NativeInputEventSender() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mSenderWeakGlobal);
}

status_t NativeInputEventSender::initialize() {
    int receiveFd = mInputPublisher.getChannel()->getFd();
    mMessageQueue->getLooper()->addFd(receiveFd, 0, ALOOPER_EVENT_INPUT, this, NULL);
    return OK;
}

void NativeInputEventSender::dispose() {
#if DEBUG_DISPATCH_CYCLE
    ALOGD("channel '%s' ~ Disposing input event sender.", getInputChannelName());
#endif

    mMessageQueue->getLooper()->removeFd(mInputPublisher.getChannel()->getFd());
}

status_t NativeInputEventSender::sendKeyEvent(uint32_t seq, const KeyEvent* event) {
#if DEBUG_DISPATCH_CYCLE
    ALOGD("channel '%s' ~ Sending key event, seq=%u.", getInputChannelName(), seq);
#endif

    uint32_t publishedSeq = mNextPublishedSeq++;
    status_t status = mInputPublisher.publishKeyEvent(publishedSeq,
            event->getDeviceId(), event->getSource(), event->getAction(), event->getFlags(),
            event->getKeyCode(), event->getScanCode(), event->getMetaState(),
            event->getRepeatCount(), event->getDownTime(), event->getEventTime());
    if (status) {
        ALOGW("Failed to send key event on channel '%s'.  status=%d",
                getInputChannelName(), status);
        return status;
    }
    mPublishedSeqMap.add(publishedSeq, seq);
    return OK;
}

status_t NativeInputEventSender::sendMotionEvent(uint32_t seq, const MotionEvent* event) {
#if DEBUG_DISPATCH_CYCLE
    ALOGD("channel '%s' ~ Sending motion event, seq=%u.", getInputChannelName(), seq);
#endif

    uint32_t publishedSeq;
    for (size_t i = 0; i <= event->getHistorySize(); i++) {
        publishedSeq = mNextPublishedSeq++;
        status_t status = mInputPublisher.publishMotionEvent(publishedSeq,
                event->getDeviceId(), event->getSource(), event->getAction(), event->getFlags(),
                event->getEdgeFlags(), event->getMetaState(), event->getButtonState(),
                event->getXOffset(), event->getYOffset(),
                event->getXPrecision(), event->getYPrecision(),
                event->getDownTime(), event->getHistoricalEventTime(i),
                event->getPointerCount(), event->getPointerProperties(),
                event->getHistoricalRawPointerCoords(0, i));
        if (status) {
            ALOGW("Failed to send motion event sample on channel '%s'.  status=%d",
                    getInputChannelName(), status);
            return status;
        }
    }
    mPublishedSeqMap.add(publishedSeq, seq);
    return OK;
}

int NativeInputEventSender::handleEvent(int receiveFd, int events, void* data) {
    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
#if DEBUG_DISPATCH_CYCLE
        // This error typically occurs when the consumer has closed the input channel
        // as part of finishing an IME session, in which case the publisher will
        // soon be disposed as well.
        ALOGD("channel '%s' ~ Consumer closed input channel or an error occurred.  "
                "events=0x%x", getInputChannelName(), events);
#endif
        return 0; // remove the callback
    }

    if (!(events & ALOOPER_EVENT_INPUT)) {
        ALOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  "
                "events=0x%x", getInputChannelName(), events);
        return 1;
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    status_t status = receiveFinishedSignals(env);
    mMessageQueue->raiseAndClearException(env, "handleReceiveCallback");
    return status == OK || status == NO_MEMORY ? 1 : 0;
}

status_t NativeInputEventSender::receiveFinishedSignals(JNIEnv* env) {
#if DEBUG_DISPATCH_CYCLE
    ALOGD("channel '%s' ~ Receiving finished signals.", getInputChannelName());
#endif

    ScopedLocalRef<jobject> senderObj(env, NULL);
    bool skipCallbacks = false;
    for (;;) {
        uint32_t publishedSeq;
        bool handled;
        status_t status = mInputPublisher.receiveFinishedSignal(&publishedSeq, &handled);
        if (status) {
            if (status == WOULD_BLOCK) {
                return OK;
            }
            ALOGE("channel '%s' ~ Failed to consume finished signals.  status=%d",
                    getInputChannelName(), status);
            return status;
        }

        ssize_t index = mPublishedSeqMap.indexOfKey(publishedSeq);
        if (index >= 0) {
            uint32_t seq = mPublishedSeqMap.valueAt(index);
            mPublishedSeqMap.removeItemsAt(index);

#if DEBUG_DISPATCH_CYCLE
            ALOGD("channel '%s' ~ Received finished signal, seq=%u, handled=%s, "
                    "pendingEvents=%u.",
                    getInputChannelName(), seq, handled ? "true" : "false",
                    mPublishedSeqMap.size());
#endif

            if (!skipCallbacks) {
                if (!senderObj.get()) {
                    senderObj.reset(jniGetReferent(env, mSenderWeakGlobal));
                    if (!senderObj.get()) {
                        ALOGW("channel '%s' ~ Sender object was finalized "
                                "without being disposed.", getInputChannelName());
                        return DEAD_OBJECT;
                    }
                }

                env->CallVoidMethod(senderObj.get(),
                        gInputEventSenderClassInfo.dispatchInputEventFinished,
                        jint(seq), jboolean(handled));
                if (env->ExceptionCheck()) {
                    ALOGE("Exception dispatching finished signal.");
                    skipCallbacks = true;
                }
            }
        }
    }
}


static jint nativeInit(JNIEnv* env, jclass clazz, jobject senderWeak,
        jobject inputChannelObj, jobject messageQueueObj) {
    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == NULL) {
        jniThrowRuntimeException(env, "InputChannel is not initialized.");
        return 0;
    }

    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueObj);
    if (messageQueue == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<NativeInputEventSender> sender = new NativeInputEventSender(env,
            senderWeak, inputChannel, messageQueue);
    status_t status = sender->initialize();
    if (status) {
        String8 message;
        message.appendFormat("Failed to initialize input event sender.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
        return 0;
    }

    sender->incStrong(gInputEventSenderClassInfo.clazz); // retain a reference for the object
    return reinterpret_cast<jint>(sender.get());
}

static void nativeDispose(JNIEnv* env, jclass clazz, jint senderPtr) {
    sp<NativeInputEventSender> sender =
            reinterpret_cast<NativeInputEventSender*>(senderPtr);
    sender->dispose();
    sender->decStrong(gInputEventSenderClassInfo.clazz); // drop reference held by the object
}

static jboolean nativeSendKeyEvent(JNIEnv* env, jclass clazz, jint senderPtr,
        jint seq, jobject eventObj) {
    sp<NativeInputEventSender> sender =
            reinterpret_cast<NativeInputEventSender*>(senderPtr);
    KeyEvent event;
    android_view_KeyEvent_toNative(env, eventObj, &event);
    status_t status = sender->sendKeyEvent(seq, &event);
    return !status;
}

static jboolean nativeSendMotionEvent(JNIEnv* env, jclass clazz, jint senderPtr,
        jint seq, jobject eventObj) {
    sp<NativeInputEventSender> sender =
            reinterpret_cast<NativeInputEventSender*>(senderPtr);
    MotionEvent* event = android_view_MotionEvent_getNativePtr(env, eventObj);
    status_t status = sender->sendMotionEvent(seq, event);
    return !status;
}


static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit",
            "(Ljava/lang/ref/WeakReference;Landroid/view/InputChannel;Landroid/os/MessageQueue;)I",
            (void*)nativeInit },
    { "nativeDispose", "(I)V",
            (void*)nativeDispose },
    { "nativeSendKeyEvent", "(IILandroid/view/KeyEvent;)Z",
            (void*)nativeSendKeyEvent },
    { "nativeSendMotionEvent", "(IILandroid/view/MotionEvent;)Z",
            (void*)nativeSendMotionEvent },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

int register_android_view_InputEventSender(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/InputEventSender",
            gMethods, NELEM(gMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    FIND_CLASS(gInputEventSenderClassInfo.clazz, "android/view/InputEventSender");

    GET_METHOD_ID(gInputEventSenderClassInfo.dispatchInputEventFinished,
            gInputEventSenderClassInfo.clazz,
            "dispatchInputEventFinished", "(IZ)V");
    return 0;
}

} // namespace android
