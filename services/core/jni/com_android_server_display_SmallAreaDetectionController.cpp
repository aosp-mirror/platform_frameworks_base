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

#define LOG_TAG "SmallAreaDetectionController"

#include <gui/SurfaceComposerClient.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedPrimitiveArray.h>

#include "jni.h"
#include "utils/Log.h"

namespace android {
static void nativeUpdateSmallAreaDetection(JNIEnv* env, jclass clazz, jintArray jappIds,
                                           jfloatArray jthresholds) {
    if (jappIds == nullptr || jthresholds == nullptr) return;

    ScopedIntArrayRO appIds(env, jappIds);
    ScopedFloatArrayRO thresholds(env, jthresholds);

    if (appIds.size() != thresholds.size()) {
        ALOGE("appIds size exceeds thresholds size!");
        return;
    }

    std::vector<int32_t> appIdVector;
    std::vector<float> thresholdVector;
    size_t size = appIds.size();
    appIdVector.reserve(size);
    thresholdVector.reserve(size);
    for (int i = 0; i < size; i++) {
        appIdVector.push_back(static_cast<int32_t>(appIds[i]));
        thresholdVector.push_back(static_cast<float>(thresholds[i]));
    }
    SurfaceComposerClient::updateSmallAreaDetection(appIdVector, thresholdVector);
}

static void nativeSetSmallAreaDetectionThreshold(JNIEnv* env, jclass clazz, jint appId,
                                                 jfloat threshold) {
    SurfaceComposerClient::setSmallAreaDetectionThreshold(appId, threshold);
}

static const JNINativeMethod gMethods[] = {
        {"nativeUpdateSmallAreaDetection", "([I[F)V", (void*)nativeUpdateSmallAreaDetection},
        {"nativeSetSmallAreaDetectionThreshold", "(IF)V",
         (void*)nativeSetSmallAreaDetectionThreshold},
};

int register_android_server_display_smallAreaDetectionController(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/server/display/SmallAreaDetectionController",
                                    gMethods, NELEM(gMethods));
}

}; // namespace android
