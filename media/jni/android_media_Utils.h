/*
 * Copyright 2011, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _ANDROID_MEDIA_UTILS_H_
#define _ANDROID_MEDIA_UTILS_H_

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <utils/KeyedVector.h>
#include <utils/String8.h>

namespace android {

/**
 * Returns true if the conversion is successful; otherwise, false.
 */
bool ConvertKeyValueArraysToKeyedVector(
    JNIEnv *env, jobjectArray keys, jobjectArray values,
    KeyedVector<String8, String8>* vector);

struct AMessage;
status_t ConvertMessageToMap(
        JNIEnv *env, const sp<AMessage> &msg, jobject *map);

status_t ConvertKeyValueArraysToMessage(
        JNIEnv *env, jobjectArray keys, jobjectArray values,
        sp<AMessage> *msg);

};  // namespace android

#endif //  _ANDROID_MEDIA_UTILS_H_
