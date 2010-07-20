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

#define LOG_TAG "MtpClient"

#include "MtpDebug.h"
#include "MtpClient.h"
#include "MtpDevice.h"

#include <stdio.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>

#include <usbhost/usbhost.h>
#include <linux/version.h>
#if LINUX_VERSION_CODE > KERNEL_VERSION(2, 6, 20)
#include <linux/usb/ch9.h>
#else
#include <linux/usb_ch9.h>
#endif

namespace android {

class MtpClientThread : public Thread {
private:
    MtpClient*   mClient;

public:
    MtpClientThread(MtpClient* client)
        : mClient(client)
    {
    }

    virtual bool threadLoop() {
        return mClient->threadLoop();
    }
};


MtpClient::MtpClient()
    :   mThread(NULL),
        mUsbHostContext(NULL),
        mDone(false)
{
}

MtpClient::~MtpClient() {
    usb_host_cleanup(mUsbHostContext);
}

bool MtpClient::start() {
    Mutex::Autolock autoLock(mMutex);

    if (mThread)
        return true;

    mUsbHostContext = usb_host_init();
    if (!mUsbHostContext)
        return false;

    mThread = new MtpClientThread(this);
    mThread->run("MtpClientThread");
    // wait for the thread to do initial device discovery before returning
    mThreadStartCondition.wait(mMutex);

    return true;
}

void MtpClient::stop() {
    mDone = true;
}

MtpDevice* MtpClient::getDevice(int id) {
    for (int i = 0; i < mDeviceList.size(); i++) {
        MtpDevice* device = mDeviceList[i];
        if (device->getID() == id)
            return device;
    }
    return NULL;
}

bool MtpClient::usbDeviceAdded(const char *devname) {
    struct usb_descriptor_header* desc;
    struct usb_descriptor_iter iter;

    struct usb_device *device = usb_device_open(devname);
    if (!device) {
        LOGE("usb_device_open failed\n");
        return mDone;
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
                        return mDone;
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
                    return mDone;
                }

                struct usb_endpoint *ep_in = usb_endpoint_open(device, ep_in_desc);
                struct usb_endpoint *ep_out = usb_endpoint_open(device, ep_out_desc);
                struct usb_endpoint *ep_intr = usb_endpoint_open(device, ep_intr_desc);

                if (usb_device_claim_interface(device, interface->bInterfaceNumber)) {
                    LOGE("usb_device_claim_interface failed\n");
                    usb_endpoint_close(ep_in);
                    usb_endpoint_close(ep_out);
                    usb_endpoint_close(ep_intr);
                    return mDone;
                }

                MtpDevice* mtpDevice = new MtpDevice(device, interface->bInterfaceNumber,
                            ep_in, ep_out, ep_intr);
                mDeviceList.add(mtpDevice);
                mtpDevice->initialize();
                deviceAdded(mtpDevice);
                return mDone;
            }
        }
    }

    usb_device_close(device);
    return mDone;
}

bool MtpClient::usbDeviceRemoved(const char *devname) {
    for (int i = 0; i < mDeviceList.size(); i++) {
        MtpDevice* device = mDeviceList[i];
        if (!strcmp(devname, device->getDeviceName())) {
            deviceRemoved(device);
            mDeviceList.removeAt(i);
            delete device;
            LOGD("Camera removed!\n");
            break;
        }
    }
    return mDone;
}

bool MtpClient::usbDiscoveryDone() {
    Mutex::Autolock autoLock(mMutex);
    mThreadStartCondition.signal();
    return mDone;
}

bool MtpClient::threadLoop() {
    usb_host_run(mUsbHostContext, usb_device_added, usb_device_removed, usb_discovery_done, this);
    return false;
}

int MtpClient::usb_device_added(const char *devname, void* client_data) {
    LOGD("usb_device_added %s\n", devname);
    return ((MtpClient *)client_data)->usbDeviceAdded(devname);
}

int MtpClient::usb_device_removed(const char *devname, void* client_data) {
    LOGD("usb_device_removed %s\n", devname);
    return ((MtpClient *)client_data)->usbDeviceRemoved(devname);
}

int MtpClient::usb_discovery_done(void* client_data) {
    LOGD("usb_discovery_done\n");
    return ((MtpClient *)client_data)->usbDiscoveryDone();
}

}  // namespace android
