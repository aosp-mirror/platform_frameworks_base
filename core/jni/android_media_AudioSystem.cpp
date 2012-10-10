/*
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "AudioSystem"
#include <utils/Log.h>

#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <math.h>

#include <jni.h>
#include <JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <media/AudioSystem.h>

#include <system/audio.h>
#include <system/audio_policy.h>

// ----------------------------------------------------------------------------

using namespace android;

static const char* const kClassPathName = "android/media/AudioSystem";

enum AudioError {
    kAudioStatusOk = 0,
    kAudioStatusError = 1,
    kAudioStatusMediaServerDied = 100
};

static int check_AudioSystem_Command(status_t status)
{
    if (status == NO_ERROR) {
        return kAudioStatusOk;
    } else {
        return kAudioStatusError;
    }
}

static int
android_media_AudioSystem_muteMicrophone(JNIEnv *env, jobject thiz, jboolean on)
{
    return check_AudioSystem_Command(AudioSystem::muteMicrophone(on));
}

static jboolean
android_media_AudioSystem_isMicrophoneMuted(JNIEnv *env, jobject thiz)
{
    bool state = false;
    AudioSystem::isMicrophoneMuted(&state);
    return state;
}

static jboolean
android_media_AudioSystem_isStreamActive(JNIEnv *env, jobject thiz, jint stream, jint inPastMs)
{
    bool state = false;
    AudioSystem::isStreamActive((audio_stream_type_t) stream, &state, inPastMs);
    return state;
}

static jboolean
android_media_AudioSystem_isSourceActive(JNIEnv *env, jobject thiz, jint source)
{
    bool state = false;
    AudioSystem::isSourceActive((audio_source_t) source, &state);
    return state;
}

static int
android_media_AudioSystem_setParameters(JNIEnv *env, jobject thiz, jstring keyValuePairs)
{
    const jchar* c_keyValuePairs = env->GetStringCritical(keyValuePairs, 0);
    String8 c_keyValuePairs8;
    if (keyValuePairs) {
        c_keyValuePairs8 = String8(c_keyValuePairs, env->GetStringLength(keyValuePairs));
        env->ReleaseStringCritical(keyValuePairs, c_keyValuePairs);
    }
    int status = check_AudioSystem_Command(AudioSystem::setParameters(0, c_keyValuePairs8));
    return status;
}

static jstring
android_media_AudioSystem_getParameters(JNIEnv *env, jobject thiz, jstring keys)
{
    const jchar* c_keys = env->GetStringCritical(keys, 0);
    String8 c_keys8;
    if (keys) {
        c_keys8 = String8(c_keys, env->GetStringLength(keys));
        env->ReleaseStringCritical(keys, c_keys);
    }
    return env->NewStringUTF(AudioSystem::getParameters(0, c_keys8).string());
}

static void
android_media_AudioSystem_error_callback(status_t err)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        return;
    }

    jclass clazz = env->FindClass(kClassPathName);

    int error;

    switch (err) {
    case DEAD_OBJECT:
        error = kAudioStatusMediaServerDied;
        break;
    case NO_ERROR:
        error = kAudioStatusOk;
        break;
    default:
        error = kAudioStatusError;
        break;
    }

    env->CallStaticVoidMethod(clazz, env->GetStaticMethodID(clazz, "errorCallbackFromNative","(I)V"), error);
}

static int
android_media_AudioSystem_setDeviceConnectionState(JNIEnv *env, jobject thiz, jint device, jint state, jstring device_address)
{
    const char *c_address = env->GetStringUTFChars(device_address, NULL);
    int status = check_AudioSystem_Command(AudioSystem::setDeviceConnectionState(static_cast <audio_devices_t>(device),
                                          static_cast <audio_policy_dev_state_t>(state),
                                          c_address));
    env->ReleaseStringUTFChars(device_address, c_address);
    return status;
}

static int
android_media_AudioSystem_getDeviceConnectionState(JNIEnv *env, jobject thiz, jint device, jstring device_address)
{
    const char *c_address = env->GetStringUTFChars(device_address, NULL);
    int state = static_cast <int>(AudioSystem::getDeviceConnectionState(static_cast <audio_devices_t>(device),
                                          c_address));
    env->ReleaseStringUTFChars(device_address, c_address);
    return state;
}

static int
android_media_AudioSystem_setPhoneState(JNIEnv *env, jobject thiz, jint state)
{
    return check_AudioSystem_Command(AudioSystem::setPhoneState((audio_mode_t) state));
}

static int
android_media_AudioSystem_setForceUse(JNIEnv *env, jobject thiz, jint usage, jint config)
{
    return check_AudioSystem_Command(AudioSystem::setForceUse(static_cast <audio_policy_force_use_t>(usage),
                                                           static_cast <audio_policy_forced_cfg_t>(config)));
}

static int
android_media_AudioSystem_getForceUse(JNIEnv *env, jobject thiz, jint usage)
{
    return static_cast <int>(AudioSystem::getForceUse(static_cast <audio_policy_force_use_t>(usage)));
}

static int
android_media_AudioSystem_initStreamVolume(JNIEnv *env, jobject thiz, jint stream, jint indexMin, jint indexMax)
{
    return check_AudioSystem_Command(AudioSystem::initStreamVolume(static_cast <audio_stream_type_t>(stream),
                                                                   indexMin,
                                                                   indexMax));
}

static int
android_media_AudioSystem_setStreamVolumeIndex(JNIEnv *env,
                                               jobject thiz,
                                               jint stream,
                                               jint index,
                                               jint device)
{
    return check_AudioSystem_Command(
            AudioSystem::setStreamVolumeIndex(static_cast <audio_stream_type_t>(stream),
                                              index,
                                              (audio_devices_t)device));
}

static int
android_media_AudioSystem_getStreamVolumeIndex(JNIEnv *env,
                                               jobject thiz,
                                               jint stream,
                                               jint device)
{
    int index;
    if (AudioSystem::getStreamVolumeIndex(static_cast <audio_stream_type_t>(stream),
                                          &index,
                                          (audio_devices_t)device)
            != NO_ERROR) {
        index = -1;
    }
    return index;
}

static int
android_media_AudioSystem_setMasterVolume(JNIEnv *env, jobject thiz, jfloat value)
{
    return check_AudioSystem_Command(AudioSystem::setMasterVolume(value));
}

static jfloat
android_media_AudioSystem_getMasterVolume(JNIEnv *env, jobject thiz)
{
    float value;
    if (AudioSystem::getMasterVolume(&value) != NO_ERROR) {
        value = -1.0;
    }
    return value;
}

static int
android_media_AudioSystem_setMasterMute(JNIEnv *env, jobject thiz, jboolean mute)
{
    return check_AudioSystem_Command(AudioSystem::setMasterMute(mute));
}

static jfloat
android_media_AudioSystem_getMasterMute(JNIEnv *env, jobject thiz)
{
    bool mute;
    if (AudioSystem::getMasterMute(&mute) != NO_ERROR) {
        mute = false;
    }
    return mute;
}

static jint
android_media_AudioSystem_getDevicesForStream(JNIEnv *env, jobject thiz, jint stream)
{
    return (jint) AudioSystem::getDevicesForStream(static_cast <audio_stream_type_t>(stream));
}

static jint
android_media_AudioSystem_getPrimaryOutputSamplingRate(JNIEnv *env, jobject clazz)
{
    return (jint) AudioSystem::getPrimaryOutputSamplingRate();
}

static jint
android_media_AudioSystem_getPrimaryOutputFrameCount(JNIEnv *env, jobject clazz)
{
    return (jint) AudioSystem::getPrimaryOutputFrameCount();
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"setParameters",        "(Ljava/lang/String;)I", (void *)android_media_AudioSystem_setParameters},
    {"getParameters",        "(Ljava/lang/String;)Ljava/lang/String;", (void *)android_media_AudioSystem_getParameters},
    {"muteMicrophone",      "(Z)I",     (void *)android_media_AudioSystem_muteMicrophone},
    {"isMicrophoneMuted",   "()Z",      (void *)android_media_AudioSystem_isMicrophoneMuted},
    {"isStreamActive",      "(II)Z",    (void *)android_media_AudioSystem_isStreamActive},
    {"isSourceActive",      "(I)Z",     (void *)android_media_AudioSystem_isSourceActive},
    {"setDeviceConnectionState", "(IILjava/lang/String;)I", (void *)android_media_AudioSystem_setDeviceConnectionState},
    {"getDeviceConnectionState", "(ILjava/lang/String;)I",  (void *)android_media_AudioSystem_getDeviceConnectionState},
    {"setPhoneState",       "(I)I",     (void *)android_media_AudioSystem_setPhoneState},
    {"setForceUse",         "(II)I",    (void *)android_media_AudioSystem_setForceUse},
    {"getForceUse",         "(I)I",     (void *)android_media_AudioSystem_getForceUse},
    {"initStreamVolume",    "(III)I",   (void *)android_media_AudioSystem_initStreamVolume},
    {"setStreamVolumeIndex","(III)I",   (void *)android_media_AudioSystem_setStreamVolumeIndex},
    {"getStreamVolumeIndex","(II)I",    (void *)android_media_AudioSystem_getStreamVolumeIndex},
    {"setMasterVolume",     "(F)I",     (void *)android_media_AudioSystem_setMasterVolume},
    {"getMasterVolume",     "()F",      (void *)android_media_AudioSystem_getMasterVolume},
    {"setMasterMute",       "(Z)I",     (void *)android_media_AudioSystem_setMasterMute},
    {"getMasterMute",       "()Z",      (void *)android_media_AudioSystem_getMasterMute},
    {"getDevicesForStream", "(I)I",     (void *)android_media_AudioSystem_getDevicesForStream},
    {"getPrimaryOutputSamplingRate", "()I", (void *)android_media_AudioSystem_getPrimaryOutputSamplingRate},
    {"getPrimaryOutputFrameCount",   "()I", (void *)android_media_AudioSystem_getPrimaryOutputFrameCount},
};

int register_android_media_AudioSystem(JNIEnv *env)
{
    AudioSystem::setErrorCallback(android_media_AudioSystem_error_callback);

    return AndroidRuntime::registerNativeMethods(env,
                kClassPathName, gMethods, NELEM(gMethods));
}
