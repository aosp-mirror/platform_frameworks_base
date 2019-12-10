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

#include "core_jni_helpers.h"
#include "android_media_AudioDeviceAddress.h"
#include "android_media_AudioErrors.h"

#include <media/AudioDeviceTypeAddr.h>

using namespace android;

static jclass gAudioDeviceAddressClass;
static jmethodID gAudioDeviceAddressCstor;

namespace android {

jint createAudioDeviceAddressFromNative(
        JNIEnv *env, jobject *jAudioDeviceAddress,
        const AudioDeviceTypeAddr *devTypeAddr) {
    jint jStatus = (jint)AUDIO_JAVA_SUCCESS;
    jint jNativeType = (jint)devTypeAddr->mType;
    ScopedLocalRef<jstring> jAddress(env, env->NewStringUTF(devTypeAddr->mAddress.data()));

    *jAudioDeviceAddress = env->NewObject(gAudioDeviceAddressClass, gAudioDeviceAddressCstor,
            jNativeType, jAddress.get());

    return jStatus;
}

}

int register_android_media_AudioDeviceAddress(JNIEnv *env)
{
    jclass audioDeviceTypeAddressClass = FindClassOrDie(env, "android/media/AudioDeviceAddress");
    gAudioDeviceAddressClass = MakeGlobalRefOrDie(env, audioDeviceTypeAddressClass);
    gAudioDeviceAddressCstor = GetMethodIDOrDie(env, audioDeviceTypeAddressClass, "<init>",
                                                "(ILjava/lang/String;)V");

    return 0;
}
