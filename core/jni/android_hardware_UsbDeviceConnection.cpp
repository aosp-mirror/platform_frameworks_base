/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "UsbDeviceConnectionJNI"

#include "utils/Log.h"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <usbhost/usbhost.h>

#include <stdio.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

using namespace android;

static jfieldID field_context;

struct usb_device* get_device_from_object(JNIEnv* env, jobject connection)
{
    return (struct usb_device*)env->GetIntField(connection, field_context);
}

static jboolean
android_hardware_UsbDeviceConnection_open(JNIEnv *env, jobject thiz, jstring deviceName,
        jobject fileDescriptor)
{
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    // duplicate the file descriptor, since ParcelFileDescriptor will eventually close its copy
    fd = dup(fd);
    if (fd < 0)
        return false;

    const char *deviceNameStr = env->GetStringUTFChars(deviceName, NULL);
    struct usb_device* device = usb_device_new(deviceNameStr, fd);
    if (device) {
        env->SetIntField(thiz, field_context, (int)device);
    } else {
        ALOGE("usb_device_open failed for %s", deviceNameStr);
        close(fd);
    }

    env->ReleaseStringUTFChars(deviceName, deviceNameStr);
    return (device != NULL);
}

static void
android_hardware_UsbDeviceConnection_close(JNIEnv *env, jobject thiz)
{
    ALOGD("close\n");
    struct usb_device* device = get_device_from_object(env, thiz);
    if (device) {
        usb_device_close(device);
        env->SetIntField(thiz, field_context, 0);
    }
}

static jint
android_hardware_UsbDeviceConnection_get_fd(JNIEnv *env, jobject thiz)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_get_fd");
        return -1;
    }
    return usb_device_get_fd(device);
}

static jbyteArray
android_hardware_UsbDeviceConnection_get_desc(JNIEnv *env, jobject thiz)
{
    char buffer[16384];
    int fd = android_hardware_UsbDeviceConnection_get_fd(env, thiz);
    if (fd < 0) return NULL;
    lseek(fd, 0, SEEK_SET);
    int length = read(fd, buffer, sizeof(buffer));
    if (length < 0) return NULL;

    jbyteArray ret = env->NewByteArray(length);
    if (ret) {
        jbyte* bytes = (jbyte*)env->GetPrimitiveArrayCritical(ret, 0);
        if (bytes) {
            memcpy(bytes, buffer, length);
            env->ReleasePrimitiveArrayCritical(ret, bytes, 0);
        }
    }
    return ret;
}

static jboolean
android_hardware_UsbDeviceConnection_claim_interface(JNIEnv *env, jobject thiz,
        int interfaceID, jboolean force)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_claim_interface");
        return -1;
    }

    int ret = usb_device_claim_interface(device, interfaceID);
    if (ret && force && errno == EBUSY) {
        // disconnect kernel driver and try again
        usb_device_connect_kernel_driver(device, interfaceID, false);
        ret = usb_device_claim_interface(device, interfaceID);
    }
    return ret == 0;
}

static jint
android_hardware_UsbDeviceConnection_release_interface(JNIEnv *env, jobject thiz, int interfaceID)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_release_interface");
        return -1;
    }
    int ret = usb_device_release_interface(device, interfaceID);
    if (ret == 0) {
        // allow kernel to reconnect its driver
        usb_device_connect_kernel_driver(device, interfaceID, true);
    }
    return ret;
}

static jint
android_hardware_UsbDeviceConnection_control_request(JNIEnv *env, jobject thiz,
        jint requestType, jint request, jint value, jint index,
        jbyteArray buffer, jint length, jint timeout)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_control_request");
        return -1;
    }

    jbyte* bufferBytes = NULL;
    if (buffer) {
        if (env->GetArrayLength(buffer) < length) {
            jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
            return -1;
        }
        bufferBytes = env->GetByteArrayElements(buffer, 0);
    }

    jint result = usb_device_control_transfer(device, requestType, request,
            value, index, bufferBytes, length, timeout);

    if (bufferBytes)
        env->ReleaseByteArrayElements(buffer, bufferBytes, 0);

    return result;
}

static jint
android_hardware_UsbDeviceConnection_bulk_request(JNIEnv *env, jobject thiz,
        jint endpoint, jbyteArray buffer, jint length, jint timeout)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_control_request");
        return -1;
    }

    jbyte* bufferBytes = NULL;
    if (buffer) {
        if (env->GetArrayLength(buffer) < length) {
            jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
            return -1;
        }
        bufferBytes = env->GetByteArrayElements(buffer, 0);
    }

    jint result = usb_device_bulk_transfer(device, endpoint, bufferBytes, length, timeout);

    if (bufferBytes)
        env->ReleaseByteArrayElements(buffer, bufferBytes, 0);

    return result;
}

static jobject
android_hardware_UsbDeviceConnection_request_wait(JNIEnv *env, jobject thiz)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_request_wait");
        return NULL;
    }

    struct usb_request* request = usb_request_wait(device);
    if (request)
        return (jobject)request->client_data;
    else
        return NULL;
}

static jstring
android_hardware_UsbDeviceConnection_get_serial(JNIEnv *env, jobject thiz)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_request_wait");
        return NULL;
    }
    char* serial = usb_device_get_serial(device);
    if (!serial)
        return NULL;
    jstring result = env->NewStringUTF(serial);
    free(serial);
    return result;
}

static JNINativeMethod method_table[] = {
    {"native_open",             "(Ljava/lang/String;Ljava/io/FileDescriptor;)Z",
                                        (void *)android_hardware_UsbDeviceConnection_open},
    {"native_close",            "()V",  (void *)android_hardware_UsbDeviceConnection_close},
    {"native_get_fd",           "()I",  (void *)android_hardware_UsbDeviceConnection_get_fd},
    {"native_get_desc",         "()[B", (void *)android_hardware_UsbDeviceConnection_get_desc},
    {"native_claim_interface",  "(IZ)Z",(void *)android_hardware_UsbDeviceConnection_claim_interface},
    {"native_release_interface","(I)Z", (void *)android_hardware_UsbDeviceConnection_release_interface},
    {"native_control_request",  "(IIII[BII)I",
                                        (void *)android_hardware_UsbDeviceConnection_control_request},
    {"native_bulk_request",     "(I[BII)I",
                                        (void *)android_hardware_UsbDeviceConnection_bulk_request},
    {"native_request_wait",             "()Landroid/hardware/usb/UsbRequest;",
                                        (void *)android_hardware_UsbDeviceConnection_request_wait},
    { "native_get_serial",      "()Ljava/lang/String;",
                                        (void*)android_hardware_UsbDeviceConnection_get_serial },
};

int register_android_hardware_UsbDeviceConnection(JNIEnv *env)
{
    jclass clazz = env->FindClass("android/hardware/usb/UsbDeviceConnection");
    if (clazz == NULL) {
        ALOGE("Can't find android/hardware/usb/UsbDeviceConnection");
        return -1;
    }
    field_context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (field_context == NULL) {
        ALOGE("Can't find UsbDeviceConnection.mNativeContext");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env, "android/hardware/usb/UsbDeviceConnection",
            method_table, NELEM(method_table));
}
