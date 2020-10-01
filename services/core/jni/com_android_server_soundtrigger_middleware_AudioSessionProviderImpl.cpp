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

#include <sstream>

#include "core_jni_helpers.h"
#include <media/AudioSystem.h>

namespace android {

namespace {

#define PACKAGE "com/android/server/soundtrigger_middleware"
#define CLASSNAME PACKAGE "/AudioSessionProviderImpl"
#define SESSION_CLASSNAME PACKAGE "/SoundTriggerMiddlewareImpl$AudioSessionProvider$AudioSession"

jobject acquireAudioSession(
        JNIEnv* env,
        jobject clazz) {

    audio_session_t session;
    audio_io_handle_t ioHandle;
    audio_devices_t device;

    status_t status = AudioSystem::acquireSoundTriggerSession(&session,
                                                              &ioHandle,
                                                              &device);
    if (status != 0) {
        std::ostringstream message;
        message
                << "AudioSystem::acquireSoundTriggerSession returned an error code: "
                << status;
        env->ThrowNew(FindClassOrDie(env, "java/lang/RuntimeException"),
                      message.str().c_str());
        return nullptr;
    }

    jclass cls = FindClassOrDie(env, SESSION_CLASSNAME);
    jmethodID ctor = GetMethodIDOrDie(env, cls, "<init>", "(III)V");
    return env->NewObject(cls,
                          ctor,
                          static_cast<int>(session),
                          static_cast<int>(ioHandle),
                          static_cast<int>(device));
}

void releaseAudioSession(JNIEnv* env, jobject clazz, jint handle) {
    status_t status =
            AudioSystem::releaseSoundTriggerSession(static_cast<audio_session_t>(handle));

    if (status != 0) {
        std::ostringstream message;
        message
                << "AudioSystem::releaseAudioSystemSession returned an error code: "
                << status;
        env->ThrowNew(FindClassOrDie(env, "java/lang/RuntimeException"),
                      message.str().c_str());
    }
}

const JNINativeMethod g_methods[] = {
        {"acquireSession", "()L" SESSION_CLASSNAME ";",
         reinterpret_cast<void*>(acquireAudioSession)},
        {"releaseSession", "(I)V",
         reinterpret_cast<void*>(releaseAudioSession)},
};

}  // namespace

int register_com_android_server_soundtrigger_middleware_AudioSessionProviderImpl(
        JNIEnv* env) {
    return RegisterMethodsOrDie(env,
                                CLASSNAME,
                                g_methods,
                                NELEM(g_methods));
}

} // namespace android
