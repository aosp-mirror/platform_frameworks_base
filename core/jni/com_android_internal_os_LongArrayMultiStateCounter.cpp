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
#include <nativehelper/ScopedPrimitiveArray.h>

#include <cstring>

#include "LongArrayMultiStateCounter.h"
#include "core_jni_helpers.h"

namespace android {
namespace battery {

/**
 * Implementation of Uint64Array that wraps a Java long[]. Since it uses the "critical"
 * version of JNI array access (halting GC), any usage of this class must be extra quick.
 */
class JavaUint64Array : public Uint64Array {
    JNIEnv *mEnv;
    jlongArray mJavaArray;
    uint64_t *mData;

public:
    JavaUint64Array(JNIEnv *env, jlongArray values) : Uint64Array(env->GetArrayLength(values)) {
        mEnv = env;
        mJavaArray = values;
        mData = reinterpret_cast<uint64_t *>(mEnv->GetPrimitiveArrayCritical(mJavaArray, nullptr));
    }

    ~JavaUint64Array() override {
        mEnv->ReleasePrimitiveArrayCritical(mJavaArray, mData, 0);
    }

    const uint64_t *data() const override {
        return mData;
    }
};

static jlong native_init(jint stateCount, jint arrayLength) {
    auto *counter = new LongArrayMultiStateCounter(stateCount, Uint64Array(arrayLength));
    return reinterpret_cast<jlong>(counter);
}

static void native_dispose(void *nativePtr) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    delete counter;
}

static jlong native_getReleaseFunc() {
    return reinterpret_cast<jlong>(native_dispose);
}

static void native_setEnabled(jlong nativePtr, jboolean enabled, jlong timestamp) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    counter->setEnabled(enabled, timestamp);
}

static void native_setState(jlong nativePtr, jint state, jlong timestamp) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    counter->setState(state, timestamp);
}

static void native_copyStatesFrom(jlong nativePtrTarget, jlong nativePtrSource) {
    auto *counterTarget = reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtrTarget);
    auto *counterSource = reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtrSource);
    counterTarget->copyStatesFrom(*counterSource);
}

static void native_setValues(JNIEnv *env, jclass, jlong nativePtr, jint state, jlongArray values) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    counter->setValue(state, JavaUint64Array(env, values));
}

static void native_updateValues(JNIEnv *env, jclass, jlong nativePtr, jlongArray values,
                                jlong timestamp) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    counter->updateValue(JavaUint64Array(env, values), timestamp);
}

static void native_incrementValues(JNIEnv *env, jclass, jlong nativePtr, jlongArray values,
                                   jlong timestamp) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    counter->incrementValue(JavaUint64Array(env, values), timestamp);
}

static void native_addCounts(JNIEnv *env, jclass, jlong nativePtr, jlongArray values) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    counter->addValue(JavaUint64Array(env, values));
}

static void native_reset(jlong nativePtr) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    counter->reset();
}

static void native_getCounts(JNIEnv *env, jclass, jlong nativePtr, jlongArray values, jint state) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    ScopedLongArrayRW scopedArray(env, values);
    auto *data = counter->getCount(state).data();
    auto size = env->GetArrayLength(values);
    auto *outData = scopedArray.get();
    if (data == nullptr) {
        memset(outData, 0, size * sizeof(uint64_t));
    } else {
        memcpy(outData, data, size * sizeof(uint64_t));
    }
}

static jobject native_toString(JNIEnv *env, jclass, jlong nativePtr) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    return env->NewStringUTF(counter->toString().c_str());
}

static void throwWriteRE(JNIEnv *env, binder_status_t status) {
    ALOGE("Could not write LongArrayMultiStateCounter to Parcel, status = %d", status);
    jniThrowRuntimeException(env, "Could not write LongArrayMultiStateCounter to Parcel");
}

#define THROW_AND_RETURN_ON_WRITE_ERROR(expr) \
    {                                         \
        binder_status_t status = expr;        \
        if (status != STATUS_OK) {            \
            throwWriteRE(env, status);        \
            return;                           \
        }                                     \
    }

static void native_writeToParcel(JNIEnv *env, jclass, jlong nativePtr, jobject jParcel,
                                 jint flags) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    ndk::ScopedAParcel parcel(AParcel_fromJavaParcel(env, jParcel));

    uint16_t stateCount = counter->getStateCount();
    THROW_AND_RETURN_ON_WRITE_ERROR(AParcel_writeInt32(parcel.get(), stateCount));

    // LongArrayMultiStateCounter has at least state 0
    const Uint64Array &anyState = counter->getCount(0);
    THROW_AND_RETURN_ON_WRITE_ERROR(AParcel_writeInt32(parcel.get(), anyState.size()));

    for (battery::state_t state = 0; state < stateCount; state++) {
        const Uint64Array &value = counter->getCount(state);
        if (value.data() == nullptr) {
            THROW_AND_RETURN_ON_WRITE_ERROR(AParcel_writeBool(parcel.get(), false));
        } else {
            THROW_AND_RETURN_ON_WRITE_ERROR(AParcel_writeBool(parcel.get(), true));
            for (size_t i = 0; i < anyState.size(); i++) {
                THROW_AND_RETURN_ON_WRITE_ERROR(AParcel_writeUint64(parcel.get(), value.data()[i]));
            }
        }
    }
}

static void throwReadException(JNIEnv *env, binder_status_t status) {
    ALOGE("Could not read LongArrayMultiStateCounter from Parcel, status = %d", status);
    jniThrowException(env, "android.os.BadParcelableException",
                      "Could not read LongArrayMultiStateCounter from Parcel");
}

#define THROW_AND_RETURN_ON_READ_ERROR(expr) \
    {                                        \
        binder_status_t status = expr;       \
        if (status != STATUS_OK) {           \
            throwReadException(env, status); \
            return 0L;                       \
        }                                    \
    }

static jlong native_initFromParcel(JNIEnv *env, jclass, jobject jParcel) {
    ndk::ScopedAParcel parcel(AParcel_fromJavaParcel(env, jParcel));

    int32_t stateCount;
    THROW_AND_RETURN_ON_READ_ERROR(AParcel_readInt32(parcel.get(), &stateCount));

    if (stateCount < 0 || stateCount > 0xEFFF) {
        throwReadException(env, STATUS_INVALID_OPERATION);
        return 0L;
    }

    int32_t arrayLength;
    THROW_AND_RETURN_ON_READ_ERROR(AParcel_readInt32(parcel.get(), &arrayLength));

    auto counter =
            std::make_unique<LongArrayMultiStateCounter>(stateCount, Uint64Array(arrayLength));
    Uint64ArrayRW array(arrayLength);
    for (battery::state_t state = 0; state < stateCount; state++) {
        bool hasValues;
        THROW_AND_RETURN_ON_READ_ERROR(AParcel_readBool(parcel.get(), &hasValues));
        if (hasValues) {
            for (int i = 0; i < arrayLength; i++) {
                THROW_AND_RETURN_ON_READ_ERROR(
                        AParcel_readUint64(parcel.get(), &(array.dataRW()[i])));
            }
            counter->setValue(state, array);
        }
    }

    return reinterpret_cast<jlong>(counter.release());
}

static jint native_getStateCount(jlong nativePtr) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);
    return counter->getStateCount();
}

static jint native_getArrayLength(jlong nativePtr) {
    auto *counter = reinterpret_cast<LongArrayMultiStateCounter *>(nativePtr);

    // LongArrayMultiStateCounter has at least state 0
    const Uint64Array &anyState = counter->getCount(0);
    return anyState.size();
}

static const JNINativeMethod g_LongArrayMultiStateCounter_methods[] = {
        // @CriticalNative
        {"native_init", "(II)J", (void *)native_init},
        // @CriticalNative
        {"native_getReleaseFunc", "()J", (void *)native_getReleaseFunc},
        // @CriticalNative
        {"native_setEnabled", "(JZJ)V", (void *)native_setEnabled},
        // @CriticalNative
        {"native_setState", "(JIJ)V", (void *)native_setState},
        // @CriticalNative
        {"native_copyStatesFrom", "(JJ)V", (void *)native_copyStatesFrom},
        // @FastNative
        {"native_setValues", "(JI[J)V", (void *)native_setValues},
        // @FastNative
        {"native_updateValues", "(J[JJ)V", (void *)native_updateValues},
        // @FastNative
        {"native_incrementValues", "(J[JJ)V", (void *)native_incrementValues},
        // @FastNative
        {"native_addCounts", "(J[J)V", (void *)native_addCounts},
        // @CriticalNative
        {"native_reset", "(J)V", (void *)native_reset},
        // @FastNative
        {"native_getCounts", "(J[JI)V", (void *)native_getCounts},
        // @FastNative
        {"native_toString", "(J)Ljava/lang/String;", (void *)native_toString},
        // @FastNative
        {"native_writeToParcel", "(JLandroid/os/Parcel;I)V", (void *)native_writeToParcel},
        // @FastNative
        {"native_initFromParcel", "(Landroid/os/Parcel;)J", (void *)native_initFromParcel},
        // @CriticalNative
        {"native_getStateCount", "(J)I", (void *)native_getStateCount},
        // @CriticalNative
        {"native_getArrayLength", "(J)I", (void *)native_getArrayLength},
};

} // namespace battery

int register_com_android_internal_os_LongArrayMultiStateCounter(JNIEnv *env) {
    // 0 represents success, thus "|" and not "&"
    return RegisterMethodsOrDie(env, "com/android/internal/os/LongArrayMultiStateCounter",
                                battery::g_LongArrayMultiStateCounter_methods,
                                NELEM(battery::g_LongArrayMultiStateCounter_methods));
}
} // namespace android
