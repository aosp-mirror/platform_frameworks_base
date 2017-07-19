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

#define LOG_TAG "UsbDeviceJNI"

#include "utils/Log.h"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "core_jni_helpers.h"

#include <usbhost/usbhost.h>

using namespace android;

static jint
android_hardware_UsbDevice_get_device_id(JNIEnv *env, jobject clazz, jstring name)
{
    const char *nameStr = env->GetStringUTFChars(name, NULL);
    int id = usb_device_get_unique_id_from_name(nameStr);
    env->ReleaseStringUTFChars(name, nameStr);
    return id;
}

static jstring
android_hardware_UsbDevice_get_device_name(JNIEnv *env, jobject clazz, jint id)
{
    char* name = usb_device_get_name_from_unique_id(id);
    jstring result = env->NewStringUTF(name);
    free(name);
    return result;
}

static const JNINativeMethod method_table[] = {
    // static methods
    { "native_get_device_id", "(Ljava/lang/String;)I",
                                        (void*)android_hardware_UsbDevice_get_device_id },
    { "native_get_device_name", "(I)Ljava/lang/String;",
                                        (void*)android_hardware_UsbDevice_get_device_name },
};

int register_android_hardware_UsbDevice(JNIEnv *env)
{
    return RegisterMethodsOrDie(env, "android/hardware/usb/UsbDevice",
            method_table, NELEM(method_table));
}
