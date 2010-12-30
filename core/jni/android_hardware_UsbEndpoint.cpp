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

#define LOG_TAG "UsbEndpoint"

#include "utils/Log.h"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <usbhost/usbhost.h>

#include <stdio.h>

using namespace android;

static jfieldID field_context;
static jfieldID field_address;
static jfieldID field_attributes;
static jfieldID field_max_packet_size;
static jfieldID field_interval;

struct usb_endpoint* get_endpoint_from_object(JNIEnv* env, jobject javaEndpoint)
{
    return (struct usb_endpoint*)env->GetIntField(javaEndpoint, field_context);
}

// in android_hardware_UsbDevice.cpp
extern struct usb_device* get_device_from_object(JNIEnv* env, jobject javaDevice);

static jboolean
android_hardware_UsbEndpoint_init(JNIEnv *env, jobject thiz, jobject javaDevice)
{
    LOGD("open\n");

    struct usb_device* device = get_device_from_object(env, javaDevice);
    if (!device) {
        LOGE("device null in native_init");
        return false;
    }

    // construct an endpoint descriptor from the Java object fields
    struct usb_endpoint_descriptor desc;
    desc.bLength = USB_DT_ENDPOINT_SIZE;
    desc.bDescriptorType = USB_DT_ENDPOINT;
    desc.bEndpointAddress = env->GetIntField(thiz, field_address);
    desc.bmAttributes = env->GetIntField(thiz, field_attributes);
    desc.wMaxPacketSize = env->GetIntField(thiz, field_max_packet_size);
    desc.bInterval = env->GetIntField(thiz, field_interval);

    struct usb_endpoint* endpoint = usb_endpoint_init(device, &desc);
    if (endpoint)
        env->SetIntField(thiz, field_context, (int)device);
    return (endpoint != NULL);
}

static void
android_hardware_UsbEndpoint_close(JNIEnv *env, jobject thiz)
{
    LOGD("close\n");
    struct usb_endpoint* endpoint = get_endpoint_from_object(env, thiz);
    if (endpoint) {
        usb_endpoint_close(endpoint);
        env->SetIntField(thiz, field_context, 0);
    }
}

static JNINativeMethod method_table[] = {
    {"native_init",             "(Landroid/hardware/UsbDevice;)Z",
                                (void *)android_hardware_UsbEndpoint_init},
    {"native_close",            "()V",  (void *)android_hardware_UsbEndpoint_close},
};

int register_android_hardware_UsbEndpoint(JNIEnv *env)
{
    jclass clazz = env->FindClass("android/hardware/UsbEndpoint");
    if (clazz == NULL) {
        LOGE("Can't find android/hardware/UsbEndpoint");
        return -1;
    }
    field_context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (field_context == NULL) {
        LOGE("Can't find UsbEndpoint.mNativeContext");
        return -1;
    }
    field_address = env->GetFieldID(clazz, "mAddress", "I");
    if (field_address == NULL) {
        LOGE("Can't find UsbEndpoint.mAddress");
        return -1;
    }
    field_attributes = env->GetFieldID(clazz, "mAttributes", "I");
    if (field_attributes == NULL) {
        LOGE("Can't find UsbEndpoint.mAttributes");
        return -1;
    }
    field_max_packet_size = env->GetFieldID(clazz, "mMaxPacketSize", "I");
    if (field_max_packet_size == NULL) {
        LOGE("Can't find UsbEndpoint.mMaxPacketSize");
        return -1;
    }
    field_interval = env->GetFieldID(clazz, "mInterval", "I");
    if (field_interval == NULL) {
        LOGE("Can't find UsbEndpoint.mInterval");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env, "android/hardware/UsbEndpoint",
            method_table, NELEM(method_table));
}

