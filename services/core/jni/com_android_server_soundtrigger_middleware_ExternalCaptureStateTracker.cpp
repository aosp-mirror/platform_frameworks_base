/*
 * Copyright (C) 2020 The Android Open Source Project
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

#define LOG_TAG "ExternalCaptureStateTracker"

#include "core_jni_helpers.h"
#include <log/log.h>
#include <media/AudioSystem.h>

namespace android {
namespace {

#define PACKAGE "com/android/server/soundtrigger_middleware"
#define CLASSNAME PACKAGE "/ExternalCaptureStateTracker"

jclass gExternalCaptureStateTrackerClassId;
jmethodID gSetCaptureStateMethodId;
jmethodID gBinderDiedMethodId;

void PopulateIds(JNIEnv* env) {
    gExternalCaptureStateTrackerClassId =
        (jclass) env->NewGlobalRef(FindClassOrDie(env, CLASSNAME));
    gSetCaptureStateMethodId = GetMethodIDOrDie(env,
                                                gExternalCaptureStateTrackerClassId,
                                                "setCaptureState",
                                                "(Z)V");
    gBinderDiedMethodId = GetMethodIDOrDie(env,
                                           gExternalCaptureStateTrackerClassId,
                                           "binderDied",
                                           "()V");
}

class Listener : public AudioSystem::CaptureStateListener {
public:
    Listener(JNIEnv* env, jobject obj) : mObj(env->NewGlobalRef(obj)) {}

    ~Listener() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteGlobalRef(mObj);
    }

    void onStateChanged(bool active) override {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(mObj, gSetCaptureStateMethodId, active);
    }

    void onServiceDied() override {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(mObj, gBinderDiedMethodId);
    }

private:
    jobject mObj;
};

void connect(JNIEnv* env, jobject obj) {
    sp<AudioSystem::CaptureStateListener> listener(new Listener(env, obj));
    status_t status =
        AudioSystem::registerSoundTriggerCaptureStateListener(listener);
    LOG_ALWAYS_FATAL_IF(status != NO_ERROR);
}

const JNINativeMethod gMethods[] = {
    {"connect", "()V", reinterpret_cast<void*>(connect)},
};

}  // namespace

int register_com_android_server_soundtrigger_middleware_ExternalCaptureStateTracker(
    JNIEnv* env) {
    PopulateIds(env);
    return RegisterMethodsOrDie(env,
                                CLASSNAME,
                                gMethods,
                                NELEM(gMethods));
}

} // namespace android
