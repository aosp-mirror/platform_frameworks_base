/*
 * Copyright 2024 The Android Open Source Project
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

#ifndef _ANDROID_WINDOW_INPUTTRANSFERTOKEN_H
#define _ANDROID_WINDOW_INPUTTRANSFERTOKEN_H

#include <gui/InputTransferToken.h>
#include <jni.h>

namespace android {

extern InputTransferToken* android_window_InputTransferToken_getNativeInputTransferToken(
        JNIEnv* env, jobject inputTransferTokenObj);

extern jobject android_window_InputTransferToken_getJavaInputTransferToken(
        JNIEnv* env, const InputTransferToken* inputTransferToken);

} // namespace android

#endif // _ANDROID_WINDOW_INPUTTRANSFERTOKEN_H
