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

#define LOG_TAG "native_activity"
#include <utils/Log.h>

#include <android_runtime/android_app_NativeActivity.h>

using namespace android;

void ANativeActivity_finish(ANativeActivity* activity) {
    android_NativeActivity_finish(activity);
}

void ANativeActivity_setWindowFormat(ANativeActivity* activity, int32_t format) {
	android_NativeActivity_setWindowFormat(activity, format);
}

void ANativeActivity_setWindowFlags(ANativeActivity* activity,
		uint32_t addFlags, uint32_t removeFlags) {
	android_NativeActivity_setWindowFlags(activity, addFlags, addFlags|removeFlags);
}

void ANativeActivity_showSoftInput(ANativeActivity* activity, uint32_t flags) {
	android_NativeActivity_showSoftInput(activity, flags);
}

void ANativeActivity_hideSoftInput(ANativeActivity* activity, uint32_t flags) {
	android_NativeActivity_hideSoftInput(activity, flags);
}
