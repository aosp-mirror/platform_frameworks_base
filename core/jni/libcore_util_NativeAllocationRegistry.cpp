/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include <nativehelper/JNIHelp.h>
#include <nativehelper/jni_macros.h>
#include "core_jni_helpers.h"

typedef void (*FreeFunction)(void*);

static void NativeAllocationRegistry_applyFreeFunction(JNIEnv*, jclass, jlong freeFunction,
                                                       jlong ptr) {
    void* nativePtr = reinterpret_cast<void*>(static_cast<uintptr_t>(ptr));
    FreeFunction nativeFreeFunction =
            reinterpret_cast<FreeFunction>(static_cast<uintptr_t>(freeFunction));
    nativeFreeFunction(nativePtr);
}

static JNINativeMethod gMethods[] = {
        NATIVE_METHOD(NativeAllocationRegistry, applyFreeFunction, "(JJ)V"),
};

int register_libcore_util_NativeAllocationRegistry(JNIEnv* env) {
    return android::RegisterMethodsOrDie(env, "libcore/util/NativeAllocationRegistry", gMethods,
                                         NELEM(gMethods));
}
