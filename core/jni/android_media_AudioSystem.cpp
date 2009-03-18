/* //device/libs/android_runtime/android_media_AudioSystem.cpp
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
#include "utils/Log.h"

#include <stdio.h>
#include <unistd.h>
#include <fcntl.h>
#include <math.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <media/AudioSystem.h>
#include <media/AudioTrack.h>

// ----------------------------------------------------------------------------

using namespace android;

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
android_media_AudioSystem_setVolume(JNIEnv *env, jobject clazz, jint type, jint volume)
{
    LOGV("setVolume(%d)", int(volume));
    
    return check_AudioSystem_Command(AudioSystem::setStreamVolume(type, AudioSystem::linearToLog(volume)));
}

static int
android_media_AudioSystem_getVolume(JNIEnv *env, jobject clazz, jint type)
{
    float v;
    int v_int = -1;
    if (AudioSystem::getStreamVolume(int(type), &v) == NO_ERROR) {
        v_int = AudioSystem::logToLinear(v);
    }
    return v_int;
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

static int
android_media_AudioSystem_setRouting(JNIEnv *env, jobject clazz, jint mode, jint routes, jint mask)
{
    return check_AudioSystem_Command(AudioSystem::setRouting(mode, uint32_t(routes), uint32_t(mask)));
}

static jint
android_media_AudioSystem_getRouting(JNIEnv *env, jobject clazz, jint mode)
{
    uint32_t routes = -1;
    AudioSystem::getRouting(mode, &routes);
    return jint(routes);
}

static int
android_media_AudioSystem_setMode(JNIEnv *env, jobject clazz, jint mode)
{
    return check_AudioSystem_Command(AudioSystem::setMode(mode));
}

static jint
android_media_AudioSystem_getMode(JNIEnv *env, jobject clazz)
{
    int mode = AudioSystem::MODE_INVALID;
    AudioSystem::getMode(&mode);
    return jint(mode);
}

static jboolean
android_media_AudioSystem_isMusicActive(JNIEnv *env, jobject thiz)
{
    bool state = false;
    AudioSystem::isMusicActive(&state);
    return state;
}

// Temporary interface, do not use
// TODO: Replace with a more generic key:value get/set mechanism
static void
android_media_AudioSystem_setParameter(JNIEnv *env, jobject thiz, jstring key, jstring value)
{
    const char *c_key = env->GetStringUTFChars(key, NULL);
    const char *c_value = env->GetStringUTFChars(value, NULL);
    AudioSystem::setParameter(c_key, c_value);
    env->ReleaseStringUTFChars(key, c_key);
    env->ReleaseStringUTFChars(value, c_value);
}

void android_media_AudioSystem_error_callback(status_t err)
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass clazz = env->FindClass("android/media/AudioSystem");

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

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"setVolume",           "(II)I",    (void *)android_media_AudioSystem_setVolume},
    {"getVolume",           "(I)I",     (void *)android_media_AudioSystem_getVolume},
    {"setParameter",        "(Ljava/lang/String;Ljava/lang/String;)V", (void *)android_media_AudioSystem_setParameter},
    {"muteMicrophone",      "(Z)I",     (void *)android_media_AudioSystem_muteMicrophone},
    {"isMicrophoneMuted",   "()Z",      (void *)android_media_AudioSystem_isMicrophoneMuted},
    {"setRouting",          "(III)I",   (void *)android_media_AudioSystem_setRouting},
    {"getRouting",          "(I)I",     (void *)android_media_AudioSystem_getRouting},
    {"setMode",             "(I)I",     (void *)android_media_AudioSystem_setMode},
    {"getMode",             "()I",      (void *)android_media_AudioSystem_getMode},
    {"isMusicActive",       "()Z",      (void *)android_media_AudioSystem_isMusicActive},
};

const char* const kClassPathName = "android/media/AudioSystem";

int register_android_media_AudioSystem(JNIEnv *env)
{
    AudioSystem::setErrorCallback(android_media_AudioSystem_error_callback);
    
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/AudioSystem", gMethods, NELEM(gMethods));
}
