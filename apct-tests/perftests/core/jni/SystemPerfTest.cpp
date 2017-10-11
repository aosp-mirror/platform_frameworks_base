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

#define NELEM(x) ((int) (sizeof(x) / sizeof((x)[0])))

static void jintarrayArgumentNoop(JNIEnv*, jclass, jintArray, jint) {
}

static jint jintarrayGetLength(JNIEnv* env, jclass, jintArray jarray) {
    const jsize len = env->GetArrayLength(jarray);
    return static_cast<jint>(len);
}

static jint jintarrayCriticalAccess(JNIEnv* env, jclass, jintArray jarray, jint index) {
    const jsize len = env->GetArrayLength(jarray);
    if (index < 0 || index >= len) {
        return -1;
    }
    jint* data = (jint*) env->GetPrimitiveArrayCritical(jarray, 0);
    jint ret = data[index];
    env->ReleasePrimitiveArrayCritical(jarray, data, 0);
    return ret;
}

static jint jintarrayBasicAccess(JNIEnv* env, jclass, jintArray jarray, jint index) {
    const jsize len = env->GetArrayLength(jarray);
    if (index < 0 || index >= len) {
        return -1;
    }
    jint* data = env->GetIntArrayElements(jarray, 0);
    jint ret = data[index];
    env->ReleaseIntArrayElements(jarray, data, 0);
    return ret;
}

static const JNINativeMethod sMethods[] = {
    {"jintarrayArgumentNoop", "([II)V", (void *) jintarrayArgumentNoop},
    {"jintarrayGetLength", "([I)I", (void *) jintarrayGetLength},
    {"jintarrayCriticalAccess", "([II)I", (void *) jintarrayCriticalAccess},
    {"jintarrayBasicAccess", "([II)I", (void *) jintarrayBasicAccess},
};

static int registerNativeMethods(JNIEnv* env, const char* className,
        const JNINativeMethod* gMethods, int numMethods) {
    jclass clazz = env->FindClass(className);
    if (clazz == NULL) {
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jint JNI_OnLoad(JavaVM* jvm, void*) {
    JNIEnv *env = NULL;
    if (jvm->GetEnv((void**) &env, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    if (registerNativeMethods(env, "android/perftests/SystemPerfTest",
            sMethods, NELEM(sMethods)) == -1) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
