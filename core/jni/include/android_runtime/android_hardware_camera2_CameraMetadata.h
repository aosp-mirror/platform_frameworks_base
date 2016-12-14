/*
 * Copyright 2014 The Android Open Source Project
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

#ifndef ANDROID_HARDWARE_CAMERA2_CAMERAMETADATA_JNI_H
#define ANDROID_HARDWARE_CAMERA2_CAMERAMETADATA_JNI_H

#include <camera/CameraMetadata.h>

#include "jni.h"

namespace android {

/**
 * Copies the native metadata for this java object into the given output CameraMetadata object.
 */
status_t CameraMetadata_getNativeMetadata(JNIEnv* env, jobject thiz,
               /*out*/CameraMetadata* metadata);

} /*namespace android*/

#endif /*ANDROID_HARDWARE_CAMERA2_CAMERAMETADATA_JNI_H*/
