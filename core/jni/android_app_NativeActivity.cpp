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

#define LOG_TAG "NativeActivity"
#include <utils/Log.h>

#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>
#include <android/native_activity.h>

#include <dlfcn.h>

namespace android
{

struct NativeCode {
    android_activity_t activity;
    android_activity_callbacks_t callbacks;
    
    void* dlhandle;
    
    android_activity_create_t* createActivityFunc;
    void* clientContext;
};

static jint
loadNativeCode_native(JNIEnv* env, jobject clazz, jstring path)
{
    const char* pathStr = env->GetStringUTFChars(path, NULL);
    NativeCode* code = NULL;
    
    void* handle = dlopen(pathStr, RTLD_LAZY);
    
    env->ReleaseStringUTFChars(path, pathStr);
    
    if (handle != NULL) {
        code = new NativeCode();
        code->dlhandle = handle;
        code->createActivityFunc = (android_activity_create_t*)
                dlsym(handle, "android_onCreateActivity");
        if (code->createActivityFunc == NULL) {
            LOGW("android_onCreateActivity not found");
            delete code;
            dlclose(handle);
            return 0;
        }
        memset(&code->activity, sizeof(code->activity), 0);
        memset(&code->callbacks, sizeof(code->callbacks), 0);
        code->activity.callbacks = &code->callbacks;
        code->activity.env = env;
        code->activity.clazz = clazz;
        code->createActivityFunc(&code->activity, NULL, 0);
    }
    
    return (jint)code;
}

static void
unloadNativeCode_native(JNIEnv* env, jobject clazz, jint handle)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onDestroy != NULL) {
            code->callbacks.onDestroy(&code->activity);
        }
        dlclose(code->dlhandle);
        delete code;
    }
}

static void
onStart_native(JNIEnv* env, jobject clazz, jint handle)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onStart != NULL) {
            code->callbacks.onStart(&code->activity);
        }
    }
}

static void
onResume_native(JNIEnv* env, jobject clazz, jint handle)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onResume != NULL) {
            code->callbacks.onResume(&code->activity);
        }
    }
}

static void
onSaveInstanceState_native(JNIEnv* env, jobject clazz, jint handle)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onSaveInstanceState != NULL) {
            size_t len = 0;
            code->callbacks.onSaveInstanceState(&code->activity, &len);
        }
    }
}

static void
onPause_native(JNIEnv* env, jobject clazz, jint handle)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onPause != NULL) {
            code->callbacks.onPause(&code->activity);
        }
    }
}

static void
onStop_native(JNIEnv* env, jobject clazz, jint handle)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onStop != NULL) {
            code->callbacks.onStop(&code->activity);
        }
    }
}

static void
onLowMemory_native(JNIEnv* env, jobject clazz, jint handle)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onLowMemory != NULL) {
            code->callbacks.onLowMemory(&code->activity);
        }
    }
}

static void
onWindowFocusChanged_native(JNIEnv* env, jobject clazz, jint handle, jboolean focused)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onWindowFocusChanged != NULL) {
            code->callbacks.onWindowFocusChanged(&code->activity, focused ? 1 : 0);
        }
    }
}

static const JNINativeMethod g_methods[] = {
    { "loadNativeCode", "(Ljava/lang/String;)I", (void*)loadNativeCode_native },
    { "unloadNativeCode", "(I)V", (void*)unloadNativeCode_native },
    { "onStartNative", "(I)V", (void*)onStart_native },
    { "onResumeNative", "(I)V", (void*)onResume_native },
    { "onSaveInstanceStateNative", "(I)V", (void*)onSaveInstanceState_native },
    { "onPauseNative", "(I)V", (void*)onPause_native },
    { "onStopNative", "(I)V", (void*)onStop_native },
    { "onLowMemoryNative", "(I)V", (void*)onLowMemory_native },
    { "onWindowFocusChangedNative", "(IZ)V", (void*)onWindowFocusChanged_native },
};

static const char* const kNativeActivityPathName = "android/app/NativeActivity";

int register_android_app_NativeActivity(JNIEnv* env)
{
    //LOGD("register_android_app_NativeActivity");

    jclass clazz;

    clazz = env->FindClass(kNativeActivityPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.app.NativeActivity");

    return AndroidRuntime::registerNativeMethods(
        env, kNativeActivityPathName,
        g_methods, NELEM(g_methods));
}

}
