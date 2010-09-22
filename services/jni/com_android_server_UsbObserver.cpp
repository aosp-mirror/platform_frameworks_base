/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "UsbObserver"
#include "utils/Log.h"

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include "utils/Vector.h"

#include <usbhost/usbhost.h>
#include <linux/version.h>
#if LINUX_VERSION_CODE > KERNEL_VERSION(2, 6, 20)
#include <linux/usb/ch9.h>
#else
#include <linux/usb_ch9.h>
#endif

#include <stdio.h>

namespace android
{

static jmethodID method_usbCameraAdded;
static jmethodID method_usbCameraRemoved;

Vector<int> mDeviceList;

static void checkAndClearExceptionFromCallback(JNIEnv* env, const char* methodName) {
    if (env->ExceptionCheck()) {
        LOGE("An exception was thrown by callback '%s'.", methodName);
        LOGE_EX(env);
        env->ExceptionClear();
    }
}

static int usb_device_added(const char *devname, void* client_data) {
    // check to see if it is a camera
    struct usb_descriptor_header* desc;
    struct usb_descriptor_iter iter;

    struct usb_device *device = usb_device_open(devname);
    if (!device) {
        LOGE("usb_device_open failed\n");
        return 0;
    }

    usb_descriptor_iter_init(device, &iter);

    while ((desc = usb_descriptor_iter_next(&iter)) != NULL) {
        if (desc->bDescriptorType == USB_DT_INTERFACE) {
            struct usb_interface_descriptor *interface = (struct usb_interface_descriptor *)desc;

            if (interface->bInterfaceClass == USB_CLASS_STILL_IMAGE &&
                interface->bInterfaceSubClass == 1 && // Still Image Capture
                interface->bInterfaceProtocol == 1)     // Picture Transfer Protocol (PIMA 15470)
            {
                LOGD("Found camera: \"%s\" \"%s\"\n", usb_device_get_manufacturer_name(device),
                        usb_device_get_product_name(device));

                // interface should be followed by three endpoints
                struct usb_endpoint_descriptor *ep;
                struct usb_endpoint_descriptor *ep_in_desc = NULL;
                struct usb_endpoint_descriptor *ep_out_desc = NULL;
                struct usb_endpoint_descriptor *ep_intr_desc = NULL;
                for (int i = 0; i < 3; i++) {
                    ep = (struct usb_endpoint_descriptor *)usb_descriptor_iter_next(&iter);
                    if (!ep || ep->bDescriptorType != USB_DT_ENDPOINT) {
                        LOGE("endpoints not found\n");
                        goto done;
                    }
                    if (ep->bmAttributes == USB_ENDPOINT_XFER_BULK) {
                        if (ep->bEndpointAddress & USB_ENDPOINT_DIR_MASK)
                            ep_in_desc = ep;
                        else
                            ep_out_desc = ep;
                    } else if (ep->bmAttributes == USB_ENDPOINT_XFER_INT &&
                        ep->bEndpointAddress & USB_ENDPOINT_DIR_MASK) {
                        ep_intr_desc = ep;
                    }
                }
                if (!ep_in_desc || !ep_out_desc || !ep_intr_desc) {
                    LOGE("endpoints not found\n");
                    goto done;
                }

                // if we got here, we found a camera
                JNIEnv* env = AndroidRuntime::getJNIEnv();
                jobject thiz = (jobject)client_data;

                int id = usb_device_get_unique_id_from_name(devname);
                mDeviceList.add(id);

                env->CallVoidMethod(thiz, method_usbCameraAdded, id);
                checkAndClearExceptionFromCallback(env, __FUNCTION__);
            }
        }
    }
done:
    usb_device_close(device);
    return 0;
}

static int usb_device_removed(const char *devname, void* client_data) {
    int id = usb_device_get_unique_id_from_name(devname);

    // see if it is a device we know about
    for (int i = 0; i < mDeviceList.size(); i++) {
        if (id  == mDeviceList[i]) {
            mDeviceList.removeAt(i);

            JNIEnv* env = AndroidRuntime::getJNIEnv();
            jobject thiz = (jobject)client_data;

            env->CallVoidMethod(thiz, method_usbCameraRemoved, id);
            checkAndClearExceptionFromCallback(env, __FUNCTION__);
            break;
        }
    }
    return 0;
}

static void android_server_UsbObserver_monitorUsbHostBus(JNIEnv *env, jobject thiz)
{
    struct usb_host_context* context = usb_host_init();
    if (!context) {
        LOGE("usb_host_init failed");
        return;
    }
    // this will never return so it is safe to pass thiz directly
    usb_host_run(context, usb_device_added, usb_device_removed, NULL, (void *)thiz);
}

static JNINativeMethod method_table[] = {
    { "monitorUsbHostBus", "()V", (void*)android_server_UsbObserver_monitorUsbHostBus }
};

int register_android_server_UsbObserver(JNIEnv *env)
{
    jclass clazz = env->FindClass("com/android/server/UsbObserver");
    if (clazz == NULL) {
        LOGE("Can't find com/android/server/UsbObserver");
        return -1;
    }
    method_usbCameraAdded = env->GetMethodID(clazz, "usbCameraAdded", "(I)V");
    if (method_usbCameraAdded == NULL) {
        LOGE("Can't find usbCameraAdded");
        return -1;
    }
    method_usbCameraRemoved = env->GetMethodID(clazz, "usbCameraRemoved", "(I)V");
    if (method_usbCameraRemoved == NULL) {
        LOGE("Can't find usbCameraRemoved");
        return -1;
    }

    return jniRegisterNativeMethods(env, "com/android/server/UsbObserver",
            method_table, NELEM(method_table));
}

};
