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

#define LOG_TAG "InputQueue"

#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#include <android/input.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_InputQueue.h>
#include <input/Input.h>
#include <utils/Looper.h>
#include <utils/TypeHelpers.h>
#include <ScopedLocalRef.h>

#include "JNIHelp.h"
#include "android_os_MessageQueue.h"
#include "android_view_KeyEvent.h"
#include "android_view_MotionEvent.h"

namespace android {

static struct {
    jmethodID finishInputEvent;
} gInputQueueClassInfo;

enum {
    MSG_FINISH_INPUT = 1,
};

InputQueue::InputQueue(jobject inputQueueObj, const sp<Looper>& looper,
        int dispatchReadFd, int dispatchWriteFd) :
        mDispatchReadFd(dispatchReadFd), mDispatchWriteFd(dispatchWriteFd),
        mDispatchLooper(looper), mHandler(new WeakMessageHandler(this)) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    mInputQueueWeakGlobal = env->NewGlobalRef(inputQueueObj);
}

InputQueue::~InputQueue() {
    mDispatchLooper->removeMessages(mHandler);
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mInputQueueWeakGlobal);
    close(mDispatchReadFd);
    close(mDispatchWriteFd);
}

void InputQueue::attachLooper(Looper* looper, int ident,
        ALooper_callbackFunc callback, void* data) {
    Mutex::Autolock _l(mLock);
    for (size_t i = 0; i < mAppLoopers.size(); i++) {
        if (looper == mAppLoopers[i]) {
            return;
        }
    }
    mAppLoopers.push(looper);
    looper->addFd(mDispatchReadFd, ident, ALOOPER_EVENT_INPUT, callback, data);
}

void InputQueue::detachLooper() {
    Mutex::Autolock _l(mLock);
    detachLooperLocked();
}

void InputQueue::detachLooperLocked() {
    for (size_t i = 0; i < mAppLoopers.size(); i++) {
        mAppLoopers[i]->removeFd(mDispatchReadFd);
    }
    mAppLoopers.clear();
}

bool InputQueue::hasEvents() {
    Mutex::Autolock _l(mLock);
    return mPendingEvents.size() > 0;
}

status_t InputQueue::getEvent(InputEvent** outEvent) {
    Mutex::Autolock _l(mLock);
    *outEvent = NULL;
    if (!mPendingEvents.isEmpty()) {
        *outEvent = mPendingEvents[0];
        mPendingEvents.removeAt(0);
    }

    if (mPendingEvents.isEmpty()) {
        char byteread[16];
        ssize_t nRead;
        do {
            nRead = TEMP_FAILURE_RETRY(read(mDispatchReadFd, &byteread, sizeof(byteread)));
            if (nRead < 0 && errno != EAGAIN) {
                ALOGW("Failed to read from native dispatch pipe: %s", strerror(errno));
            }
        } while (nRead > 0);
    }

    return *outEvent != NULL ? OK : WOULD_BLOCK;
}

bool InputQueue::preDispatchEvent(InputEvent* e) {
    if (e->getType() == AINPUT_EVENT_TYPE_KEY) {
        KeyEvent* keyEvent = static_cast<KeyEvent*>(e);
        if (keyEvent->getFlags() & AKEY_EVENT_FLAG_PREDISPATCH) {
            finishEvent(e, false);
            return true;
        }
    }
    return false;
}

void InputQueue::finishEvent(InputEvent* event, bool handled) {
    Mutex::Autolock _l(mLock);
    mFinishedEvents.push(key_value_pair_t<InputEvent*, bool>(event, handled));
    if (mFinishedEvents.size() == 1) {
        mDispatchLooper->sendMessage(this, Message(MSG_FINISH_INPUT));
    }
}

void InputQueue::handleMessage(const Message& message) {
    switch(message.what) {
    case MSG_FINISH_INPUT:
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        ScopedLocalRef<jobject> inputQueueObj(env, jniGetReferent(env, mInputQueueWeakGlobal));
        if (!inputQueueObj.get()) {
            ALOGW("InputQueue was finalized without being disposed");
            return;
        }
        while (true) {
            InputEvent* event;
            bool handled;
            {
                Mutex::Autolock _l(mLock);
                if (mFinishedEvents.isEmpty()) {
                    break;
                }
                event = mFinishedEvents[0].getKey();
                handled = mFinishedEvents[0].getValue();
                mFinishedEvents.removeAt(0);
            }
            env->CallVoidMethod(inputQueueObj.get(), gInputQueueClassInfo.finishInputEvent,
                    reinterpret_cast<jint>(event), handled);
            recycleInputEvent(event);
        }
        break;
    }
}

void InputQueue::recycleInputEvent(InputEvent* event) {
    mPooledInputEventFactory.recycle(event);
}

KeyEvent* InputQueue::createKeyEvent() {
    return mPooledInputEventFactory.createKeyEvent();
}

MotionEvent* InputQueue::createMotionEvent() {
    return mPooledInputEventFactory.createMotionEvent();
}

void InputQueue::enqueueEvent(InputEvent* event) {
    Mutex::Autolock _l(mLock);
    mPendingEvents.push(event);
    if (mPendingEvents.size() == 1) {
        char dummy = 0;
        int res = TEMP_FAILURE_RETRY(write(mDispatchWriteFd, &dummy, sizeof(dummy)));
        if (res < 0 && errno != EAGAIN) {
            ALOGW("Failed writing to dispatch fd: %s", strerror(errno));
        }
    }
}

InputQueue* InputQueue::createQueue(jobject inputQueueObj, const sp<Looper>& looper) {
    int pipeFds[2];
    if (pipe(pipeFds)) {
        ALOGW("Could not create native input dispatching pipe: %s", strerror(errno));
        return NULL;
    }
    fcntl(pipeFds[0], F_SETFL, O_NONBLOCK);
    fcntl(pipeFds[1], F_SETFL, O_NONBLOCK);
    return new InputQueue(inputQueueObj, looper, pipeFds[0], pipeFds[1]);
}

static jint nativeInit(JNIEnv* env, jobject clazz, jobject queueWeak, jobject jMsgQueue) {
    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, jMsgQueue);
    if (messageQueue == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }
    sp<InputQueue> queue = InputQueue::createQueue(queueWeak, messageQueue->getLooper());
    if (!queue.get()) {
        jniThrowRuntimeException(env, "InputQueue failed to initialize");
        return 0;
    }
    queue->incStrong(&gInputQueueClassInfo);
    return reinterpret_cast<jint>(queue.get());
}

static void nativeDispose(JNIEnv* env, jobject clazz, jint ptr) {
    sp<InputQueue> queue = reinterpret_cast<InputQueue*>(ptr);
    queue->detachLooper();
    queue->decStrong(&gInputQueueClassInfo);
}

static jint nativeSendKeyEvent(JNIEnv* env, jobject clazz, jint ptr, jobject eventObj,
        jboolean predispatch) {
    InputQueue* queue = reinterpret_cast<InputQueue*>(ptr);
    KeyEvent* event = queue->createKeyEvent();
    status_t status = android_view_KeyEvent_toNative(env, eventObj, event);
    if (status) {
        queue->recycleInputEvent(event);
        jniThrowRuntimeException(env, "Could not read contents of KeyEvent object.");
        return -1;
    }

    if (predispatch) {
        event->setFlags(event->getFlags() | AKEY_EVENT_FLAG_PREDISPATCH);
    }

    queue->enqueueEvent(event);
    return reinterpret_cast<jint>(event);
}

static jint nativeSendMotionEvent(JNIEnv* env, jobject clazz, jint ptr, jobject eventObj) {
    sp<InputQueue> queue = reinterpret_cast<InputQueue*>(ptr);
    MotionEvent* originalEvent = android_view_MotionEvent_getNativePtr(env, eventObj);
    if (!originalEvent) {
        jniThrowRuntimeException(env, "Could not obtain MotionEvent pointer.");
        return -1;
    }
    MotionEvent* event = queue->createMotionEvent();
    event->copyFrom(originalEvent, true /* keepHistory */);
    queue->enqueueEvent(event);
    return reinterpret_cast<jint>(event);
}

static const JNINativeMethod g_methods[] = {
    { "nativeInit", "(Ljava/lang/ref/WeakReference;Landroid/os/MessageQueue;)I",
        (void*) nativeInit },
    { "nativeDispose", "(I)V", (void*) nativeDispose },
    { "nativeSendKeyEvent", "(ILandroid/view/KeyEvent;Z)I", (void*) nativeSendKeyEvent },
    { "nativeSendMotionEvent", "(ILandroid/view/MotionEvent;)I", (void*) nativeSendMotionEvent },
};

static const char* const kInputQueuePathName = "android/view/InputQueue";

#define FIND_CLASS(var, className) \
        do { \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class %s", className); \
        } while(0)

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        do { \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method" methodName); \
        } while(0)

int register_android_view_InputQueue(JNIEnv* env)
{
    jclass clazz;
    FIND_CLASS(clazz, kInputQueuePathName);
    GET_METHOD_ID(gInputQueueClassInfo.finishInputEvent, clazz, "finishInputEvent", "(IZ)V");

    return AndroidRuntime::registerNativeMethods(
        env, kInputQueuePathName,
        g_methods, NELEM(g_methods));
}

} // namespace android
