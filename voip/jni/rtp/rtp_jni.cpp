/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include <stdio.h>

#include "jni.h"

extern int registerRtpStream(JNIEnv *env);
extern int registerAudioGroup(JNIEnv *env);

__attribute__((visibility("default"))) jint JNI_OnLoad(JavaVM *vm, void *unused)
{
    JNIEnv *env = NULL;
    if (vm->GetEnv((void **)&env, JNI_VERSION_1_4) != JNI_OK ||
        registerRtpStream(env) < 0 || registerAudioGroup(env) < 0) {
        return -1;
    }
    return JNI_VERSION_1_4;
}
