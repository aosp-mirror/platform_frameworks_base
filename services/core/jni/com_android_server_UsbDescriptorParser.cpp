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

#include "jni.h"
#include "JNIHelp.h"

#include <usbhost/usbhost.h>

#define MAX_DESCRIPTORS_LENGTH 16384

// com.android.server.usb.descriptors
extern "C" {
jbyteArray JNICALL Java_com_android_server_usb_descriptors_UsbDescriptorParser_getRawDescriptors(
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
        return NULL;
    }

    // from android_hardware_UsbDeviceConnection_get_desc()
    jbyte buffer[MAX_DESCRIPTORS_LENGTH];
    lseek(fd, 0, SEEK_SET);
    int numBytes = read(fd, buffer, sizeof(buffer));

    usb_device_close(device);

    jbyteArray ret = NULL;
    if (numBytes != 0) {
        ret = env->NewByteArray(numBytes);
        env->SetByteArrayRegion(ret, 0, numBytes, buffer);
    }
    return ret;
}

} // extern "C"


