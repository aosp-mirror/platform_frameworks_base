/*
 * Copyright (C) 2024 The Android Open Source Project
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

#define LOG_TAG "InputTransferToken"

#include <android_runtime/android_window_InputTransferToken.h>
#include <gui/InputTransferToken.h>
#include <nativehelper/JNIHelp.h>

#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "core_jni_helpers.h"

namespace android {

static struct {
    jclass clazz;
    jfieldID mNativeObject;
    jmethodID ctor;
} gInputTransferTokenClassInfo;

static jlong nativeCreate(JNIEnv* env, jclass clazz) {
    sp<InputTransferToken> inputTransferToken = sp<InputTransferToken>::make();
    inputTransferToken->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(inputTransferToken.get());
}

static jlong nativeCreateFromBinder(JNIEnv* env, jclass clazz, jobject tokenBinderObj) {
    if (tokenBinderObj == nullptr) {
        return 0;
    }
    sp<IBinder> token(ibinderForJavaObject(env, tokenBinderObj));
    if (token == nullptr) {
        return 0;
    }
    sp<InputTransferToken> inputTransferToken = sp<InputTransferToken>::make(token);
    inputTransferToken->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(inputTransferToken.get());
}

static void nativeWriteToParcel(JNIEnv* env, jclass clazz, jlong nativeObj, jobject parcelObj) {
    InputTransferToken* inputTransferToken = reinterpret_cast<InputTransferToken*>(nativeObj);
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    inputTransferToken->writeToParcel(parcel);
}

static jlong nativeReadFromParcel(JNIEnv* env, jclass clazz, jobject parcelObj) {
    sp<InputTransferToken> inputTransferToken = sp<InputTransferToken>::make();
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    inputTransferToken->readFromParcel(parcel);
    inputTransferToken->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(inputTransferToken.get());
}

static jobject nativeGetBinderToken(JNIEnv* env, jclass clazz, jlong nativeObj) {
    sp<InputTransferToken> inputTransferToken = reinterpret_cast<InputTransferToken*>(nativeObj);
    return javaObjectForIBinder(env, inputTransferToken->mToken);
}

InputTransferToken* android_window_InputTransferToken_getNativeInputTransferToken(
        JNIEnv* env, jobject inputTransferTokenObj) {
    if (inputTransferTokenObj != nullptr &&
        env->IsInstanceOf(inputTransferTokenObj, gInputTransferTokenClassInfo.clazz)) {
        return reinterpret_cast<InputTransferToken*>(
                env->GetLongField(inputTransferTokenObj,
                                  gInputTransferTokenClassInfo.mNativeObject));
    } else {
        return nullptr;
    }
}

jobject android_window_InputTransferToken_getJavaInputTransferToken(
        JNIEnv* env, const InputTransferToken* inputTransferToken) {
    if (inputTransferToken == nullptr || env == nullptr) {
        return nullptr;
    }

    inputTransferToken->incStrong((void*)nativeCreate);
    return env->NewObject(gInputTransferTokenClassInfo.clazz, gInputTransferTokenClassInfo.ctor,
                          reinterpret_cast<jlong>(inputTransferToken));
}

static void release(InputTransferToken* inputTransferToken) {
    inputTransferToken->decStrong((void*)nativeCreate);
}

static jlong nativeGetNativeInputTransferTokenFinalizer(JNIEnv* env, jclass clazz) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&release));
}

static bool nativeEquals(JNIEnv* env, jclass clazz, jlong inputTransferTokenObj1,
                         jlong inputTransferTokenObj2) {
    sp<InputTransferToken> inputTransferToken1(
            reinterpret_cast<InputTransferToken*>(inputTransferTokenObj1));
    sp<InputTransferToken> inputTransferToken2(
            reinterpret_cast<InputTransferToken*>(inputTransferTokenObj2));

    return inputTransferToken1 == inputTransferToken2;
}

static const JNINativeMethod sInputTransferTokenMethods[] = {
        // clang-format off
    {"nativeCreate", "()J", (void*)nativeCreate},
    {"nativeCreate", "(Landroid/os/IBinder;)J", (void*)nativeCreateFromBinder},
    {"nativeWriteToParcel", "(JLandroid/os/Parcel;)V", (void*)nativeWriteToParcel},
    {"nativeReadFromParcel", "(Landroid/os/Parcel;)J", (void*)nativeReadFromParcel},
    {"nativeGetBinderToken", "(J)Landroid/os/IBinder;", (void*)nativeGetBinderToken},
    {"nativeGetNativeInputTransferTokenFinalizer", "()J", (void*)nativeGetNativeInputTransferTokenFinalizer},
        {"nativeEquals", "(JJ)Z", (void*) nativeEquals},
        // clang-format on
};

int register_android_window_InputTransferToken(JNIEnv* env) {
    int err = RegisterMethodsOrDie(env, "android/window/InputTransferToken",
                                   sInputTransferTokenMethods, NELEM(sInputTransferTokenMethods));
    jclass inputTransferTokenClass = FindClassOrDie(env, "android/window/InputTransferToken");
    gInputTransferTokenClassInfo.clazz = MakeGlobalRefOrDie(env, inputTransferTokenClass);
    gInputTransferTokenClassInfo.mNativeObject =
            GetFieldIDOrDie(env, gInputTransferTokenClassInfo.clazz, "mNativeObject", "J");
    gInputTransferTokenClassInfo.ctor =
            GetMethodIDOrDie(env, gInputTransferTokenClassInfo.clazz, "<init>", "(J)V");
    return err;
}

} // namespace android