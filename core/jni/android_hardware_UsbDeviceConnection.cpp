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

#include <fcntl.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <usbhost/usbhost.h>
#include <usbhost/usbhost_jni.h>

#include <chrono>

#include "core_jni_helpers.h"
#include "jni.h"
#include "utils/Log.h"

using namespace android;
using namespace std::chrono;

static const int USB_CONTROL_READ_TIMEOUT_MS = 200;

static jfieldID field_context;

struct usb_device* get_device_from_object(JNIEnv* env, jobject connection)
{
    return (struct usb_device*)env->GetLongField(connection, field_context);
}

static jboolean
android_hardware_UsbDeviceConnection_open(JNIEnv *env, jobject thiz, jstring deviceName,
        jobject fileDescriptor)
{
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    // duplicate the file descriptor, since ParcelFileDescriptor will eventually close its copy
    fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (fd < 0)
        return JNI_FALSE;

    const char *deviceNameStr = env->GetStringUTFChars(deviceName, NULL);
    struct usb_device* device = usb_device_new(deviceNameStr, fd);
    if (device) {
        env->SetLongField(thiz, field_context, (jlong)device);
    } else {
        ALOGE("usb_device_open failed for %s", deviceNameStr);
        close(fd);
    }

    env->ReleaseStringUTFChars(deviceName, deviceNameStr);
    return (device != NULL) ? JNI_TRUE : JNI_FALSE;
}

static void
android_hardware_UsbDeviceConnection_close(JNIEnv *env, jobject thiz)
{
    ALOGD("close\n");
    struct usb_device* device = get_device_from_object(env, thiz);
    if (device) {
        usb_device_close(device);
        env->SetLongField(thiz, field_context, 0);
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
    int fd = android_hardware_UsbDeviceConnection_get_fd(env, thiz);
    return usb_jni_read_descriptors(env, fd);
}

static jboolean
android_hardware_UsbDeviceConnection_claim_interface(JNIEnv *env, jobject thiz,
        jint interfaceID, jboolean force)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_claim_interface");
        return JNI_FALSE;
    }

    int ret = usb_device_claim_interface(device, interfaceID);
    if (ret && force && errno == EBUSY) {
        // disconnect kernel driver and try again
        usb_device_connect_kernel_driver(device, interfaceID, false);
        ret = usb_device_claim_interface(device, interfaceID);
    }
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

static jboolean
android_hardware_UsbDeviceConnection_release_interface(JNIEnv *env, jobject thiz, jint interfaceID)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_release_interface");
        return JNI_FALSE;
    }
    int ret = usb_device_release_interface(device, interfaceID);
    if (ret == 0) {
        // allow kernel to reconnect its driver
        usb_device_connect_kernel_driver(device, interfaceID, true);
    }
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

static jboolean
android_hardware_UsbDeviceConnection_set_interface(JNIEnv *env, jobject thiz, jint interfaceID,
        jint alternateSetting)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_set_interface");
        return JNI_FALSE;
    }
    int ret = usb_device_set_interface(device, interfaceID, alternateSetting);
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

static jboolean
android_hardware_UsbDeviceConnection_set_configuration(JNIEnv *env, jobject thiz, jint configurationID)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_set_configuration");
        return JNI_FALSE;
    }
    int ret = usb_device_set_configuration(device, configurationID);
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

static jint
android_hardware_UsbDeviceConnection_control_request(JNIEnv *env, jobject thiz,
        jint requestType, jint request, jint value, jint index,
        jbyteArray buffer, jint start, jint length, jint timeout)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_control_request");
        return -1;
    }

    jbyte* bufferBytes = NULL;
    if (buffer) {
        bufferBytes = (jbyte*)env->GetPrimitiveArrayCritical(buffer, NULL);
    }

    jint result = usb_device_control_transfer(device, requestType, request,
            value, index, bufferBytes + start, length, timeout);

    if (bufferBytes) {
        env->ReleasePrimitiveArrayCritical(buffer, bufferBytes, 0);
    }

    return result;
}

static jint
android_hardware_UsbDeviceConnection_bulk_request(JNIEnv *env, jobject thiz,
        jint endpoint, jbyteArray buffer, jint start, jint length, jint timeout)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_control_request");
        return -1;
    }

    bool is_dir_in = (endpoint & USB_ENDPOINT_DIR_MASK) == USB_DIR_IN;
    jbyte *bufferBytes = (jbyte *)malloc(length);

    if (!is_dir_in && buffer) {
        env->GetByteArrayRegion(buffer, start, length, bufferBytes);
    }

    jint result = usb_device_bulk_transfer(device, endpoint, bufferBytes, length, timeout);

    if (is_dir_in && buffer) {
        env->SetByteArrayRegion(buffer, start, length, bufferBytes);
    }

    free(bufferBytes);

    return result;
}

static jobject
android_hardware_UsbDeviceConnection_request_wait(JNIEnv *env, jobject thiz, jlong timeoutMillis)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_request_wait");
        return NULL;
    }

    struct usb_request* request;
    if (timeoutMillis == -1) {
        request = usb_request_wait(device, -1);
    } else {
        steady_clock::time_point currentTime = steady_clock::now();
        steady_clock::time_point endTime = currentTime + std::chrono::milliseconds(timeoutMillis);

        // Poll the existence of a request via usb_request_wait until we get a result, an unexpected
        // error or time out. As several threads can listen on the same fd, we might get wakeups
        // without data.
        while (1) {
            request = usb_request_wait(device, duration_cast<std::chrono::milliseconds>(endTime
                               - currentTime).count());

            int error = errno;
            if (request != NULL) {
                break;
            }

            currentTime = steady_clock::now();
            if (currentTime >= endTime) {
                jniThrowException(env, "java/util/concurrent/TimeoutException", "");
                break;
            }

            if (error != EAGAIN) {
                break;
            }
        };
    }

    if (request) {
        return (jobject)request->client_data;
    } else {
        return NULL;
    }
}

static jstring
android_hardware_UsbDeviceConnection_get_serial(JNIEnv *env, jobject thiz)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_get_serial");
        return NULL;
    }
    char* serial = usb_device_get_serial(device,
            USB_CONTROL_READ_TIMEOUT_MS);
    if (!serial)
        return NULL;
    jstring result = env->NewStringUTF(serial);
    free(serial);
    return result;
}

static jboolean
android_hardware_UsbDeviceConnection_reset_device(JNIEnv *env, jobject thiz)
{
    struct usb_device* device = get_device_from_object(env, thiz);
    if (!device) {
        ALOGE("device is closed in native_reset_device");
        return JNI_FALSE;
    }
    int ret = usb_device_reset(device);
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

static const JNINativeMethod method_table[] = {
    {"native_open",             "(Ljava/lang/String;Ljava/io/FileDescriptor;)Z",
                                        (void *)android_hardware_UsbDeviceConnection_open},
    {"native_close",            "()V",  (void *)android_hardware_UsbDeviceConnection_close},
    {"native_get_fd",           "()I",  (void *)android_hardware_UsbDeviceConnection_get_fd},
    {"native_get_desc",         "()[B", (void *)android_hardware_UsbDeviceConnection_get_desc},
    {"native_claim_interface",  "(IZ)Z",(void *)android_hardware_UsbDeviceConnection_claim_interface},
    {"native_release_interface","(I)Z", (void *)android_hardware_UsbDeviceConnection_release_interface},
    {"native_set_interface","(II)Z",    (void *)android_hardware_UsbDeviceConnection_set_interface},
    {"native_set_configuration","(I)Z", (void *)android_hardware_UsbDeviceConnection_set_configuration},
    {"native_control_request",  "(IIII[BIII)I",
                                        (void *)android_hardware_UsbDeviceConnection_control_request},
    {"native_bulk_request",     "(I[BIII)I",
                                        (void *)android_hardware_UsbDeviceConnection_bulk_request},
    {"native_request_wait",             "(J)Landroid/hardware/usb/UsbRequest;",
                                        (void *)android_hardware_UsbDeviceConnection_request_wait},
    { "native_get_serial",      "()Ljava/lang/String;",
                                        (void*)android_hardware_UsbDeviceConnection_get_serial },
    {"native_reset_device","()Z", (void *)android_hardware_UsbDeviceConnection_reset_device},
};

int register_android_hardware_UsbDeviceConnection(JNIEnv *env)
{
    jclass clazz = FindClassOrDie(env, "android/hardware/usb/UsbDeviceConnection");
    field_context = GetFieldIDOrDie(env, clazz, "mNativeContext", "J");

    return RegisterMethodsOrDie(env, "android/hardware/usb/UsbDeviceConnection",
            method_table, NELEM(method_table));
}
