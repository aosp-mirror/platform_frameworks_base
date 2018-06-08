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

#include <nativehelper/JNIHelp.h>

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

#include <nativehelper/ScopedLocalRef.h>

#include "core_jni_helpers.h"

namespace android {

// Log debug messages about the dispatch cycle.
static const bool kDebugDispatchCycle = false;
// Display id for default(primary) display.
static const int32_t kDefaultDisplayId = 0;

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

    const std::string getInputChannelName() {
        return mInputPublisher.getChannel()->getName();
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
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Initializing input event sender.", getInputChannelName().c_str());
    }
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
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Disposing input event sender.", getInputChannelName().c_str());
    }

    mMessageQueue->getLooper()->removeFd(mInputPublisher.getChannel()->getFd());
}

status_t NativeInputEventSender::sendKeyEvent(uint32_t seq, const KeyEvent* event) {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Sending key event, seq=%u.", getInputChannelName().c_str(), seq);
    }

    uint32_t publishedSeq = mNextPublishedSeq++;
    status_t status = mInputPublisher.publishKeyEvent(publishedSeq,
            event->getDeviceId(), event->getSource(), event->getAction(), event->getFlags(),
            event->getKeyCode(), event->getScanCode(), event->getMetaState(),
            event->getRepeatCount(), event->getDownTime(), event->getEventTime());
    if (status) {
        ALOGW("Failed to send key event on channel '%s'.  status=%d",
                getInputChannelName().c_str(), status);
        return status;
    }
    mPublishedSeqMap.add(publishedSeq, seq);
    return OK;
}

status_t NativeInputEventSender::sendMotionEvent(uint32_t seq, const MotionEvent* event) {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Sending motion event, seq=%u.", getInputChannelName().c_str(), seq);
    }

    uint32_t publishedSeq;
    for (size_t i = 0; i <= event->getHistorySize(); i++) {
        publishedSeq = mNextPublishedSeq++;
        status_t status = mInputPublisher.publishMotionEvent(publishedSeq,
                event->getDeviceId(), event->getSource(),
                kDefaultDisplayId /* TODO(multi-display): propagate display id */,
                event->getAction(), event->getActionButton(), event->getFlags(),
                event->getEdgeFlags(), event->getMetaState(), event->getButtonState(),
                event->getXOffset(), event->getYOffset(),
                event->getXPrecision(), event->getYPrecision(),
                event->getDownTime(), event->getHistoricalEventTime(i),
                event->getPointerCount(), event->getPointerProperties(),
                event->getHistoricalRawPointerCoords(0, i));
        if (status) {
            ALOGW("Failed to send motion event sample on channel '%s'.  status=%d",
                    getInputChannelName().c_str(), status);
            return status;
        }
    }
    mPublishedSeqMap.add(publishedSeq, seq);
    return OK;
}

int NativeInputEventSender::handleEvent(int receiveFd, int events, void* data) {
    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        // This error typically occurs when the consumer has closed the input channel
        // as part of finishing an IME session, in which case the publisher will
        // soon be disposed as well.
        if (kDebugDispatchCycle) {
            ALOGD("channel '%s' ~ Consumer closed input channel or an error occurred.  "
                    "events=0x%x", getInputChannelName().c_str(), events);
        }

        return 0; // remove the callback
    }

    if (!(events & ALOOPER_EVENT_INPUT)) {
        ALOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  "
                "events=0x%x", getInputChannelName().c_str(), events);
        return 1;
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    status_t status = receiveFinishedSignals(env);
    mMessageQueue->raiseAndClearException(env, "handleReceiveCallback");
    return status == OK || status == NO_MEMORY ? 1 : 0;
}

status_t NativeInputEventSender::receiveFinishedSignals(JNIEnv* env) {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Receiving finished signals.", getInputChannelName().c_str());
    }

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
                    getInputChannelName().c_str(), status);
            return status;
        }

        ssize_t index = mPublishedSeqMap.indexOfKey(publishedSeq);
        if (index >= 0) {
            uint32_t seq = mPublishedSeqMap.valueAt(index);
            mPublishedSeqMap.removeItemsAt(index);

            if (kDebugDispatchCycle) {
                ALOGD("channel '%s' ~ Received finished signal, seq=%u, handled=%s, "
                        "pendingEvents=%zu.",
                        getInputChannelName().c_str(), seq, handled ? "true" : "false",
                        mPublishedSeqMap.size());
            }

            if (!skipCallbacks) {
                if (!senderObj.get()) {
                    senderObj.reset(jniGetReferent(env, mSenderWeakGlobal));
                    if (!senderObj.get()) {
                        ALOGW("channel '%s' ~ Sender object was finalized "
                                "without being disposed.", getInputChannelName().c_str());
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


static jlong nativeInit(JNIEnv* env, jclass clazz, jobject senderWeak,
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
    return reinterpret_cast<jlong>(sender.get());
}

static void nativeDispose(JNIEnv* env, jclass clazz, jlong senderPtr) {
    sp<NativeInputEventSender> sender =
            reinterpret_cast<NativeInputEventSender*>(senderPtr);
    sender->dispose();
    sender->decStrong(gInputEventSenderClassInfo.clazz); // drop reference held by the object
}

static jboolean nativeSendKeyEvent(JNIEnv* env, jclass clazz, jlong senderPtr,
        jint seq, jobject eventObj) {
    sp<NativeInputEventSender> sender =
            reinterpret_cast<NativeInputEventSender*>(senderPtr);
    KeyEvent event;
    android_view_KeyEvent_toNative(env, eventObj, &event);
    status_t status = sender->sendKeyEvent(seq, &event);
    return !status;
}

static jboolean nativeSendMotionEvent(JNIEnv* env, jclass clazz, jlong senderPtr,
        jint seq, jobject eventObj) {
    sp<NativeInputEventSender> sender =
            reinterpret_cast<NativeInputEventSender*>(senderPtr);
    MotionEvent* event = android_view_MotionEvent_getNativePtr(env, eventObj);
    status_t status = sender->sendMotionEvent(seq, event);
    return !status;
}


static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit",
            "(Ljava/lang/ref/WeakReference;Landroid/view/InputChannel;Landroid/os/MessageQueue;)J",
            (void*)nativeInit },
    { "nativeDispose", "(J)V",
            (void*)nativeDispose },
    { "nativeSendKeyEvent", "(JILandroid/view/KeyEvent;)Z",
            (void*)nativeSendKeyEvent },
    { "nativeSendMotionEvent", "(JILandroid/view/MotionEvent;)Z",
            (void*)nativeSendMotionEvent },
};

int register_android_view_InputEventSender(JNIEnv* env) {
    int res = RegisterMethodsOrDie(env, "android/view/InputEventSender", gMethods, NELEM(gMethods));

    jclass clazz = FindClassOrDie(env, "android/view/InputEventSender");
    gInputEventSenderClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);

    gInputEventSenderClassInfo.dispatchInputEventFinished = GetMethodIDOrDie(
            env, gInputEventSenderClassInfo.clazz, "dispatchInputEventFinished", "(IZ)V");

    return res;
}

} // namespace android
