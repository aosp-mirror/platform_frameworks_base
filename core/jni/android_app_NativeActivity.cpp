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

#include <poll.h>
#include <dlfcn.h>

#include <android_runtime/AndroidRuntime.h>
#include <android/native_activity.h>
#include <surfaceflinger/Surface.h>
#include <ui/egl/android_natives.h>
#include <ui/InputTransport.h>
#include <utils/PollLoop.h>

#include "JNIHelp.h"
#include "android_os_MessageQueue.h"
#include "android_view_InputChannel.h"
#include "android_view_KeyEvent.h"
#include "android_view_Surface.h"

namespace android
{

static struct {
    jclass clazz;

    jmethodID dispatchUnhandledKeyEvent;
    jmethodID setWindowFlags;
    jmethodID setWindowFormat;
} gNativeActivityClassInfo;

// ------------------------------------------------------------------------

/*
 * Specialized input queue that allows unhandled key events to be dispatched
 * back to the native activity's Java framework code.
 */
struct MyInputQueue : AInputQueue {
    explicit MyInputQueue(const android::sp<android::InputChannel>& channel, int workWrite)
        : AInputQueue(channel), mWorkWrite(workWrite) {
    }
    
    virtual void doDefaultKey(android::KeyEvent* keyEvent) {
        mLock.lock();
        LOGI("Default key: pending=%d write=%d\n", mPendingKeys.size(), mWorkWrite);
        if (mPendingKeys.size() <= 0 && mWorkWrite >= 0) {
            int8_t cmd = 1;
            write(mWorkWrite, &cmd, sizeof(cmd));
        }
        mPendingKeys.add(keyEvent);
        mLock.unlock();
    }
    
    KeyEvent* getNextEvent() {
        KeyEvent* event = NULL;
        
        mLock.lock();
        if (mPendingKeys.size() > 0) {
            event = mPendingKeys[0];
            mPendingKeys.removeAt(0);
        }
        mLock.unlock();
        
        return event;
    }
    
    int mWorkWrite;
    
    Mutex mLock;
    Vector<KeyEvent*> mPendingKeys;
};

// ------------------------------------------------------------------------

/*
 * Native state for interacting with the NativeActivity class.
 */
struct NativeCode {
    NativeCode(void* _dlhandle, ANativeActivity_createFunc* _createFunc) {
        memset(&activity, sizeof(activity), 0);
        memset(&callbacks, sizeof(callbacks), 0);
        dlhandle = _dlhandle;
        createActivityFunc = _createFunc;
        nativeWindow = NULL;
        inputChannel = NULL;
        nativeInputQueue = NULL;
        mainWorkRead = mainWorkWrite = -1;
    }
    
    ~NativeCode() {
        if (activity.env != NULL && activity.clazz != NULL) {
            activity.env->DeleteGlobalRef(activity.clazz);
        }
        if (pollLoop != NULL && mainWorkRead >= 0) {
            pollLoop->removeCallback(mainWorkRead);
        }
        if (nativeInputQueue != NULL) {
            nativeInputQueue->mWorkWrite = -1;
        }
        setSurface(NULL);
        setInputChannel(NULL);
        if (callbacks.onDestroy != NULL) {
            callbacks.onDestroy(&activity);
        }
        if (mainWorkRead >= 0) close(mainWorkRead);
        if (mainWorkWrite >= 0) close(mainWorkWrite);
        if (dlhandle != NULL) {
            // for now don't unload...  we probably should clean this
            // up and only keep one open dlhandle per proc, since there
            // is really no benefit to unloading the code.
            //dlclose(dlhandle);
        }
    }
    
    void setSurface(jobject _surface) {
        if (_surface != NULL) {
            nativeWindow = android_Surface_getNativeWindow(activity.env, _surface);
        } else {
            nativeWindow = NULL;
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
                nativeInputQueue = new MyInputQueue(ic, mainWorkWrite);
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
    
    String8 internalDataPath;
    String8 externalDataPath;
    
    sp<ANativeWindow> nativeWindow;
    jobject inputChannel;
    struct MyInputQueue* nativeInputQueue;
    
    // These are used to wake up the main thread to process work.
    int mainWorkRead;
    int mainWorkWrite;
    sp<PollLoop> pollLoop;
};

// ------------------------------------------------------------------------

/*
 * Callback for handling native events on the application's main thread.
 */
static bool mainWorkCallback(int fd, int events, void* data) {
    NativeCode* code = (NativeCode*)data;
    if ((events & POLLIN) != 0) {
        KeyEvent* keyEvent;
        while ((keyEvent=code->nativeInputQueue->getNextEvent()) != NULL) {
            jobject inputEventObj = android_view_KeyEvent_fromNative(
                    code->activity.env, keyEvent);
            code->activity.env->CallVoidMethod(code->activity.clazz,
                    gNativeActivityClassInfo.dispatchUnhandledKeyEvent, inputEventObj);
            int32_t res = code->nativeInputQueue->getConsumer().sendFinishedSignal();
            if (res != OK) {
                LOGW("Failed to send finished signal on channel '%s'.  status=%d",
                        code->nativeInputQueue->getConsumer().getChannel()->getName().string(), res);
            }
        }
    }
    
    return true;
}

// ------------------------------------------------------------------------

static jint
loadNativeCode_native(JNIEnv* env, jobject clazz, jstring path, jobject messageQueue,
        jstring internalDataDir, jstring externalDataDir, int sdkVersion)
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
        
        code->pollLoop = android_os_MessageQueue_getPollLoop(env, messageQueue);
        if (code->pollLoop == NULL) {
            LOGW("Unable to retrieve MessageQueue's PollLoop");
            delete code;
            return 0;
        }
        
        int msgpipe[2];
        if (pipe(msgpipe)) {
            LOGW("could not create pipe: %s", strerror(errno));
            delete code;
            return 0;
        }
        code->mainWorkRead = msgpipe[0];
        code->mainWorkWrite = msgpipe[1];
        code->pollLoop->setCallback(code->mainWorkRead, POLLIN, mainWorkCallback, code);
        
        code->activity.callbacks = &code->callbacks;
        if (env->GetJavaVM(&code->activity.vm) < 0) {
            LOGW("NativeActivity GetJavaVM failed");
            delete code;
            return 0;
        }
        code->activity.env = env;
        code->activity.clazz = env->NewGlobalRef(clazz);
        
        const char* dirStr = env->GetStringUTFChars(internalDataDir, NULL);
        code->internalDataPath = dirStr;
        code->activity.internalDataPath = code->internalDataPath.string();
        env->ReleaseStringUTFChars(path, dirStr);
    
        dirStr = env->GetStringUTFChars(externalDataDir, NULL);
        code->externalDataPath = dirStr;
        code->activity.externalDataPath = code->externalDataPath.string();
        env->ReleaseStringUTFChars(path, dirStr);
    
        code->activity.sdkVersion = sdkVersion;
        
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
        if (code->nativeWindow != NULL && code->callbacks.onNativeWindowCreated != NULL) {
            code->callbacks.onNativeWindowCreated(&code->activity,
                    code->nativeWindow.get());
        }
    }
}

static void
onSurfaceChanged_native(JNIEnv* env, jobject clazz, jint handle, jobject surface,
        jint format, jint width, jint height)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        sp<ANativeWindow> oldNativeWindow = code->nativeWindow;
        code->setSurface(surface);
        if (oldNativeWindow != code->nativeWindow) {
            if (oldNativeWindow != NULL && code->callbacks.onNativeWindowDestroyed != NULL) {
                code->callbacks.onNativeWindowDestroyed(&code->activity,
                        oldNativeWindow.get());
            }
            if (code->nativeWindow != NULL && code->callbacks.onNativeWindowCreated != NULL) {
                code->callbacks.onNativeWindowCreated(&code->activity,
                        code->nativeWindow.get());
            }
        }
    }
}

static void
onSurfaceDestroyed_native(JNIEnv* env, jobject clazz, jint handle, jobject surface)
{
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->nativeWindow != NULL && code->callbacks.onNativeWindowDestroyed != NULL) {
            code->callbacks.onNativeWindowDestroyed(&code->activity,
                    code->nativeWindow.get());
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
    { "loadNativeCode", "(Ljava/lang/String;Landroid/os/MessageQueue;Ljava/lang/String;Ljava/lang/String;I)I",
            (void*)loadNativeCode_native },
    { "unloadNativeCode", "(I)V", (void*)unloadNativeCode_native },
    { "onStartNative", "(I)V", (void*)onStart_native },
    { "onResumeNative", "(I)V", (void*)onResume_native },
    { "onSaveInstanceStateNative", "(I)V", (void*)onSaveInstanceState_native },
    { "onPauseNative", "(I)V", (void*)onPause_native },
    { "onStopNative", "(I)V", (void*)onStop_native },
    { "onLowMemoryNative", "(I)V", (void*)onLowMemory_native },
    { "onWindowFocusChangedNative", "(IZ)V", (void*)onWindowFocusChanged_native },
    { "onSurfaceCreatedNative", "(ILandroid/view/Surface;)V", (void*)onSurfaceCreated_native },
    { "onSurfaceChangedNative", "(ILandroid/view/Surface;III)V", (void*)onSurfaceChanged_native },
    { "onSurfaceDestroyedNative", "(I)V", (void*)onSurfaceDestroyed_native },
    { "onInputChannelCreatedNative", "(ILandroid/view/InputChannel;)V", (void*)onInputChannelCreated_native },
    { "onInputChannelDestroyedNative", "(ILandroid/view/InputChannel;)V", (void*)onInputChannelDestroyed_native },
};

static const char* const kNativeActivityPathName = "android/app/NativeActivity";

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method" methodName);
        
int register_android_app_NativeActivity(JNIEnv* env)
{
    //LOGD("register_android_app_NativeActivity");

    FIND_CLASS(gNativeActivityClassInfo.clazz, kNativeActivityPathName);
    
    GET_METHOD_ID(gNativeActivityClassInfo.dispatchUnhandledKeyEvent,
            gNativeActivityClassInfo.clazz,
            "dispatchUnhandledKeyEvent", "(Landroid/view/KeyEvent;)V");

    GET_METHOD_ID(gNativeActivityClassInfo.setWindowFlags,
            gNativeActivityClassInfo.clazz,
            "setWindowFlags", "(II)V");
    GET_METHOD_ID(gNativeActivityClassInfo.setWindowFormat,
            gNativeActivityClassInfo.clazz,
            "setWindowFormat", "(I)V");

    return AndroidRuntime::registerNativeMethods(
        env, kNativeActivityPathName,
        g_methods, NELEM(g_methods));
}

} // namespace android
