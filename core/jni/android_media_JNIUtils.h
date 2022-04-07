/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <jni.h>

#include <mutex>

#include <android_runtime/AndroidRuntime.h>
#include <log/log_main.h>
#include <utils/RefBase.h>

namespace android {

// TODO (b/218351957) remove static lock
// This lock is intentionally static, it is local to each TU which is
// responsible for a single Java class.
static std::mutex sFieldSpLock;

// getFieldSp and setFieldSp are used to logically represent an owning reference
// to a native object from across the JNI. The data (underlying ptr) is stored
// in a field in the Java object as a long. Setting a Java field to a sp
// involves incrementing the reference count to prevent object destruction.
// Resetting the field decrements the reference count to avoid memory leaks.

template <typename T>
sp<T> getFieldSp(JNIEnv* env, jobject thiz, jfieldID id) {
    const std::lock_guard l{sFieldSpLock};
    return sp<T>::fromExisting(reinterpret_cast<T*>(env->GetLongField(thiz, id)));
}

template <typename T>
sp<T> setFieldSp(JNIEnv* env, jobject thiz, const sp<T>& newSp, jfieldID id) {
    const std::lock_guard l{sFieldSpLock};
    sp<T> old = sp<T>::fromExisting(reinterpret_cast<T*>(env->GetLongField(thiz, id)));
    if (newSp) {
        newSp->incStrong((void*)setFieldSp<T>);
    }
    if (old) {
        old->decStrong((void*)setFieldSp<T>);
    }
    env->SetLongField(thiz, id, (jlong)newSp.get());
    return old;
}

inline JNIEnv* getJNIEnvOrDie() {
    const auto env = AndroidRuntime::getJNIEnv();
    LOG_ALWAYS_FATAL_IF(env == nullptr,
                        "Thread JNI reference is null. Thread not prepared for Java.");
    return env;
}


} // namespace android
