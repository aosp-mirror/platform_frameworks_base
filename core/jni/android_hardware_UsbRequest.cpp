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

#define LOG_TAG "UsbRequestJNI"

#include "utils/Log.h"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

#include <usbhost/usbhost.h>

#include <stdio.h>

using namespace android;

static jfieldID field_context;

struct usb_request* get_request_from_object(JNIEnv* env, jobject java_request)
{
    return (struct usb_request*)env->GetIntField(java_request, field_context);
}

// in android_hardware_UsbDeviceConnection.cpp
extern struct usb_device* get_device_from_object(JNIEnv* env, jobject connection);

static jboolean
android_hardware_UsbRequest_init(JNIEnv *env, jobject thiz, jobject java_device,
        jint ep_address, jint ep_attributes, jint ep_max_packet_size, jint ep_interval)
{
    ALOGD("init\n");

    struct usb_device* device = get_device_from_object(env, java_device);
    if (!device) {
        ALOGE("device null in native_init");
        return false;
    }

    // construct an endpoint descriptor from the Java object fields
    struct usb_endpoint_descriptor desc;
    desc.bLength = USB_DT_ENDPOINT_SIZE;
    desc.bDescriptorType = USB_DT_ENDPOINT;
    desc.bEndpointAddress = ep_address;
    desc.bmAttributes = ep_attributes;
    desc.wMaxPacketSize = ep_max_packet_size;
    desc.bInterval = ep_interval;

    struct usb_request* request = usb_request_new(device, &desc);
    if (request)
        env->SetIntField(thiz, field_context, (int)request);
    return (request != NULL);
}

static void
android_hardware_UsbRequest_close(JNIEnv *env, jobject thiz)
{
    ALOGD("close\n");
    struct usb_request* request = get_request_from_object(env, thiz);
    if (request) {
        usb_request_free(request);
        env->SetIntField(thiz, field_context, 0);
    }
}

static jboolean
android_hardware_UsbRequest_queue_array(JNIEnv *env, jobject thiz,
        jbyteArray buffer, jint length, jboolean out)
{
    struct usb_request* request = get_request_from_object(env, thiz);
    if (!request) {
        ALOGE("request is closed in native_queue");
        return false;
    }

    if (buffer && length) {
        request->buffer = malloc(length);
        if (!request->buffer)
            return false;
        memset(request->buffer, 0, length);
        if (out) {
            // copy data from Java buffer to native buffer
            env->GetByteArrayRegion(buffer, 0, length, (jbyte *)request->buffer);
        }
    } else {
        request->buffer = NULL;
    }
    request->buffer_length = length;

    if (usb_request_queue(request)) {
        if (request->buffer) {
            // free our buffer if usb_request_queue fails
            free(request->buffer);
            request->buffer = NULL;
        }
        return false;
    } else {
        // save a reference to ourselves so UsbDeviceConnection.waitRequest() can find us
        request->client_data = (void *)env->NewGlobalRef(thiz);
        return true;
    }
}

static int
android_hardware_UsbRequest_dequeue_array(JNIEnv *env, jobject thiz,
        jbyteArray buffer, jint length, jboolean out)
{
    struct usb_request* request = get_request_from_object(env, thiz);
    if (!request) {
        ALOGE("request is closed in native_dequeue");
        return -1;
    }

    if (buffer && length && request->buffer && !out) {
        // copy data from native buffer to Java buffer
        env->SetByteArrayRegion(buffer, 0, length, (jbyte *)request->buffer);
    }
    free(request->buffer);
    env->DeleteGlobalRef((jobject)request->client_data);
    return request->actual_length;
}

static jboolean
android_hardware_UsbRequest_queue_direct(JNIEnv *env, jobject thiz,
        jobject buffer, jint length, jboolean out)
{
    struct usb_request* request = get_request_from_object(env, thiz);
    if (!request) {
        ALOGE("request is closed in native_queue");
        return false;
    }

    if (buffer && length) {
        request->buffer = env->GetDirectBufferAddress(buffer);
        if (!request->buffer)
            return false;
    } else {
        request->buffer = NULL;
    }
    request->buffer_length = length;

    if (usb_request_queue(request)) {
        request->buffer = NULL;
        return false;
    } else {
        // save a reference to ourselves so UsbDeviceConnection.waitRequest() can find us
        // we also need this to make sure our native buffer is not deallocated
        // while IO is active
        request->client_data = (void *)env->NewGlobalRef(thiz);
        return true;
    }
}

static int
android_hardware_UsbRequest_dequeue_direct(JNIEnv *env, jobject thiz)
{
    struct usb_request* request = get_request_from_object(env, thiz);
    if (!request) {
        ALOGE("request is closed in native_dequeue");
        return -1;
    }
    // all we need to do is delete our global ref
    env->DeleteGlobalRef((jobject)request->client_data);
    return request->actual_length;
}

static jboolean
android_hardware_UsbRequest_cancel(JNIEnv *env, jobject thiz)
{
    struct usb_request* request = get_request_from_object(env, thiz);
    if (!request) {
        ALOGE("request is closed in native_cancel");
        return false;
    }
    return (usb_request_cancel(request) == 0);
}

static JNINativeMethod method_table[] = {
    {"native_init",             "(Landroid/hardware/usb/UsbDeviceConnection;IIII)Z",
                                            (void *)android_hardware_UsbRequest_init},
    {"native_close",            "()V",      (void *)android_hardware_UsbRequest_close},
    {"native_queue_array",      "([BIZ)Z",  (void *)android_hardware_UsbRequest_queue_array},
    {"native_dequeue_array",    "([BIZ)I",  (void *)android_hardware_UsbRequest_dequeue_array},
    {"native_queue_direct",     "(Ljava/nio/ByteBuffer;IZ)Z",
                                            (void *)android_hardware_UsbRequest_queue_direct},
    {"native_dequeue_direct",   "()I",      (void *)android_hardware_UsbRequest_dequeue_direct},
    {"native_cancel",           "()Z",      (void *)android_hardware_UsbRequest_cancel},
};

int register_android_hardware_UsbRequest(JNIEnv *env)
{
    jclass clazz = env->FindClass("android/hardware/usb/UsbRequest");
    if (clazz == NULL) {
        ALOGE("Can't find android/hardware/usb/UsbRequest");
        return -1;
    }
    field_context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (field_context == NULL) {
        ALOGE("Can't find UsbRequest.mNativeContext");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env, "android/hardware/usb/UsbRequest",
            method_table, NELEM(method_table));
}

