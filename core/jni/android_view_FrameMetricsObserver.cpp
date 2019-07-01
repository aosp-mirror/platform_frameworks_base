/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "android_view_FrameMetricsObserver.h"

namespace android {

struct {
    jfieldID frameMetrics;
    jfieldID timingDataBuffer;
    jfieldID messageQueue;
    jmethodID callback;
} gFrameMetricsObserverClassInfo;

static JNIEnv* getenv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", vm);
    }
    return env;
}

static jlongArray get_metrics_buffer(JNIEnv* env, jobject observer) {
    jobject frameMetrics = env->GetObjectField(
            observer, gFrameMetricsObserverClassInfo.frameMetrics);
    LOG_ALWAYS_FATAL_IF(frameMetrics == nullptr, "unable to retrieve data sink object");
    jobject buffer = env->GetObjectField(
            frameMetrics, gFrameMetricsObserverClassInfo.timingDataBuffer);
    LOG_ALWAYS_FATAL_IF(buffer == nullptr, "unable to retrieve data sink buffer");
    return reinterpret_cast<jlongArray>(buffer);
}

class NotifyHandler : public MessageHandler {
public:
    NotifyHandler(JavaVM* vm, FrameMetricsObserverProxy* observer) : mVm(vm), mObserver(observer) {}

    virtual void handleMessage(const Message& message);

private:
    JavaVM* const mVm;
    FrameMetricsObserverProxy* const mObserver;
};

void NotifyHandler::handleMessage(const Message& message) {
    JNIEnv* env = getenv(mVm);

    jobject target = env->NewLocalRef(mObserver->getObserverReference());

    if (target != nullptr) {
        jlongArray javaBuffer = get_metrics_buffer(env, target);
        int dropCount = 0;
        while (mObserver->getNextBuffer(env, javaBuffer, &dropCount)) {
            env->CallVoidMethod(target, gFrameMetricsObserverClassInfo.callback, dropCount);
        }
        env->DeleteLocalRef(target);
    }

    mObserver->decStrong(nullptr);
}

FrameMetricsObserverProxy::FrameMetricsObserverProxy(JavaVM *vm, jobject observer) : mVm(vm) {
    JNIEnv* env = getenv(mVm);

    mObserverWeak = env->NewWeakGlobalRef(observer);
    LOG_ALWAYS_FATAL_IF(mObserverWeak == nullptr,
            "unable to create frame stats observer reference");

    jlongArray buffer = get_metrics_buffer(env, observer);
    jsize bufferSize = env->GetArrayLength(reinterpret_cast<jarray>(buffer));
    LOG_ALWAYS_FATAL_IF(bufferSize != kBufferSize,
            "Mismatched Java/Native FrameMetrics data format.");

    jobject messageQueueLocal = env->GetObjectField(
            observer, gFrameMetricsObserverClassInfo.messageQueue);
    mMessageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueLocal);
    LOG_ALWAYS_FATAL_IF(mMessageQueue == nullptr, "message queue not available");

    mMessageHandler = new NotifyHandler(mVm, this);
    LOG_ALWAYS_FATAL_IF(mMessageHandler == nullptr,
            "OOM: unable to allocate NotifyHandler");
}

FrameMetricsObserverProxy::~FrameMetricsObserverProxy() {
    JNIEnv* env = getenv(mVm);
    env->DeleteWeakGlobalRef(mObserverWeak);
}

bool FrameMetricsObserverProxy::getNextBuffer(JNIEnv* env, jlongArray sink, int* dropCount) {
    FrameMetricsNotification& elem = mRingBuffer[mNextInQueue];

    if (elem.hasData.load()) {
        env->SetLongArrayRegion(sink, 0, kBufferSize, elem.buffer);
        *dropCount = elem.dropCount;
        mNextInQueue = (mNextInQueue + 1) % kRingSize;
        elem.hasData = false;
        return true;
    }

    return false;
}

void FrameMetricsObserverProxy::notify(const int64_t* stats) {
    FrameMetricsNotification& elem = mRingBuffer[mNextFree];

    if (!elem.hasData.load()) {
        memcpy(elem.buffer, stats, kBufferSize * sizeof(stats[0]));

        elem.dropCount = mDroppedReports;
        mDroppedReports = 0;

        incStrong(nullptr);
        mNextFree = (mNextFree + 1) % kRingSize;
        elem.hasData = true;

        mMessageQueue->getLooper()->sendMessage(mMessageHandler, mMessage);
    } else {
        mDroppedReports++;
    }
}

int register_android_view_FrameMetricsObserver(JNIEnv* env) {
    jclass observerClass = FindClassOrDie(env, "android/view/FrameMetricsObserver");
    gFrameMetricsObserverClassInfo.frameMetrics = GetFieldIDOrDie(
            env, observerClass, "mFrameMetrics", "Landroid/view/FrameMetrics;");
    gFrameMetricsObserverClassInfo.messageQueue = GetFieldIDOrDie(
            env, observerClass, "mMessageQueue", "Landroid/os/MessageQueue;");
    gFrameMetricsObserverClassInfo.callback = GetMethodIDOrDie(
            env, observerClass, "notifyDataAvailable", "(I)V");

    jclass metricsClass = FindClassOrDie(env, "android/view/FrameMetrics");
    gFrameMetricsObserverClassInfo.timingDataBuffer = GetFieldIDOrDie(
            env, metricsClass, "mTimingData", "[J");
    return JNI_OK;
}

} // namespace android