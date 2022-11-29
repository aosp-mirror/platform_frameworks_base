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

#include "random_parcel_jni.h"
#include <android_util_Binder.h>
#include <android_os_Parcel.h>
#include <fuzzbinder/libbinder_driver.h>
#include <fuzzbinder/random_parcel.h>
#include <fuzzer/FuzzedDataProvider.h>
using namespace android;

// JNI interface for fuzzService
JNIEXPORT void JNICALL Java_randomparcel_FuzzBinder_fuzzServiceInternal(JNIEnv *env, jobject thiz, jobject javaBinder, jbyteArray fuzzData) {
    size_t len = static_cast<size_t>(env->GetArrayLength(fuzzData));
    uint8_t data[len];
    env->GetByteArrayRegion(fuzzData, 0, len, reinterpret_cast<jbyte*>(data));

    FuzzedDataProvider provider(data, len);
    sp<IBinder> binder = android::ibinderForJavaObject(env, javaBinder);
    fuzzService(binder, std::move(provider));
}

// API used by AIDL fuzzers to access JNI functions from libandroid_runtime.
JNIEXPORT jint JNICALL Java_randomparcel_FuzzBinder_registerNatives(JNIEnv* env) {
    return registerFrameworkNatives(env);
}

JNIEXPORT void JNICALL Java_randomparcel_FuzzBinder_fillParcelInternal(JNIEnv *env, jobject thiz, jobject jparcel, jbyteArray fuzzData) {
    size_t len = static_cast<size_t>(env->GetArrayLength(fuzzData));
    uint8_t data[len];
    env->GetByteArrayRegion(fuzzData, 0, len, reinterpret_cast<jbyte*>(data));

    FuzzedDataProvider provider(data, len);
    RandomParcelOptions options;

    Parcel* parcel = parcelForJavaObject(env, jparcel);
    fillRandomParcel(parcel, std::move(provider), &options);
}
