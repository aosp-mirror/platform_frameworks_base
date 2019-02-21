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

#define LOG_TAG "CompositionSamplingListener"

#include "android_util_Binder.h"

#include <nativehelper/JNIHelp.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <utils/Log.h>
#include <utils/RefBase.h>
#include <binder/IServiceManager.h>

#include <gui/IRegionSamplingListener.h>
#include <gui/ISurfaceComposer.h>
#include <ui/Rect.h>

namespace android {

namespace {

struct {
    jclass mClass;
    jmethodID mDispatchOnSampleCollected;
} gListenerClassInfo;

struct CompositionSamplingListener : public BnRegionSamplingListener {
    CompositionSamplingListener(JNIEnv* env, jobject listener)
            : mListener(env->NewGlobalRef(listener)) {}

    void onSampleCollected(float medianLuma) override {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        LOG_ALWAYS_FATAL_IF(env == nullptr, "Unable to retrieve JNIEnv in onSampleCollected.");

        env->CallStaticVoidMethod(gListenerClassInfo.mClass,
                gListenerClassInfo.mDispatchOnSampleCollected, mListener,
                static_cast<jfloat>(medianLuma));
        if (env->ExceptionCheck()) {
            ALOGE("CompositionSamplingListener.onSampleCollected() failed.");
            LOGE_EX(env);
            env->ExceptionClear();
        }
    }

protected:
    virtual ~CompositionSamplingListener() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteGlobalRef(mListener);
    }

private:
    jobject mListener;
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

void nativeRegister(JNIEnv* env, jclass clazz, jlong ptr, jobject stopLayerTokenObj,
        jint left, jint top, jint right, jint bottom) {
    sp<CompositionSamplingListener> listener = reinterpret_cast<CompositionSamplingListener*>(ptr);
    sp<IBinder> stopLayerHandle = ibinderForJavaObject(env, stopLayerTokenObj);

    // TODO: Use SurfaceComposerClient once it has addRegionSamplingListener.
    sp<ISurfaceComposer> composer;
    if (getService(String16("SurfaceFlinger"), &composer) != NO_ERROR) {
        jniThrowRuntimeException(env, "Couldn't retrieve SurfaceFlinger");
        return;
    }

    composer->addRegionSamplingListener(
            Rect(left, top, right, bottom), stopLayerHandle, listener);
}

void nativeUnregister(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<CompositionSamplingListener> listener = reinterpret_cast<CompositionSamplingListener*>(ptr);

    // TODO: Use SurfaceComposerClient once it has addRegionSamplingListener.
    sp<ISurfaceComposer> composer;
    if (getService(String16("SurfaceFlinger"), &composer) != NO_ERROR) {
        jniThrowRuntimeException(env, "Couldn't retrieve SurfaceFlinger");
        return;
    }

    composer->removeRegionSamplingListener(listener);
}

const JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "nativeCreate", "(Landroid/view/CompositionSamplingListener;)J",
            (void*)nativeCreate },
    { "nativeDestroy", "(J)V",
            (void*)nativeDestroy },
    { "nativeRegister", "(JLandroid/os/IBinder;IIII)V",
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
    gListenerClassInfo.mDispatchOnSampleCollected = env->GetStaticMethodID(
            clazz, "dispatchOnSampleCollected", "(Landroid/view/CompositionSamplingListener;F)V");
    return 0;
}

} // namespace android
