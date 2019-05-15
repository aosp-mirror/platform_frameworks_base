/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <jni.h>

extern jint android_util_MemoryIntArrayTest_createAshmem(JNIEnv* env,
        jobject clazz, jstring name, jint size);
extern void android_util_MemoryIntArrayTest_setAshmemSize(JNIEnv* env,
       jobject clazz, jint fd, jint size);

extern "C" {
    JNIEXPORT jint JNICALL Java_android_util_MemoryIntArrayTest_nativeCreateAshmem(
            JNIEnv * env, jobject obj, jstring name, jint size);
    JNIEXPORT void JNICALL Java_android_util_MemoryIntArrayTest_nativeSetAshmemSize(
            JNIEnv * env, jobject obj, jint fd, jint size);
};

JNIEXPORT jint JNICALL Java_android_util_MemoryIntArrayTest_nativeCreateAshmem(
        __attribute__((unused)) JNIEnv * env,__attribute__((unused)) jobject obj,
        jstring name, jint size)
{
    return android_util_MemoryIntArrayTest_createAshmem(env, obj, name, size);
}

JNIEXPORT void JNICALL Java_android_util_MemoryIntArrayTest_nativeSetAshmemSize(
        __attribute__((unused)) JNIEnv * env,__attribute__((unused)) jobject obj,
        jint fd, jint size)
{
    android_util_MemoryIntArrayTest_setAshmemSize(env, obj, fd, size);
}
