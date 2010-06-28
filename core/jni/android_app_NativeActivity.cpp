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
#include "android_view_InputChannel.h"
#include <android_runtime/AndroidRuntime.h>
#include <android/native_activity.h>
#include <ui/InputTransport.h>

#include <dlfcn.h>

namespace android
{

struct NativeCode {
    NativeCode(void* _dlhandle, ANativeActivity_createFunc* _createFunc) {
        memset(&activity, sizeof(activity), 0);
        memset(&callbacks, sizeof(callbacks), 0);
        dlhandle = _dlhandle;
        createActivityFunc = _createFunc;
        surface = NULL;
        inputChannel = NULL;
        nativeInputQueue = NULL;
    }
    
    ~NativeCode() {
        setSurface(NULL);
        setInputChannel(NULL);
        if (callbacks.onDestroy != NULL) {
            callbacks.onDestroy(&activity);
        }
        if (dlhandle != NULL) {
            dlclose(dlhandle);
        }
    }
    
    void setSurface(jobject _surface) {
        if (surface != NULL) {
            activity.env->DeleteGlobalRef(surface);
        }
        if (_surface != NULL) {
            surface = activity.env->NewGlobalRef(_surface);
        } else {
            surface = NULL;
        }
    }
    
    status_t setInputChannel(jobject _channel) {
        if (inputChannel != NULL) {
            delete nativeInputQueue;
            activity.env->DeleteGlobalRef(inputChannel);
        }
        inputChannel = NULL;
        nativeInputQueue = NULL;
        if (_channel != NULL) {
            inputChannel = activity.env->NewGlobalRef(_channel);
            sp<InputChannel> ic =
                    android_view_InputChannel_getInputChannel(activity.env, _channel);
            if (ic != NULL) {
                nativeInputQueue = new AInputQueue(ic);
                if (nativeInputQueue->getConsumer().initialize() != android::OK) {
                    delete nativeInputQueue;
                    nativeInputQueue = NULL;
                    return UNKNOWN_ERROR;
                }
            } else {
                return UNKNOWN_ERROR;
            }
        }
        return OK;
    }
    
    ANativeActivity activity;
    ANativeActivityCallbacks callbacks;
    
    void* dlhandle;
    ANativeActivity_createFunc* createActivityFunc;
    
    jobject surface;
    jobject inputChannel;
    struct AInputQueue* nativeInputQueue;
};

static jint
loadNativeCode_native(JNIEnv* env, jobject clazz, jstring path)
{
    const char* pathStr = env->GetStringUTFChars(path, NULL);
    NativeCode* code = NULL;
    
    void* handle = dlopen(pathStr, RTLD_LAZY);
    
    env->ReleaseStringUTFChars(path, pathStr);
    
    if (handle != NULL) {
        code = new NativeCode(handle, (ANativeActivity_createFunc*)
                dlsym(handle, "ANativeActivity_onCreate"));
        if (code->createActivityFunc == NULL) {
            LOGW("ANativeActivity_onCreate not found");
            delete code;
            return 0;
        }
        code->activity.callbacks = &code->callbacks;
        if (env->GetJavaVM(&code->activity.vm) < 0) {
            LOGW("NativeActivity GetJavaVM failed");
            delete code;
            return 0;
        }
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

static void
onSurfaceCreated_native(JNIEnv* env, jobject clazz, jint handle, jobject surface)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        code->setSurface(surface);
        if (code->callbacks.onSurfaceCreated != NULL) {
            code->callbacks.onSurfaceCreated(&code->activity,
                    (ASurfaceHolder*)code->surface);
        }
    }
}

static void
onSurfaceChanged_native(JNIEnv* env, jobject clazz, jint handle, jobject surface,
        jint format, jint width, jint height)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->surface != NULL && code->callbacks.onSurfaceChanged != NULL) {
            code->callbacks.onSurfaceChanged(&code->activity,
                    (ASurfaceHolder*)code->surface, format, width, height);
        }
    }
}

static void
onSurfaceDestroyed_native(JNIEnv* env, jobject clazz, jint handle, jobject surface)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->surface != NULL && code->callbacks.onSurfaceDestroyed != NULL) {
            code->callbacks.onSurfaceDestroyed(&code->activity,
                    (ASurfaceHolder*)code->surface);
        }
        code->setSurface(NULL);
    }
}

static void
onInputChannelCreated_native(JNIEnv* env, jobject clazz, jint handle, jobject channel)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        status_t err = code->setInputChannel(channel);
        if (err != OK) {
            jniThrowException(env, "java/lang/IllegalStateException",
                    "Error setting input channel");
            return;
        }
        if (code->callbacks.onInputQueueCreated != NULL) {
            code->callbacks.onInputQueueCreated(&code->activity,
                    code->nativeInputQueue);
        }
    }
}

static void
onInputChannelDestroyed_native(JNIEnv* env, jobject clazz, jint handle, jobject channel)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->nativeInputQueue != NULL
                && code->callbacks.onInputQueueDestroyed != NULL) {
            code->callbacks.onInputQueueDestroyed(&code->activity,
                    code->nativeInputQueue);
        }
        code->setInputChannel(NULL);
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
    { "onSurfaceCreatedNative", "(ILandroid/view/SurfaceHolder;)V", (void*)onSurfaceCreated_native },
    { "onSurfaceChangedNative", "(ILandroid/view/SurfaceHolder;III)V", (void*)onSurfaceChanged_native },
    { "onSurfaceDestroyedNative", "(ILandroid/view/SurfaceHolder;)V", (void*)onSurfaceDestroyed_native },
    { "onInputChannelCreatedNative", "(ILandroid/view/InputChannel;)V", (void*)onInputChannelCreated_native },
    { "onInputChannelDestroyedNative", "(ILandroid/view/InputChannel;)V", (void*)onInputChannelDestroyed_native },
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

} // namespace android
