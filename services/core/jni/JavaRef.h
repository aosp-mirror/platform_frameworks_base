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

#ifndef _ANDROID_JAVA_REF_H
#define _ANDROID_JAVA_REF_H

#include <android-base/macros.h>
#include <functional>
#include <jni.h>
#include <memory>
#include <type_traits>

namespace android {

template <typename T>
using JavaRef = std::unique_ptr<typename std::remove_pointer<T>::type, std::function<void(T)>>;

template <typename T>
JavaRef<T> make_javaref(JNIEnv *env, T ref) {
    return JavaRef<T>(ref, [env](T ref) {
        if (env && ref) {
            env->DeleteLocalRef(ref);
        }
    });
}

JavaRef<jstring> make_javastr(JNIEnv *env, const std::string &str);

} // namespace android

#endif // _ANDROID_JAVA_REF_H
