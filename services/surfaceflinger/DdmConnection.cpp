/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <android_runtime/AndroidRuntime.h>

#include "jni.h"
#include "DdmConnection.h"

extern "C" jint Java_com_android_internal_util_WithFramework_registerNatives(
        JNIEnv* env, jclass clazz);

namespace android {

void DdmConnection::start(const char* name) {
    JavaVM* vm;
    JNIEnv* env;

    // start a VM
    JavaVMInitArgs args;
    JavaVMOption opt;

    opt.optionString =
        "-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y";

    args.version = JNI_VERSION_1_4;
    args.options = &opt;
    args.nOptions = 1;
    args.ignoreUnrecognized = JNI_FALSE;

    if (JNI_CreateJavaVM(&vm, &env, &args) == 0) {
        jclass startClass;
        jmethodID startMeth;

        // register native code
        if (Java_com_android_internal_util_WithFramework_registerNatives(env, 0) == 0) {
            // set our name by calling DdmHandleAppName.setAppName()
            startClass = env->FindClass("android/ddm/DdmHandleAppName");
            if (startClass) {
                startMeth = env->GetStaticMethodID(startClass,
                        "setAppName", "(Ljava/lang/String;)V");
                if (startMeth) {
                    jstring str = env->NewStringUTF(name);
                    env->CallStaticVoidMethod(startClass, startMeth, str);
                    env->DeleteLocalRef(str);
                }
            }

            // initialize DDMS communication by calling
            // DdmRegister.registerHandlers()
            startClass = env->FindClass("android/ddm/DdmRegister");
            if (startClass) {
                startMeth = env->GetStaticMethodID(startClass,
                        "registerHandlers", "()V");
                if (startMeth) {
                    env->CallStaticVoidMethod(startClass, startMeth);
                }
            }
        }
    }
}

}; // namespace android
