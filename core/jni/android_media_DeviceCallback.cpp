/*
 * Copyright (C) 2015 The Android Open Source Project
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

#define LOG_TAG "AudioDeviceCallback-JNI"

#include <utils/Log.h>
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"
#include <media/AudioSystem.h>

#include "android_media_DeviceCallback.h"


// ----------------------------------------------------------------------------

using namespace android;

JNIDeviceCallback::JNIDeviceCallback(JNIEnv* env, jobject thiz, jobject weak_thiz,
                                     jmethodID postEventFromNative)
{

    // Hold onto the AudioTrack/AudioRecord class for use in calling the static method
    // that posts events to the application thread.
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);

    // We use a weak reference so the AudioTrack/AudioRecord object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    mObject  = env->NewGlobalRef(weak_thiz);

    mPostEventFromNative = postEventFromNative;
}

JNIDeviceCallback::~JNIDeviceCallback()
{
    // remove global references
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }
    env->DeleteGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
}

void JNIDeviceCallback::onAudioDeviceUpdate(audio_io_handle_t audioIo,
                                            const DeviceIdVector& deviceIds) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }

    ALOGV("%s audioIo %d deviceIds %s", __FUNCTION__, audioIo, toString(deviceIds).c_str());
    // Java should query the new device ids once it gets the event.
    // TODO(b/378505346): Pass the deviceIds to Java to avoid race conditions.
    env->CallStaticVoidMethod(mClass, mPostEventFromNative, mObject,
                              AUDIO_NATIVE_EVENT_ROUTING_CHANGE, 0 /*arg1*/, 0 /*arg2*/,
                              NULL /*obj*/);
    if (env->ExceptionCheck()) {
        ALOGW("An exception occurred while notifying an event.");
        env->ExceptionClear();
    }
}
