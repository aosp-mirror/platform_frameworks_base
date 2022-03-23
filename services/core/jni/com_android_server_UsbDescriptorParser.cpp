/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <stdlib.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>

#include <usbhost/usbhost.h>

#define MAX_DESCRIPTORS_LENGTH 4096
static const int USB_CONTROL_TRANSFER_TIMEOUT_MS = 200;

// com.android.server.usb.descriptors
extern "C" {
jbyteArray JNICALL Java_com_android_server_usb_descriptors_UsbDescriptorParser_getRawDescriptors_1native(
        JNIEnv* env, jobject thiz, jstring deviceAddr) {
    const char *deviceAddrStr = env->GetStringUTFChars(deviceAddr, NULL);
    struct usb_device* device = usb_device_open(deviceAddrStr);
    env->ReleaseStringUTFChars(deviceAddr, deviceAddrStr);

    if (!device) {
        ALOGE("usb_device_open failed");
        return NULL;
    }

    int fd = usb_device_get_fd(device);
    if (fd < 0) {
        usb_device_close(device);
        return NULL;
    }

    // from android_hardware_UsbDeviceConnection_get_desc()
    jbyte buffer[MAX_DESCRIPTORS_LENGTH];
    lseek(fd, 0, SEEK_SET);
    int numBytes = read(fd, buffer, sizeof(buffer));
    jbyteArray ret = NULL;
    usb_device_close(device);

    if (numBytes > 0) {
        ret = env->NewByteArray(numBytes);
        env->SetByteArrayRegion(ret, 0, numBytes, buffer);
    } else {
        ALOGE("error reading descriptors\n");
    }

    return ret;
}

jstring JNICALL Java_com_android_server_usb_descriptors_UsbDescriptorParser_getDescriptorString_1native(
        JNIEnv* env, jobject thiz, jstring deviceAddr, jint stringId) {

    const char *deviceAddrStr = env->GetStringUTFChars(deviceAddr, NULL);
    struct usb_device* device = usb_device_open(deviceAddrStr);
    env->ReleaseStringUTFChars(deviceAddr, deviceAddrStr);

    if (!device) {
        ALOGE("usb_device_open failed");
        return NULL;
    }

    int fd = usb_device_get_fd(device);
    if (fd < 0) {
        ALOGE("usb_device_get_fd failed");
        usb_device_close(device);
        return NULL;
    }

    // Get Raw UCS2 Bytes
    jbyte* byteBuffer = NULL;
    size_t numUSC2Bytes = 0;
    int retVal =
            usb_device_get_string_ucs2(device, stringId,
                                       USB_CONTROL_TRANSFER_TIMEOUT_MS,
                                       (void**)&byteBuffer, &numUSC2Bytes);

    jstring j_str = NULL;

    if (retVal == 0) {
        j_str = env->NewString((jchar*)byteBuffer, numUSC2Bytes/2);
        free(byteBuffer);
    }

    usb_device_close(device);

    return j_str;
}

} // extern "C"
