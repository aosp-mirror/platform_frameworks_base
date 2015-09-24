/*
 * Copyright (C) 2012 The Android Open Source Project
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

#define LOG_TAG "RemoteDisplay"

#include "jni.h"
#include "JNIHelp.h"

#include "android_os_Parcel.h"
#include "android_util_Binder.h"

#include "core_jni_helpers.h"
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/Log.h>

#include <binder/IServiceManager.h>

#include <gui/IGraphicBufferProducer.h>

#include <media/IMediaPlayerService.h>
#include <media/IRemoteDisplay.h>
#include <media/IRemoteDisplayClient.h>

#include <utils/Log.h>

#include <ScopedUtfChars.h>

namespace android {

static struct {
    jmethodID notifyDisplayConnected;
    jmethodID notifyDisplayDisconnected;
    jmethodID notifyDisplayError;
} gRemoteDisplayClassInfo;

// ----------------------------------------------------------------------------

class NativeRemoteDisplayClient : public BnRemoteDisplayClient {
public:
    NativeRemoteDisplayClient(JNIEnv* env, jobject remoteDisplayObj) :
            mRemoteDisplayObjGlobal(env->NewGlobalRef(remoteDisplayObj)) {
    }

protected:
    ~NativeRemoteDisplayClient() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();
        env->DeleteGlobalRef(mRemoteDisplayObjGlobal);
    }

public:
    virtual void onDisplayConnected(const sp<IGraphicBufferProducer>& bufferProducer,
            uint32_t width, uint32_t height, uint32_t flags, uint32_t session) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();

        jobject surfaceObj = android_view_Surface_createFromIGraphicBufferProducer(env, bufferProducer);
        if (surfaceObj == NULL) {
            ALOGE("Could not create Surface from surface texture %p provided by media server.",
                  bufferProducer.get());
            return;
        }

        env->CallVoidMethod(mRemoteDisplayObjGlobal,
                gRemoteDisplayClassInfo.notifyDisplayConnected,
                surfaceObj, width, height, flags, session);
        env->DeleteLocalRef(surfaceObj);
        checkAndClearExceptionFromCallback(env, "notifyDisplayConnected");
    }

    virtual void onDisplayDisconnected() {
        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(mRemoteDisplayObjGlobal,
                gRemoteDisplayClassInfo.notifyDisplayDisconnected);
        checkAndClearExceptionFromCallback(env, "notifyDisplayDisconnected");
    }

    virtual void onDisplayError(int32_t error) {
        JNIEnv* env = AndroidRuntime::getJNIEnv();

        env->CallVoidMethod(mRemoteDisplayObjGlobal,
                gRemoteDisplayClassInfo.notifyDisplayError, error);
        checkAndClearExceptionFromCallback(env, "notifyDisplayError");
    }

private:
    jobject mRemoteDisplayObjGlobal;

    static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
        if (env->ExceptionCheck()) {
            ALOGE("An exception was thrown by callback '%s'.", methodName);
            LOGE_EX(env);
            env->ExceptionClear();
        }
    }
};

class NativeRemoteDisplay {
public:
    NativeRemoteDisplay(const sp<IRemoteDisplay>& display,
            const sp<NativeRemoteDisplayClient>& client) :
            mDisplay(display), mClient(client) {
    }

    ~NativeRemoteDisplay() {
        mDisplay->dispose();
    }

    void pause() {
        mDisplay->pause();
    }

    void resume() {
        mDisplay->resume();
    }

private:
    sp<IRemoteDisplay> mDisplay;
    sp<NativeRemoteDisplayClient> mClient;
};


// ----------------------------------------------------------------------------

static jlong nativeListen(JNIEnv* env, jobject remoteDisplayObj, jstring ifaceStr,
        jstring opPackageNameStr) {
    ScopedUtfChars iface(env, ifaceStr);
    ScopedUtfChars opPackageName(env, opPackageNameStr);

    sp<IServiceManager> sm = defaultServiceManager();
    sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(
            sm->getService(String16("media.player")));
    if (service == NULL) {
        ALOGE("Could not obtain IMediaPlayerService from service manager");
        return 0;
    }

    sp<NativeRemoteDisplayClient> client(new NativeRemoteDisplayClient(env, remoteDisplayObj));
    sp<IRemoteDisplay> display = service->listenForRemoteDisplay(String16(opPackageName.c_str()),
            client, String8(iface.c_str()));
    if (display == NULL) {
        ALOGE("Media player service rejected request to listen for remote display '%s'.",
                iface.c_str());
        return 0;
    }

    NativeRemoteDisplay* wrapper = new NativeRemoteDisplay(display, client);
    return reinterpret_cast<jlong>(wrapper);
}

static void nativePause(JNIEnv* env, jobject remoteDisplayObj, jlong ptr) {
    NativeRemoteDisplay* wrapper = reinterpret_cast<NativeRemoteDisplay*>(ptr);
    wrapper->pause();
}

static void nativeResume(JNIEnv* env, jobject remoteDisplayObj, jlong ptr) {
    NativeRemoteDisplay* wrapper = reinterpret_cast<NativeRemoteDisplay*>(ptr);
    wrapper->resume();
}

static void nativeDispose(JNIEnv* env, jobject remoteDisplayObj, jlong ptr) {
    NativeRemoteDisplay* wrapper = reinterpret_cast<NativeRemoteDisplay*>(ptr);
    delete wrapper;
}

// ----------------------------------------------------------------------------

static const JNINativeMethod gMethods[] = {
    {"nativeListen", "(Ljava/lang/String;Ljava/lang/String;)J",
            (void*)nativeListen },
    {"nativeDispose", "(J)V",
            (void*)nativeDispose },
    {"nativePause", "(J)V",
            (void*)nativePause },
    {"nativeResume", "(J)V",
            (void*)nativeResume },
};

int register_android_media_RemoteDisplay(JNIEnv* env)
{
    int err = RegisterMethodsOrDie(env, "android/media/RemoteDisplay", gMethods, NELEM(gMethods));

    jclass clazz = FindClassOrDie(env, "android/media/RemoteDisplay");
    gRemoteDisplayClassInfo.notifyDisplayConnected = GetMethodIDOrDie(env,
            clazz, "notifyDisplayConnected", "(Landroid/view/Surface;IIII)V");
    gRemoteDisplayClassInfo.notifyDisplayDisconnected = GetMethodIDOrDie(env,
            clazz, "notifyDisplayDisconnected", "()V");
    gRemoteDisplayClassInfo.notifyDisplayError = GetMethodIDOrDie(env,
            clazz, "notifyDisplayError", "(I)V");
    return err;
}

};
