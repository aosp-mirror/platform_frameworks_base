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
    jmethodID dispatchHotplug;
} gDisplayEventReceiverClassInfo;


class NativeDisplayEventReceiver : public LooperCallback {
public:
    NativeDisplayEventReceiver(JNIEnv* env,
            jobject receiverObj, const sp<MessageQueue>& messageQueue);

    status_t initialize();
    void dispose();
    status_t scheduleVsync();

protected:
    virtual ~NativeDisplayEventReceiver();

private:
    jobject mReceiverObjGlobal;
    sp<MessageQueue> mMessageQueue;
    DisplayEventReceiver mReceiver;
    bool mWaitingForVsync;

    virtual int handleEvent(int receiveFd, int events, void* data);
    bool readLastVsyncMessage(nsecs_t* outTimestamp, int32_t* id, uint32_t* outCount);
    void dispatchVsync(nsecs_t timestamp, int32_t id, uint32_t count);
    void dispatchHotplug(nsecs_t timestamp, int32_t id, bool connected);
};


NativeDisplayEventReceiver::NativeDisplayEventReceiver(JNIEnv* env,
        jobject receiverObj, const sp<MessageQueue>& messageQueue) :
        mReceiverObjGlobal(env->NewGlobalRef(receiverObj)),
        mMessageQueue(messageQueue), mWaitingForVsync(false) {
    ALOGV("receiver %p ~ Initializing input event receiver.", this);
}

NativeDisplayEventReceiver::~NativeDisplayEventReceiver() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mReceiverObjGlobal);
}

status_t NativeDisplayEventReceiver::initialize() {
    status_t result = mReceiver.initCheck();
    if (result) {
        ALOGW("Failed to initialize display event receiver, status=%d", result);
        return result;
    }

    int rc = mMessageQueue->getLooper()->addFd(mReceiver.getFd(), 0, ALOOPER_EVENT_INPUT,
            this, NULL);
    if (rc < 0) {
        return UNKNOWN_ERROR;
    }
    return OK;
}

void NativeDisplayEventReceiver::dispose() {
    ALOGV("receiver %p ~ Disposing display event receiver.", this);

    if (!mReceiver.initCheck()) {
        mMessageQueue->getLooper()->removeFd(mReceiver.getFd());
    }
}

status_t NativeDisplayEventReceiver::scheduleVsync() {
    if (!mWaitingForVsync) {
        ALOGV("receiver %p ~ Scheduling vsync.", this);

        // Drain all pending events.
        nsecs_t vsyncTimestamp;
        int32_t vsyncDisplayId;
        uint32_t vsyncCount;
        readLastVsyncMessage(&vsyncTimestamp, &vsyncDisplayId, &vsyncCount);

        status_t status = mReceiver.requestNextVsync();
        if (status) {
            ALOGW("Failed to request next vsync, status=%d", status);
            return status;
        }

        mWaitingForVsync = true;
    }
    return OK;
}

int NativeDisplayEventReceiver::handleEvent(int receiveFd, int events, void* data) {
    if (events & (ALOOPER_EVENT_ERROR | ALOOPER_EVENT_HANGUP)) {
        ALOGE("Display event receiver pipe was closed or an error occurred.  "
                "events=0x%x", events);
        return 0; // remove the callback
    }

    if (!(events & ALOOPER_EVENT_INPUT)) {
        ALOGW("Received spurious callback for unhandled poll event.  "
                "events=0x%x", events);
        return 1; // keep the callback
    }

    // Drain all pending events, keep the last vsync.
    nsecs_t vsyncTimestamp;
    int32_t vsyncDisplayId;
    uint32_t vsyncCount;
    if (!readLastVsyncMessage(&vsyncTimestamp, &vsyncDisplayId, &vsyncCount)) {
        ALOGV("receiver %p ~ Woke up but there was no vsync pulse!", this);
        return 1; // keep the callback, did not obtain a vsync pulse
    }

    ALOGV("receiver %p ~ Vsync pulse: timestamp=%lld, id=%d, count=%d",
            this, vsyncTimestamp, vsyncDisplayId, vsyncCount);
    mWaitingForVsync = false;

    dispatchVsync(vsyncTimestamp, vsyncDisplayId, vsyncCount);
    return 1; // keep the callback
}

bool NativeDisplayEventReceiver::readLastVsyncMessage(
        nsecs_t* outTimestamp, int32_t* outId, uint32_t* outCount) {
    DisplayEventReceiver::Event buf[EVENT_BUFFER_SIZE];
    ssize_t n;
    while ((n = mReceiver.getEvents(buf, EVENT_BUFFER_SIZE)) > 0) {
        ALOGV("receiver %p ~ Read %d events.", this, int(n));
        while (n-- > 0) {
            const DisplayEventReceiver::Event& ev = buf[n];
            if (ev.header.type == DisplayEventReceiver::DISPLAY_EVENT_VSYNC) {
                *outTimestamp = ev.header.timestamp;
                *outId = ev.header.id;
                *outCount = ev.vsync.count;
                return true; // stop at last vsync in the buffer
            }

            if (ev.header.type == DisplayEventReceiver::DISPLAY_EVENT_HOTPLUG) {
                dispatchHotplug(ev.header.timestamp, ev.header.id, ev.hotplug.connected);
            }
        }
    }
    if (n < 0) {
        ALOGW("Failed to get events from display event receiver, status=%d", status_t(n));
    }
    return false;
}

void NativeDisplayEventReceiver::dispatchVsync(nsecs_t timestamp, int32_t id, uint32_t count) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ALOGV("receiver %p ~ Invoking vsync handler.", this);
    env->CallVoidMethod(mReceiverObjGlobal,
            gDisplayEventReceiverClassInfo.dispatchVsync, timestamp, id, count);
    ALOGV("receiver %p ~ Returned from vsync handler.", this);

    mMessageQueue->raiseAndClearException(env, "dispatchVsync");
}

void NativeDisplayEventReceiver::dispatchHotplug(nsecs_t timestamp, int32_t id, bool connected) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ALOGV("receiver %p ~ Invoking hotplug handler.", this);
    env->CallVoidMethod(mReceiverObjGlobal,
            gDisplayEventReceiverClassInfo.dispatchHotplug, timestamp, id, connected);
    ALOGV("receiver %p ~ Returned from hotplug handler.", this);

    mMessageQueue->raiseAndClearException(env, "dispatchHotplug");
}


static jint nativeInit(JNIEnv* env, jclass clazz, jobject receiverObj,
        jobject messageQueueObj) {
    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueObj);
    if (messageQueue == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<NativeDisplayEventReceiver> receiver = new NativeDisplayEventReceiver(env,
            receiverObj, messageQueue);
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
    receiver->dispose();
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
            "dispatchVsync", "(JII)V");
    GET_METHOD_ID(gDisplayEventReceiverClassInfo.dispatchHotplug,
            gDisplayEventReceiverClassInfo.clazz,
            "dispatchHotplug", "(JIZ)V");
    return 0;
}

} // namespace android
