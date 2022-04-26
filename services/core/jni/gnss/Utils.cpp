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

#define LOG_TAG "GnssUtilsJni"

#include "Utils.h"

namespace android {

namespace {

thread_local std::unique_ptr<ScopedJniThreadAttach> tJniThreadAttacher;

} // anonymous namespace

// Define Java method signatures for all known types.
template <>
const char* const JavaMethodHelper<uint8_t>::signature_ = "(B)V";
template <>
const char* const JavaMethodHelper<int8_t>::signature_ = "(B)V";
template <>
const char* const JavaMethodHelper<int16_t>::signature_ = "(S)V";
template <>
const char* const JavaMethodHelper<uint16_t>::signature_ = "(S)V";
template <>
const char* const JavaMethodHelper<int32_t>::signature_ = "(I)V";
template <>
const char* const JavaMethodHelper<uint32_t>::signature_ = "(I)V";
template <>
const char* const JavaMethodHelper<int64_t>::signature_ = "(J)V";
template <>
const char* const JavaMethodHelper<uint64_t>::signature_ = "(J)V";
template <>
const char* const JavaMethodHelper<float>::signature_ = "(F)V";
template <>
const char* const JavaMethodHelper<double>::signature_ = "(D)V";
template <>
const char* const JavaMethodHelper<bool>::signature_ = "(Z)V";
template <>
const char* const JavaMethodHelper<jstring>::signature_ = "(Ljava/lang/String;)V";
template <>
const char* const JavaMethodHelper<jdoubleArray>::signature_ = "([D)V";

jboolean checkAidlStatus(const android::binder::Status& status, const char* errorMessage) {
    if (!status.isOk()) {
        ALOGE("%s AIDL transport error: %s", errorMessage, status.toString8().c_str());
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jboolean checkHidlReturn(hardware::Return<bool>& result, const char* errorMessage) {
    if (!result.isOk()) {
        logHidlError(result, errorMessage);
        return JNI_FALSE;
    } else if (!result) {
        ALOGE("%s", errorMessage);
        return JNI_FALSE;
    } else {
        return JNI_TRUE;
    }
}

void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

void callObjectMethodIgnoringResult(JNIEnv* env, jobject obj, jmethodID mid, ...) {
    va_list args;
    va_start(args, mid);
    env->DeleteLocalRef(env->CallObjectMethodV(obj, mid, args));
    va_end(args);
}

JavaObject::JavaObject(JNIEnv* env, jclass clazz, jmethodID defaultCtor)
      : env_(env), clazz_(clazz) {
    object_ = env_->NewObject(clazz_, defaultCtor);
}

JavaObject::JavaObject(JNIEnv* env, jclass clazz, jmethodID stringCtor, const char* sz_arg_1)
      : env_(env), clazz_(clazz) {
    jstring szArg = env->NewStringUTF(sz_arg_1);
    object_ = env_->NewObject(clazz_, stringCtor, szArg);
    if (szArg) {
        env_->DeleteLocalRef(szArg);
    }
}

JavaObject::JavaObject(JNIEnv* env, jclass clazz, jobject object)
      : env_(env), clazz_(clazz), object_(object) {}

template <>
void JavaObject::callSetter(const char* method_name, uint8_t* value, size_t size) {
    jbyteArray array = env_->NewByteArray(size);
    env_->SetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte*>(value));
    jmethodID method = env_->GetMethodID(clazz_, method_name, "([B)V");
    env_->CallVoidMethod(object_, method, array);
    env_->DeleteLocalRef(array);
}

JNIEnv* getJniEnv() {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    /*
     * If env is nullptr, the thread is not already attached to
     * JNI. It is attached below and the destructor for ScopedJniThreadAttach
     * will detach it on thread exit.
     */
    if (env == nullptr) {
        tJniThreadAttacher.reset(new ScopedJniThreadAttach());
        env = tJniThreadAttacher->getEnv();
    }

    return env;
}

} // namespace android
