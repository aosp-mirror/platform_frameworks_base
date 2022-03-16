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

#include <nativehelper/JNIHelp.h>

#include <inttypes.h>

#include <android_runtime/AndroidRuntime.h>
#include <gui/DisplayEventDispatcher.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/threads.h>
#include "android_os_MessageQueue.h"

#include <nativehelper/ScopedLocalRef.h>

#include "core_jni_helpers.h"

namespace android {

static struct {
    jclass clazz;

    jmethodID dispatchVsync;
    jmethodID dispatchHotplug;
    jmethodID dispatchModeChanged;
    jmethodID dispatchFrameRateOverrides;

    struct {
        jclass clazz;
        jmethodID init;
    } frameRateOverrideClassInfo;

    struct {
        jclass clazz;
        jmethodID init;
    } frameTimelineClassInfo;

    struct {
        jclass clazz;
        jmethodID init;
    } vsyncEventDataClassInfo;

} gDisplayEventReceiverClassInfo;


class NativeDisplayEventReceiver : public DisplayEventDispatcher {
public:
    NativeDisplayEventReceiver(JNIEnv* env, jobject receiverWeak,
                               const sp<MessageQueue>& messageQueue, jint vsyncSource,
                               jint eventRegistration);

    void dispose();

protected:
    virtual ~NativeDisplayEventReceiver();

private:
    jobject mReceiverWeakGlobal;
    sp<MessageQueue> mMessageQueue;

    void dispatchVsync(nsecs_t timestamp, PhysicalDisplayId displayId, uint32_t count,
                       VsyncEventData vsyncEventData) override;
    void dispatchHotplug(nsecs_t timestamp, PhysicalDisplayId displayId, bool connected) override;
    void dispatchModeChanged(nsecs_t timestamp, PhysicalDisplayId displayId, int32_t modeId,
                             nsecs_t vsyncPeriod) override;
    void dispatchFrameRateOverrides(nsecs_t timestamp, PhysicalDisplayId displayId,
                                    std::vector<FrameRateOverride> overrides) override;
    void dispatchNullEvent(nsecs_t timestamp, PhysicalDisplayId displayId) override {}
};

NativeDisplayEventReceiver::NativeDisplayEventReceiver(JNIEnv* env, jobject receiverWeak,
                                                       const sp<MessageQueue>& messageQueue,
                                                       jint vsyncSource, jint eventRegistration)
      : DisplayEventDispatcher(messageQueue->getLooper(),
                               static_cast<gui::ISurfaceComposer::VsyncSource>(vsyncSource),
                               static_cast<gui::ISurfaceComposer::EventRegistration>(
                                       eventRegistration)),
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

static jobject createJavaVsyncEventData(JNIEnv* env, VsyncEventData vsyncEventData) {
    ScopedLocalRef<jobjectArray>
            frameTimelineObjs(env,
                              env->NewObjectArray(VsyncEventData::kFrameTimelinesLength,
                                                  gDisplayEventReceiverClassInfo
                                                          .frameTimelineClassInfo.clazz,
                                                  /*initial element*/ NULL));
    for (int i = 0; i < VsyncEventData::kFrameTimelinesLength; i++) {
        VsyncEventData::FrameTimeline frameTimeline = vsyncEventData.frameTimelines[i];
        ScopedLocalRef<jobject>
                frameTimelineObj(env,
                                 env->NewObject(gDisplayEventReceiverClassInfo
                                                        .frameTimelineClassInfo.clazz,
                                                gDisplayEventReceiverClassInfo
                                                        .frameTimelineClassInfo.init,
                                                frameTimeline.vsyncId,
                                                frameTimeline.expectedPresentationTime,
                                                frameTimeline.deadlineTimestamp));
        env->SetObjectArrayElement(frameTimelineObjs.get(), i, frameTimelineObj.get());
    }
    return env->NewObject(gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.clazz,
                          gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.init,
                          frameTimelineObjs.get(), vsyncEventData.preferredFrameTimelineIndex,
                          vsyncEventData.frameInterval);
}

void NativeDisplayEventReceiver::dispatchVsync(nsecs_t timestamp, PhysicalDisplayId displayId,
                                               uint32_t count, VsyncEventData vsyncEventData) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, jniGetReferent(env, mReceiverWeakGlobal));
    if (receiverObj.get()) {
        ALOGV("receiver %p ~ Invoking vsync handler.", this);

        jobject javaVsyncEventData = createJavaVsyncEventData(env, vsyncEventData);
        env->CallVoidMethod(receiverObj.get(), gDisplayEventReceiverClassInfo.dispatchVsync,
                            timestamp, displayId.value, count, javaVsyncEventData);
        ALOGV("receiver %p ~ Returned from vsync handler.", this);
    }

    mMessageQueue->raiseAndClearException(env, "dispatchVsync");
}

void NativeDisplayEventReceiver::dispatchHotplug(nsecs_t timestamp, PhysicalDisplayId displayId,
                                                 bool connected) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, jniGetReferent(env, mReceiverWeakGlobal));
    if (receiverObj.get()) {
        ALOGV("receiver %p ~ Invoking hotplug handler.", this);
        env->CallVoidMethod(receiverObj.get(), gDisplayEventReceiverClassInfo.dispatchHotplug,
                            timestamp, displayId.value, connected);
        ALOGV("receiver %p ~ Returned from hotplug handler.", this);
    }

    mMessageQueue->raiseAndClearException(env, "dispatchHotplug");
}

void NativeDisplayEventReceiver::dispatchModeChanged(nsecs_t timestamp, PhysicalDisplayId displayId,
                                                     int32_t modeId, nsecs_t) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, jniGetReferent(env, mReceiverWeakGlobal));
    if (receiverObj.get()) {
        ALOGV("receiver %p ~ Invoking mode changed handler.", this);
        env->CallVoidMethod(receiverObj.get(), gDisplayEventReceiverClassInfo.dispatchModeChanged,
                            timestamp, displayId.value, modeId);
        ALOGV("receiver %p ~ Returned from mode changed handler.", this);
    }

    mMessageQueue->raiseAndClearException(env, "dispatchModeChanged");
}

void NativeDisplayEventReceiver::dispatchFrameRateOverrides(
        nsecs_t timestamp, PhysicalDisplayId displayId, std::vector<FrameRateOverride> overrides) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, jniGetReferent(env, mReceiverWeakGlobal));
    if (receiverObj.get()) {
        ALOGV("receiver %p ~ Invoking FrameRateOverride handler.", this);
        const auto frameRateOverrideClass =
                gDisplayEventReceiverClassInfo.frameRateOverrideClassInfo.clazz;
        const auto frameRateOverrideInit =
                gDisplayEventReceiverClassInfo.frameRateOverrideClassInfo.init;
        auto frameRateOverrideInitObject =
                env->NewObject(frameRateOverrideClass, frameRateOverrideInit, 0, 0);
        auto frameRateOverrideArray = env->NewObjectArray(overrides.size(), frameRateOverrideClass,
                                                          frameRateOverrideInitObject);
        for (size_t i = 0; i < overrides.size(); i++) {
            auto FrameRateOverrideObject =
                    env->NewObject(frameRateOverrideClass, frameRateOverrideInit, overrides[i].uid,
                                   overrides[i].frameRateHz);
            env->SetObjectArrayElement(frameRateOverrideArray, i, FrameRateOverrideObject);
        }

        env->CallVoidMethod(receiverObj.get(),
                            gDisplayEventReceiverClassInfo.dispatchFrameRateOverrides, timestamp,
                            displayId.value, frameRateOverrideArray);
        ALOGV("receiver %p ~ Returned from FrameRateOverride handler.", this);
    }

    mMessageQueue->raiseAndClearException(env, "dispatchModeChanged");
}

static jlong nativeInit(JNIEnv* env, jclass clazz, jobject receiverWeak, jobject messageQueueObj,
                        jint vsyncSource, jint eventRegistration) {
    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueObj);
    if (messageQueue == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<NativeDisplayEventReceiver> receiver =
            new NativeDisplayEventReceiver(env, receiverWeak, messageQueue, vsyncSource,
                                           eventRegistration);
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

static jobject nativeGetLatestVsyncEventData(JNIEnv* env, jclass clazz, jlong receiverPtr) {
    sp<NativeDisplayEventReceiver> receiver =
            reinterpret_cast<NativeDisplayEventReceiver*>(receiverPtr);
    gui::ParcelableVsyncEventData parcelableVsyncEventData;
    status_t status = receiver->getLatestVsyncEventData(&parcelableVsyncEventData);
    if (status) {
        ALOGW("Failed to get latest vsync event data from surface flinger");
        return NULL;
    }
    return createJavaVsyncEventData(env, parcelableVsyncEventData.vsync);
}

static const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativeInit", "(Ljava/lang/ref/WeakReference;Landroid/os/MessageQueue;II)J",
         (void*)nativeInit},
        {"nativeDispose", "(J)V", (void*)nativeDispose},
        // @FastNative
        {"nativeScheduleVsync", "(J)V", (void*)nativeScheduleVsync},
        {"nativeGetLatestVsyncEventData", "(J)Landroid/view/DisplayEventReceiver$VsyncEventData;",
         (void*)nativeGetLatestVsyncEventData}};

int register_android_view_DisplayEventReceiver(JNIEnv* env) {
    int res = RegisterMethodsOrDie(env, "android/view/DisplayEventReceiver", gMethods,
                                   NELEM(gMethods));

    jclass clazz = FindClassOrDie(env, "android/view/DisplayEventReceiver");
    gDisplayEventReceiverClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);

    gDisplayEventReceiverClassInfo.dispatchVsync =
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.clazz, "dispatchVsync",
                             "(JJILandroid/view/DisplayEventReceiver$VsyncEventData;)V");
    gDisplayEventReceiverClassInfo.dispatchHotplug = GetMethodIDOrDie(env,
            gDisplayEventReceiverClassInfo.clazz, "dispatchHotplug", "(JJZ)V");
    gDisplayEventReceiverClassInfo.dispatchModeChanged =
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.clazz, "dispatchModeChanged",
                             "(JJI)V");
    gDisplayEventReceiverClassInfo.dispatchFrameRateOverrides =
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.clazz,
                             "dispatchFrameRateOverrides",
                             "(JJ[Landroid/view/DisplayEventReceiver$FrameRateOverride;)V");

    jclass frameRateOverrideClazz =
            FindClassOrDie(env, "android/view/DisplayEventReceiver$FrameRateOverride");
    gDisplayEventReceiverClassInfo.frameRateOverrideClassInfo.clazz =
            MakeGlobalRefOrDie(env, frameRateOverrideClazz);
    gDisplayEventReceiverClassInfo.frameRateOverrideClassInfo.init =
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.frameRateOverrideClassInfo.clazz,
                             "<init>", "(IF)V");

    jclass frameTimelineClazz =
            FindClassOrDie(env, "android/view/DisplayEventReceiver$VsyncEventData$FrameTimeline");
    gDisplayEventReceiverClassInfo.frameTimelineClassInfo.clazz =
            MakeGlobalRefOrDie(env, frameTimelineClazz);
    gDisplayEventReceiverClassInfo.frameTimelineClassInfo.init =
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.frameTimelineClassInfo.clazz,
                             "<init>", "(JJJ)V");

    jclass vsyncEventDataClazz =
            FindClassOrDie(env, "android/view/DisplayEventReceiver$VsyncEventData");
    gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.clazz =
            MakeGlobalRefOrDie(env, vsyncEventDataClazz);
    gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.init =
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.clazz,
                             "<init>",
                             "([Landroid/view/"
                             "DisplayEventReceiver$VsyncEventData$FrameTimeline;IJ)V");

    return res;
}

} // namespace android
