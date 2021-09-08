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

#include <nativehelper/ScopedPrimitiveArray.h>
#include <cstring>
#include "LongArrayMultiStateCounter.h"
#include "core_jni_helpers.h"

namespace android {

static jlong native_init(jint stateCount, jint arrayLength, jint initialState, jlong timestamp) {
    battery::LongArrayMultiStateCounter *counter =
            new battery::LongArrayMultiStateCounter(stateCount, initialState,
                                                    std::vector<uint64_t>(arrayLength), timestamp);
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

static void native_setState(jlong nativePtr, jint state, jlong timestamp) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    counter->setState(state, timestamp);
}

static void native_updateValues(jlong nativePtr, jlong longArrayContainerNativePtr,
                                jlong timestamp) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    std::vector<uint64_t> *vector =
            reinterpret_cast<std::vector<uint64_t> *>(longArrayContainerNativePtr);

    counter->updateValue(*vector, timestamp);
}

static void native_getCounts(jlong nativePtr, jlong longArrayContainerNativePtr, jint state) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    std::vector<uint64_t> *vector =
            reinterpret_cast<std::vector<uint64_t> *>(longArrayContainerNativePtr);

    *vector = counter->getCount(state);
}

static jobject native_toString(JNIEnv *env, jobject self, jlong nativePtr) {
    battery::LongArrayMultiStateCounter *counter =
            reinterpret_cast<battery::LongArrayMultiStateCounter *>(nativePtr);
    return env->NewStringUTF(counter->toString().c_str());
}

static jlong native_init_LongArrayContainer(jint length) {
    return reinterpret_cast<jlong>(new std::vector<uint64_t>(length));
}

static const JNINativeMethod g_LongArrayMultiStateCounter_methods[] = {
        // @CriticalNative
        {"native_init", "(IIIJ)J", (void *)native_init},
        // @CriticalNative
        {"native_getReleaseFunc", "()J", (void *)native_getReleaseFunc},
        // @CriticalNative
        {"native_setState", "(JIJ)V", (void *)native_setState},
        // @CriticalNative
        {"native_updateValues", "(JJJ)V", (void *)native_updateValues},
        // @CriticalNative
        {"native_getCounts", "(JJI)V", (void *)native_getCounts},
        // @FastNative
        {"native_toString", "(J)Ljava/lang/String;", (void *)native_toString},
};

/////////////////////// LongArrayMultiStateCounter.LongArrayContainer ////////////////////////

static void native_dispose_LongArrayContainer(jlong nativePtr) {
    std::vector<uint64_t> *vector = reinterpret_cast<std::vector<uint64_t> *>(nativePtr);
    delete vector;
}

static jlong native_getReleaseFunc_LongArrayContainer() {
    return reinterpret_cast<jlong>(native_dispose_LongArrayContainer);
}

static void native_setValues_LongArrayContainer(JNIEnv *env, jobject self, jlong nativePtr,
                                                jlongArray jarray) {
    std::vector<uint64_t> *vector = reinterpret_cast<std::vector<uint64_t> *>(nativePtr);
    ScopedLongArrayRO scopedArray(env, jarray);
    const uint64_t *array = reinterpret_cast<const uint64_t *>(scopedArray.get());
    uint8_t size = scopedArray.size();

    // Boundary checks are performed in the Java layer
    std::copy(array, array + size, vector->data());
}

static void native_getValues_LongArrayContainer(JNIEnv *env, jobject self, jlong nativePtr,
                                                jlongArray jarray) {
    std::vector<uint64_t> *vector = reinterpret_cast<std::vector<uint64_t> *>(nativePtr);
    ScopedLongArrayRW scopedArray(env, jarray);

    // Boundary checks are performed in the Java layer
    std::copy(vector->data(), vector->data() + vector->size(), scopedArray.get());
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
