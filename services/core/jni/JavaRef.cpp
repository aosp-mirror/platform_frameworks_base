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

#define LOG_TAG "JavaRef"
//#define LOG_NDEBUG 0

#include "JavaRef.h"

#include <utils/Log.h>

namespace android {

JavaRef make_javaref(JNIEnv *env, jobject ref) {
    ALOGV("wrapping %p", ref);
    ALOGE_IF(env == nullptr, "Environment is a nullptr");

    return JavaRef(ref, [env](jobject ref) {
        ALOGV("deleting %p", ref);
        if (env && ref) {
            env->DeleteLocalRef(ref);
        }
    });
}

EnvWrapper::EnvWrapper(JNIEnv *env) : mEnv(env) {
    ALOGE_IF(env == nullptr, "Environment is a nullptr");
}

JavaRef EnvWrapper::operator() (jobject ref) const {
    return make_javaref(mEnv, ref);
}

} // namespace android
