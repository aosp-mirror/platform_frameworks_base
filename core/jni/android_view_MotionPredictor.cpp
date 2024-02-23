/*
 * Copyright (C) 2023 The Android Open Source Project
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

#define LOG_TAG "MotionPredictor-JNI"

#include <input/Input.h>
#include <input/MotionPredictor.h>

#include "android_view_MotionEvent.h"
#include "core_jni_helpers.h"

/**
 * This file is a bridge from Java to native for MotionPredictor class.
 * It should be pass-through only. Do not store any state or put any business logic into this file.
 */

namespace android {

static void release(void* ptr) {
    delete reinterpret_cast<MotionPredictor*>(ptr);
}

static jlong android_view_MotionPredictor_nativeGetNativeMotionPredictorFinalizer(JNIEnv* env,
                                                                                  jclass clazz) {
    return reinterpret_cast<jlong>(release);
}

static jlong android_view_MotionPredictor_nativeInitialize(JNIEnv* env, jclass clazz,
                                                           jint offsetNanos) {
    const nsecs_t offset = static_cast<nsecs_t>(offsetNanos);
    return reinterpret_cast<jlong>(new MotionPredictor(offset));
}

static void android_view_MotionPredictor_nativeRecord(JNIEnv* env, jclass clazz, jlong ptr,
                                                      jobject event) {
    MotionPredictor* predictor = reinterpret_cast<MotionPredictor*>(ptr);
    MotionEvent* motionEvent = android_view_MotionEvent_getNativePtr(env, event);

    android::base::Result<void> result = predictor->record(*motionEvent);
    if (!result.ok()) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                          result.error().message().c_str());
    }
}

static jobject android_view_MotionPredictor_nativePredict(JNIEnv* env, jclass clazz, jlong ptr,
                                                          jlong predictionTimeNanos) {
    MotionPredictor* predictor = reinterpret_cast<MotionPredictor*>(ptr);
    return android_view_MotionEvent_obtainFromNative(env,
                                                     predictor->predict(static_cast<nsecs_t>(
                                                             predictionTimeNanos)))
            .release();
}

static jboolean android_view_MotionPredictor_nativeIsPredictionAvailable(JNIEnv* env, jclass clazz,
                                                                         jlong ptr, jint deviceId,
                                                                         jint source) {
    MotionPredictor* predictor = reinterpret_cast<MotionPredictor*>(ptr);
    return predictor->isPredictionAvailable(static_cast<int32_t>(deviceId),
                                            static_cast<int32_t>(source));
}

// ----------------------------------------------------------------------------

static const std::array<JNINativeMethod, 5> gMotionPredictorMethods{{
        /* name, signature, funcPtr */
        {"nativeInitialize", "(I)J", (void*)android_view_MotionPredictor_nativeInitialize},
        {"nativeGetNativeMotionPredictorFinalizer", "()J",
         (void*)android_view_MotionPredictor_nativeGetNativeMotionPredictorFinalizer},
        {"nativeRecord", "(JLandroid/view/MotionEvent;)V",
         (void*)android_view_MotionPredictor_nativeRecord},
        {"nativePredict", "(JJ)Landroid/view/MotionEvent;",
         (void*)android_view_MotionPredictor_nativePredict},
        {"nativeIsPredictionAvailable", "(JII)Z",
         (void*)android_view_MotionPredictor_nativeIsPredictionAvailable},
}};

int register_android_view_MotionPredictor(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/view/MotionPredictor", gMotionPredictorMethods.data(),
                                gMotionPredictorMethods.size());
}

} // namespace android
