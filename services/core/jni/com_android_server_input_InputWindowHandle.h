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

#ifndef _ANDROID_SERVER_INPUT_WINDOW_HANDLE_H
#define _ANDROID_SERVER_INPUT_WINDOW_HANDLE_H

#include <input/InputWindow.h>

#include "JNIHelp.h"
#include "jni.h"

namespace android {

class NativeInputWindowHandle : public InputWindowHandle {
public:
    NativeInputWindowHandle(const sp<InputApplicationHandle>& inputApplicationHandle,
            jweak objWeak);
    virtual ~NativeInputWindowHandle();

    jobject getInputWindowHandleObjLocalRef(JNIEnv* env);

    virtual bool updateInfo();

private:
    jweak mObjWeak;
};


extern sp<NativeInputWindowHandle> android_server_InputWindowHandle_getHandle(
        JNIEnv* env, jobject inputWindowHandleObj);

} // namespace android

#endif // _ANDROID_SERVER_INPUT_WINDOW_HANDLE_H
