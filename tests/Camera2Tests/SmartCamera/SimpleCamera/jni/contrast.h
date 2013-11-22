/* Copyright (C) 2013 The Android Open Source Project
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

// Native function to extract contrast from image (handed down as ByteBuffer).

#ifndef ANDROID_FILTERFW_JNI_CONTRAST_H
#define ANDROID_FILTERFW_JNI_CONTRAST_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

    JNIEXPORT jfloat JNICALL
    Java_androidx_media_filterfw_samples_simplecamera_ContrastRatioFilter_contrastOperator(
        JNIEnv* env, jclass clazz, jint width, jint height, jobject imageBuffer);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_FILTERFW_JNI_CONTRAST_H

