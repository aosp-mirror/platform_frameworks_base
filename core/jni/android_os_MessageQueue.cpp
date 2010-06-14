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

#include "JNIHelp.h"

#include <utils/PollLoop.h>
#include <utils/Log.h>
#include "android_os_MessageQueue.h"

namespace android {

// ----------------------------------------------------------------------------

static struct {
    jclass clazz;

    jfieldID mPtr;   // native object attached to the DVM MessageQueue
} gMessageQueueClassInfo;

// ----------------------------------------------------------------------------

class NativeMessageQueue {
public:
    NativeMessageQueue();
    ~NativeMessageQueue();

    inline sp<PollLoop> getPollLoop() { return mPollLoop; }

    bool pollOnce(int timeoutMillis);
    void wake();

private:
    sp<PollLoop> mPollLoop;
};

// ----------------------------------------------------------------------------

NativeMessageQueue::NativeMessageQueue() {
    mPollLoop = new PollLoop();
}

NativeMessageQueue::~NativeMessageQueue() {
}

bool NativeMessageQueue::pollOnce(int timeoutMillis) {
    return mPollLoop->pollOnce(timeoutMillis);
}

void NativeMessageQueue::wake() {
    mPollLoop->wake();
}

// ----------------------------------------------------------------------------

static NativeMessageQueue* android_os_MessageQueue_getNativeMessageQueue(JNIEnv* env,
        jobject messageQueueObj) {
    jint intPtr = env->GetIntField(messageQueueObj, gMessageQueueClassInfo.mPtr);
    return reinterpret_cast<NativeMessageQueue*>(intPtr);
}

static void android_os_MessageQueue_setNativeMessageQueue(JNIEnv* env, jobject messageQueueObj,
        NativeMessageQueue* nativeMessageQueue) {
    env->SetIntField(messageQueueObj, gMessageQueueClassInfo.mPtr,
             reinterpret_cast<jint>(nativeMessageQueue));
}

sp<PollLoop> android_os_MessageQueue_getPollLoop(JNIEnv* env, jobject messageQueueObj) {
    NativeMessageQueue* nativeMessageQueue =
            android_os_MessageQueue_getNativeMessageQueue(env, messageQueueObj);
    return nativeMessageQueue != NULL ? nativeMessageQueue->getPollLoop() : NULL;
}

static void android_os_MessageQueue_nativeInit(JNIEnv* env, jobject obj) {
    NativeMessageQueue* nativeMessageQueue = new NativeMessageQueue();
    if (! nativeMessageQueue) {
        jniThrowRuntimeException(env, "Unable to allocate native queue");
        return;
    }

    android_os_MessageQueue_setNativeMessageQueue(env, obj, nativeMessageQueue);
}

static void android_os_MessageQueue_nativeDestroy(JNIEnv* env, jobject obj) {
    NativeMessageQueue* nativeMessageQueue =
            android_os_MessageQueue_getNativeMessageQueue(env, obj);
    if (nativeMessageQueue) {
        android_os_MessageQueue_setNativeMessageQueue(env, obj, NULL);
        delete nativeMessageQueue;
    }
}

static void throwQueueNotInitialized(JNIEnv* env) {
    jniThrowException(env, "java/lang/IllegalStateException", "Message queue not initialized");
}

static jboolean android_os_MessageQueue_nativePollOnce(JNIEnv* env, jobject obj,
        jint timeoutMillis) {
    NativeMessageQueue* nativeMessageQueue =
            android_os_MessageQueue_getNativeMessageQueue(env, obj);
    if (! nativeMessageQueue) {
        throwQueueNotInitialized(env);
        return false;
    }
    return nativeMessageQueue->pollOnce(timeoutMillis);
}

static void android_os_MessageQueue_nativeWake(JNIEnv* env, jobject obj) {
    NativeMessageQueue* nativeMessageQueue =
            android_os_MessageQueue_getNativeMessageQueue(env, obj);
    if (! nativeMessageQueue) {
        throwQueueNotInitialized(env);
        return;
    }
    return nativeMessageQueue->wake();
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMessageQueueMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit", "()V", (void*)android_os_MessageQueue_nativeInit },
    { "nativeDestroy", "()V", (void*)android_os_MessageQueue_nativeDestroy },
    { "nativePollOnce", "(I)Z", (void*)android_os_MessageQueue_nativePollOnce },
    { "nativeWake", "()V", (void*)android_os_MessageQueue_nativeWake }
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_FIELD_ID(var, clazz, fieldName, fieldDescriptor) \
        var = env->GetFieldID(clazz, fieldName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find field " fieldName);

int register_android_os_MessageQueue(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/os/MessageQueue",
            gMessageQueueMethods, NELEM(gMessageQueueMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    FIND_CLASS(gMessageQueueClassInfo.clazz, "android/os/MessageQueue");

    GET_FIELD_ID(gMessageQueueClassInfo.mPtr, gMessageQueueClassInfo.clazz,
            "mPtr", "I");
    
    return 0;
}

} // namespace android
