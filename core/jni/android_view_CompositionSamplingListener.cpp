/*
 * Copyright (C) 2012 The Android Open Source Project
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
#undef ANDROID_UTILS_REF_BASE_DISABLE_IMPLICIT_CONSTRUCTION // TODO:remove this and fix code

#define LOG_TAG "CompositionSamplingListener"

#include <android/gui/BnRegionSamplingListener.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <binder/IServiceManager.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <nativehelper/JNIHelp.h>
#include <ui/Rect.h>
#include <utils/Log.h>
#include <utils/RefBase.h>

#include "android_util_Binder.h"
#include "core_jni_helpers.h"

namespace android {

namespace {

struct {
    jclass mClass;
    jmethodID mDispatchOnSampleCollected;
} gListenerClassInfo;

struct CompositionSamplingListener : public gui::BnRegionSamplingListener {
    CompositionSamplingListener(JNIEnv* env, jobject listener)
            : mListener(env->NewWeakGlobalRef(listener)) {}

    binder::Status onSampleCollected(float medianLuma) override {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        LOG_ALWAYS_FATAL_IF(env == nullptr, "Unable to retrieve JNIEnv in onSampleCollected.");

        jobject listener = env->NewGlobalRef(mListener);
        if (listener == NULL) {
            // Weak reference went out of scope
            return binder::Status::ok();
        }
        env->CallStaticVoidMethod(gListenerClassInfo.mClass,
                gListenerClassInfo.mDispatchOnSampleCollected, listener,
                static_cast<jfloat>(medianLuma));
        env->DeleteGlobalRef(listener);

        if (env->ExceptionCheck()) {
            ALOGE("CompositionSamplingListener.onSampleCollected() failed.");
            LOGE_EX(env);
            env->ExceptionClear();
        }

        return binder::Status::ok();
    }

protected:
    virtual ~CompositionSamplingListener() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteWeakGlobalRef(mListener);
    }

private:
    jweak mListener;
};

jlong nativeCreate(JNIEnv* env, jclass clazz, jobject obj) {
    CompositionSamplingListener* listener = new CompositionSamplingListener(env, obj);
    listener->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(listener);
}

void nativeDestroy(JNIEnv* env, jclass clazz, jlong ptr) {
    CompositionSamplingListener* listener = reinterpret_cast<CompositionSamplingListener*>(ptr);
    listener->decStrong((void*)nativeCreate);
}

void nativeRegister(JNIEnv* env, jclass clazz, jlong ptr, jlong stopLayerObj,
        jint left, jint top, jint right, jint bottom) {
    sp<CompositionSamplingListener> listener = reinterpret_cast<CompositionSamplingListener*>(ptr);
    auto stopLayer = reinterpret_cast<SurfaceControl*>(stopLayerObj);
    sp<IBinder> stopLayerHandle = stopLayer != nullptr ? stopLayer->getHandle() : nullptr;
    if (SurfaceComposerClient::addRegionSamplingListener(
            Rect(left, top, right, bottom), stopLayerHandle, listener) != OK) {
        constexpr auto error_msg = "Couldn't addRegionSamplingListener";
        ALOGE(error_msg);
        jniThrowRuntimeException(env, error_msg);
    }
}

void nativeUnregister(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<CompositionSamplingListener> listener = reinterpret_cast<CompositionSamplingListener*>(ptr);

    if (SurfaceComposerClient::removeRegionSamplingListener(listener) != OK) {
        constexpr auto error_msg = "Couldn't removeRegionSamplingListener";
        ALOGE(error_msg);
        jniThrowRuntimeException(env, error_msg);
    }
}

const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeCreate", "(Landroid/view/CompositionSamplingListener;)J",
            (void*)nativeCreate },
    { "nativeDestroy", "(J)V",
            (void*)nativeDestroy },
    { "nativeRegister", "(JJIIII)V",
            (void*)nativeRegister },
    { "nativeUnregister", "(J)V",
            (void*)nativeUnregister }
};

} // namespace

int register_android_view_CompositionSamplingListener(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/CompositionSamplingListener",
            gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz = env->FindClass("android/view/CompositionSamplingListener");
    gListenerClassInfo.mClass = MakeGlobalRefOrDie(env, clazz);
    gListenerClassInfo.mDispatchOnSampleCollected = env->GetStaticMethodID(
            clazz, "dispatchOnSampleCollected", "(Landroid/view/CompositionSamplingListener;F)V");
    return 0;
}

} // namespace android
