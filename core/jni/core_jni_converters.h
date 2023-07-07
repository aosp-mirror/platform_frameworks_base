/*
 * Copyright (C) 2023 The Android Open Source Project
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

#pragma once

#include <nativehelper/scoped_local_ref.h>

template <class T>
static jobject toJavaArray(JNIEnv* env, std::vector<T>&& list, jclass clazz,
                           jobject (*convert)(JNIEnv* env, T)) {
    jobjectArray arr = env->NewObjectArray(list.size(), clazz, nullptr);
    LOG_ALWAYS_FATAL_IF(arr == nullptr);
    for (size_t i = 0; i < list.size(); i++) {
        T& t = list[i];
        ScopedLocalRef<jobject> javaObj(env, convert(env, std::move(t)));
        env->SetObjectArrayElement(arr, i, javaObj.get());
    }
    return arr;
}