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

static jmethodID android_animation_PropertyValuesHolder_getIntMethod(
        JNIEnv* env, jclass pvhClass, jclass targetClass, jstring methodName)
{
    const char *nativeString = env->GetStringUTFChars(methodName, 0);
    jmethodID mid = env->GetMethodID(targetClass, nativeString, "(I)V");
    env->ReleaseStringUTFChars(methodName, nativeString);
    return mid;
}

static jmethodID android_animation_PropertyValuesHolder_getFloatMethod(
        JNIEnv* env, jclass pvhClass, jclass targetClass, jstring methodName)
{
    const char *nativeString = env->GetStringUTFChars(methodName, 0);
    jmethodID mid = env->GetMethodID(targetClass, nativeString, "(F)V");
    env->ReleaseStringUTFChars(methodName, nativeString);
    return mid;
}

static jmethodID getMultiparameterMethod(JNIEnv* env, jclass targetClass, jstring methodName,
    jint parameterCount, char parameterType)
{
    const char *nativeString = env->GetStringUTFChars(methodName, 0);
    char *signature = new char[parameterCount + 4];
    signature[0] = '(';
    memset(&(signature[1]), parameterType, parameterCount);
    strcpy(&(signature[parameterCount + 1]), ")V");
    jmethodID mid = env->GetMethodID(targetClass, nativeString, signature);
    delete[] signature;
    env->ReleaseStringUTFChars(methodName, nativeString);
    return mid;
}

static jmethodID android_animation_PropertyValuesHolder_getMultipleFloatMethod(
        JNIEnv* env, jclass pvhClass, jclass targetClass, jstring methodName, jint parameterCount)
{
    return getMultiparameterMethod(env, targetClass, methodName, parameterCount, 'F');
}

static jmethodID android_animation_PropertyValuesHolder_getMultipleIntMethod(
        JNIEnv* env, jclass pvhClass, jclass targetClass, jstring methodName, jint parameterCount)
{
    return getMultiparameterMethod(env, targetClass, methodName, parameterCount, 'I');
}

static void android_animation_PropertyValuesHolder_callIntMethod(
        JNIEnv* env, jclass pvhObject, jobject target, jmethodID methodID, int arg)
{
    env->CallVoidMethod(target, methodID, arg);
}

static void android_animation_PropertyValuesHolder_callFloatMethod(
        JNIEnv* env, jclass pvhObject, jobject target, jmethodID methodID, float arg)
{
    env->CallVoidMethod(target, methodID, arg);
}

static void android_animation_PropertyValuesHolder_callTwoFloatMethod(
        JNIEnv* env, jclass pvhObject, jobject target, jmethodID methodID, float arg1, float arg2)
{
    env->CallVoidMethod(target, methodID, arg1, arg2);
}

static void android_animation_PropertyValuesHolder_callFourFloatMethod(
        JNIEnv* env, jclass pvhObject, jobject target, jmethodID methodID, float arg1, float arg2,
        float arg3, float arg4)
{
    env->CallVoidMethod(target, methodID, arg1, arg2, arg3, arg4);
}

static void android_animation_PropertyValuesHolder_callMultipleFloatMethod(
        JNIEnv* env, jclass pvhObject, jobject target, jmethodID methodID, jfloatArray arg)
{
    jsize parameterCount = env->GetArrayLength(arg);
    jfloat *floatValues = env->GetFloatArrayElements(arg, NULL);
    jvalue* values = new jvalue[parameterCount];
    for (int i = 0; i < parameterCount; i++) {
        values[i].f = floatValues[i];
    }
    env->CallVoidMethodA(target, methodID, values);
    delete[] values;
    env->ReleaseFloatArrayElements(arg, floatValues, JNI_ABORT);
}

static void android_animation_PropertyValuesHolder_callTwoIntMethod(
        JNIEnv* env, jclass pvhObject, jobject target, jmethodID methodID, int arg1, int arg2)
{
    env->CallVoidMethod(target, methodID, arg1, arg2);
}

static void android_animation_PropertyValuesHolder_callFourIntMethod(
        JNIEnv* env, jclass pvhObject, jobject target, jmethodID methodID, int arg1, int arg2,
        int arg3, int arg4)
{
    env->CallVoidMethod(target, methodID, arg1, arg2, arg3, arg4);
}

static void android_animation_PropertyValuesHolder_callMultipleIntMethod(
        JNIEnv* env, jclass pvhObject, jobject target, jmethodID methodID, jintArray arg)
{
    jsize parameterCount = env->GetArrayLength(arg);
    jint *intValues = env->GetIntArrayElements(arg, NULL);
    jvalue* values = new jvalue[parameterCount];
    for (int i = 0; i < parameterCount; i++) {
        values[i].i = intValues[i];
    }
    env->CallVoidMethodA(target, methodID, values);
    delete[] values;
    env->ReleaseIntArrayElements(arg, intValues, JNI_ABORT);
}

static JNINativeMethod gMethods[] = {
    {   "nGetIntMethod", "(Ljava/lang/Class;Ljava/lang/String;)I",
            (void*)android_animation_PropertyValuesHolder_getIntMethod },
    {   "nGetFloatMethod", "(Ljava/lang/Class;Ljava/lang/String;)I",
            (void*)android_animation_PropertyValuesHolder_getFloatMethod },
    {   "nGetMultipleFloatMethod", "(Ljava/lang/Class;Ljava/lang/String;I)I",
            (void*)android_animation_PropertyValuesHolder_getMultipleFloatMethod },
    {   "nGetMultipleIntMethod", "(Ljava/lang/Class;Ljava/lang/String;I)I",
            (void*)android_animation_PropertyValuesHolder_getMultipleIntMethod },
    {   "nCallIntMethod", "(Ljava/lang/Object;II)V",
            (void*)android_animation_PropertyValuesHolder_callIntMethod },
    {   "nCallFloatMethod", "(Ljava/lang/Object;IF)V",
            (void*)android_animation_PropertyValuesHolder_callFloatMethod },
    {   "nCallTwoFloatMethod", "(Ljava/lang/Object;IFF)V",
            (void*)android_animation_PropertyValuesHolder_callTwoFloatMethod },
    {   "nCallFourFloatMethod", "(Ljava/lang/Object;IFFFF)V",
            (void*)android_animation_PropertyValuesHolder_callFourFloatMethod },
    {   "nCallMultipleFloatMethod", "(Ljava/lang/Object;I[F)V",
            (void*)android_animation_PropertyValuesHolder_callMultipleFloatMethod },
    {   "nCallTwoIntMethod", "(Ljava/lang/Object;III)V",
            (void*)android_animation_PropertyValuesHolder_callTwoIntMethod },
    {   "nCallFourIntMethod", "(Ljava/lang/Object;IIIII)V",
            (void*)android_animation_PropertyValuesHolder_callFourIntMethod },
    {   "nCallMultipleIntMethod", "(Ljava/lang/Object;I[I)V",
            (void*)android_animation_PropertyValuesHolder_callMultipleIntMethod },
};

int register_android_animation_PropertyValuesHolder(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}

};
