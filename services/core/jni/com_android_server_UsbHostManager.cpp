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

#define LOG_TAG "UsbHostManagerJNI"
#include "utils/Log.h"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/Log.h"

#include <usbhost/usbhost.h>

#include <stdio.h>
#include <asm/byteorder.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/ioctl.h>

namespace android
{

static struct parcel_file_descriptor_offsets_t
{
    jclass mClass;
    jmethodID mConstructor;
} gParcelFileDescriptorOffsets;

static jmethodID method_beginUsbDeviceAdded;
static jmethodID method_addUsbConfiguration;
static jmethodID method_addUsbInterface;
static jmethodID method_addUsbEndpoint;
static jmethodID method_endUsbDeviceAdded;
static jmethodID method_usbDeviceRemoved;

static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        ALOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

static int usb_device_added(const char *devname, void* client_data) {
    struct usb_descriptor_header* desc;
    struct usb_descriptor_iter iter;

    struct usb_device *device = usb_device_open(devname);
    if (!device) {
        ALOGE("usb_device_open failed\n");
        return 0;
    }

    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject thiz = (jobject)client_data;
    const usb_device_descriptor* deviceDesc = usb_device_get_device_descriptor(device);

    char *manufacturer = usb_device_get_manufacturer_name(device);
    char *product = usb_device_get_product_name(device);
    int version = usb_device_get_version(device);
    char *serial = usb_device_get_serial(device);

    jstring deviceName = env->NewStringUTF(devname);
    jstring manufacturerName = AndroidRuntime::NewStringLatin1(env, manufacturer);
    jstring productName = AndroidRuntime::NewStringLatin1(env, product);
    jstring serialNumber = AndroidRuntime::NewStringLatin1(env, serial);

    jboolean result = env->CallBooleanMethod(thiz, method_beginUsbDeviceAdded,
            deviceName, usb_device_get_vendor_id(device), usb_device_get_product_id(device),
            deviceDesc->bDeviceClass, deviceDesc->bDeviceSubClass, deviceDesc->bDeviceProtocol,
            manufacturerName, productName, version, serialNumber);

    env->DeleteLocalRef(serialNumber);
    env->DeleteLocalRef(productName);
    env->DeleteLocalRef(manufacturerName);
    env->DeleteLocalRef(deviceName);
    free(manufacturer);
    free(product);
    free(serial);

    if (!result) goto fail;

    usb_descriptor_iter_init(device, &iter);

    while ((desc = usb_descriptor_iter_next(&iter)) != NULL) {
        if (desc->bDescriptorType == USB_DT_CONFIG) {
            struct usb_config_descriptor *config = (struct usb_config_descriptor *)desc;
            char *name = usb_device_get_string(device, config->iConfiguration);
            jstring configName = AndroidRuntime::NewStringLatin1(env, name);

            env->CallVoidMethod(thiz, method_addUsbConfiguration,
                    config->bConfigurationValue, configName, config->bmAttributes,
                    config->bMaxPower);

            env->DeleteLocalRef(configName);
            free(name);
        } else if (desc->bDescriptorType == USB_DT_INTERFACE) {
            struct usb_interface_descriptor *interface = (struct usb_interface_descriptor *)desc;
            char *name = usb_device_get_string(device, interface->iInterface);
            jstring interfaceName = AndroidRuntime::NewStringLatin1(env, name);

            env->CallVoidMethod(thiz, method_addUsbInterface,
                    interface->bInterfaceNumber, interfaceName, interface->bAlternateSetting,
                    interface->bInterfaceClass, interface->bInterfaceSubClass,
                    interface->bInterfaceProtocol);

            env->DeleteLocalRef(interfaceName);
            free(name);
        } else if (desc->bDescriptorType == USB_DT_ENDPOINT) {
            struct usb_endpoint_descriptor *endpoint = (struct usb_endpoint_descriptor *)desc;

            env->CallVoidMethod(thiz, method_addUsbEndpoint,
                    endpoint->bEndpointAddress, endpoint->bmAttributes,
                    __le16_to_cpu(endpoint->wMaxPacketSize), endpoint->bInterval);
        }
    }

    env->CallVoidMethod(thiz, method_endUsbDeviceAdded);

fail:
    usb_device_close(device);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);

    return 0;
}

static int usb_device_removed(const char *devname, void* client_data) {
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    jobject thiz = (jobject)client_data;

    jstring deviceName = env->NewStringUTF(devname);
    env->CallVoidMethod(thiz, method_usbDeviceRemoved, deviceName);
    env->DeleteLocalRef(deviceName);
    checkAndClearExceptionFromCallback(env, __FUNCTION__);
    return 0;
}

static void android_server_UsbHostManager_monitorUsbHostBus(JNIEnv* /* env */, jobject thiz)
{
    struct usb_host_context* context = usb_host_init();
    if (!context) {
        ALOGE("usb_host_init failed");
        return;
    }
    // this will never return so it is safe to pass thiz directly
    usb_host_run(context, usb_device_added, usb_device_removed, NULL, (void *)thiz);
}

static jobject android_server_UsbHostManager_openDevice(JNIEnv *env, jobject /* thiz */,
                                                        jstring deviceName)
{
    const char *deviceNameStr = env->GetStringUTFChars(deviceName, NULL);
    struct usb_device* device = usb_device_open(deviceNameStr);
    env->ReleaseStringUTFChars(deviceName, deviceNameStr);

    if (!device)
        return NULL;

    int fd = usb_device_get_fd(device);
    if (fd < 0) {
        usb_device_close(device);
        return NULL;
    }
    int newFD = dup(fd);
    usb_device_close(device);

    jobject fileDescriptor = jniCreateFileDescriptor(env, newFD);
    if (fileDescriptor == NULL) {
        return NULL;
    }
    return env->NewObject(gParcelFileDescriptorOffsets.mClass,
        gParcelFileDescriptorOffsets.mConstructor, fileDescriptor);
}

static const JNINativeMethod method_table[] = {
    { "monitorUsbHostBus", "()V", (void*)android_server_UsbHostManager_monitorUsbHostBus },
    { "nativeOpenDevice",  "(Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;",
                                  (void*)android_server_UsbHostManager_openDevice },
};

int register_android_server_UsbHostManager(JNIEnv *env)
{
    jclass clazz = env->FindClass("com/android/server/usb/UsbHostManager");
    if (clazz == NULL) {
        ALOGE("Can't find com/android/server/usb/UsbHostManager");
        return -1;
    }
    method_beginUsbDeviceAdded = env->GetMethodID(clazz, "beginUsbDeviceAdded",
            "(Ljava/lang/String;IIIIILjava/lang/String;Ljava/lang/String;ILjava/lang/String;)Z");
    if (method_beginUsbDeviceAdded == NULL) {
        ALOGE("Can't find beginUsbDeviceAdded");
        return -1;
    }
    method_addUsbConfiguration = env->GetMethodID(clazz, "addUsbConfiguration",
            "(ILjava/lang/String;II)V");
    if (method_addUsbConfiguration == NULL) {
        ALOGE("Can't find addUsbConfiguration");
        return -1;
    }
    method_addUsbInterface = env->GetMethodID(clazz, "addUsbInterface",
            "(ILjava/lang/String;IIII)V");
    if (method_addUsbInterface == NULL) {
        ALOGE("Can't find addUsbInterface");
        return -1;
    }
    method_addUsbEndpoint = env->GetMethodID(clazz, "addUsbEndpoint", "(IIII)V");
    if (method_addUsbEndpoint == NULL) {
        ALOGE("Can't find addUsbEndpoint");
        return -1;
    }
    method_endUsbDeviceAdded = env->GetMethodID(clazz, "endUsbDeviceAdded", "()V");
    if (method_endUsbDeviceAdded == NULL) {
        ALOGE("Can't find endUsbDeviceAdded");
        return -1;
    }
    method_usbDeviceRemoved = env->GetMethodID(clazz, "usbDeviceRemoved",
            "(Ljava/lang/String;)V");
    if (method_usbDeviceRemoved == NULL) {
        ALOGE("Can't find usbDeviceRemoved");
        return -1;
    }

    clazz = env->FindClass("android/os/ParcelFileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>",
            "(Ljava/io/FileDescriptor;)V");
    LOG_FATAL_IF(gParcelFileDescriptorOffsets.mConstructor == NULL,
                 "Unable to find constructor for android.os.ParcelFileDescriptor");

    return jniRegisterNativeMethods(env, "com/android/server/usb/UsbHostManager",
            method_table, NELEM(method_table));
}

};
