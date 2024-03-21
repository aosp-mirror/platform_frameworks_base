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

#define LOG_TAG "WindowManagerGlobal"

#include "android_view_WindowManagerGlobal.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_SurfaceControl.h>
#include <android_runtime/android_window_InputTransferToken.h>
#include <jni.h>
#include <nativehelper/ScopedLocalRef.h>

#include "android_util_Binder.h"
#include "android_view_InputChannel.h"
#include "jni_wrappers.h"

namespace android {

static struct {
    jclass clazz;
    jmethodID createInputChannel;
    jmethodID removeInputChannel;
} gWindowManagerGlobal;

std::shared_ptr<InputChannel> createInputChannel(
        const sp<IBinder>& clientToken, const InputTransferToken& hostInputTransferToken,
        const SurfaceControl& surfaceControl, const InputTransferToken& clientInputTransferToken) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    ScopedLocalRef<jobject> hostInputTransferTokenObj(
            env,
            android_window_InputTransferToken_getJavaInputTransferToken(env,
                                                                        hostInputTransferToken));
    ScopedLocalRef<jobject>
            surfaceControlObj(env,
                              android_view_SurfaceControl_getJavaSurfaceControl(env,
                                                                                surfaceControl));
    ScopedLocalRef<jobject> clientTokenObj(env, javaObjectForIBinder(env, clientToken));
    ScopedLocalRef<jobject> clientInputTransferTokenObj(
            env,
            android_window_InputTransferToken_getJavaInputTransferToken(env,
                                                                        clientInputTransferToken));
    ScopedLocalRef<jobject>
            inputChannelObj(env,
                            env->CallStaticObjectMethod(gWindowManagerGlobal.clazz,
                                                        gWindowManagerGlobal.createInputChannel,
                                                        clientTokenObj.get(),
                                                        hostInputTransferTokenObj.get(),
                                                        surfaceControlObj.get(),
                                                        clientInputTransferTokenObj.get()));

    return android_view_InputChannel_getInputChannel(env, inputChannelObj.get());
}

void removeInputChannel(const sp<IBinder>& clientToken) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();

    ScopedLocalRef<jobject> clientTokenObj(env, javaObjectForIBinder(env, clientToken));
    env->CallStaticObjectMethod(gWindowManagerGlobal.clazz, gWindowManagerGlobal.removeInputChannel,
                                clientTokenObj.get());
}

int register_android_view_WindowManagerGlobal(JNIEnv* env) {
    jclass windowManagerGlobalClass = FindClassOrDie(env, "android/view/WindowManagerGlobal");
    gWindowManagerGlobal.clazz = MakeGlobalRefOrDie(env, windowManagerGlobalClass);
    gWindowManagerGlobal.createInputChannel =
            GetStaticMethodIDOrDie(env, windowManagerGlobalClass, "createInputChannel",
                                   "(Landroid/os/IBinder;Landroid/window/"
                                   "InputTransferToken;Landroid/view/SurfaceControl;Landroid/"
                                   "window/InputTransferToken;)Landroid/view/InputChannel;");
    gWindowManagerGlobal.removeInputChannel =
            GetStaticMethodIDOrDie(env, windowManagerGlobalClass, "removeInputChannel",
                                   "(Landroid/os/IBinder;)V");

    return NO_ERROR;
}

} // namespace android