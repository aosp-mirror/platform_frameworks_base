/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef ANDROID_OS_NATIVE_HANDLE_H
#define ANDROID_OS_NATIVE_HANDLE_H

#include "hwbinder/EphemeralStorage.h"

#include <cutils/native_handle.h>
#include <jni.h>

namespace android {

struct JNativeHandle {

    /**
     * Returns a Java NativeHandle object representing the parameterized
     * native_handle_t instance.
     */
    static jobject MakeJavaNativeHandleObj(JNIEnv *env, const native_handle_t *handle);

    /**
     * Returns a heap-allocated native_handle_t instance representing the
     * parameterized Java object. Note that if no valid EphemeralStorage*
     * parameter is supplied (storage is nullptr), the return value must
     * be explicitly deallocated (using native_handle_delete).
     */
    static native_handle_t* MakeCppNativeHandle(JNIEnv *env, jobject jHandle,
            EphemeralStorage *storage);

    /**
     * Returns an (uninitialized) array of Java NativeHandle objects.
     */
    static jobjectArray AllocJavaNativeHandleObjArray(JNIEnv *env, jsize length);
};

}

#endif  // ANDROID_OS_NATIVE_HANDLE_H
