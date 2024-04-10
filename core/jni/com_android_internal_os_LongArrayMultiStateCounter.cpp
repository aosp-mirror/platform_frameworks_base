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

static jlong native_init(jint stateCount, jint arrayLength) {
    battery::LongArrayMultiStateCounter *counter =
            new battery::LongArrayMultiStateCounter(stateCount, std::vector<uint64_t>(arrayLength));
    return reinterpret_cast<jlong>(counter);
}

static void native_dispose(void *nativePtr) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    delete counter;
}

static jlong native_getReleaseFunc() {
    return reinterpret_cast<jlong>(native_dispose);
}

static void native_setEnabled(jlong nativePtr, jboolean enabled, jlong timestamp) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    counter->setEnabled(enabled, timestamp);
}

static void native_setState(jlong nativePtr, jint state, jlong timestamp) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    counter->setState(state, timestamp);
}

static void native_copyStatesFrom(jlong nativePtrTarget, jlong nativePtrSource) {
    battery::LongArrayMultiStateCounter *counterTarget =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtrTarget);
    battery::LongArrayMultiStateCounter *counterSource =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtrSource);
    counterTarget->copyStatesFrom(*counterSource);
}

static void native_setValues(jlong nativePtr, jint state, jlong longArrayContainerNativePtr) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    std::vector<uint64_t> *vector =
            reinterpret_cast<std::vector<uint64_t> *>(longArrayContainerNativePtr);

    counter->setValue(state, *vector);
}

static void native_updateValues(jlong nativePtr, jlong longArrayContainerNativePtr,
                                jlong timestamp) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    std::vector<uint64_t> *vector =
            reinterpret_cast<std::vector<uint64_t> *>(longArrayContainerNativePtr);

    counter->updateValue(*vector, timestamp);
}

static void native_incrementValues(jlong nativePtr, jlong longArrayContainerNativePtr,
                                   jlong timestamp) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    std::vector<uint64_t> *vector =
            reinterpret_cast<std::vector<uint64_t> *>(longArrayContainerNativePtr);

    counter->incrementValue(*vector, timestamp);
}

static void native_addCounts(jlong nativePtr, jlong longArrayContainerNativePtr) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    std::vector<uint64_t> *vector =
            reinterpret_cast<std::vector<uint64_t> *>(longArrayContainerNativePtr);
    counter->addValue(*vector);
}

static void native_reset(jlong nativePtr) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    counter->reset();
}

static void native_getCounts(jlong nativePtr, jlong longArrayContainerNativePtr, jint state) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    std::vector<uint64_t> *vector =
            reinterpret_cast<std::vector<uint64_t> *>(longArrayContainerNativePtr);

    *vector = counter->getCount(state);
}

static jobject native_toString(JNIEnv *env, jclass, jlong nativePtr) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
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
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    ndk::ScopedAParcel parcel(AParcel_fromJavaParcel(env, jParcel));

    uint16_t stateCount = counter->getStateCount();
    THROW_AND_RETURN_ON_WRITE_ERROR(AParcel_writeInt32(parcel.get(), stateCount));

    // LongArrayMultiStateCounter has at least state 0
    const std::vector<uint64_t> &anyState = counter->getCount(0);
    THROW_AND_RETURN_ON_WRITE_ERROR(AParcel_writeInt32(parcel.get(), anyState.size()));

    for (battery::state_t state = 0; state < stateCount; state++) {
        THROW_AND_RETURN_ON_WRITE_ERROR(
                ndk::AParcel_writeVector(parcel.get(), counter->getCount(state)));
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

    auto counter = std::make_unique<battery::LongArrayMultiStateCounter>(stateCount,
                                                                         std::vector<uint64_t>(
                                                                                 arrayLength));

    std::vector<uint64_t> value;
    value.reserve(arrayLength);

    for (battery::state_t state = 0; state < stateCount; state++) {
        THROW_AND_RETURN_ON_READ_ERROR(ndk::AParcel_readVector(parcel.get(), &value));
        counter->setValue(state, value);
    }

    return reinterpret_cast<jlong>(counter.release());
}

static jint native_getStateCount(jlong nativePtr) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    return counter->getStateCount();
}

static jint native_getArrayLength(jlong nativePtr) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);

    // LongArrayMultiStateCounter has at least state 0
    const std::vector<uint64_t> &anyState = counter->getCount(0);
    return anyState.size();
}

static jlong native_init_LongArrayContainer(jint length) {
    return reinterpret_cast<jlong>(new std::vector<uint64_t>(length));
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
        // @CriticalNative
        {"native_setValues", "(JIJ)V", (void *)native_setValues},
        // @CriticalNative
        {"native_updateValues", "(JJJ)V", (void *)native_updateValues},
        // @CriticalNative
        {"native_incrementValues", "(JJJ)V", (void *)native_incrementValues},
        // @CriticalNative
        {"native_addCounts", "(JJ)V", (void *)native_addCounts},
        // @CriticalNative
        {"native_reset", "(J)V", (void *)native_reset},
        // @CriticalNative
        {"native_getCounts", "(JJI)V", (void *)native_getCounts},
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

/////////////////////// LongArrayMultiStateCounter.LongArrayContainer ////////////////////////

static void native_dispose_LongArrayContainer(jlong nativePtr) {
    std::vector<uint64_t> *vector = reinterpret_cast<std::vector<uint64_t> *>(nativePtr);
    delete vector;
}

static jlong native_getReleaseFunc_LongArrayContainer() {
    return reinterpret_cast<jlong>(native_dispose_LongArrayContainer);
}

static void native_setValues_LongArrayContainer(JNIEnv *env, jclass, jlong nativePtr,
                                                jlongArray jarray) {
    std::vector<uint64_t> *vector = reinterpret_cast<std::vector<uint64_t> *>(nativePtr);
    ScopedLongArrayRO scopedArray(env, jarray);
    const uint64_t *array = reinterpret_cast<const uint64_t *>(scopedArray.get());
    uint8_t size = scopedArray.size();

    // Boundary checks are performed in the Java layer
    std::copy(array, array + size, vector->data());
}

static void native_getValues_LongArrayContainer(JNIEnv *env, jclass, jlong nativePtr,
                                                jlongArray jarray) {
    std::vector<uint64_t> *vector = reinterpret_cast<std::vector<uint64_t> *>(nativePtr);
    ScopedLongArrayRW scopedArray(env, jarray);

    // Boundary checks are performed in the Java layer
    std::copy(vector->data(), vector->data() + vector->size(), scopedArray.get());
}

static jboolean native_combineValues_LongArrayContainer(JNIEnv *env, jclass, jlong nativePtr,
                                                        jlongArray jarray, jintArray jindexMap) {
    std::vector<uint64_t> *vector = reinterpret_cast<std::vector<uint64_t> *>(nativePtr);
    ScopedLongArrayRW scopedArray(env, jarray);
    ScopedIntArrayRO scopedIndexMap(env, jindexMap);

    const uint64_t *data = vector->data();
    uint64_t *array = reinterpret_cast<uint64_t *>(scopedArray.get());
    const uint8_t size = scopedArray.size();

    for (int i = 0; i < size; i++) {
        array[i] = 0;
    }

    bool nonZero = false;
    for (size_t i = 0; i < vector->size(); i++) {
        jint index = scopedIndexMap[i];
        if (index < 0 || index >= size) {
            jniThrowExceptionFmt(env, "java/lang/IndexOutOfBoundsException",
                                 "Index %d is out of bounds: [0, %d]", index, size - 1);
            return false;
        }

        if (data[i] != 0L) {
            array[index] += data[i];
            nonZero = true;
        }
    }

    return nonZero;
}

static const JNINativeMethod g_LongArrayContainer_methods[] = {
        // @CriticalNative
        {"native_init", "(I)J", (void *)native_init_LongArrayContainer},
        // @CriticalNative
        {"native_getReleaseFunc", "()J", (void *)native_getReleaseFunc_LongArrayContainer},
        // @FastNative
        {"native_setValues", "(J[J)V", (void *)native_setValues_LongArrayContainer},
        // @FastNative
        {"native_getValues", "(J[J)V", (void *)native_getValues_LongArrayContainer},
        // @FastNative
        {"native_combineValues", "(J[J[I)Z", (void *)native_combineValues_LongArrayContainer},
};

int register_com_android_internal_os_LongArrayMultiStateCounter(JNIEnv *env) {
    // 0 represents success, thus "|" and not "&"
    return RegisterMethodsOrDie(env, "com/android/internal/os/LongArrayMultiStateCounter",
                                g_LongArrayMultiStateCounter_methods,
                                NELEM(g_LongArrayMultiStateCounter_methods)) |
            RegisterMethodsOrDie(env,
                                 "com/android/internal/os/LongArrayMultiStateCounter"
                                 "$LongArrayContainer",
                                 g_LongArrayContainer_methods, NELEM(g_LongArrayContainer_methods));
}

} // namespace android
