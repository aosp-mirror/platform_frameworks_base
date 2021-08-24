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

#include "android_media_AudioDeviceAttributes.h"
#include "android_media_AudioErrors.h"
#include "core_jni_helpers.h"

#include <media/AudioDeviceTypeAddr.h>

using namespace android;

static jclass gAudioDeviceAttributesClass;
static jmethodID gAudioDeviceAttributesCstor;
static struct {
    jfieldID mAddress;
    jfieldID mNativeType;
    // other fields unused by JNI
} gAudioDeviceAttributesFields;

namespace android {

jint createAudioDeviceAttributesFromNative(JNIEnv *env, jobject *jAudioDeviceAttributes,
                                 const AudioDeviceTypeAddr *devTypeAddr) {
    jint jStatus = (jint)AUDIO_JAVA_SUCCESS;
    jint jNativeType = (jint)devTypeAddr->mType;
    ScopedLocalRef<jstring> jAddress(env, env->NewStringUTF(devTypeAddr->getAddress()));

    *jAudioDeviceAttributes =
        env->NewObject(gAudioDeviceAttributesClass, gAudioDeviceAttributesCstor,
                       jNativeType, jAddress.get());

    return jStatus;
}

jint createAudioDeviceTypeAddrFromJava(JNIEnv *env, AudioDeviceTypeAddr *devTypeAddr,
                                       const jobject jAudioDeviceAttributes) {
    devTypeAddr->mType = (audio_devices_t)env->GetIntField(jAudioDeviceAttributes,
                         gAudioDeviceAttributesFields.mNativeType);

    jstring jAddress = (jstring)env->GetObjectField(jAudioDeviceAttributes,
                       gAudioDeviceAttributesFields.mAddress);
    devTypeAddr->setAddress(ScopedUtfChars(env, jAddress).c_str());

    return AUDIO_JAVA_SUCCESS;
}

} // namespace android

int register_android_media_AudioDeviceAttributes(JNIEnv *env) {
    jclass audioDeviceTypeAddressClass =
            FindClassOrDie(env, "android/media/AudioDeviceAttributes");
    gAudioDeviceAttributesClass = MakeGlobalRefOrDie(env, audioDeviceTypeAddressClass);
    gAudioDeviceAttributesCstor =
            GetMethodIDOrDie(env, audioDeviceTypeAddressClass, "<init>", "(ILjava/lang/String;)V");

    gAudioDeviceAttributesFields.mNativeType =
            GetFieldIDOrDie(env, gAudioDeviceAttributesClass, "mNativeType", "I");
    gAudioDeviceAttributesFields.mAddress =
            GetFieldIDOrDie(env, gAudioDeviceAttributesClass, "mAddress", "Ljava/lang/String;");

    return 0;
}
