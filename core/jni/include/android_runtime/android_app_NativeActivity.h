/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef _ANDROID_APP_NATIVEACTIVITY_H
#define _ANDROID_APP_NATIVEACTIVITY_H

#include <utils/Looper.h>

#include <android/native_activity.h>

#include "jni.h"

namespace android {

extern void android_NativeActivity_finish(
        ANativeActivity* activity);

extern void android_NativeActivity_setWindowFormat(
        ANativeActivity* activity, int32_t format);

extern void android_NativeActivity_setWindowFlags(
        ANativeActivity* activity, int32_t values, int32_t mask);

extern void android_NativeActivity_showSoftInput(
        ANativeActivity* activity, int32_t flags);

extern void android_NativeActivity_hideSoftInput(
        ANativeActivity* activity, int32_t flags);

} // namespace android

#endif // _ANDROID_APP_NATIVEACTIVITY_H
