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

#define LOG_TAG "SurfaceControlHdrLayerInfoListener"

#include <android/gui/BnHdrLayerInfoListener.h>
#include <android_runtime/Log.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <utils/RefBase.h>

#include "android_util_Binder.h"
#include "core_jni_helpers.h"

namespace android {

namespace {

struct {
    jclass mClass;
    jmethodID mOnHdrInfoChanged;
} gListenerClassInfo;

struct SurfaceControlHdrLayerInfoListener : public gui::BnHdrLayerInfoListener {
    SurfaceControlHdrLayerInfoListener(JNIEnv* env, jobject listener, jobject displayToken)
          : mListener(env->NewGlobalRef(listener)), mDisplayToken(env->NewGlobalRef(displayToken)) {
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&mVm) != JNI_OK, "Failed to GetJavaVm");
    }

    binder::Status onHdrLayerInfoChanged(int numberOfHdrLayers, int maxW, int maxH,
                                         int flags) override {
        JNIEnv* env = requireEnv();

        env->CallVoidMethod(mListener, gListenerClassInfo.mOnHdrInfoChanged, mDisplayToken,
                            numberOfHdrLayers, maxW, maxH, flags);

        if (env->ExceptionCheck()) {
            ALOGE("SurfaceControlHdrLayerInfoListener.onHdrInfoChanged() failed.");
            LOGE_EX(env);
            env->ExceptionClear();
        }
        return binder::Status::ok();
    }

    status_t startListening() {
        auto token = ibinderForJavaObject(requireEnv(), mDisplayToken);
        return SurfaceComposerClient::addHdrLayerInfoListener(token, this);
    }

    status_t stopListening() {
        auto token = ibinderForJavaObject(requireEnv(), mDisplayToken);
        return SurfaceComposerClient::removeHdrLayerInfoListener(token, this);
    }

protected:
    virtual ~SurfaceControlHdrLayerInfoListener() {
        JNIEnv* env = requireEnv();
        env->DeleteGlobalRef(mListener);
        env->DeleteGlobalRef(mDisplayToken);
    }

    JNIEnv* requireEnv() {
        JNIEnv* env = nullptr;
        if (mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (mVm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) {
                LOG_ALWAYS_FATAL("Failed to AttachCurrentThread!");
            }
        }
        return env;
    }

private:
    jobject mListener;
    jobject mDisplayToken;
    JavaVM* mVm;
};

jlong nRegister(JNIEnv* env, jobject jthis, jobject jbinderToken) {
    auto callback = sp<SurfaceControlHdrLayerInfoListener>::make(env, jthis, jbinderToken);
    status_t err = callback->startListening();
    if (err != OK) {
        auto errStr = statusToString(err);
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                             "Failed to register HdrLayerInfoListener, err = %d (%s)", err,
                             errStr.c_str());
        return 0;
    }
    SurfaceControlHdrLayerInfoListener* ret = callback.get();
    ret->incStrong(0);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(ret));
}

static void destroy(SurfaceControlHdrLayerInfoListener* listener) {
    listener->stopListening();
    listener->decStrong(0);
}

static jlong nGetDestructor(JNIEnv* env, jobject clazz) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(&destroy));
}

const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nGetDestructor", "()J", (void*)nGetDestructor},
        {"nRegister", "(Landroid/os/IBinder;)J", (void*)nRegister}};

} // namespace

int register_android_view_SurfaceControlHdrLayerInfoListener(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/SurfaceControlHdrLayerInfoListener",
                                       gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz = env->FindClass("android/view/SurfaceControlHdrLayerInfoListener");
    gListenerClassInfo.mClass = MakeGlobalRefOrDie(env, clazz);
    gListenerClassInfo.mOnHdrInfoChanged =
            env->GetMethodID(clazz, "onHdrInfoChanged", "(Landroid/os/IBinder;IIII)V");
    return 0;
}

} // namespace android
