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
#include "utils/Vector.h"

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

static jmethodID method_usbDeviceAdded;
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
    Vector<int> interfaceValues;
    Vector<int> endpointValues;
    const usb_device_descriptor* deviceDesc = usb_device_get_device_descriptor(device);

    uint16_t vendorId = usb_device_get_vendor_id(device);
    uint16_t productId = usb_device_get_product_id(device);
    uint8_t deviceClass = deviceDesc->bDeviceClass;
    uint8_t deviceSubClass = deviceDesc->bDeviceSubClass;
    uint8_t protocol = deviceDesc->bDeviceProtocol;

    usb_descriptor_iter_init(device, &iter);

    while ((desc = usb_descriptor_iter_next(&iter)) != NULL) {
        if (desc->bDescriptorType == USB_DT_INTERFACE) {
            struct usb_interface_descriptor *interface = (struct usb_interface_descriptor *)desc;

            // push class, subclass, protocol and number of endpoints into interfaceValues vector
            interfaceValues.add(interface->bInterfaceNumber);
            interfaceValues.add(interface->bInterfaceClass);
            interfaceValues.add(interface->bInterfaceSubClass);
            interfaceValues.add(interface->bInterfaceProtocol);
            interfaceValues.add(interface->bNumEndpoints);
        } else if (desc->bDescriptorType == USB_DT_ENDPOINT) {
            struct usb_endpoint_descriptor *endpoint = (struct usb_endpoint_descriptor *)desc;

            // push address, attributes, max packet size and interval into endpointValues vector
            endpointValues.add(endpoint->bEndpointAddress);
            endpointValues.add(endpoint->bmAttributes);
            endpointValues.add(__le16_to_cpu(endpoint->wMaxPacketSize));
            endpointValues.add(endpoint->bInterval);
        }
    }

    usb_device_close(device);

    // handle generic device notification
    int length = interfaceValues.size();
    jintArray interfaceArray = env->NewIntArray(length);
    env->SetIntArrayRegion(interfaceArray, 0, length, interfaceValues.array());

    length = endpointValues.size();
    jintArray endpointArray = env->NewIntArray(length);
    env->SetIntArrayRegion(endpointArray, 0, length, endpointValues.array());

    jstring deviceName = env->NewStringUTF(devname);
    env->CallVoidMethod(thiz, method_usbDeviceAdded,
            deviceName, vendorId, productId, deviceClass,
            deviceSubClass, protocol, interfaceArray, endpointArray);

    env->DeleteLocalRef(interfaceArray);
    env->DeleteLocalRef(endpointArray);
    env->DeleteLocalRef(deviceName);
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

static void android_server_UsbHostManager_monitorUsbHostBus(JNIEnv *env, jobject thiz)
{
    struct usb_host_context* context = usb_host_init();
    if (!context) {
        ALOGE("usb_host_init failed");
        return;
    }
    // this will never return so it is safe to pass thiz directly
    usb_host_run(context, usb_device_added, usb_device_removed, NULL, (void *)thiz);
}

static jobject android_server_UsbHostManager_openDevice(JNIEnv *env, jobject thiz, jstring deviceName)
{
    const char *deviceNameStr = env->GetStringUTFChars(deviceName, NULL);
    struct usb_device* device = usb_device_open(deviceNameStr);
    env->ReleaseStringUTFChars(deviceName, deviceNameStr);

    if (!device)
        return NULL;

    int fd = usb_device_get_fd(device);
    if (fd < 0)
        return NULL;
    int newFD = dup(fd);
    usb_device_close(device);

    jobject fileDescriptor = jniCreateFileDescriptor(env, newFD);
    if (fileDescriptor == NULL) {
        return NULL;
    }
    return env->NewObject(gParcelFileDescriptorOffsets.mClass,
        gParcelFileDescriptorOffsets.mConstructor, fileDescriptor);
}

static JNINativeMethod method_table[] = {
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
    method_usbDeviceAdded = env->GetMethodID(clazz, "usbDeviceAdded", "(Ljava/lang/String;IIIII[I[I)V");
    if (method_usbDeviceAdded == NULL) {
        ALOGE("Can't find usbDeviceAdded");
        return -1;
    }
    method_usbDeviceRemoved = env->GetMethodID(clazz, "usbDeviceRemoved", "(Ljava/lang/String;)V");
    if (method_usbDeviceRemoved == NULL) {
        ALOGE("Can't find usbDeviceRemoved");
        return -1;
    }

    clazz = env->FindClass("android/os/ParcelFileDescriptor");
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mClass = (jclass) env->NewGlobalRef(clazz);
    gParcelFileDescriptorOffsets.mConstructor = env->GetMethodID(clazz, "<init>", "(Ljava/io/FileDescriptor;)V");
    LOG_FATAL_IF(gParcelFileDescriptorOffsets.mConstructor == NULL,
                 "Unable to find constructor for android.os.ParcelFileDescriptor");

    return jniRegisterNativeMethods(env, "com/android/server/usb/UsbHostManager",
            method_table, NELEM(method_table));
}

};
