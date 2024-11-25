/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef _ANDROID_VIEW_INPUT_WINDOW_HANDLE_H
#define _ANDROID_VIEW_INPUT_WINDOW_HANDLE_H

#include <gui/WindowInfo.h>

#include <nativehelper/JNIHelp.h>
#include "jni.h"

namespace android {

sp<gui::WindowInfoHandle> android_view_InputWindowHandle_getHandle(JNIEnv* env,
                                                                   jobject inputWindowHandleObj);

jobject android_view_InputWindowHandle_fromWindowInfo(JNIEnv* env,
                                                      const gui::WindowInfo& windowInfo);

} // namespace android

#endif // _ANDROID_VIEW_INPUT_WINDOW_HANDLE_H
