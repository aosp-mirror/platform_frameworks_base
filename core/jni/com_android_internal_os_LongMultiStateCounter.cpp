/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <android/binder_parcel.h>
#include <android/binder_parcel_jni.h>
#include <android/binder_parcel_utils.h>
#include <android_runtime/Log.h>

#include <cstring>

#include "MultiStateCounter.h"
#include "core_jni_helpers.h"

namespace android {

namespace battery {

typedef battery::MultiStateCounter<int64_t> LongMultiStateCounter;

template <>
bool LongMultiStateCounter::delta(const int64_t &previousValue, const int64_t &newValue,
                                  int64_t *outValue) const {
    *outValue = newValue - previousValue;
    return *outValue >= 0;
}

template <>
void LongMultiStateCounter::add(int64_t *value1, const int64_t &value2, const uint64_t numerator,
                                const uint64_t denominator) const {
    if (numerator != denominator) {
        // The caller ensures that denominator != 0
        *value1 += value2 * numerator / denominator;
    } else {
        *value1 += value2;
    }
}

template <>
std::string LongMultiStateCounter::valueToString(const int64_t &v) const {
    return std::to_string(v);
}

} // namespace battery

static inline battery::LongMultiStateCounter *asLongMultiStateCounter(const jlong nativePtr) {
    return reinterpret_cast<battery::LongMultiStateCounter *>(nativePtr);
}

static jlong native_init(jint stateCount) {
    battery::LongMultiStateCounter *counter = new battery::LongMultiStateCounter(stateCount, 0);
    return reinterpret_cast<jlong>(counter);
}

static void native_dispose(void *nativePtr) {
    delete reinterpret_cast<battery::LongMultiStateCounter *>(nativePtr);
}

static jlong native_getReleaseFunc() {
    return reinterpret_cast<jlong>(native_dispose);
}

static void native_setEnabled(jlong nativePtr, jboolean enabled, jlong timestamp) {
    asLongMultiStateCounter(nativePtr)->setEnabled(enabled, timestamp);
}

static void native_setState(jlong nativePtr, jint state, jlong timestamp) {
    asLongMultiStateCounter(nativePtr)->setState(state, timestamp);
}

static jlong native_updateValue(jlong nativePtr, jlong value, jlong timestamp) {
    return (jlong)asLongMultiStateCounter(nativePtr)->updateValue((int64_t)value, timestamp);
}

static void native_incrementValue(jlong nativePtr, jlong count, jlong timestamp) {
    asLongMultiStateCounter(nativePtr)->incrementValue(count, timestamp);
}

static void native_addCount(jlong nativePtr, jlong count) {
    asLongMultiStateCounter(nativePtr)->addValue(count);
}

static void native_reset(jlong nativePtr) {
    asLongMultiStateCounter(nativePtr)->reset();
}

static jlong native_getCount(jlong nativePtr, jint state) {
    return asLongMultiStateCounter(nativePtr)->getCount(state);
}

static jobject native_toString(JNIEnv *env, jobject self, jlong nativePtr) {
    return env->NewStringUTF(asLongMultiStateCounter(nativePtr)->toString().c_str());
}

static void throwWriteRE(JNIEnv *env, binder_status_t status) {
    ALOGE("Could not write LongMultiStateCounter to Parcel, status = %d", status);
    jniThrowRuntimeException(env, "Could not write LongMultiStateCounter to Parcel");
}

#define THROW_ON_WRITE_ERROR(expr)     \
    {                                  \
        binder_status_t status = expr; \
        if (status != STATUS_OK) {     \
            throwWriteRE(env, status); \
        }                              \
    }

static void native_writeToParcel(JNIEnv *env, jobject self, jlong nativePtr, jobject jParcel,
                                 jint flags) {
    battery::LongMultiStateCounter *counter = asLongMultiStateCounter(nativePtr);
    ndk::ScopedAParcel parcel(AParcel_fromJavaParcel(env, jParcel));

    uint16_t stateCount = counter->getStateCount();
    THROW_ON_WRITE_ERROR(AParcel_writeInt32(parcel.get(), stateCount));

    for (battery::state_t state = 0; state < stateCount; state++) {
        THROW_ON_WRITE_ERROR(AParcel_writeInt64(parcel.get(), counter->getCount(state)));
    }
}

static void throwReadRE(JNIEnv *env, binder_status_t status) {
    ALOGE("Could not read LongMultiStateCounter from Parcel, status = %d", status);
    jniThrowRuntimeException(env, "Could not read LongMultiStateCounter from Parcel");
}

#define THROW_ON_READ_ERROR(expr)      \
    {                                  \
        binder_status_t status = expr; \
        if (status != STATUS_OK) {     \
            throwReadRE(env, status);  \
        }                              \
    }

static jlong native_initFromParcel(JNIEnv *env, jclass theClass, jobject jParcel) {
    ndk::ScopedAParcel parcel(AParcel_fromJavaParcel(env, jParcel));

    int32_t stateCount;
    THROW_ON_READ_ERROR(AParcel_readInt32(parcel.get(), &stateCount));

    battery::LongMultiStateCounter *counter = new battery::LongMultiStateCounter(stateCount, 0);

    for (battery::state_t state = 0; state < stateCount; state++) {
        int64_t value;
        THROW_ON_READ_ERROR(AParcel_readInt64(parcel.get(), &value));
        counter->setValue(state, value);
    }

    return reinterpret_cast<jlong>(counter);
}

static jint native_getStateCount(jlong nativePtr) {
    return asLongMultiStateCounter(nativePtr)->getStateCount();
}

static const JNINativeMethod g_methods[] = {
        // @CriticalNative
        {"native_init", "(I)J", (void *)native_init},
        // @CriticalNative
        {"native_getReleaseFunc", "()J", (void *)native_getReleaseFunc},
        // @CriticalNative
        {"native_setEnabled", "(JZJ)V", (void *)native_setEnabled},
        // @CriticalNative
        {"native_setState", "(JIJ)V", (void *)native_setState},
        // @CriticalNative
        {"native_updateValue", "(JJJ)J", (void *)native_updateValue},
        // @CriticalNative
        {"native_incrementValue", "(JJJ)V", (void *)native_incrementValue},
        // @CriticalNative
        {"native_addCount", "(JJ)V", (void *)native_addCount},
        // @CriticalNative
        {"native_reset", "(J)V", (void *)native_reset},
        // @CriticalNative
        {"native_getCount", "(JI)J", (void *)native_getCount},
        // @FastNative
        {"native_toString", "(J)Ljava/lang/String;", (void *)native_toString},
        // @FastNative
        {"native_writeToParcel", "(JLandroid/os/Parcel;I)V", (void *)native_writeToParcel},
        // @FastNative
        {"native_initFromParcel", "(Landroid/os/Parcel;)J", (void *)native_initFromParcel},
        // @CriticalNative
        {"native_getStateCount", "(J)I", (void *)native_getStateCount},
};

int register_com_android_internal_os_LongMultiStateCounter(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/LongMultiStateCounter", g_methods,
                                NELEM(g_methods));
}

} // namespace android
