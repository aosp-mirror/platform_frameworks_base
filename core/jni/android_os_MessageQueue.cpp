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

#define LOG_TAG "MessageQueue-JNI"

#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <utils/Looper.h>
#include <utils/Log.h>
#include "android_os_MessageQueue.h"

#include "core_jni_helpers.h"

namespace android {

static struct {
    jfieldID mPtr;   // native object attached to the DVM MessageQueue
    jmethodID dispatchEvents;
} gMessageQueueClassInfo;

// Must be kept in sync with the constants in Looper.FileDescriptorCallback
static const int CALLBACK_EVENT_INPUT = 1 << 0;
static const int CALLBACK_EVENT_OUTPUT = 1 << 1;
static const int CALLBACK_EVENT_ERROR = 1 << 2;


class NativeMessageQueue : public MessageQueue, public LooperCallback {
public:
    NativeMessageQueue();
    virtual ~NativeMessageQueue();

    virtual void raiseException(JNIEnv* env, const char* msg, jthrowable exceptionObj);

    void pollOnce(JNIEnv* env, jobject obj, int timeoutMillis);
    void wake();
    void setFileDescriptorEvents(int fd, int events);

    virtual int handleEvent(int fd, int events, void* data);

private:
    JNIEnv* mPollEnv;
    jobject mPollObj;
    jthrowable mExceptionObj;
};


MessageQueue::MessageQueue() {
}

MessageQueue::~MessageQueue() {
}

bool MessageQueue::raiseAndClearException(JNIEnv* env, const char* msg) {
    if (env->ExceptionCheck()) {
        jthrowable exceptionObj = env->ExceptionOccurred();
        env->ExceptionClear();
        raiseException(env, msg, exceptionObj);
        env->DeleteLocalRef(exceptionObj);
        return true;
    }
    return false;
}

NativeMessageQueue::NativeMessageQueue() :
        mPollEnv(NULL), mPollObj(NULL), mExceptionObj(NULL) {
    mLooper = Looper::getForThread();
    if (mLooper == NULL) {
        mLooper = new Looper(false);
        Looper::setForThread(mLooper);
    }
}

NativeMessageQueue::~NativeMessageQueue() {
}

void NativeMessageQueue::raiseException(JNIEnv* env, const char* msg, jthrowable exceptionObj) {
    if (exceptionObj) {
        if (mPollEnv == env) {
            if (mExceptionObj) {
                env->DeleteLocalRef(mExceptionObj);
            }
            mExceptionObj = jthrowable(env->NewLocalRef(exceptionObj));
            ALOGE("Exception in MessageQueue callback: %s", msg);
            jniLogException(env, ANDROID_LOG_ERROR, LOG_TAG, exceptionObj);
        } else {
            ALOGE("Exception: %s", msg);
            jniLogException(env, ANDROID_LOG_ERROR, LOG_TAG, exceptionObj);
            LOG_ALWAYS_FATAL("raiseException() was called when not in a callback, exiting.");
        }
    }
}

void NativeMessageQueue::pollOnce(JNIEnv* env, jobject pollObj, int timeoutMillis) {
    mPollEnv = env;
    mPollObj = pollObj;
    mLooper->pollOnce(timeoutMillis);
    mPollObj = NULL;
    mPollEnv = NULL;

    if (mExceptionObj) {
        env->Throw(mExceptionObj);
        env->DeleteLocalRef(mExceptionObj);
        mExceptionObj = NULL;
    }
}

void NativeMessageQueue::wake() {
    mLooper->wake();
}

void NativeMessageQueue::setFileDescriptorEvents(int fd, int events) {
    if (events) {
        int looperEvents = 0;
        if (events & CALLBACK_EVENT_INPUT) {
            looperEvents |= Looper::EVENT_INPUT;
        }
        if (events & CALLBACK_EVENT_OUTPUT) {
            looperEvents |= Looper::EVENT_OUTPUT;
        }
        mLooper->addFd(fd, Looper::POLL_CALLBACK, looperEvents, this,
                reinterpret_cast<void*>(events));
    } else {
        mLooper->removeFd(fd);
    }
}

int NativeMessageQueue::handleEvent(int fd, int looperEvents, void* data) {
    int events = 0;
    if (looperEvents & Looper::EVENT_INPUT) {
        events |= CALLBACK_EVENT_INPUT;
    }
    if (looperEvents & Looper::EVENT_OUTPUT) {
        events |= CALLBACK_EVENT_OUTPUT;
    }
    if (looperEvents & (Looper::EVENT_ERROR | Looper::EVENT_HANGUP | Looper::EVENT_INVALID)) {
        events |= CALLBACK_EVENT_ERROR;
    }
    int oldWatchedEvents = reinterpret_cast<intptr_t>(data);
    int newWatchedEvents = mPollEnv->CallIntMethod(mPollObj,
            gMessageQueueClassInfo.dispatchEvents, fd, events);
    if (!newWatchedEvents) {
        return 0; // unregister the fd
    }
    if (newWatchedEvents != oldWatchedEvents) {
        setFileDescriptorEvents(fd, newWatchedEvents);
    }
    return 1;
}


// ----------------------------------------------------------------------------

sp<MessageQueue> android_os_MessageQueue_getMessageQueue(JNIEnv* env, jobject messageQueueObj) {
    jlong ptr = env->GetLongField(messageQueueObj, gMessageQueueClassInfo.mPtr);
    return reinterpret_cast<NativeMessageQueue*>(ptr);
}

static jlong android_os_MessageQueue_nativeInit(JNIEnv* env, jclass clazz) {
    NativeMessageQueue* nativeMessageQueue = new NativeMessageQueue();
    if (!nativeMessageQueue) {
        jniThrowRuntimeException(env, "Unable to allocate native queue");
        return 0;
    }

    nativeMessageQueue->incStrong(env);
    return reinterpret_cast<jlong>(nativeMessageQueue);
}

static void android_os_MessageQueue_nativeDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    NativeMessageQueue* nativeMessageQueue = reinterpret_cast<NativeMessageQueue*>(ptr);
    nativeMessageQueue->decStrong(env);
}

static void android_os_MessageQueue_nativePollOnce(JNIEnv* env, jobject obj,
        jlong ptr, jint timeoutMillis) {
    NativeMessageQueue* nativeMessageQueue = reinterpret_cast<NativeMessageQueue*>(ptr);
    nativeMessageQueue->pollOnce(env, obj, timeoutMillis);
}

static void android_os_MessageQueue_nativeWake(JNIEnv* env, jclass clazz, jlong ptr) {
    NativeMessageQueue* nativeMessageQueue = reinterpret_cast<NativeMessageQueue*>(ptr);
    nativeMessageQueue->wake();
}

static jboolean android_os_MessageQueue_nativeIsPolling(JNIEnv* env, jclass clazz, jlong ptr) {
    NativeMessageQueue* nativeMessageQueue = reinterpret_cast<NativeMessageQueue*>(ptr);
    return nativeMessageQueue->getLooper()->isPolling();
}

static void android_os_MessageQueue_nativeSetFileDescriptorEvents(JNIEnv* env, jclass clazz,
        jlong ptr, jint fd, jint events) {
    NativeMessageQueue* nativeMessageQueue = reinterpret_cast<NativeMessageQueue*>(ptr);
    nativeMessageQueue->setFileDescriptorEvents(fd, events);
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMessageQueueMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "()J", (void*)android_os_MessageQueue_nativeInit },
    { "nativeDestroy", "(J)V", (void*)android_os_MessageQueue_nativeDestroy },
    { "nativePollOnce", "(JI)V", (void*)android_os_MessageQueue_nativePollOnce },
    { "nativeWake", "(J)V", (void*)android_os_MessageQueue_nativeWake },
    { "nativeIsPolling", "(J)Z", (void*)android_os_MessageQueue_nativeIsPolling },
    { "nativeSetFileDescriptorEvents", "(JII)V",
            (void*)android_os_MessageQueue_nativeSetFileDescriptorEvents },
};

int register_android_os_MessageQueue(JNIEnv* env) {
    int res = RegisterMethodsOrDie(env, "android/os/MessageQueue", gMessageQueueMethods,
                                   NELEM(gMessageQueueMethods));

    jclass clazz = FindClassOrDie(env, "android/os/MessageQueue");
    gMessageQueueClassInfo.mPtr = GetFieldIDOrDie(env, clazz, "mPtr", "J");
    gMessageQueueClassInfo.dispatchEvents = GetMethodIDOrDie(env, clazz,
            "dispatchEvents", "(II)I");

    return res;
}

} // namespace android
