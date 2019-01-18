/*
 * Copyright 2017, The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_MEDIAMETRICSJNI_H_
#define _ANDROID_MEDIA_MEDIAMETRICSJNI_H_

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <media/MediaAnalyticsItem.h>

// Copeid from core/jni/ (libandroid_runtime.so)
namespace android {

class MediaMetricsJNI {
public:
    static jobject writeMetricsToBundle(JNIEnv* env, MediaAnalyticsItem *item, jobject mybundle);
    static jobject writeAttributesToBundle(JNIEnv* env, jobject mybundle, char *buffer, size_t length);
};

};  // namespace android

#endif //  _ANDROID_MEDIA_MEDIAMETRICSJNI_H_
