/*
 * Copyright (C) 2022 The Android Open Source Project
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

extern "C" {
    JNIEXPORT void JNICALL Java_randomparcel_FuzzBinder_fuzzServiceInternal(JNIEnv *env, jobject thiz, jobject javaBinder, jbyteArray fuzzData);

    // Function to register libandroid_runtime JNI functions with java env.
    JNIEXPORT jint JNICALL Java_randomparcel_FuzzBinder_registerNatives(JNIEnv* env);

    // Function from AndroidRuntime
    jint registerFrameworkNatives(JNIEnv* env);

    JNIEXPORT void JNICALL Java_randomparcel_FuzzBinder_fillParcelInternal(JNIEnv *env, jobject thiz, jobject parcel, jbyteArray fuzzData);
}
