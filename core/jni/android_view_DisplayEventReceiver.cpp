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

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <gui/DisplayEventDispatcher.h>
#include <inttypes.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <utils/Log.h>
#include <utils/Looper.h>
#include <utils/threads.h>

#include "android_os_MessageQueue.h"
#include "core_jni_helpers.h"

namespace android {

static struct {
    jclass clazz;

    jmethodID dispatchVsync;
    jmethodID dispatchHotplug;
    jmethodID dispatchHotplugConnectionError;
    jmethodID dispatchModeChanged;
    jmethodID dispatchFrameRateOverrides;

    struct {
        jclass clazz;
        jmethodID init;
    } frameRateOverrideClassInfo;

    struct {
        jclass clazz;

        jmethodID init;

        jfieldID vsyncId;
        jfieldID expectedPresentationTime;
        jfieldID deadline;
    } frameTimelineClassInfo;

    struct {
        jclass clazz;

        jmethodID init;

        jfieldID frameInterval;
        jfieldID preferredFrameTimelineIndex;
        jfieldID frameTimelinesLength;
        jfieldID frameTimelines;
    } vsyncEventDataClassInfo;

} gDisplayEventReceiverClassInfo;


class NativeDisplayEventReceiver : public DisplayEventDispatcher {
public:
    NativeDisplayEventReceiver(JNIEnv* env, jobject receiverWeak, jobject vsyncEventDataWeak,
                               const sp<MessageQueue>& messageQueue, jint vsyncSource,
                               jint eventRegistration, jlong layerHandle);

    void dispose();

protected:
    virtual ~NativeDisplayEventReceiver();

private:
    jobject mReceiverWeakGlobal;
    jobject mVsyncEventDataWeakGlobal;
    sp<MessageQueue> mMessageQueue;

    void dispatchVsync(nsecs_t timestamp, PhysicalDisplayId displayId, uint32_t count,
                       VsyncEventData vsyncEventData) override;
    void dispatchHotplug(nsecs_t timestamp, PhysicalDisplayId displayId, bool connected) override;
    void dispatchHotplugConnectionError(nsecs_t timestamp, int errorCode) override;
    void dispatchModeChanged(nsecs_t timestamp, PhysicalDisplayId displayId, int32_t modeId,
                             nsecs_t renderPeriod) override;
    void dispatchFrameRateOverrides(nsecs_t timestamp, PhysicalDisplayId displayId,
                                    std::vector<FrameRateOverride> overrides) override;
    void dispatchNullEvent(nsecs_t timestamp, PhysicalDisplayId displayId) override {}
};

NativeDisplayEventReceiver::NativeDisplayEventReceiver(JNIEnv* env, jobject receiverWeak,
                                                       jobject vsyncEventDataWeak,
                                                       const sp<MessageQueue>& messageQueue,
                                                       jint vsyncSource, jint eventRegistration,
                                                       jlong layerHandle)
      : DisplayEventDispatcher(messageQueue->getLooper(),
                               static_cast<gui::ISurfaceComposer::VsyncSource>(vsyncSource),
                               static_cast<gui::ISurfaceComposer::EventRegistration>(
                                       eventRegistration),
                               layerHandle != 0 ? sp<IBinder>::fromExisting(
                                                          reinterpret_cast<IBinder*>(layerHandle))
                                                : nullptr),
        mReceiverWeakGlobal(env->NewGlobalRef(receiverWeak)),
        mVsyncEventDataWeakGlobal(env->NewGlobalRef(vsyncEventDataWeak)),
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
                              env->NewObjectArray(vsyncEventData.frameTimelinesLength,
                                                  gDisplayEventReceiverClassInfo
                                                          .frameTimelineClassInfo.clazz,
                                                  /*initial element*/ NULL));
    if (!frameTimelineObjs.get() || env->ExceptionCheck()) {
        ALOGW("%s: Failed to create FrameTimeline array", __func__);
        LOGW_EX(env);
        env->ExceptionClear();
        return NULL;
    }
    for (size_t i = 0; i < vsyncEventData.frameTimelinesLength; i++) {
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
        if (!frameTimelineObj.get() || env->ExceptionCheck()) {
            ALOGW("%s: Failed to create FrameTimeline object", __func__);
            LOGW_EX(env);
            env->ExceptionClear();
            return NULL;
        }
        env->SetObjectArrayElement(frameTimelineObjs.get(), i, frameTimelineObj.get());
    }
    return env->NewObject(gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.clazz,
                          gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.init,
                          frameTimelineObjs.get(), vsyncEventData.preferredFrameTimelineIndex,
                          vsyncEventData.frameTimelinesLength, vsyncEventData.frameInterval);
}

void NativeDisplayEventReceiver::dispatchVsync(nsecs_t timestamp, PhysicalDisplayId displayId,
                                               uint32_t count, VsyncEventData vsyncEventData) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, GetReferent(env, mReceiverWeakGlobal));
    ScopedLocalRef<jobject> vsyncEventDataObj(env, GetReferent(env, mVsyncEventDataWeakGlobal));
    if (receiverObj.get() && vsyncEventDataObj.get()) {
        ALOGV("receiver %p ~ Invoking vsync handler.", this);

        env->SetIntField(vsyncEventDataObj.get(),
                         gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo
                                 .preferredFrameTimelineIndex,
                         vsyncEventData.preferredFrameTimelineIndex);
        env->SetIntField(vsyncEventDataObj.get(),
                         gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo
                                 .frameTimelinesLength,
                         vsyncEventData.frameTimelinesLength);
        env->SetLongField(vsyncEventDataObj.get(),
                          gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.frameInterval,
                          vsyncEventData.frameInterval);

        ScopedLocalRef<jobjectArray>
                frameTimelinesObj(env,
                                  reinterpret_cast<jobjectArray>(
                                          env->GetObjectField(vsyncEventDataObj.get(),
                                                              gDisplayEventReceiverClassInfo
                                                                      .vsyncEventDataClassInfo
                                                                      .frameTimelines)));
        for (size_t i = 0; i < vsyncEventData.frameTimelinesLength; i++) {
            VsyncEventData::FrameTimeline& frameTimeline = vsyncEventData.frameTimelines[i];
            ScopedLocalRef<jobject>
                    frameTimelineObj(env, env->GetObjectArrayElement(frameTimelinesObj.get(), i));
            env->SetLongField(frameTimelineObj.get(),
                              gDisplayEventReceiverClassInfo.frameTimelineClassInfo.vsyncId,
                              frameTimeline.vsyncId);
            env->SetLongField(frameTimelineObj.get(),
                              gDisplayEventReceiverClassInfo.frameTimelineClassInfo
                                      .expectedPresentationTime,
                              frameTimeline.expectedPresentationTime);
            env->SetLongField(frameTimelineObj.get(),
                              gDisplayEventReceiverClassInfo.frameTimelineClassInfo.deadline,
                              frameTimeline.deadlineTimestamp);
        }

        env->CallVoidMethod(receiverObj.get(), gDisplayEventReceiverClassInfo.dispatchVsync,
                            timestamp, displayId.value, count);
        ALOGV("receiver %p ~ Returned from vsync handler.", this);
    }

    mMessageQueue->raiseAndClearException(env, "dispatchVsync");
}

void NativeDisplayEventReceiver::dispatchHotplug(nsecs_t timestamp, PhysicalDisplayId displayId,
                                                 bool connected) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, GetReferent(env, mReceiverWeakGlobal));
    if (receiverObj.get()) {
        ALOGV("receiver %p ~ Invoking hotplug handler.", this);
        env->CallVoidMethod(receiverObj.get(), gDisplayEventReceiverClassInfo.dispatchHotplug,
                            timestamp, displayId.value, connected);
        ALOGV("receiver %p ~ Returned from hotplug handler.", this);
    }

    mMessageQueue->raiseAndClearException(env, "dispatchHotplug");
}

void NativeDisplayEventReceiver::dispatchHotplugConnectionError(nsecs_t timestamp,
                                                                int connectionError) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, GetReferent(env, mReceiverWeakGlobal));
    if (receiverObj.get()) {
        ALOGV("receiver %p ~ Invoking hotplug dispatchHotplugConnectionError handler.", this);
        env->CallVoidMethod(receiverObj.get(),
                            gDisplayEventReceiverClassInfo.dispatchHotplugConnectionError,
                            timestamp, connectionError);
        ALOGV("receiver %p ~ Returned from hotplug dispatchHotplugConnectionError handler.", this);
    }

    mMessageQueue->raiseAndClearException(env, "dispatchHotplugConnectionError");
}

void NativeDisplayEventReceiver::dispatchModeChanged(nsecs_t timestamp, PhysicalDisplayId displayId,
                                                     int32_t modeId, nsecs_t renderPeriod) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, GetReferent(env, mReceiverWeakGlobal));
    if (receiverObj.get()) {
        ALOGV("receiver %p ~ Invoking mode changed handler.", this);
        env->CallVoidMethod(receiverObj.get(), gDisplayEventReceiverClassInfo.dispatchModeChanged,
                            timestamp, displayId.value, modeId, renderPeriod);
        ALOGV("receiver %p ~ Returned from mode changed handler.", this);
    }

    mMessageQueue->raiseAndClearException(env, "dispatchModeChanged");
}

void NativeDisplayEventReceiver::dispatchFrameRateOverrides(
        nsecs_t timestamp, PhysicalDisplayId displayId, std::vector<FrameRateOverride> overrides) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> receiverObj(env, GetReferent(env, mReceiverWeakGlobal));
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

static jlong nativeInit(JNIEnv* env, jclass clazz, jobject receiverWeak, jobject vsyncEventDataWeak,
                        jobject messageQueueObj, jint vsyncSource, jint eventRegistration,
                        jlong layerHandle) {
    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueObj);
    if (messageQueue == NULL) {
        jniThrowRuntimeException(env, "MessageQueue is not initialized.");
        return 0;
    }

    sp<NativeDisplayEventReceiver> receiver =
            new NativeDisplayEventReceiver(env, receiverWeak, vsyncEventDataWeak, messageQueue,
                                           vsyncSource, eventRegistration, layerHandle);
    status_t status = receiver->initialize();
    if (status) {
        String8 message;
        message.appendFormat("Failed to initialize display event receiver.  status=%d", status);
        jniThrowRuntimeException(env, message.c_str());
        return 0;
    }

    receiver->incStrong(gDisplayEventReceiverClassInfo.clazz); // retain a reference for the object
    return reinterpret_cast<jlong>(receiver.get());
}

static void release(NativeDisplayEventReceiver* receiver) {
    receiver->dispose();
    receiver->decStrong(gDisplayEventReceiverClassInfo.clazz); // drop reference held by the object
}

static jlong nativeGetDisplayEventReceiverFinalizer(JNIEnv*, jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&release));
}

static void nativeScheduleVsync(JNIEnv* env, jclass clazz, jlong receiverPtr) {
    sp<NativeDisplayEventReceiver> receiver =
            reinterpret_cast<NativeDisplayEventReceiver*>(receiverPtr);
    status_t status = receiver->scheduleVsync();
    if (status) {
        String8 message;
        message.appendFormat("Failed to schedule next vertical sync pulse.  status=%d", status);
        jniThrowRuntimeException(env, message.c_str());
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
        {"nativeInit",
         "(Ljava/lang/ref/WeakReference;Ljava/lang/ref/WeakReference;Landroid/os/"
         "MessageQueue;IIJ)J",
         (void*)nativeInit},
        {"nativeGetDisplayEventReceiverFinalizer", "()J",
         (void*)nativeGetDisplayEventReceiverFinalizer},
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
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.clazz, "dispatchVsync", "(JJI)V");
    gDisplayEventReceiverClassInfo.dispatchHotplug =
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.clazz, "dispatchHotplug",
                             "(JJZ)V");
    gDisplayEventReceiverClassInfo.dispatchHotplugConnectionError =
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.clazz,
                             "dispatchHotplugConnectionError", "(JI)V");
    gDisplayEventReceiverClassInfo.dispatchModeChanged =
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.clazz, "dispatchModeChanged",
                             "(JJIJ)V");
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
    gDisplayEventReceiverClassInfo.frameTimelineClassInfo.vsyncId =
            GetFieldIDOrDie(env, gDisplayEventReceiverClassInfo.frameTimelineClassInfo.clazz,
                            "vsyncId", "J");
    gDisplayEventReceiverClassInfo.frameTimelineClassInfo.expectedPresentationTime =
            GetFieldIDOrDie(env, gDisplayEventReceiverClassInfo.frameTimelineClassInfo.clazz,
                            "expectedPresentationTime", "J");
    gDisplayEventReceiverClassInfo.frameTimelineClassInfo.deadline =
            GetFieldIDOrDie(env, gDisplayEventReceiverClassInfo.frameTimelineClassInfo.clazz,
                            "deadline", "J");

    jclass vsyncEventDataClazz =
            FindClassOrDie(env, "android/view/DisplayEventReceiver$VsyncEventData");
    gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.clazz =
            MakeGlobalRefOrDie(env, vsyncEventDataClazz);
    gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.init =
            GetMethodIDOrDie(env, gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.clazz,
                             "<init>",
                             "([Landroid/view/"
                             "DisplayEventReceiver$VsyncEventData$FrameTimeline;IIJ)V");

    gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.preferredFrameTimelineIndex =
            GetFieldIDOrDie(env, gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.clazz,
                            "preferredFrameTimelineIndex", "I");
    gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.frameTimelinesLength =
            GetFieldIDOrDie(env, gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.clazz,
                            "frameTimelinesLength", "I");
    gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.frameInterval =
            GetFieldIDOrDie(env, gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.clazz,
                            "frameInterval", "J");
    gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.frameTimelines =
            GetFieldIDOrDie(env, gDisplayEventReceiverClassInfo.vsyncEventDataClassInfo.clazz,
                            "frameTimelines",
                            "[Landroid/view/DisplayEventReceiver$VsyncEventData$FrameTimeline;");

    return res;
}

} // namespace android
