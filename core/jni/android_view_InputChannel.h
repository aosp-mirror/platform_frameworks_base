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

#ifndef _ANDROID_VIEW_INPUTCHANNEL_H
#define _ANDROID_VIEW_INPUTCHANNEL_H

#include "jni.h"

#include <input/InputTransport.h>

#include <vector>

namespace android {

typedef void (*InputChannelObjDisposeCallback)(JNIEnv* env, jobject inputChannelObj,
        const sp<InputChannel>& inputChannel, void* data);

extern sp<InputChannel> android_view_InputChannel_getInputChannel(JNIEnv* env,
        jobject inputChannelObj);

/* Sets a callback that is invoked when the InputChannel DVM object is disposed (or finalized).
 * This is used to automatically dispose of other native objects in the input dispatcher
 * and input queue to prevent memory leaks. */
extern void android_view_InputChannel_setDisposeCallback(JNIEnv* env, jobject inputChannelObj,
        InputChannelObjDisposeCallback callback, void* data = NULL);

}

namespace util {
/**
 * Makes a std::unique_ptr<> with the template parameter inferred by the compiler.
 * This will be present in C++14 and can be removed then.
 */
template <typename T, class... Args>
std::unique_ptr<T> make_unique(Args&&... args) {
    return std::unique_ptr<T>(new T{std::forward<Args>(args)...});
} // namespace util
} // namespace android

#endif // _ANDROID_OS_INPUTCHANNEL_H
