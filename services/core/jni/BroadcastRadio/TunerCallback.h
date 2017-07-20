/**
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef _ANDROID_SERVER_BROADCASTRADIO_TUNERCALLBACK_H
#define _ANDROID_SERVER_BROADCASTRADIO_TUNERCALLBACK_H

#include "JavaRef.h"
#include "NativeCallbackThread.h"
#include "types.h"

#include <android/hardware/broadcastradio/1.1/ITunerCallback.h>
#include <jni.h>

namespace android {

void register_android_server_broadcastradio_TunerCallback(JavaVM *vm, JNIEnv *env);

namespace server {
namespace BroadcastRadio {
namespace TunerCallback {

sp<hardware::broadcastradio::V1_1::ITunerCallback>
getNativeCallback(JNIEnv *env, jobject jTunerCallback);

} // namespace TunerCallback
} // namespace BroadcastRadio
} // namespace server
} // namespace android

#endif // _ANDROID_SERVER_BROADCASTRADIO_TUNERCALLBACK_H
