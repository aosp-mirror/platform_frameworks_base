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

#define LOG_TAG "InputEventReceiver"

//#define LOG_NDEBUG 0

#include "JNIHelp.h"

#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/Vector.h>
#include <utils/threads.h>
#include <input/InputTransport.h>
#include "android_os_MessageQueue.h"
#include "android_view_InputChannel.h"
#include "android_view_KeyEvent.h"
#include "android_view_MotionEvent.h"

#include <ScopedLocalRef.h>

#include "core_jni_helpers.h"

namespace android {

static const bool kDebugDispatchCycle = false;

static struct {
    jclass clazz;

    jmethodID dispatchInputEvent;
    jmethodID dispatchBatchedInputEventPending;
} gInputEventReceiverClassInfo;


class NativeInputEventReceiver : public LooperCallback {
public:
    NativeInputEventReceiver(JNIEnv* env,
            jobject receiverWeak, const sp<InputChannel>& inputChannel,
            const sp<MessageQueue>& messageQueue);

    status_t initialize();
    void dispose();
    status_t finishInputEvent(uint32_t seq, bool handled);
    status_t consumeEvents(JNIEnv* env, bool consumeBatches, nsecs_t frameTime,
            bool* outConsumedBatch);

protected:
    virtual ~NativeInputEventReceiver();

private:
    struct Finish {
        uint32_t seq;
        bool handled;
    };

    jobject mReceiverWeakGlobal;
    InputConsumer mInputConsumer;
    sp<MessageQueue> mMessageQueue;
    PreallocatedInputEventFactory mInputEventFactory;
    bool mBatchedInputEventPending;
    int mFdEvents;
    Vector<Finish> mFinishQueue;

    void setFdEvents(int events);

    const char* getInputChannelName() {
        return mInputConsumer.getChannel()->getName().string();
    }

    virtual int handleEvent(int receiveFd, int events, void* data);
};


NativeInputEventReceiver::NativeInputEventReceiver(JNIEnv* env,
        jobject receiverWeak, const sp<InputChannel>& inputChannel,
        const sp<MessageQueue>& messageQueue) :
        mReceiverWeakGlobal(env->NewGlobalRef(receiverWeak)),
        mInputConsumer(inputChannel), mMessageQueue(messageQueue),
        mBatchedInputEventPending(false), mFdEvents(0) {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Initializing input event receiver.", getInputChannelName());
    }
}

NativeInputEventReceiver::~NativeInputEventReceiver() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mReceiverWeakGlobal);
}

status_t NativeInputEventReceiver::initialize() {
    setFdEvents(ALOOPER_EVENT_INPUT);
    return OK;
}

void NativeInputEventReceiver::dispose() {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Disposing input event receiver.", getInputChannelName());
    }

    setFdEvents(0);
}

status_t NativeInputEventReceiver::finishInputEvent(uint32_t seq, bool handled) {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Finished input event.", getInputChannelName());
    }

    status_t status = mInputConsumer.sendFinishedSignal(seq, handled);
    if (status) {
        if (status == WOULD_BLOCK) {
            if (kDebugDispatchCycle) {
                ALOGD("channel '%s' ~ Could not send finished signal immediately.  "
                        "Enqueued for later.", getInputChannelName());
            }
            Finish finish;
            finish.seq = seq;
            finish.handled = handled;
            mFinishQueue.add(finish);
            if (mFinishQueue.size() == 1) {
                setFdEvents(ALOOPER_EVENT_INPUT | ALOOPER_EVENT_OUTPUT);
            }
            return OK;
        }
        ALOGW("Failed to send finished signal on channel '%s'.  status=%d",
                getInputChannelName(), status);
    }
    return status;
}

void NativeInputEventReceiver::setFdEvents(int events) {
    if (mFdEvents != events) {
        mFdEvents = events;
        int fd = mInputConsumer.getChannel()->getFd();
        if (events) {
            mMessageQueue->getLooper()->addFd(fd, 0, events, this, NULL);
        } else {
            mMessageQueue->getLooper()->removeFd(fd);
        }
    }
}

int NativeInputEventReceiver::handleEvent(int receiveFd, int events, void* data) {
    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        // This error typically occurs when the publisher has closed the input channel
        // as part of removing a window or finishing an IME session, in which case
        // the consumer will soon be disposed as well.
        if (kDebugDispatchCycle) {
            ALOGD("channel '%s' ~ Publisher closed input channel or an error occurred.  "
                    "events=0x%x", getInputChannelName(), events);
        }
        return 0; // remove the callback
    }

    if (events & ALOOPER_EVENT_INPUT) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        status_t status = consumeEvents(env, false /*consumeBatches*/, -1, NULL);
        mMessageQueue->raiseAndClearException(env, "handleReceiveCallback");
        return status == OK || status == NO_MEMORY ? 1 : 0;
    }

    if (events & ALOOPER_EVENT_OUTPUT) {
        for (size_t i = 0; i < mFinishQueue.size(); i++) {
            const Finish& finish = mFinishQueue.itemAt(i);
            status_t status = mInputConsumer.sendFinishedSignal(finish.seq, finish.handled);
            if (status) {
                mFinishQueue.removeItemsAt(0, i);

                if (status == WOULD_BLOCK) {
                    if (kDebugDispatchCycle) {
                        ALOGD("channel '%s' ~ Sent %zu queued finish events; %zu left.",
                                getInputChannelName(), i, mFinishQueue.size());
                    }
                    return 1; // keep the callback, try again later
                }

                ALOGW("Failed to send finished signal on channel '%s'.  status=%d",
                        getInputChannelName(), status);
                if (status != DEAD_OBJECT) {
                    JNIEnv* env = AndroidRuntime::getJNIEnv();
                    String8 message;
                    message.appendFormat("Failed to finish input event.  status=%d", status);
                    jniThrowRuntimeException(env, message.string());
                    mMessageQueue->raiseAndClearException(env, "finishInputEvent");
                }
                return 0; // remove the callback
            }
        }
        if (kDebugDispatchCycle) {
            ALOGD("channel '%s' ~ Sent %zu queued finish events; none left.",
                    getInputChannelName(), mFinishQueue.size());
        }
        mFinishQueue.clear();
        setFdEvents(ALOOPER_EVENT_INPUT);
        return 1;
    }

    ALOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  "
            "events=0x%x", getInputChannelName(), events);
    return 1;
}

status_t NativeInputEventReceiver::consumeEvents(JNIEnv* env,
        bool consumeBatches, nsecs_t frameTime, bool* outConsumedBatch) {
    if (kDebugDispatchCycle) {
        ALOGD("channel '%s' ~ Consuming input events, consumeBatches=%s, frameTime=%lld.",
                getInputChannelName(), consumeBatches ? "true" : "false", (long long)frameTime);
    }

    if (consumeBatches) {
        mBatchedInputEventPending = false;
    }
    if (outConsumedBatch) {
        *outConsumedBatch = false;
    }

    ScopedLocalRef<jobject> receiverObj(env, NULL);
    bool skipCallbacks = false;
    for (;;) {
        uint32_t seq;
        InputEvent* inputEvent;
        status_t status = mInputConsumer.consume(&mInputEventFactory,
                consumeBatches, frameTime, &seq, &inputEvent);
        if (status) {
            if (status == WOULD_BLOCK) {
                if (!skipCallbacks && !mBatchedInputEventPending
                        && mInputConsumer.hasPendingBatch()) {
                    // There is a pending batch.  Come back later.
                    if (!receiverObj.get()) {
                        receiverObj.reset(jniGetReferent(env, mReceiverWeakGlobal));
                        if (!receiverObj.get()) {
                            ALOGW("channel '%s' ~ Receiver object was finalized "
                                    "without being disposed.", getInputChannelName());
                            return DEAD_OBJECT;
                        }
                    }

                    mBatchedInputEventPending = true;
                    if (kDebugDispatchCycle) {
                        ALOGD("channel '%s' ~ Dispatching batched input event pending notification.",
                                getInputChannelName());
                    }
                    env->CallVoidMethod(receiverObj.get(),
                            gInputEventReceiverClassInfo.dispatchBatchedInputEventPending);
                    if (env->ExceptionCheck()) {
                        ALOGE("Exception dispatching batched input events.");
                        mBatchedInputEventPending = false; // try again later
                    }
                }
                return OK;
            }
            ALOGE("channel '%s' ~ Failed to consume input event.  status=%d",
                    getInputChannelName(), status);
            return status;
        }
        assert(inputEvent);

        if (!skipCallbacks) {
            if (!receiverObj.get()) {
                receiverObj.reset(jniGetReferent(env, mReceiverWeakGlobal));
                if (!receiverObj.get()) {
                    ALOGW("channel '%s' ~ Receiver object was finalized "
                            "without being disposed.", getInputChannelName());
                    return DEAD_OBJECT;
                }
            }

            jobject inputEventObj;
            switch (inputEvent->getType()) {
            case AINPUT_EVENT_TYPE_KEY:
                if (kDebugDispatchCycle) {
                    ALOGD("channel '%s' ~ Received key event.", getInputChannelName());
                }
                inputEventObj = android_view_KeyEvent_fromNative(env,
                        static_cast<KeyEvent*>(inputEvent));
                break;

            case AINPUT_EVENT_TYPE_MOTION: {
                if (kDebugDispatchCycle) {
                    ALOGD("channel '%s' ~ Received motion event.", getInputChannelName());
                }
                MotionEvent* motionEvent = static_cast<MotionEvent*>(inputEvent);
                if ((motionEvent->getAction() & AMOTION_EVENT_ACTION_MOVE) && outConsumedBatch) {
                    *outConsumedBatch = true;
                }
                inputEventObj = android_view_MotionEvent_obtainAsCopy(env, motionEvent);
                break;
            }

            default:
                assert(false); // InputConsumer should prevent this from ever happening
                inputEventObj = NULL;
            }

            if (inputEventObj) {
                if (kDebugDispatchCycle) {
                    ALOGD("channel '%s' ~ Dispatching input event.", getInputChannelName());
                }
                env->CallVoidMethod(receiverObj.get(),
                        gInputEventReceiverClassInfo.dispatchInputEvent, seq, inputEventObj);
                if (env->ExceptionCheck()) {
                    ALOGE("Exception dispatching input event.");
                    skipCallbacks = true;
                }
                env->DeleteLocalRef(inputEventObj);
            } else {
                ALOGW("channel '%s' ~ Failed to obtain event object.", getInputChannelName());
                skipCallbacks = true;
            }
        }

        if (skipCallbacks) {
            mInputConsumer.sendFinishedSignal(seq, false);
        }
    }
}


static jlong nativeInit(JNIEnv* env, jclass clazz, jobject receiverWeak,
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

    sp<NativeInputEventReceiver> receiver = new NativeInputEventReceiver(env,
            receiverWeak, inputChannel, messageQueue);
    status_t status = receiver->initialize();
    if (status) {
        String8 message;
        message.appendFormat("Failed to initialize input event receiver.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
        return 0;
    }

    receiver->incStrong(gInputEventReceiverClassInfo.clazz); // retain a reference for the object
    return reinterpret_cast<jlong>(receiver.get());
}

static void nativeDispose(JNIEnv* env, jclass clazz, jlong receiverPtr) {
    sp<NativeInputEventReceiver> receiver =
            reinterpret_cast<NativeInputEventReceiver*>(receiverPtr);
    receiver->dispose();
    receiver->decStrong(gInputEventReceiverClassInfo.clazz); // drop reference held by the object
}

static void nativeFinishInputEvent(JNIEnv* env, jclass clazz, jlong receiverPtr,
        jint seq, jboolean handled) {
    sp<NativeInputEventReceiver> receiver =
            reinterpret_cast<NativeInputEventReceiver*>(receiverPtr);
    status_t status = receiver->finishInputEvent(seq, handled);
    if (status && status != DEAD_OBJECT) {
        String8 message;
        message.appendFormat("Failed to finish input event.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
    }
}

static jboolean nativeConsumeBatchedInputEvents(JNIEnv* env, jclass clazz, jlong receiverPtr,
        jlong frameTimeNanos) {
    sp<NativeInputEventReceiver> receiver =
            reinterpret_cast<NativeInputEventReceiver*>(receiverPtr);
    bool consumedBatch;
    status_t status = receiver->consumeEvents(env, true /*consumeBatches*/, frameTimeNanos,
            &consumedBatch);
    if (status && status != DEAD_OBJECT && !env->ExceptionCheck()) {
        String8 message;
        message.appendFormat("Failed to consume batched input event.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
        return JNI_FALSE;
    }
    return consumedBatch ? JNI_TRUE : JNI_FALSE;
}


static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit",
            "(Ljava/lang/ref/WeakReference;Landroid/view/InputChannel;Landroid/os/MessageQueue;)J",
            (void*)nativeInit },
    { "nativeDispose", "(J)V",
            (void*)nativeDispose },
    { "nativeFinishInputEvent", "(JIZ)V",
            (void*)nativeFinishInputEvent },
    { "nativeConsumeBatchedInputEvents", "(JJ)Z",
            (void*)nativeConsumeBatchedInputEvents },
};

int register_android_view_InputEventReceiver(JNIEnv* env) {
    int res = RegisterMethodsOrDie(env, "android/view/InputEventReceiver",
            gMethods, NELEM(gMethods));

    jclass clazz = FindClassOrDie(env, "android/view/InputEventReceiver");
    gInputEventReceiverClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);

    gInputEventReceiverClassInfo.dispatchInputEvent = GetMethodIDOrDie(env,
            gInputEventReceiverClassInfo.clazz,
            "dispatchInputEvent", "(ILandroid/view/InputEvent;)V");
    gInputEventReceiverClassInfo.dispatchBatchedInputEventPending = GetMethodIDOrDie(env,
            gInputEventReceiverClassInfo.clazz, "dispatchBatchedInputEventPending", "()V");

    return res;
}

} // namespace android
