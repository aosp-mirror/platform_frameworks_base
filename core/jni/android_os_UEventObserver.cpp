/*
 * Copyright (C) 2008 The Android Open Source Project
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

#define LOG_TAG "UEventObserver"
#include "utils/Log.h"

#include "hardware_legacy/uevent.h"
#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

namespace android
{

static void
android_os_UEventObserver_native_setup(JNIEnv *env, jclass clazz)
{
    if (!uevent_init()) {
        jniThrowException(env, "java/lang/RuntimeException",
                          "Unable to open socket for UEventObserver");
    }
}

static int
android_os_UEventObserver_next_event(JNIEnv *env, jclass clazz, jbyteArray jbuffer)
{
    int buf_sz = env->GetArrayLength(jbuffer);
    char *buffer = (char*)env->GetByteArrayElements(jbuffer, NULL);

    int length = uevent_next_event(buffer, buf_sz - 1);

    env->ReleaseByteArrayElements(jbuffer, (jbyte*)buffer, 0);

    return length;
}

static JNINativeMethod gMethods[] = {
    {"native_setup", "()V",   (void *)android_os_UEventObserver_native_setup},
    {"next_event",   "([B)I", (void *)android_os_UEventObserver_next_event},
};


int register_android_os_UEventObserver(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/os/UEventObserver");
    if (clazz == NULL) {
        ALOGE("Can't find android/os/UEventObserver");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
                "android/os/UEventObserver", gMethods, NELEM(gMethods));
}

}   // namespace android
