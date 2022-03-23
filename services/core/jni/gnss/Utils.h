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

#ifndef _ANDROID_SERVER_GNSS_UTILS_H
#define _ANDROID_SERVER_GNSS_UTILS_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/1.0/IGnss.h>
#include <android/hardware/gnss/BnGnss.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "jni.h"

namespace android {

namespace {

// Must match the value from GnssMeasurement.java
const uint32_t ADR_STATE_HALF_CYCLE_REPORTED = (1 << 4);

} // anonymous namespace

extern jobject mCallbacksObj;

jboolean checkHidlReturn(hardware::Return<bool>& result, const char* errorMessage);

jboolean checkAidlStatus(const android::binder::Status& status, const char* errorMessage);

void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName);

template <class T>
void logHidlError(hardware::Return<T>& result, const char* errorMessage) {
    ALOGE("%s HIDL transport error: %s", errorMessage, result.description().c_str());
}

template <class T>
jboolean checkHidlReturn(hardware::Return<T>& result, const char* errorMessage) {
    if (!result.isOk()) {
        logHidlError(result, errorMessage);
        return JNI_FALSE;
    } else {
        return JNI_TRUE;
    }
}

template <class T>
jboolean checkHidlReturn(hardware::Return<sp<T>>& result, const char* errorMessage) {
    if (!result.isOk()) {
        logHidlError(result, errorMessage);
        return JNI_FALSE;
    } else if ((sp<T>)result == nullptr) {
        return JNI_FALSE;
    } else {
        return JNI_TRUE;
    }
}

template <class T>
class JavaMethodHelper {
public:
    // Helper function to call setter on a Java object.
    static void callJavaMethod(JNIEnv* env, jclass clazz, jobject object, const char* method_name,
                               T value);

private:
    static const char* const signature_;
};

// Define Java method signatures for all known types.
template <>
const char* const JavaMethodHelper<uint8_t>::signature_;
template <>
const char* const JavaMethodHelper<int8_t>::signature_;
template <>
const char* const JavaMethodHelper<int16_t>::signature_;
template <>
const char* const JavaMethodHelper<uint16_t>::signature_;
template <>
const char* const JavaMethodHelper<int32_t>::signature_;
template <>
const char* const JavaMethodHelper<uint32_t>::signature_;
template <>
const char* const JavaMethodHelper<int64_t>::signature_;
template <>
const char* const JavaMethodHelper<uint64_t>::signature_;
template <>
const char* const JavaMethodHelper<float>::signature_;
template <>
const char* const JavaMethodHelper<double>::signature_;
template <>
const char* const JavaMethodHelper<bool>::signature_;
template <>
const char* const JavaMethodHelper<jstring>::signature_;
template <>
const char* const JavaMethodHelper<jdoubleArray>::signature_;

template <class T>
void JavaMethodHelper<T>::callJavaMethod(JNIEnv* env, jclass clazz, jobject object,
                                         const char* method_name, T value) {
    jmethodID method = env->GetMethodID(clazz, method_name, signature_);
    env->CallVoidMethod(object, method, value);
}

class JavaObject {
public:
    JavaObject(JNIEnv* env, jclass clazz, jmethodID defaultCtor);
    JavaObject(JNIEnv* env, jclass clazz, jmethodID stringCtor, const char* sz_arg_1);
    JavaObject(JNIEnv* env, jclass clazz, jobject object);

    virtual ~JavaObject() = default;

    template <class T>
    void callSetter(const char* method_name, T value);
    template <class T>
    void callSetter(const char* method_name, T* value, size_t size);
    jobject get() { return object_; }

private:
    JNIEnv* env_;
    jclass clazz_;
    jobject object_;
};

template <class T>
void JavaObject::callSetter(const char* method_name, T value) {
    JavaMethodHelper<T>::callJavaMethod(env_, clazz_, object_, method_name, value);
}

#define SET(setter, value) object.callSetter("set" #setter, (value))

class ScopedJniThreadAttach {
public:
    static JavaVM* sJvm;

    ScopedJniThreadAttach() {
        /*
         * attachResult will also be JNI_OK if the thead was already attached to
         * JNI before the call to AttachCurrentThread().
         */
        jint attachResult = sJvm->AttachCurrentThread(&mEnv, nullptr);
        LOG_ALWAYS_FATAL_IF(attachResult != JNI_OK, "Unable to attach thread. Error %d",
                            attachResult);
    }

    ~ScopedJniThreadAttach() {
        jint detachResult = sJvm->DetachCurrentThread();
        /*
         * Return if the thread was already detached. Log error for any other
         * failure.
         */
        if (detachResult == JNI_EDETACHED) {
            return;
        }

        LOG_ALWAYS_FATAL_IF(detachResult != JNI_OK, "Unable to detach thread. Error %d",
                            detachResult);
    }

    JNIEnv* getEnv() {
        /*
         * Checking validity of mEnv in case the thread was detached elsewhere.
         */
        LOG_ALWAYS_FATAL_IF(AndroidRuntime::getJNIEnv() != mEnv);
        return mEnv;
    }

private:
    JNIEnv* mEnv = nullptr;
};

JNIEnv* getJniEnv();

} // namespace android

#endif // _ANDROID_SERVER_GNSS_UTILS_H
