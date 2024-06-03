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

#include <nativehelper/JNIHelp.h>
#include <atomic>
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

// JNI methods for RavenwoodJniTest

static jint add(JNIEnv* env, jclass clazz, jint a, jint b) {
    return a + b;
}

static const JNINativeMethod sMethods_JniTest[] =
{
    { "add", "(II)I", (void*)add },
};

// JNI methods for RavenwoodNativeAllocationRegistryTest
std::atomic<int> numTotalAlloc = 0;

class NarTestData {
public:
    NarTestData(jint v): value(v) {
        numTotalAlloc++;
    }

    ~NarTestData() {
        value = -1;
        numTotalAlloc--;
    }

    volatile jint value;
};

static jlong NarTestData_nMalloc(JNIEnv* env, jclass clazz, jint value) {
    NarTestData* p = new NarTestData(value);
    return reinterpret_cast<jlong>(p);
}

static jint NarTestData_nGet(JNIEnv* env, jclass clazz, jlong ptr) {
    NarTestData* p = reinterpret_cast<NarTestData*>(ptr);
    return p->value;
}

static void NarTestData_free(jlong ptr) {
    NarTestData* p = reinterpret_cast<NarTestData*>(ptr);
    delete p;
}

static jlong NarTestData_nGetNativeFinalizer(JNIEnv* env, jclass clazz) {
    return reinterpret_cast<jlong>(NarTestData_free);
}

static jint NarTestData_nGetTotalAlloc(JNIEnv* env, jclass clazz) {
    return numTotalAlloc;
}

static const JNINativeMethod sMethods_NarTestData[] =
{
    { "nMalloc", "(I)J", (void*)NarTestData_nMalloc },
    { "nGet", "(J)I", (void*)NarTestData_nGet },
    { "nGetNativeFinalizer", "()J", (void*)NarTestData_nGetNativeFinalizer },
    { "nGetTotalAlloc", "()I", (void*)NarTestData_nGetTotalAlloc },
};

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("GetEnv failed!");
        return result;
    }
    ALOG_ASSERT(env, "Could not retrieve the env!");

    ALOGI("%s: JNI_OnLoad", __FILE__);

    int res = jniRegisterNativeMethods(env,
            "com/android/ravenwoodtest/bivalenttest/RavenwoodJniTest",
            sMethods_JniTest, NELEM(sMethods_JniTest));
    if (res < 0) {
        return res;
    }
    res = jniRegisterNativeMethods(env,
            "com/android/ravenwoodtest/bivalenttest/RavenwoodNativeAllocationRegistryTest$Data",
            sMethods_NarTestData, NELEM(sMethods_NarTestData));
    if (res < 0) {
        return res;
    }

    return JNI_VERSION_1_4;
}
