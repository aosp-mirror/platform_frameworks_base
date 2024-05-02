/*
 * Copyright (C) 2024 The Android Open Source Project
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
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"


typedef void (*FreeFunction)(void*);

static void NativeAllocationRegistry_applyFreeFunction(JNIEnv*,
                                                       jclass,
                                                       jlong freeFunction,
                                                       jlong ptr) {
    void* nativePtr = reinterpret_cast<void*>(static_cast<uintptr_t>(ptr));
    FreeFunction nativeFreeFunction
        = reinterpret_cast<FreeFunction>(static_cast<uintptr_t>(freeFunction));
    nativeFreeFunction(nativePtr);
}

static const JNINativeMethod sMethods_NAR[] =
{
    { "applyFreeFunction", "(JJ)V", (void*)NativeAllocationRegistry_applyFreeFunction },
};

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("GetEnv failed!");
        return result;
    }
    ALOG_ASSERT(env, "Could not retrieve the env!");

    ALOGI("%s: JNI_OnLoad", __FILE__);

    // Initialize the Ravenwood version of NativeAllocationRegistry.
    // We don't use this JNI on the device side, but if we ever have to do, skip this part.
#ifndef __ANDROID__
    int res = jniRegisterNativeMethods(env, "libcore/util/NativeAllocationRegistry",
            sMethods_NAR, NELEM(sMethods_NAR));
    if (res < 0) {
        return res;
    }
#endif

    return JNI_VERSION_1_4;
}
