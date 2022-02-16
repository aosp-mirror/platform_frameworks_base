/*
 * Copyright 2021 The Android Open Source Project
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

#define LOG_TAG "SurfaceControlFpsListener"

#include <android/gui/BnFpsListener.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <nativehelper/JNIHelp.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include "android_util_Binder.h"
#include "core_jni_helpers.h"

namespace android {

namespace {

struct {
    jclass mClass;
    jmethodID mDispatchOnFpsReported;
} gListenerClassInfo;

struct SurfaceControlFpsListener : public gui::BnFpsListener {
    SurfaceControlFpsListener(JNIEnv* env, jobject listener)
          : mListener(env->NewWeakGlobalRef(listener)) {}

    binder::Status onFpsReported(float fps) override {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        LOG_ALWAYS_FATAL_IF(env == nullptr, "Unable to retrieve JNIEnv in onFpsReported.");

        jobject listener = env->NewGlobalRef(mListener);
        if (listener == NULL) {
            // Weak reference went out of scope
            return binder::Status::ok();
        }
        env->CallStaticVoidMethod(gListenerClassInfo.mClass,
                                  gListenerClassInfo.mDispatchOnFpsReported, listener,
                                  static_cast<jfloat>(fps));
        env->DeleteGlobalRef(listener);

        if (env->ExceptionCheck()) {
            ALOGE("SurfaceControlFpsListener.onFpsReported() failed.");
            LOGE_EX(env);
            env->ExceptionClear();
        }
        return binder::Status::ok();
    }

protected:
    virtual ~SurfaceControlFpsListener() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteWeakGlobalRef(mListener);
    }

private:
    jweak mListener;
};

jlong nativeCreate(JNIEnv* env, jclass clazz, jobject obj) {
    SurfaceControlFpsListener* listener = new SurfaceControlFpsListener(env, obj);
    listener->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(listener);
}

void nativeDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    SurfaceControlFpsListener* listener = reinterpret_cast<SurfaceControlFpsListener*>(ptr);
    listener->decStrong((void*)nativeCreate);
}

void nativeRegister(JNIEnv* env, jclass clazz, jlong ptr, jint taskId) {
    sp<SurfaceControlFpsListener> listener = reinterpret_cast<SurfaceControlFpsListener*>(ptr);
    if (SurfaceComposerClient::addFpsListener(taskId, listener) != OK) {
        constexpr auto error_msg = "Couldn't addFpsListener";
        ALOGE(error_msg);
        jniThrowRuntimeException(env, error_msg);
    }
}

void nativeUnregister(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<SurfaceControlFpsListener> listener = reinterpret_cast<SurfaceControlFpsListener*>(ptr);

    if (SurfaceComposerClient::removeFpsListener(listener) != OK) {
        constexpr auto error_msg = "Couldn't removeFpsListener";
        ALOGE(error_msg);
        jniThrowRuntimeException(env, error_msg);
    }
}

const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativeCreate", "(Landroid/view/SurfaceControlFpsListener;)J", (void*)nativeCreate},
        {"nativeDestroy", "(J)V", (void*)nativeDestroy},
        {"nativeRegister", "(JI)V", (void*)nativeRegister},
        {"nativeUnregister", "(J)V", (void*)nativeUnregister}};

} // namespace

int register_android_view_SurfaceControlFpsListener(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/SurfaceControlFpsListener", gMethods,
                                       NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz = env->FindClass("android/view/SurfaceControlFpsListener");
    gListenerClassInfo.mClass = MakeGlobalRefOrDie(env, clazz);
    gListenerClassInfo.mDispatchOnFpsReported =
            env->GetStaticMethodID(clazz, "dispatchOnFpsReported",
                                   "(Landroid/view/SurfaceControlFpsListener;F)V");
    return 0;
}

} // namespace android
