/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "AndroidRuntimeBase"
//#define LOG_NDEBUG 0
#include <utils/Log.h>

#include <android_runtime/AndroidRuntime.h>

#include "jni.h"
#include "JNIHelp.h"

namespace android {

/*static*/ JavaVM* AndroidRuntimeBase::mJavaVM = NULL;

/*
 * Get the JNIEnv pointer for this thread.
 *
 * Returns NULL if the slot wasn't allocated or populated.
 */
/*static*/ JNIEnv* AndroidRuntimeBase::getJNIEnv()
{
    JNIEnv* env;
    JavaVM* vm = AndroidRuntimeBase::getJavaVM();
    assert(vm != NULL);

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK)
        return NULL;
    return env;
}

/*
 * Register native methods using JNI.
 */
/*static*/ int AndroidRuntimeBase::registerNativeMethods(JNIEnv* env,
    const char* className, const JNINativeMethod* gMethods, int numMethods)
{
    return jniRegisterNativeMethods(env, className, gMethods, numMethods);
}

}  // namespace android

