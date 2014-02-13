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

#ifndef _RUNTIME_ANDROID_RUNTIME_BASE_H
#define _RUNTIME_ANDROID_RUNTIME_BASE_H

#include <nativehelper/jni.h>

namespace android {

struct AndroidRuntimeBase {
    /** return a pointer to the VM running in this process */
    static JavaVM* getJavaVM() { return mJavaVM; }

    /** return a pointer to the JNIEnv pointer for this thread */
    static JNIEnv* getJNIEnv();

    /**
     * Register a set of methods in the specified class.
     */
    static int registerNativeMethods(JNIEnv* env,
        const char* className, const JNINativeMethod* gMethods, int numMethods);

protected:
    /* JNI JavaVM pointer */
    static JavaVM* mJavaVM;

    AndroidRuntimeBase() {}
    virtual ~AndroidRuntimeBase() {}

    AndroidRuntimeBase(const AndroidRuntimeBase &);
    AndroidRuntimeBase &operator=(const AndroidRuntimeBase &);
};

}  // namespace android

#endif // _RUNTIME_ANDROID_RUNTIME_BASE_H
