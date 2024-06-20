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

#define LOG_TAG "WindowInfosListener"

#include <android/graphics/matrix.h>
#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/Log.h>
#include <gui/DisplayInfo.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/WindowInfosUpdate.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalFrame.h>
#include <utils/Log.h>

#include "android_hardware_input_InputWindowHandle.h"
#include "core_jni_helpers.h"

namespace android {

using gui::DisplayInfo;
using gui::WindowInfo;

namespace {

static struct {
    jclass clazz;
    jmethodID onWindowInfosChanged;
} gListenerClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gDisplayInfoClassInfo;

static struct {
    jclass clazz;
    jmethodID ctor;
} gPairClassInfo;

static jclass gInputWindowHandleClass;

jobject fromDisplayInfo(JNIEnv* env, gui::DisplayInfo displayInfo) {
    float transformValues[9];
    for (int i = 0; i < 9; i++) {
        transformValues[i] = displayInfo.transform[i % 3][i / 3];
    }
    ScopedLocalRef<jobject> matrixObj(env, AMatrix_newInstance(env, transformValues));
    return env->NewObject(gDisplayInfoClassInfo.clazz, gDisplayInfoClassInfo.ctor,
                          displayInfo.displayId.val(), displayInfo.logicalWidth,
                          displayInfo.logicalHeight, matrixObj.get());
}

static jobjectArray fromWindowInfos(JNIEnv* env, const std::vector<WindowInfo>& windowInfos) {
    jobjectArray jWindowHandlesArray =
            env->NewObjectArray(windowInfos.size(), gInputWindowHandleClass, nullptr);
    for (size_t i = 0; i < windowInfos.size(); i++) {
        ScopedLocalRef<jobject>
                jWindowHandle(env,
                              android_view_InputWindowHandle_fromWindowInfo(env, windowInfos[i]));
        env->SetObjectArrayElement(jWindowHandlesArray, i, jWindowHandle.get());
    }

    return jWindowHandlesArray;
}

static jobjectArray fromDisplayInfos(JNIEnv* env, const std::vector<DisplayInfo>& displayInfos) {
    jobjectArray jDisplayInfoArray =
            env->NewObjectArray(displayInfos.size(), gDisplayInfoClassInfo.clazz, nullptr);
    for (size_t i = 0; i < displayInfos.size(); i++) {
        ScopedLocalRef<jobject> jDisplayInfo(env, fromDisplayInfo(env, displayInfos[i]));
        env->SetObjectArrayElement(jDisplayInfoArray, i, jDisplayInfo.get());
    }

    return jDisplayInfoArray;
}

struct WindowInfosListener : public gui::WindowInfosListener {
    WindowInfosListener(JNIEnv* env, jobject listener)
          : mListener(env->NewWeakGlobalRef(listener)) {}

    void onWindowInfosChanged(const gui::WindowInfosUpdate& update) override {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        LOG_ALWAYS_FATAL_IF(env == nullptr, "Unable to retrieve JNIEnv in onWindowInfoChanged.");

        ScopedLocalFrame localFrame(env);
        jobject listener = env->NewGlobalRef(mListener);
        if (listener == nullptr) {
            // Weak reference went out of scope
            return;
        }

        ScopedLocalRef<jobjectArray> jWindowHandlesArray(env,
                                                         fromWindowInfos(env, update.windowInfos));
        ScopedLocalRef<jobjectArray> jDisplayInfoArray(env,
                                                       fromDisplayInfos(env, update.displayInfos));

        env->CallVoidMethod(listener, gListenerClassInfo.onWindowInfosChanged,
                            jWindowHandlesArray.get(), jDisplayInfoArray.get());
        env->DeleteGlobalRef(listener);

        if (env->ExceptionCheck()) {
            ALOGE("WindowInfosListener.onWindowInfosChanged() failed.");
            LOGE_EX(env);
            env->ExceptionClear();
        }
    }

    ~WindowInfosListener() override {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteWeakGlobalRef(mListener);
    }

private:
    jweak mListener;
};

jlong nativeCreate(JNIEnv* env, jclass clazz, jobject obj) {
    WindowInfosListener* listener = new WindowInfosListener(env, obj);
    listener->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(listener);
}

void destroyNativeService(void* ptr) {
    WindowInfosListener* listener = reinterpret_cast<WindowInfosListener*>(ptr);
    SurfaceComposerClient::getDefault()->removeWindowInfosListener(listener);
    listener->decStrong((void*)nativeCreate);
}

jobject nativeRegister(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<WindowInfosListener> listener = reinterpret_cast<WindowInfosListener*>(ptr);
    std::pair<std::vector<gui::WindowInfo>, std::vector<gui::DisplayInfo>> initialInfo;
    SurfaceComposerClient::getDefault()->addWindowInfosListener(listener, &initialInfo);

    ScopedLocalRef<jobjectArray> jWindowHandlesArray(env, fromWindowInfos(env, initialInfo.first));
    ScopedLocalRef<jobjectArray> jDisplayInfoArray(env, fromDisplayInfos(env, initialInfo.second));

    return env->NewObject(gPairClassInfo.clazz, gPairClassInfo.ctor, jWindowHandlesArray.get(),
                          jDisplayInfoArray.get());
}

void nativeUnregister(JNIEnv* env, jclass clazz, jlong ptr) {
    sp<WindowInfosListener> listener = reinterpret_cast<WindowInfosListener*>(ptr);
    SurfaceComposerClient::getDefault()->removeWindowInfosListener(listener);
}

static jlong nativeGetFinalizer(JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&destroyNativeService));
}

const JNINativeMethod gMethods[] = {
        /* name, signature, funcPtr */
        {"nativeCreate", "(Landroid/window/WindowInfosListener;)J", (void*)nativeCreate},
        {"nativeRegister", "(J)Landroid/util/Pair;", (void*)nativeRegister},
        {"nativeUnregister", "(J)V", (void*)nativeUnregister},
        {"nativeGetFinalizer", "()J", (void*)nativeGetFinalizer}};

} // namespace

int register_android_window_WindowInfosListener(JNIEnv* env) {
    int res = jniRegisterNativeMethods(env, "android/window/WindowInfosListener", gMethods,
                                       NELEM(gMethods));
    LOG_ALWAYS_FATAL_IF(res < 0, "Unable to register native methods.");

    jclass clazz = env->FindClass("android/window/WindowInfosListener");
    gListenerClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gListenerClassInfo.onWindowInfosChanged =
            env->GetMethodID(gListenerClassInfo.clazz, "onWindowInfosChanged",
                             "([Landroid/view/InputWindowHandle;[Landroid/window/"
                             "WindowInfosListener$DisplayInfo;)V");

    clazz = env->FindClass("android/view/InputWindowHandle");
    gInputWindowHandleClass = MakeGlobalRefOrDie(env, clazz);

    clazz = env->FindClass("android/window/WindowInfosListener$DisplayInfo");
    gDisplayInfoClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gDisplayInfoClassInfo.ctor = env->GetMethodID(gDisplayInfoClassInfo.clazz, "<init>",
                                                  "(IIILandroid/graphics/Matrix;)V");

    clazz = env->FindClass("android/util/Pair");
    gPairClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gPairClassInfo.ctor = env->GetMethodID(gPairClassInfo.clazz, "<init>",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)V");

    return 0;
}

} // namespace android
