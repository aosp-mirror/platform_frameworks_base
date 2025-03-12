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

#define LOG_TAG "SurfaceControlActivePictureListener"

#include <android/gui/BnActivePictureListener.h>
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
    jclass clazz;
    jmethodID onActivePicturesChanged;
} gListenerClassInfo;

static struct {
    jclass clazz;
    jmethodID constructor;
} gActivePictureClassInfo;

static struct {
    jclass clazz;
    jmethodID constructor;
    jfieldID id;
} gPictureProfileHandleClassInfo;

struct SurfaceControlActivePictureListener : public gui::BnActivePictureListener {
    SurfaceControlActivePictureListener(JNIEnv* env, jobject listener)
          : mListener(env->NewGlobalRef(listener)) {
        LOG_ALWAYS_FATAL_IF(env->GetJavaVM(&mVm) != JNI_OK, "Failed to GetJavaVm");
    }

    binder::Status onActivePicturesChanged(
            const std::vector<gui::ActivePicture>& activePictures) override {
        JNIEnv* env = requireEnv();

        ScopedLocalRef<jobjectArray> activePictureArrayObj(env);
        activePictureArrayObj.reset(
                env->NewObjectArray(activePictures.size(), gActivePictureClassInfo.clazz, NULL));
        if (env->ExceptionCheck() || !activePictureArrayObj.get()) {
            LOGE_EX(env);
            LOG_ALWAYS_FATAL("Failed to create an active picture array.");
        }

        {
            std::vector<ScopedLocalRef<jobject>> pictureProfileHandleObjs;
            std::vector<ScopedLocalRef<jobject>> activePictureObjs;

            for (size_t i = 0; i < activePictures.size(); ++i) {
                pictureProfileHandleObjs.push_back(ScopedLocalRef<jobject>(env));
                pictureProfileHandleObjs[i].reset(
                        env->NewObject(gPictureProfileHandleClassInfo.clazz,
                                       gPictureProfileHandleClassInfo.constructor,
                                       activePictures[i].pictureProfileId));
                if (env->ExceptionCheck() || !pictureProfileHandleObjs[i].get()) {
                    LOGE_EX(env);
                    LOG_ALWAYS_FATAL("Failed to create a picture profile handle.");
                }
                activePictureObjs.push_back(ScopedLocalRef<jobject>(env));
                activePictureObjs[i].reset(env->NewObject(gActivePictureClassInfo.clazz,
                                                          gActivePictureClassInfo.constructor,
                                                          activePictures[i].layerId,
                                                          activePictures[i].ownerUid,
                                                          pictureProfileHandleObjs[i].get()));
                if (env->ExceptionCheck() || !activePictureObjs[i].get()) {
                    LOGE_EX(env);
                    LOG_ALWAYS_FATAL("Failed to create an active picture.");
                }
                env->SetObjectArrayElement(activePictureArrayObj.get(), i,
                                           activePictureObjs[i].get());
            }

            env->CallVoidMethod(mListener, gListenerClassInfo.onActivePicturesChanged,
                                activePictureArrayObj.get());
        }

        if (env->ExceptionCheck()) {
            ALOGE("SurfaceControlActivePictureListener.onActivePicturesChanged failed");
            LOGE_EX(env);
            env->ExceptionClear();
        }
        return binder::Status::ok();
    }

    status_t startListening() {
        // TODO(b/337330263): Make SF multiple-listener capable
        return SurfaceComposerClient::setActivePictureListener(sp<SurfaceControlActivePictureListener>::fromExisting(this));
    }

    status_t stopListening() {
        return SurfaceComposerClient::setActivePictureListener(nullptr);
    }

protected:
    virtual ~SurfaceControlActivePictureListener() {
        JNIEnv* env = requireEnv();
        env->DeleteGlobalRef(mListener);
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
    JavaVM* mVm;
};

jlong nativeMakeAndStartListening(JNIEnv* env, jobject jthis) {
    auto listener = sp<SurfaceControlActivePictureListener>::make(env, jthis);
    status_t err = listener->startListening();
    if (err != OK) {
        auto errStr = statusToString(err);
        jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                             "Failed to start listening, err = %d (%s)", err, errStr.c_str());
        return 0;
    }
    SurfaceControlActivePictureListener* listenerRawPtr = listener.get();
    listenerRawPtr->incStrong(0);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(listenerRawPtr));
}

static void destroy(SurfaceControlActivePictureListener* listener) {
    listener->stopListening();
    listener->decStrong(0);
}

static jlong nativeGetDestructor(JNIEnv* env, jobject clazz) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(&destroy));
}

const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativeGetDestructor", "()J", (void*)nativeGetDestructor},
        {"nativeMakeAndStartListening", "()J", (void*)nativeMakeAndStartListening}};
} // namespace

int register_android_view_SurfaceControlActivePictureListener(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/view/SurfaceControlActivePictureListener",
                                       gMethods, NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass listenerClazz = env->FindClass("android/view/SurfaceControlActivePictureListener");
    gListenerClassInfo.clazz = MakeGlobalRefOrDie(env, listenerClazz);
    gListenerClassInfo.onActivePicturesChanged =
            env->GetMethodID(listenerClazz, "onActivePicturesChanged",
                             "([Landroid/view/SurfaceControlActivePicture;)V");

    gActivePictureClassInfo.clazz = static_cast<jclass>(
            env->NewGlobalRef(env->FindClass("android/view/SurfaceControlActivePicture")));
    gActivePictureClassInfo.constructor =
            env->GetMethodID(gActivePictureClassInfo.clazz, "<init>",
                             "(IILandroid/media/quality/PictureProfileHandle;)V");

    gPictureProfileHandleClassInfo.clazz = static_cast<jclass>(
            env->NewGlobalRef(env->FindClass("android/media/quality/PictureProfileHandle")));
    gPictureProfileHandleClassInfo.constructor =
            env->GetMethodID(gPictureProfileHandleClassInfo.clazz, "<init>", "(J)V");
    return 0;
}

} // namespace android
