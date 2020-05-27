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

#define LOG_TAG "UsbDeviceManagerJNI"
#include "utils/Log.h"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"
#include "MtpDescriptors.h"

#include <stdio.h>
#include <asm/byteorder.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/usb/f_accessory.h>

#define DRIVER_NAME "/dev/usb_accessory"

namespace android
{

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
} gParcelFileDescriptorOffsets;

static void set_accessory_string(JNIEnv *env, int fd, int cmd, jobjectArray strArray, int index)
{
    char buffer[256];

    buffer[0] = 0;
    ioctl(fd, cmd, buffer);
    if (buffer[0]) {
        jstring obj = env->NewStringUTF(buffer);
        env->SetObjectArrayElement(strArray, index, obj);
        env->DeleteLocalRef(obj);
    }
}


static jobjectArray android_server_UsbDeviceManager_getAccessoryStrings(JNIEnv *env,
                                                                        jobject /* thiz */)
{
    int fd = open(DRIVER_NAME, O_RDWR);
    if (fd < 0) {
        ALOGE("could not open %s", DRIVER_NAME);
        return NULL;
    }
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray strArray = env->NewObjectArray(6, stringClass, NULL);
    if (!strArray) goto out;
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_MANUFACTURER, strArray, 0);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_MODEL, strArray, 1);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_DESCRIPTION, strArray, 2);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_VERSION, strArray, 3);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_URI, strArray, 4);
    set_accessory_string(env, fd, ACCESSORY_GET_STRING_SERIAL, strArray, 5);

out:
    close(fd);
    return strArray;
}

static jobject android_server_UsbDeviceManager_openAccessory(JNIEnv *env, jobject /* thiz */)
{
    int fd = open(DRIVER_NAME, O_RDWR);
    if (fd < 0) {
        ALOGE("could not open %s", DRIVER_NAME);
        return NULL;
    }
    jobject fileDescriptor = jniCreateFileDescriptor(env, fd);
    if (fileDescriptor == NULL) {
        close(fd);
        return NULL;
    }
    return env->NewObject(gParcelFileDescriptorOffsets.mClass,
        gParcelFileDescriptorOffsets.mConstructor, fileDescriptor);
}

static jboolean android_server_UsbDeviceManager_isStartRequested(JNIEnv* /* env */,
                                                                 jobject /* thiz */)
{
    int fd = open(DRIVER_NAME, O_RDWR);
    if (fd < 0) {
        ALOGE("could not open %s", DRIVER_NAME);
        return false;
    }
    int result = ioctl(fd, ACCESSORY_IS_START_REQUESTED);
    close(fd);
    return (result == 1);
}

static jint android_server_UsbDeviceManager_getAudioMode(JNIEnv* /* env */, jobject /* thiz */)
{
    int fd = open(DRIVER_NAME, O_RDWR);
    if (fd < 0) {
        ALOGE("could not open %s", DRIVER_NAME);
        return false;
    }
    int result = ioctl(fd, ACCESSORY_GET_AUDIO_MODE);
    close(fd);
    return result;
}

static jobject android_server_UsbDeviceManager_openControl(JNIEnv *env, jobject /* thiz */, jstring jFunction) {
    ScopedUtfChars function(env, jFunction);
    bool ptp = false;
    int fd = -1;
    if (!strcmp(function.c_str(), "ptp")) {
        ptp = true;
    }
    if (!strcmp(function.c_str(), "mtp") || ptp) {
        fd = TEMP_FAILURE_RETRY(open(ptp ? FFS_PTP_EP0 : FFS_MTP_EP0, O_RDWR));
        if (fd < 0) {
            ALOGE("could not open control for %s %s", function.c_str(), strerror(errno));
            return NULL;
        }
        if (!writeDescriptors(fd, ptp)) {
            close(fd);
            return NULL;
        }
    }

    jobject jifd = jniCreateFileDescriptor(env, fd);
    if (jifd == NULL) {
        // OutOfMemoryError will be pending.
        close(fd);
    }
    return jifd;
}

static const JNINativeMethod method_table[] = {
    { "nativeGetAccessoryStrings",  "()[Ljava/lang/String;",
                                    (void*)android_server_UsbDeviceManager_getAccessoryStrings },
    { "nativeOpenAccessory",        "()Landroid/os/ParcelFileDescriptor;",
                                    (void*)android_server_UsbDeviceManager_openAccessory },
    { "nativeIsStartRequested",     "()Z",
                                    (void*)android_server_UsbDeviceManager_isStartRequested },
    { "nativeGetAudioMode",         "()I",
                                    (void*)android_server_UsbDeviceManager_getAudioMode },
    { "nativeOpenControl",          "(Ljava/lang/String;)Ljava/io/FileDescriptor;",
                                    (void*)android_server_UsbDeviceManager_openControl },
};

int register_android_server_UsbDeviceManager(JNIEnv *env)
{
    jclass clazz = env->FindClass("com/android/server/usb/UsbDeviceManager");
    if (clazz == NULL) {
        ALOGE("Can't find com/android/server/usb/UsbDeviceManager");
        return -1;
    }

    clazz = env->FindClass("android/os/ParcelFileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "(Ljava/io/FileDescriptor;)V");
    LOG_FATAL_IF(gParcelFileDescriptorOffsets.mConstructor == NULL,
                 "Unable to find constructor for android.os.ParcelFileDescriptor");

    return jniRegisterNativeMethods(env, "com/android/server/usb/UsbDeviceManager",
            method_table, NELEM(method_table));
}

};
