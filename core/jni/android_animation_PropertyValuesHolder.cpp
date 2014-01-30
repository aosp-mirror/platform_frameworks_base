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
#include <assert.h>

#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>

// ----------------------------------------------------------------------------

namespace android {

// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/animation/PropertyValuesHolder";

static jlong android_animation_PropertyValuesHolder_getIntMethod(
        JNIEnv* env, jclass pvhClass, jclass targetClass, jstring methodName)
{
    const char *nativeString = env->GetStringUTFChars(methodName, 0);
    jmethodID mid = env->GetMethodID(targetClass, nativeString, "(I)V");
    env->ReleaseStringUTFChars(methodName, nativeString);
    return reinterpret_cast<jlong>(mid);
}

static jlong android_animation_PropertyValuesHolder_getFloatMethod(
        JNIEnv* env, jclass pvhClass, jclass targetClass, jstring methodName)
{
    const char *nativeString = env->GetStringUTFChars(methodName, 0);
    jmethodID mid = env->GetMethodID(targetClass, nativeString, "(F)V");
    env->ReleaseStringUTFChars(methodName, nativeString);
    return reinterpret_cast<jlong>(mid);
}

static void android_animation_PropertyValuesHolder_callIntMethod(
        JNIEnv* env, jclass pvhObject, jobject target, jlong methodID, jint arg)
{
    env->CallVoidMethod(target, reinterpret_cast<jmethodID>(methodID), arg);
}

static void android_animation_PropertyValuesHolder_callFloatMethod(
        JNIEnv* env, jclass pvhObject, jobject target, jlong methodID, jfloat arg)
{
    env->CallVoidMethod(target, reinterpret_cast<jmethodID>(methodID), arg);
}

static JNINativeMethod gMethods[] = {
    {   "nGetIntMethod", "(Ljava/lang/Class;Ljava/lang/String;)J",
            (void*)android_animation_PropertyValuesHolder_getIntMethod },
    {   "nGetFloatMethod", "(Ljava/lang/Class;Ljava/lang/String;)J",
            (void*)android_animation_PropertyValuesHolder_getFloatMethod },
    {   "nCallIntMethod", "(Ljava/lang/Object;JI)V",
            (void*)android_animation_PropertyValuesHolder_callIntMethod },
    {   "nCallFloatMethod", "(Ljava/lang/Object;JF)V",
            (void*)android_animation_PropertyValuesHolder_callFloatMethod }
};

int register_android_animation_PropertyValuesHolder(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}

};
