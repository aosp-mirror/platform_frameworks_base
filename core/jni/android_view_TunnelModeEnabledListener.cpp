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

#define LOG_TAG "TunnelModeEnabledListener"

#include "android_util_Binder.h"
#include "core_jni_helpers.h"

#include <nativehelper/JNIHelp.h>

#include <android/gui/BnTunnelModeEnabledListener.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <utils/Log.h>

#include <gui/SurfaceComposerClient.h>
#include <ui/Rect.h>

namespace android {

namespace {

struct {
    jclass mClass;
    jmethodID mDispatchOnTunnelModeEnabledChanged;
} gListenerClassInfo;

struct TunnelModeEnabledListener : public gui::BnTunnelModeEnabledListener {
    TunnelModeEnabledListener(JNIEnv* env, jobject listener)
          : mListener(env->NewWeakGlobalRef(listener)) {}

    binder::Status onTunnelModeEnabledChanged(bool tunnelModeEnabled) override {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        LOG_ALWAYS_FATAL_IF(env == nullptr,
                            "Unable to retrieve JNIEnv in onTunnelModeEnabledChanged.");

        jobject listener = env->NewGlobalRef(mListener);
        if (listener == NULL) {
            // Weak reference went out of scope
            return binder::Status::ok();
        }
        env->CallStaticVoidMethod(gListenerClassInfo.mClass,
                                  gListenerClassInfo.mDispatchOnTunnelModeEnabledChanged, listener,
                                  static_cast<jboolean>(tunnelModeEnabled));
        env->DeleteGlobalRef(listener);

        if (env->ExceptionCheck()) {
            ALOGE("TunnelModeEnabledListener.onTunnelModeEnabledChanged() failed.");
            LOGE_EX(env);
            env->ExceptionClear();
        }
        return binder::Status::ok();
    }

protected:
    virtual ~TunnelModeEnabledListener() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteWeakGlobalRef(mListener);
    }

private:
    jweak mListener;
};

jlong nativeCreate(JNIEnv* env, jclass clazz, jobject obj) {
    TunnelModeEnabledListener* listener = new TunnelModeEnabledListener(env, obj);
    listener->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(listener);
}

void nativeDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    TunnelModeEnabledListener* listener = reinterpret_cast<TunnelModeEnabledListener*>(ptr);
    listener->decStrong((void*)nativeCreate);
}

void nativeRegister(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<TunnelModeEnabledListener> listener = reinterpret_cast<TunnelModeEnabledListener*>(ptr);
    status_t status = SurfaceComposerClient::addTunnelModeEnabledListener(listener);
    if (status != OK) {
        ALOGE("Couldn't addTunnelModeEnabledListener (%d)", status);
        jniThrowRuntimeException(env, "Couldn't addTunnelModeEnabledListener");
    }
}

void nativeUnregister(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<TunnelModeEnabledListener> listener = reinterpret_cast<TunnelModeEnabledListener*>(ptr);
    status_t status = SurfaceComposerClient::removeTunnelModeEnabledListener(listener);
    if (status != OK) {
        ALOGE("Couldn't removeTunnelModeEnabledListener (%d)", status);
        jniThrowRuntimeException(env, "Couldn't removeTunnelModeEnabledListener");
    }
}

const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativeCreate", "(Landroid/view/TunnelModeEnabledListener;)J", (void*)nativeCreate},
        {"nativeDestroy", "(J)V", (void*)nativeDestroy},
        {"nativeRegister", "(J)V", (void*)nativeRegister},
        {"nativeUnregister", "(J)V", (void*)nativeUnregister}};

} // namespace

int register_android_view_TunnelModeEnabledListener(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/TunnelModeEnabledListener", gMethods,
                                       NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz = env->FindClass("android/view/TunnelModeEnabledListener");
    gListenerClassInfo.mClass = MakeGlobalRefOrDie(env, clazz);
    gListenerClassInfo.mDispatchOnTunnelModeEnabledChanged =
            env->GetStaticMethodID(clazz, "dispatchOnTunnelModeEnabledChanged",
                                   "(Landroid/view/TunnelModeEnabledListener;Z)V");
    return 0;
}

} // namespace android
