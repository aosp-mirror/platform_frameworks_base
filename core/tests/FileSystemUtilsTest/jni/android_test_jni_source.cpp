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

#include <jni.h>

// This will be called from embedded_native_libs_test_app
extern "C" JNIEXPORT
jint JNICALL Java_android_test_embedded_MainActivity_add(JNIEnv*, jclass, jint op1, jint op2) {
    return op1 + op2;
}

// This will be called from extract_native_libs_test_app
extern "C" JNIEXPORT
jint JNICALL Java_android_test_extract_MainActivity_subtract(JNIEnv*, jclass, jint op1, jint op2) {
    return op1 - op2;
}

// Initialize JNI
jint JNI_OnLoad(JavaVM *jvm, void */* reserved */) {
    JNIEnv *e;

    // Check JNI version
    if (jvm->GetEnv((void **) &e, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
