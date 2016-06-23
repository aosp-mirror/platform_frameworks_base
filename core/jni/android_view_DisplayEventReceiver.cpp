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

#define LOG_TAG "DisplayEventReceiver"

//#define LOG_NDEBUG 0

#include "JNIHelp.h"

#include <inttypes.h>

#include <android_runtime/AndroidRuntime.h>
#include <androidfw/DisplayEventDispatcher.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/threads.h>
#include <gui/DisplayEventReceiver.h>
#include "android_os_MessageQueue.h"

#include <ScopedLocalRef.h>

#include "core_jni_helpers.h"

namespace android {

static struct {
    jclass clazz;

    jmethodID dispatchVsync;
    jmethodID dispatchHotplug;
} gDisplayEventReceiverClassInfo;


class NativeDisplayEventReceiver : public DisplayEventDispatcher {
public:
    NativeDisplayEventReceiver(JNIEnv* env,
            jobject receiverWeak, const sp<MessageQueue>& messageQueue);

    void dispose();

protected:
    virtual ~NativeDisplayEventReceiver();

private:
    jobject mReceiverWeakGlobal;
    sp<MessageQueue> mMessageQueue;
    DisplayEventReceiver mReceiver;

    virtual void dispatchVsync(nsecs_t timestamp, int32_t id, uint32_t count);
    virtual void dispatchHotplug(nsecs_t timestamp, int32_t id, bool connected);
};


NativeDisplayEventReceiver::NativeDisplayEventReceiver(JNIEnv* env,
        jobject receiverWeak, const sp<MessageQueue>& messageQueue) :
        DisplayEventDispatcher(messageQueue->getLooper()),
        mReceiverWeakGlobal(env->NewGlobalRef(receiverWeak)),
        mMessageQueue(messageQueue) {
    ALOGV("receiver %p ~ Initializing display event receiver.", this);
}

NativeDisplayEventReceiver::~NativeDisplayEventReceiver() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mReceiverWeakGlobal);
    ALOGV("receiver %p ~ dtor display event receiver.", this);
}

void NativeDisplayEventReceiver::dispose() {
    ALOGV("receiver %p ~ Disposing display event receiver.", this);
    DisplayEventDispatcher::dispose();
}

void NativeDisplayEventReceiver::dispatchVsync(nsecs_t timestamp, int32_t id, uint32_t count) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, jniGetReferent(env, mReceiverWeakGlobal));
    if (receiverObj.get()) {
        ALOGV("receiver %p ~ Invoking vsync handler.", this);
        env->CallVoidMethod(receiverObj.get(),
                gDisplayEventReceiverClassInfo.dispatchVsync, timestamp, id, count);
        ALOGV("receiver %p ~ Returned from vsync handler.", this);
    }

    mMessageQueue->raiseAndClearException(env, "dispatchVsync");
}

void NativeDisplayEventReceiver::dispatchHotplug(nsecs_t timestamp, int32_t id, bool connected) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, jniGetReferent(env, mReceiverWeakGlobal));
    if (receiverObj.get()) {
        ALOGV("receiver %p ~ Invoking hotplug handler.", this);
        env->CallVoidMethod(receiverObj.get(),
                gDisplayEventReceiverClassInfo.dispatchHotplug, timestamp, id, connected);
        ALOGV("receiver %p ~ Returned from hotplug handler.", this);
    }

    mMessageQueue->raiseAndClearException(env, "dispatchHotplug");
}


static jlong nativeInit(JNIEnv* env, jclass clazz, jobject receiverWeak,
        jobject messageQueueObj) {
    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueObj);
    if (messageQueue == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<NativeDisplayEventReceiver> receiver = new NativeDisplayEventReceiver(env,
            receiverWeak, messageQueue);
    status_t status = receiver->initialize();
    if (status) {
        String8 message;
        message.appendFormat("Failed to initialize display event receiver.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
        return 0;
    }

    receiver->incStrong(gDisplayEventReceiverClassInfo.clazz); // retain a reference for the object
    return reinterpret_cast<jlong>(receiver.get());
}

static void nativeDispose(JNIEnv* env, jclass clazz, jlong receiverPtr) {
    NativeDisplayEventReceiver* receiver =
            reinterpret_cast<NativeDisplayEventReceiver*>(receiverPtr);
    receiver->dispose();
    receiver->decStrong(gDisplayEventReceiverClassInfo.clazz); // drop reference held by the object
}

static void nativeScheduleVsync(JNIEnv* env, jclass clazz, jlong receiverPtr) {
    sp<NativeDisplayEventReceiver> receiver =
            reinterpret_cast<NativeDisplayEventReceiver*>(receiverPtr);
    status_t status = receiver->scheduleVsync();
    if (status) {
        String8 message;
        message.appendFormat("Failed to schedule next vertical sync pulse.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
    }
}


static const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit",
            "(Ljava/lang/ref/WeakReference;Landroid/os/MessageQueue;)J",
            (void*)nativeInit },
    { "nativeDispose",
            "(J)V",
            (void*)nativeDispose },
    { "nativeScheduleVsync", "!(J)V",
            (void*)nativeScheduleVsync }
};

int register_android_view_DisplayEventReceiver(JNIEnv* env) {
    int res = RegisterMethodsOrDie(env, "android/view/DisplayEventReceiver", gMethods,
                                   NELEM(gMethods));

    jclass clazz = FindClassOrDie(env, "android/view/DisplayEventReceiver");
    gDisplayEventReceiverClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);

    gDisplayEventReceiverClassInfo.dispatchVsync = GetMethodIDOrDie(env,
            gDisplayEventReceiverClassInfo.clazz, "dispatchVsync", "(JII)V");
    gDisplayEventReceiverClassInfo.dispatchHotplug = GetMethodIDOrDie(env,
            gDisplayEventReceiverClassInfo.clazz, "dispatchHotplug", "(JIZ)V");

    return res;
}

} // namespace android
