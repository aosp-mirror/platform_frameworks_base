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
#include <fcntl.h>

#include <android_runtime/android_app_NativeActivity.h>
#include <android_runtime/android_util_AssetManager.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/AndroidRuntime.h>
#include <input/InputTransport.h>

#include <gui/Surface.h>

#include <system/window.h>

#include <utils/Looper.h>

#include "JNIHelp.h"
#include "android_os_MessageQueue.h"
#include "android_view_InputChannel.h"
#include "android_view_KeyEvent.h"

#define LOG_TRACE(...)
//#define LOG_TRACE(...) ALOG(LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace android
{

static struct {
    jmethodID finish;
    jmethodID setWindowFlags;
    jmethodID setWindowFormat;
    jmethodID showIme;
    jmethodID hideIme;
} gNativeActivityClassInfo;

// ------------------------------------------------------------------------

struct ActivityWork {
    int32_t cmd;
    int32_t arg1;
    int32_t arg2;
};

enum {
    CMD_FINISH = 1,
    CMD_SET_WINDOW_FORMAT,
    CMD_SET_WINDOW_FLAGS,
    CMD_SHOW_SOFT_INPUT,
    CMD_HIDE_SOFT_INPUT,
};

static void write_work(int fd, int32_t cmd, int32_t arg1=0, int32_t arg2=0) {
    ActivityWork work;
    work.cmd = cmd;
    work.arg1 = arg1;
    work.arg2 = arg2;
    
    LOG_TRACE("write_work: cmd=%d", cmd);

restart:
    int res = write(fd, &work, sizeof(work));
    if (res < 0 && errno == EINTR) {
        goto restart;
    }
    
    if (res == sizeof(work)) return;
    
    if (res < 0) ALOGW("Failed writing to work fd: %s", strerror(errno));
    else ALOGW("Truncated writing to work fd: %d", res);
}

static bool read_work(int fd, ActivityWork* outWork) {
    int res = read(fd, outWork, sizeof(ActivityWork));
    // no need to worry about EINTR, poll loop will just come back again.
    if (res == sizeof(ActivityWork)) return true;
    
    if (res < 0) ALOGW("Failed reading work fd: %s", strerror(errno));
    else ALOGW("Truncated reading work fd: %d", res);
    return false;
}

/*
 * Native state for interacting with the NativeActivity class.
 */
struct NativeCode : public ANativeActivity {
    NativeCode(void* _dlhandle, ANativeActivity_createFunc* _createFunc) {
        memset((ANativeActivity*)this, 0, sizeof(ANativeActivity));
        memset(&callbacks, 0, sizeof(callbacks));
        dlhandle = _dlhandle;
        createActivityFunc = _createFunc;
        nativeWindow = NULL;
        mainWorkRead = mainWorkWrite = -1;
    }
    
    ~NativeCode() {
        if (callbacks.onDestroy != NULL) {
            callbacks.onDestroy(this);
        }
        if (env != NULL && clazz != NULL) {
            env->DeleteGlobalRef(clazz);
        }
        if (messageQueue != NULL && mainWorkRead >= 0) {
            messageQueue->getLooper()->removeFd(mainWorkRead);
        }
        setSurface(NULL);
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
            nativeWindow = android_view_Surface_getNativeWindow(env, _surface);
        } else {
            nativeWindow = NULL;
        }
    }
    
    ANativeActivityCallbacks callbacks;
    
    void* dlhandle;
    ANativeActivity_createFunc* createActivityFunc;
    
    String8 internalDataPathObj;
    String8 externalDataPathObj;
    String8 obbPathObj;
    
    sp<ANativeWindow> nativeWindow;
    int32_t lastWindowWidth;
    int32_t lastWindowHeight;

    // These are used to wake up the main thread to process work.
    int mainWorkRead;
    int mainWorkWrite;
    sp<MessageQueue> messageQueue;
};

void android_NativeActivity_finish(ANativeActivity* activity) {
    NativeCode* code = static_cast<NativeCode*>(activity);
    write_work(code->mainWorkWrite, CMD_FINISH, 0);
}

void android_NativeActivity_setWindowFormat(
        ANativeActivity* activity, int32_t format) {
    NativeCode* code = static_cast<NativeCode*>(activity);
    write_work(code->mainWorkWrite, CMD_SET_WINDOW_FORMAT, format);
}

void android_NativeActivity_setWindowFlags(
        ANativeActivity* activity, int32_t values, int32_t mask) {
    NativeCode* code = static_cast<NativeCode*>(activity);
    write_work(code->mainWorkWrite, CMD_SET_WINDOW_FLAGS, values, mask);
}

void android_NativeActivity_showSoftInput(
        ANativeActivity* activity, int32_t flags) {
    NativeCode* code = static_cast<NativeCode*>(activity);
    write_work(code->mainWorkWrite, CMD_SHOW_SOFT_INPUT, flags);
}

void android_NativeActivity_hideSoftInput(
        ANativeActivity* activity, int32_t flags) {
    NativeCode* code = static_cast<NativeCode*>(activity);
    write_work(code->mainWorkWrite, CMD_HIDE_SOFT_INPUT, flags);
}

// ------------------------------------------------------------------------

/*
 * Callback for handling native events on the application's main thread.
 */
static int mainWorkCallback(int fd, int events, void* data) {
    NativeCode* code = (NativeCode*)data;
    if ((events & POLLIN) == 0) {
        return 1;
    }
    
    ActivityWork work;
    if (!read_work(code->mainWorkRead, &work)) {
        return 1;
    }

    LOG_TRACE("mainWorkCallback: cmd=%d", work.cmd);

    switch (work.cmd) {
        case CMD_FINISH: {
            code->env->CallVoidMethod(code->clazz, gNativeActivityClassInfo.finish);
            code->messageQueue->raiseAndClearException(code->env, "finish");
        } break;
        case CMD_SET_WINDOW_FORMAT: {
            code->env->CallVoidMethod(code->clazz,
                    gNativeActivityClassInfo.setWindowFormat, work.arg1);
            code->messageQueue->raiseAndClearException(code->env, "setWindowFormat");
        } break;
        case CMD_SET_WINDOW_FLAGS: {
            code->env->CallVoidMethod(code->clazz,
                    gNativeActivityClassInfo.setWindowFlags, work.arg1, work.arg2);
            code->messageQueue->raiseAndClearException(code->env, "setWindowFlags");
        } break;
        case CMD_SHOW_SOFT_INPUT: {
            code->env->CallVoidMethod(code->clazz,
                    gNativeActivityClassInfo.showIme, work.arg1);
            code->messageQueue->raiseAndClearException(code->env, "showIme");
        } break;
        case CMD_HIDE_SOFT_INPUT: {
            code->env->CallVoidMethod(code->clazz,
                    gNativeActivityClassInfo.hideIme, work.arg1);
            code->messageQueue->raiseAndClearException(code->env, "hideIme");
        } break;
        default:
            ALOGW("Unknown work command: %d", work.cmd);
            break;
    }
    
    return 1;
}

// ------------------------------------------------------------------------

static jint
loadNativeCode_native(JNIEnv* env, jobject clazz, jstring path, jstring funcName,
        jobject messageQueue, jstring internalDataDir, jstring obbDir,
        jstring externalDataDir, int sdkVersion,
        jobject jAssetMgr, jbyteArray savedState)
{
    LOG_TRACE("loadNativeCode_native");

    const char* pathStr = env->GetStringUTFChars(path, NULL);
    NativeCode* code = NULL;
    
    void* handle = dlopen(pathStr, RTLD_LAZY);
    
    env->ReleaseStringUTFChars(path, pathStr);
    
    if (handle != NULL) {
        const char* funcStr = env->GetStringUTFChars(funcName, NULL);
        code = new NativeCode(handle, (ANativeActivity_createFunc*)
                dlsym(handle, funcStr));
        env->ReleaseStringUTFChars(funcName, funcStr);
        
        if (code->createActivityFunc == NULL) {
            ALOGW("ANativeActivity_onCreate not found");
            delete code;
            return 0;
        }
        
        code->messageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueue);
        if (code->messageQueue == NULL) {
            ALOGW("Unable to retrieve native MessageQueue");
            delete code;
            return 0;
        }
        
        int msgpipe[2];
        if (pipe(msgpipe)) {
            ALOGW("could not create pipe: %s", strerror(errno));
            delete code;
            return 0;
        }
        code->mainWorkRead = msgpipe[0];
        code->mainWorkWrite = msgpipe[1];
        int result = fcntl(code->mainWorkRead, F_SETFL, O_NONBLOCK);
        SLOGW_IF(result != 0, "Could not make main work read pipe "
                "non-blocking: %s", strerror(errno));
        result = fcntl(code->mainWorkWrite, F_SETFL, O_NONBLOCK);
        SLOGW_IF(result != 0, "Could not make main work write pipe "
                "non-blocking: %s", strerror(errno));
        code->messageQueue->getLooper()->addFd(
                code->mainWorkRead, 0, ALOOPER_EVENT_INPUT, mainWorkCallback, code);
        
        code->ANativeActivity::callbacks = &code->callbacks;
        if (env->GetJavaVM(&code->vm) < 0) {
            ALOGW("NativeActivity GetJavaVM failed");
            delete code;
            return 0;
        }
        code->env = env;
        code->clazz = env->NewGlobalRef(clazz);

        const char* dirStr = env->GetStringUTFChars(internalDataDir, NULL);
        code->internalDataPathObj = dirStr;
        code->internalDataPath = code->internalDataPathObj.string();
        env->ReleaseStringUTFChars(internalDataDir, dirStr);
    
        if (externalDataDir != NULL) {
            dirStr = env->GetStringUTFChars(externalDataDir, NULL);
            code->externalDataPathObj = dirStr;
            env->ReleaseStringUTFChars(externalDataDir, dirStr);
        }
        code->externalDataPath = code->externalDataPathObj.string();

        code->sdkVersion = sdkVersion;
        
        code->assetManager = assetManagerForJavaObject(env, jAssetMgr);

        if (obbDir != NULL) {
            dirStr = env->GetStringUTFChars(obbDir, NULL);
            code->obbPathObj = dirStr;
            env->ReleaseStringUTFChars(obbDir, dirStr);
        }
        code->obbPath = code->obbPathObj.string();

        jbyte* rawSavedState = NULL;
        jsize rawSavedSize = 0;
        if (savedState != NULL) {
            rawSavedState = env->GetByteArrayElements(savedState, NULL);
            rawSavedSize = env->GetArrayLength(savedState);
        }

        code->createActivityFunc(code, rawSavedState, rawSavedSize);

        if (rawSavedState != NULL) {
            env->ReleaseByteArrayElements(savedState, rawSavedState, 0);
        }
    }
    
    return (jint)code;
}

static void
unloadNativeCode_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("unloadNativeCode_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        delete code;
    }
}

static void
onStart_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onStart_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onStart != NULL) {
            code->callbacks.onStart(code);
        }
    }
}

static void
onResume_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onResume_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onResume != NULL) {
            code->callbacks.onResume(code);
        }
    }
}

static jbyteArray
onSaveInstanceState_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onSaveInstanceState_native");

    jbyteArray array = NULL;

    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onSaveInstanceState != NULL) {
            size_t len = 0;
            jbyte* state = (jbyte*)code->callbacks.onSaveInstanceState(code, &len);
            if (len > 0) {
                array = env->NewByteArray(len);
                if (array != NULL) {
                    env->SetByteArrayRegion(array, 0, len, state);
                }
            }
            if (state != NULL) {
                free(state);
            }
        }
    }

    return array;
}

static void
onPause_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onPause_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onPause != NULL) {
            code->callbacks.onPause(code);
        }
    }
}

static void
onStop_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onStop_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onStop != NULL) {
            code->callbacks.onStop(code);
        }
    }
}

static void
onConfigurationChanged_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onConfigurationChanged_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onConfigurationChanged != NULL) {
            code->callbacks.onConfigurationChanged(code);
        }
    }
}

static void
onLowMemory_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onLowMemory_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onLowMemory != NULL) {
            code->callbacks.onLowMemory(code);
        }
    }
}

static void
onWindowFocusChanged_native(JNIEnv* env, jobject clazz, jint handle, jboolean focused)
{
    LOG_TRACE("onWindowFocusChanged_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onWindowFocusChanged != NULL) {
            code->callbacks.onWindowFocusChanged(code, focused ? 1 : 0);
        }
    }
}

static void
onSurfaceCreated_native(JNIEnv* env, jobject clazz, jint handle, jobject surface)
{
    LOG_TRACE("onSurfaceCreated_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        code->setSurface(surface);
        if (code->nativeWindow != NULL && code->callbacks.onNativeWindowCreated != NULL) {
            code->callbacks.onNativeWindowCreated(code,
                    code->nativeWindow.get());
        }
    }
}

static int32_t getWindowProp(ANativeWindow* window, int what) {
    int value;
    int res = window->query(window, what, &value);
    return res < 0 ? res : value;
}

static void
onSurfaceChanged_native(JNIEnv* env, jobject clazz, jint handle, jobject surface,
        jint format, jint width, jint height)
{
    LOG_TRACE("onSurfaceChanged_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        sp<ANativeWindow> oldNativeWindow = code->nativeWindow;
        code->setSurface(surface);
        if (oldNativeWindow != code->nativeWindow) {
            if (oldNativeWindow != NULL && code->callbacks.onNativeWindowDestroyed != NULL) {
                code->callbacks.onNativeWindowDestroyed(code,
                        oldNativeWindow.get());
            }
            if (code->nativeWindow != NULL) {
                if (code->callbacks.onNativeWindowCreated != NULL) {
                    code->callbacks.onNativeWindowCreated(code,
                            code->nativeWindow.get());
                }
                code->lastWindowWidth = getWindowProp(code->nativeWindow.get(),
                        NATIVE_WINDOW_WIDTH);
                code->lastWindowHeight = getWindowProp(code->nativeWindow.get(),
                        NATIVE_WINDOW_HEIGHT);
            }
        } else {
            // Maybe it resized?
            int32_t newWidth = getWindowProp(code->nativeWindow.get(),
                    NATIVE_WINDOW_WIDTH);
            int32_t newHeight = getWindowProp(code->nativeWindow.get(),
                    NATIVE_WINDOW_HEIGHT);
            if (newWidth != code->lastWindowWidth
                    || newHeight != code->lastWindowHeight) {
                if (code->callbacks.onNativeWindowResized != NULL) {
                    code->callbacks.onNativeWindowResized(code,
                            code->nativeWindow.get());
                }
            }
        }
    }
}

static void
onSurfaceRedrawNeeded_native(JNIEnv* env, jobject clazz, jint handle)
{
    LOG_TRACE("onSurfaceRedrawNeeded_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->nativeWindow != NULL && code->callbacks.onNativeWindowRedrawNeeded != NULL) {
            code->callbacks.onNativeWindowRedrawNeeded(code, code->nativeWindow.get());
        }
    }
}

static void
onSurfaceDestroyed_native(JNIEnv* env, jobject clazz, jint handle, jobject surface)
{
    LOG_TRACE("onSurfaceDestroyed_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->nativeWindow != NULL && code->callbacks.onNativeWindowDestroyed != NULL) {
            code->callbacks.onNativeWindowDestroyed(code,
                    code->nativeWindow.get());
        }
        code->setSurface(NULL);
    }
}

static void
onInputQueueCreated_native(JNIEnv* env, jobject clazz, jint handle, jint queuePtr)
{
    LOG_TRACE("onInputChannelCreated_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onInputQueueCreated != NULL) {
            AInputQueue* queue = reinterpret_cast<AInputQueue*>(queuePtr);
            code->callbacks.onInputQueueCreated(code, queue);
        }
    }
}

static void
onInputQueueDestroyed_native(JNIEnv* env, jobject clazz, jint handle, jint queuePtr)
{
    LOG_TRACE("onInputChannelDestroyed_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onInputQueueDestroyed != NULL) {
            AInputQueue* queue = reinterpret_cast<AInputQueue*>(queuePtr);
            code->callbacks.onInputQueueDestroyed(code, queue);
        }
    }
}

static void
onContentRectChanged_native(JNIEnv* env, jobject clazz, jint handle,
        jint x, jint y, jint w, jint h)
{
    LOG_TRACE("onContentRectChanged_native");
    if (handle != 0) {
        NativeCode* code = (NativeCode*)handle;
        if (code->callbacks.onContentRectChanged != NULL) {
            ARect rect;
            rect.left = x;
            rect.top = y;
            rect.right = x+w;
            rect.bottom = y+h;
            code->callbacks.onContentRectChanged(code, &rect);
        }
    }
}

static const JNINativeMethod g_methods[] = {
    { "loadNativeCode", "(Ljava/lang/String;Ljava/lang/String;Landroid/os/MessageQueue;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILandroid/content/res/AssetManager;[B)I",
            (void*)loadNativeCode_native },
    { "unloadNativeCode", "(I)V", (void*)unloadNativeCode_native },
    { "onStartNative", "(I)V", (void*)onStart_native },
    { "onResumeNative", "(I)V", (void*)onResume_native },
    { "onSaveInstanceStateNative", "(I)[B", (void*)onSaveInstanceState_native },
    { "onPauseNative", "(I)V", (void*)onPause_native },
    { "onStopNative", "(I)V", (void*)onStop_native },
    { "onConfigurationChangedNative", "(I)V", (void*)onConfigurationChanged_native },
    { "onLowMemoryNative", "(I)V", (void*)onLowMemory_native },
    { "onWindowFocusChangedNative", "(IZ)V", (void*)onWindowFocusChanged_native },
    { "onSurfaceCreatedNative", "(ILandroid/view/Surface;)V", (void*)onSurfaceCreated_native },
    { "onSurfaceChangedNative", "(ILandroid/view/Surface;III)V", (void*)onSurfaceChanged_native },
    { "onSurfaceRedrawNeededNative", "(ILandroid/view/Surface;)V", (void*)onSurfaceRedrawNeeded_native },
    { "onSurfaceDestroyedNative", "(I)V", (void*)onSurfaceDestroyed_native },
    { "onInputQueueCreatedNative", "(II)V",
        (void*)onInputQueueCreated_native },
    { "onInputQueueDestroyedNative", "(II)V",
        (void*)onInputQueueDestroyed_native },
    { "onContentRectChangedNative", "(IIIII)V", (void*)onContentRectChanged_native },
};

static const char* const kNativeActivityPathName = "android/app/NativeActivity";

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class %s", className);

#define GET_METHOD_ID(var, clazz, methodName, fieldDescriptor) \
        var = env->GetMethodID(clazz, methodName, fieldDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method" methodName);
        
int register_android_app_NativeActivity(JNIEnv* env)
{
    //ALOGD("register_android_app_NativeActivity");
    jclass clazz;
    FIND_CLASS(clazz, kNativeActivityPathName);

    GET_METHOD_ID(gNativeActivityClassInfo.finish,
            clazz,
            "finish", "()V");
    GET_METHOD_ID(gNativeActivityClassInfo.setWindowFlags,
            clazz,
            "setWindowFlags", "(II)V");
    GET_METHOD_ID(gNativeActivityClassInfo.setWindowFormat,
            clazz,
            "setWindowFormat", "(I)V");
    GET_METHOD_ID(gNativeActivityClassInfo.showIme,
            clazz,
            "showIme", "(I)V");
    GET_METHOD_ID(gNativeActivityClassInfo.hideIme,
            clazz,
            "hideIme", "(I)V");

    return AndroidRuntime::registerNativeMethods(
        env, kNativeActivityPathName,
        g_methods, NELEM(g_methods));
}

} // namespace android
