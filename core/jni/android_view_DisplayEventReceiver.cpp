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

#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/threads.h>
#include <gui/DisplayEventReceiver.h>
#include "android_os_MessageQueue.h"

namespace android {

// Number of events to read at a time from the DisplayEventReceiver pipe.
// The value should be large enough that we can quickly drain the pipe
// using just a few large reads.
static const size_t EVENT_BUFFER_SIZE = 100;

static struct {
    jclass clazz;

    jmethodID dispatchVsync;
} gDisplayEventReceiverClassInfo;


class NativeDisplayEventReceiver : public RefBase {
public:
    NativeDisplayEventReceiver(JNIEnv* env,
            jobject receiverObj, const sp<Looper>& looper);

    status_t initialize();
    status_t scheduleVsync();
    static int handleReceiveCallback(int receiveFd, int events, void* data);

protected:
    virtual ~NativeDisplayEventReceiver();

private:
    jobject mReceiverObjGlobal;
    sp<Looper> mLooper;
    DisplayEventReceiver mReceiver;
    bool mWaitingForVsync;
    bool mFdCallbackRegistered;
};


NativeDisplayEventReceiver::NativeDisplayEventReceiver(JNIEnv* env,
        jobject receiverObj, const sp<Looper>& looper) :
        mReceiverObjGlobal(env->NewGlobalRef(receiverObj)),
        mLooper(looper), mWaitingForVsync(false), mFdCallbackRegistered(false) {
    ALOGV("receiver %p ~ Initializing input event receiver.", this);
}

NativeDisplayEventReceiver::~NativeDisplayEventReceiver() {
    ALOGV("receiver %p ~ Disposing display event receiver.", this);

    if (mFdCallbackRegistered) {
        mLooper->removeFd(mReceiver.getFd());
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mReceiverObjGlobal);
}

status_t NativeDisplayEventReceiver::initialize() {
    status_t result = mReceiver.initCheck();
    if (result) {
        ALOGW("Failed to initialize display event receiver, status=%d", result);
        return result;
    }

    return OK;
}

status_t NativeDisplayEventReceiver::scheduleVsync() {
    if (!mWaitingForVsync) {
        ALOGV("receiver %p ~ Scheduling vsync.", this);

        // Drain all pending events.
        DisplayEventReceiver::Event buf[EVENT_BUFFER_SIZE];
        ssize_t n;
        while ((n = mReceiver.getEvents(buf, EVENT_BUFFER_SIZE)) > 0) {
            ALOGV("receiver %p ~ Drained %d events.", this, int(n));
        }

        if (n < 0) {
            ALOGW("Failed to drain events from display event receiver, status=%d", status_t(n));
            return status_t(n);
        }

        status_t status = mReceiver.requestNextVsync();
        if (status) {
            ALOGW("Failed to request next vsync, status=%d", status);
            return status;
        }

        if (!mFdCallbackRegistered) {
            int rc = mLooper->addFd(mReceiver.getFd(), 0, ALOOPER_EVENT_INPUT,
                    handleReceiveCallback, this);
            if (rc < 0) {
                return UNKNOWN_ERROR;
            }
            mFdCallbackRegistered = true;
        }

        mWaitingForVsync = true;
    }
    return OK;
}

int NativeDisplayEventReceiver::handleReceiveCallback(int receiveFd, int events, void* data) {
    sp<NativeDisplayEventReceiver> r = static_cast<NativeDisplayEventReceiver*>(data);

    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        LOGE("Display event receiver pipe was closed or an error occurred.  "
                "events=0x%x", events);
        r->mFdCallbackRegistered = false;
        return 0; // remove the callback
    }

    if (!(events & ALOOPER_EVENT_INPUT)) {
        ALOGW("Received spurious callback for unhandled poll event.  "
                "events=0x%x", events);
        return 1; // keep the callback
    }

    // Drain all pending events, keep the last vsync.
    nsecs_t vsyncTimestamp = -1;
    uint32_t vsyncCount = 0;

    DisplayEventReceiver::Event buf[EVENT_BUFFER_SIZE];
    ssize_t n;
    while ((n = r->mReceiver.getEvents(buf, EVENT_BUFFER_SIZE)) > 0) {
        ALOGV("receiver %p ~ Read %d events.", this, int(n));
        while (n-- > 0) {
            if (buf[n].header.type == DisplayEventReceiver::DISPLAY_EVENT_VSYNC) {
                vsyncTimestamp = buf[n].header.timestamp;
                vsyncCount = buf[n].vsync.count;
                break; // stop at last vsync in the buffer
            }
        }
    }

    if (vsyncTimestamp < 0) {
        ALOGV("receiver %p ~ Woke up but there was no vsync pulse!", this);
        return 1; // keep the callback, did not obtain a vsync pulse
    }

    ALOGV("receiver %p ~ Vsync pulse: timestamp=%lld, count=%d",
            this, vsyncTimestamp, vsyncCount);
    r->mWaitingForVsync = false;

    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ALOGV("receiver %p ~ Invoking vsync handler.", this);
    env->CallVoidMethod(r->mReceiverObjGlobal,
            gDisplayEventReceiverClassInfo.dispatchVsync, vsyncTimestamp, vsyncCount);
    ALOGV("receiver %p ~ Returned from vsync handler.", this);

    if (env->ExceptionCheck()) {
        LOGE("An exception occurred while dispatching a vsync event.");
        LOGE_EX(env);
        env->ExceptionClear();
    }

    // Check whether dispatchVsync called scheduleVsync reentrantly and set mWaitingForVsync.
    // If so, keep the callback, otherwise remove it.
    if (r->mWaitingForVsync) {
        return 1; // keep the callback
    }
    r->mFdCallbackRegistered = false;
    return 0; // remove the callback
}


static jint nativeInit(JNIEnv* env, jclass clazz, jobject receiverObj,
        jobject messageQueueObj) {
    sp<Looper> looper = android_os_MessageQueue_getLooper(env, messageQueueObj);
    if (looper == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<NativeDisplayEventReceiver> receiver = new NativeDisplayEventReceiver(env,
            receiverObj, looper);
    status_t status = receiver->initialize();
    if (status) {
        String8 message;
        message.appendFormat("Failed to initialize display event receiver.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
        return 0;
    }

    receiver->incStrong(gDisplayEventReceiverClassInfo.clazz); // retain a reference for the object
    return reinterpret_cast<jint>(receiver.get());
}

static void nativeDispose(JNIEnv* env, jclass clazz, jint receiverPtr) {
    sp<NativeDisplayEventReceiver> receiver =
            reinterpret_cast<NativeDisplayEventReceiver*>(receiverPtr);
    receiver->decStrong(gDisplayEventReceiverClassInfo.clazz); // drop reference held by the object
}

static void nativeScheduleVsync(JNIEnv* env, jclass clazz, jint receiverPtr) {
    sp<NativeDisplayEventReceiver> receiver =
            reinterpret_cast<NativeDisplayEventReceiver*>(receiverPtr);
    status_t status = receiver->scheduleVsync();
    if (status) {
        String8 message;
        message.appendFormat("Failed to schedule next vertical sync pulse.  status=%d", status);
        jniThrowRuntimeException(env, message.string());
    }
}


static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeInit",
            "(Landroid/view/DisplayEventReceiver;Landroid/os/MessageQueue;)I",
            (void*)nativeInit },
    { "nativeDispose",
            "(I)V",
            (void*)nativeDispose },
    { "nativeScheduleVsync", "(I)V",
            (void*)nativeScheduleVsync }
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

int register_android_view_DisplayEventReceiver(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/DisplayEventReceiver",
            gMethods, NELEM(gMethods));
    LOG_FATAL_IF(res < 0, "Unable to register native methods.");

    FIND_CLASS(gDisplayEventReceiverClassInfo.clazz, "android/view/DisplayEventReceiver");

    GET_METHOD_ID(gDisplayEventReceiverClassInfo.dispatchVsync,
            gDisplayEventReceiverClassInfo.clazz,
            "dispatchVsync", "(JI)V");
    return 0;
}

} // namespace android
