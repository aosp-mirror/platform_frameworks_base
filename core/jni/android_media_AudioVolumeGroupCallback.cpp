/*
 * Copyright (C) 2018 The Android Open Source Project
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

//#define LOG_NDEBUG 0

#define LOG_TAG "AudioVolumeGroupCallback-JNI"

#include <utils/Log.h>
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

#include "android_media_AudioVolumeGroupCallback.h"


// ----------------------------------------------------------------------------
using namespace android;

static const char* const kAudioVolumeGroupChangeHandlerClassPathName =
        "android/media/audiopolicy/AudioVolumeGroupChangeHandler";

static struct {
    jfieldID    mJniCallback;
} gAudioVolumeGroupChangeHandlerFields;

static struct {
    jmethodID    postEventFromNative;
} gAudioVolumeGroupChangeHandlerMethods;

static Mutex gLock;

JNIAudioVolumeGroupCallback::JNIAudioVolumeGroupCallback(JNIEnv* env,
                                                         jobject thiz,
                                                         jobject weak_thiz)
{
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        ALOGE("Can't find class %s", kAudioVolumeGroupChangeHandlerClassPathName);
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);

    // We use a weak reference so the AudioVolumeGroupChangeHandler object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    mObject  = env->NewGlobalRef(weak_thiz);
}

JNIAudioVolumeGroupCallback::~JNIAudioVolumeGroupCallback()
{
    // remove global references
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }
    env->DeleteGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
}

void JNIAudioVolumeGroupCallback::onAudioVolumeGroupChanged(volume_group_t group, int flags)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }
    ALOGV("%s volume group id %d", __FUNCTION__, group);
    env->CallStaticVoidMethod(mClass,
                              gAudioVolumeGroupChangeHandlerMethods.postEventFromNative,
                              mObject,
                              AUDIOVOLUMEGROUP_EVENT_VOLUME_CHANGED, group, flags, NULL);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        env->ExceptionClear();
    }
}

void JNIAudioVolumeGroupCallback::onServiceDied()
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }
    env->CallStaticVoidMethod(mClass,
                              gAudioVolumeGroupChangeHandlerMethods.postEventFromNative,
                              mObject,
                              AUDIOVOLUMEGROUP_EVENT_SERVICE_DIED, 0, 0, NULL);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        env->ExceptionClear();
    }
}

static
sp<JNIAudioVolumeGroupCallback> setJniCallback(JNIEnv* env,
                                               jobject thiz,
                                               const sp<JNIAudioVolumeGroupCallback>& callback)
{
    Mutex::Autolock l(gLock);
    sp<JNIAudioVolumeGroupCallback> old = (JNIAudioVolumeGroupCallback*)env->GetLongField(
                thiz, gAudioVolumeGroupChangeHandlerFields.mJniCallback);
    if (callback.get()) {
        callback->incStrong((void*)setJniCallback);
    }
    if (old != 0) {
        old->decStrong((void*)setJniCallback);
    }
    env->SetLongField(thiz, gAudioVolumeGroupChangeHandlerFields.mJniCallback,
                      (jlong)callback.get());
    return old;
}

static void
android_media_AudioVolumeGroupChangeHandler_eventHandlerSetup(JNIEnv *env,
                                                              jobject thiz,
                                                              jobject weak_this)
{
    ALOGV("%s", __FUNCTION__);
    sp<JNIAudioVolumeGroupCallback> callback =
            new JNIAudioVolumeGroupCallback(env, thiz, weak_this);

    if (AudioSystem::addAudioVolumeGroupCallback(callback) == NO_ERROR) {
        setJniCallback(env, thiz, callback);
    }
}

static void
android_media_AudioVolumeGroupChangeHandler_eventHandlerFinalize(JNIEnv *env, jobject thiz)
{
    ALOGV("%s", __FUNCTION__);
    sp<JNIAudioVolumeGroupCallback> callback = setJniCallback(env, thiz, 0);
    if (callback != 0) {
        AudioSystem::removeAudioVolumeGroupCallback(callback);
    }
}

/*
 * JNI registration.
 */
static const JNINativeMethod gMethods[] = {
    {"native_setup", "(Ljava/lang/Object;)V",
        (void *)android_media_AudioVolumeGroupChangeHandler_eventHandlerSetup},
    {"native_finalize",  "()V",
        (void *)android_media_AudioVolumeGroupChangeHandler_eventHandlerFinalize},
};

int register_android_media_AudioVolumeGroupChangeHandler(JNIEnv *env)
{
    jclass audioVolumeGroupChangeHandlerClass =
            FindClassOrDie(env, kAudioVolumeGroupChangeHandlerClassPathName);
    gAudioVolumeGroupChangeHandlerMethods.postEventFromNative =
            GetStaticMethodIDOrDie(env, audioVolumeGroupChangeHandlerClass, "postEventFromNative",
                                   "(Ljava/lang/Object;IIILjava/lang/Object;)V");

    gAudioVolumeGroupChangeHandlerFields.mJniCallback =
            GetFieldIDOrDie(env, audioVolumeGroupChangeHandlerClass, "mJniCallback", "J");

    env->DeleteLocalRef(audioVolumeGroupChangeHandlerClass);

    return RegisterMethodsOrDie(env,
                                kAudioVolumeGroupChangeHandlerClassPathName,
                                gMethods,
                                NELEM(gMethods));
}

