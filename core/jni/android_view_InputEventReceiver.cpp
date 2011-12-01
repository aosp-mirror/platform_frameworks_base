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

// Log debug messages about the dispatch cycle.
#define DEBUG_DISPATCH_CYCLE 0


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

static struct {
    jclass clazz;

    jmethodID dispatchInputEvent;
} gInputEventReceiverClassInfo;


class NativeInputEventReceiver : public RefBase {
public:
    NativeInputEventReceiver(JNIEnv* env,
            jobject receiverObj, const sp<InputChannel>& inputChannel,
            const sp<Looper>& looper);

    status_t initialize();
    status_t finishInputEvent(bool handled);
    static int handleReceiveCallback(int receiveFd, int events, void* data);

protected:
    virtual ~NativeInputEventReceiver();

private:
    jobject mReceiverObjGlobal;
    InputConsumer mInputConsumer;
    sp<Looper> mLooper;
    bool mEventInProgress;
    PreallocatedInputEventFactory mInputEventFactory;

    const char* getInputChannelName() {
        return mInputConsumer.getChannel()->getName().string();
    }
};


NativeInputEventReceiver::NativeInputEventReceiver(JNIEnv* env,
        jobject receiverObj, const sp<InputChannel>& inputChannel, const sp<Looper>& looper) :
        mReceiverObjGlobal(env->NewGlobalRef(receiverObj)),
        mInputConsumer(inputChannel), mLooper(looper), mEventInProgress(false) {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ Initializing input event receiver.", getInputChannelName());
#endif
}

NativeInputEventReceiver::~NativeInputEventReceiver() {
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ Disposing input event receiver.", getInputChannelName());
#endif

    mLooper->removeFd(mInputConsumer.getChannel()->getReceivePipeFd());
    if (mEventInProgress) {
        mInputConsumer.sendFinishedSignal(false); // ignoring result
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mReceiverObjGlobal);
}

status_t NativeInputEventReceiver::initialize() {
    status_t result = mInputConsumer.initialize();
    if (result) {
        LOGW("Failed to initialize input consumer for input channel '%s', status=%d",
                getInputChannelName(), result);
        return result;
    }

    int32_t receiveFd = mInputConsumer.getChannel()->getReceivePipeFd();
    mLooper->addFd(receiveFd, 0, ALOOPER_EVENT_INPUT, handleReceiveCallback, this);
    return OK;
}

status_t NativeInputEventReceiver::finishInputEvent(bool handled) {
    if (mEventInProgress) {
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Finished input event.", getInputChannelName());
#endif
        mEventInProgress = false;

        status_t status = mInputConsumer.sendFinishedSignal(handled);
        if (status) {
            LOGW("Failed to send finished signal on channel '%s'.  status=%d",
                    getInputChannelName(), status);
        }
        return status;
    } else {
        LOGW("Ignoring attempt to finish input event while no event is in progress.");
        return OK;
    }
}

int NativeInputEventReceiver::handleReceiveCallback(int receiveFd, int events, void* data) {
    sp<NativeInputEventReceiver> r = static_cast<NativeInputEventReceiver*>(data);

    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        LOGE("channel '%s' ~ Publisher closed input channel or an error occurred.  "
                "events=0x%x", r->getInputChannelName(), events);
        return 0; // remove the callback
    }

    if (!(events & ALOOPER_EVENT_INPUT)) {
        LOGW("channel '%s' ~ Received spurious callback for unhandled poll event.  "
                "events=0x%x", r->getInputChannelName(), events);
        return 1;
    }

    status_t status = r->mInputConsumer.receiveDispatchSignal();
    if (status) {
        LOGE("channel '%s' ~ Failed to receive dispatch signal.  status=%d",
                r->getInputChannelName(), status);
        return 0; // remove the callback
    }

    if (r->mEventInProgress) {
        LOGW("channel '%s' ~ Publisher sent spurious dispatch signal.",
                r->getInputChannelName());
        return 1;
    }

    InputEvent* inputEvent;
    status = r->mInputConsumer.consume(&r->mInputEventFactory, &inputEvent);
    if (status) {
        LOGW("channel '%s' ~ Failed to consume input event.  status=%d",
                r->getInputChannelName(), status);
        r->mInputConsumer.sendFinishedSignal(false);
        return 1;
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject inputEventObj;
    switch (inputEvent->getType()) {
    case AINPUT_EVENT_TYPE_KEY:
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Received key event.",
                r->getInputChannelName());
#endif
        inputEventObj = android_view_KeyEvent_fromNative(env,
                static_cast<KeyEvent*>(inputEvent));
        break;

    case AINPUT_EVENT_TYPE_MOTION:
#if DEBUG_DISPATCH_CYCLE
        LOGD("channel '%s' ~ Received motion event.",
                r->getInputChannelName());
#endif
        inputEventObj = android_view_MotionEvent_obtainAsCopy(env,
                static_cast<MotionEvent*>(inputEvent));
        break;

    default:
        assert(false); // InputConsumer should prevent this from ever happening
        inputEventObj = NULL;
    }

    if (!inputEventObj) {
        LOGW("channel '%s' ~ Failed to obtain event object.",
                r->getInputChannelName());
        r->mInputConsumer.sendFinishedSignal(false);
        return 1;
    }

    r->mEventInProgress = true;

#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ Invoking input handler.", r->getInputChannelName());
#endif
    env->CallVoidMethod(r->mReceiverObjGlobal,
            gInputEventReceiverClassInfo.dispatchInputEvent, inputEventObj);
#if DEBUG_DISPATCH_CYCLE
    LOGD("channel '%s' ~ Returned from input handler.", r->getInputChannelName());
#endif

    if (env->ExceptionCheck()) {
        LOGE("channel '%s' ~ An exception occurred while dispatching an event.",
                r->getInputChannelName());
        LOGE_EX(env);
        env->ExceptionClear();

        if (r->mEventInProgress) {
            r->mInputConsumer.sendFinishedSignal(false);
            r->mEventInProgress = false;
        }
    }

    env->DeleteLocalRef(inputEventObj);
    return 1;
}


static jint nativeInit(JNIEnv* env, jclass clazz, jobject receiverObj,
        jobject inputChannelObj, jobject messageQueueObj) {
    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    if (inputChannel == NULL) {
        jniThrowRuntimeException(env, "InputChannel is not initialized.");
        return 0;
    }

    sp<Looper> looper = android_os_MessageQueue_getLooper(env, messageQueueObj);
    if (looper == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<NativeInputEventReceiver> receiver = new NativeInputEventReceiver(env,
            receiverObj, inputChannel, looper);
    status_t status = receiver->initialize();
    if (status) {
        String8 message;
        message.appendFormat("Failed to initialize input event receiver.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
        return 0;
    }

    receiver->incStrong(gInputEventReceiverClassInfo.clazz); // retain a reference for the object
    return reinterpret_cast<jint>(receiver.get());
}

static void nativeDispose(JNIEnv* env, jclass clazz, jint receiverPtr) {
    sp<NativeInputEventReceiver> receiver =
            reinterpret_cast<NativeInputEventReceiver*>(receiverPtr);
    receiver->decStrong(gInputEventReceiverClassInfo.clazz); // drop reference held by the object
}

static void nativeFinishInputEvent(JNIEnv* env, jclass clazz, jint receiverPtr, jboolean handled) {
    sp<NativeInputEventReceiver> receiver =
            reinterpret_cast<NativeInputEventReceiver*>(receiverPtr);
    status_t status = receiver->finishInputEvent(handled);
    if (status) {
        String8 message;
        message.appendFormat("Failed to finish input event.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
    }
}


static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit",
            "(Landroid/view/InputEventReceiver;Landroid/view/InputChannel;Landroid/os/MessageQueue;)I",
            (void*)nativeInit },
    { "nativeDispose",
            "(I)V",
            (void*)nativeDispose },
    { "nativeFinishInputEvent", "(IZ)V",
            (void*)nativeFinishInputEvent }
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

int register_android_view_InputEventReceiver(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/InputEventReceiver",
            gMethods, NELEM(gMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    FIND_CLASS(gInputEventReceiverClassInfo.clazz, "android/view/InputEventReceiver");

    GET_METHOD_ID(gInputEventReceiverClassInfo.dispatchInputEvent,
            gInputEventReceiverClassInfo.clazz,
            "dispatchInputEvent", "(Landroid/view/InputEvent;)V");
    return 0;
}

} // namespace android
