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

#include <unistd.h>
#include <stdio.h>
#include <string.h>

#include <usbhost/usbhost.h>
#include <linux/usb/ch9.h>

#include "MtpClient.h"
#include "MtpDeviceInfo.h"
#include "MtpObjectInfo.h"
#include "MtpStorageInfo.h"

using namespace android;

static struct usb_device *sCameraDevice = NULL;
static int sCameraInterface = 0;
static MtpClient *sClient = NULL;


static void start_session(struct usb_endpoint *ep_in, struct usb_endpoint *ep_out,
            struct usb_endpoint *ep_intr)
{
    if (sClient)
        delete sClient;
    sClient = new MtpClient(ep_in, ep_out, ep_intr);
    sClient->openSession();
    MtpDeviceInfo* info = sClient->getDeviceInfo();
    if (info) {
        info->print();
        delete info;
    }
    MtpStorageIDList* storageIDs = sClient->getStorageIDs();
    if (storageIDs) {
        for (int i = 0; i < storageIDs->size(); i++) {
            MtpStorageID storageID = (*storageIDs)[i];
            MtpStorageInfo* info = sClient->getStorageInfo(storageID);
            if (info) {
                info->print();
                delete info;
            }
            MtpObjectHandleList* objects = sClient->getObjectHandles(storageID, 0, MTP_PARENT_ROOT);
            if (objects) {
                for (int j = 0; j < objects->size(); j++) {
                    MtpObjectHandle handle = (*objects)[j];
                    MtpObjectInfo* info = sClient->getObjectInfo(handle);
                    if (info) {
                        info->print();
                        delete info;
                    }
                }
                delete objects;
            }
        }
    }
}

static void usb_device_added(const char *devname, void *client_data)
{
    struct usb_descriptor_header* desc;
    struct usb_descriptor_iter iter;

    struct usb_device *device = usb_device_open(devname);
    if (!device) return;

    usb_descriptor_iter_init(device, &iter);

    while ((desc = usb_descriptor_iter_next(&iter)) != NULL) {
        if (desc->bDescriptorType == USB_DT_INTERFACE) {
            struct usb_interface_descriptor *interface = (struct usb_interface_descriptor *)desc;

            if (interface->bInterfaceClass == USB_CLASS_STILL_IMAGE &&
                interface->bInterfaceSubClass == 1 && // Still Image Capture
                interface->bInterfaceProtocol == 1)     // Picture Transfer Protocol (PIMA 15470)
            {
                printf("Found camera: \"%s\" \"%s\"\n", usb_device_get_manufacturer_name(device),
                        usb_device_get_product_name(device));

                // interface should be followed by three endpoints
                struct usb_endpoint_descriptor *ep, *ep_in_desc = NULL, *ep_out_desc = NULL, *ep_intr_desc = NULL;
                for (int i = 0; i < 3; i++) {
                    ep = (struct usb_endpoint_descriptor *)usb_descriptor_iter_next(&iter);
                    if (!ep || ep->bDescriptorType != USB_DT_ENDPOINT) {
                        fprintf(stderr, "endpoints not found\n");
                        return;
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
                    fprintf(stderr, "endpoints not found\n");
                    return;
                }

                struct usb_endpoint *ep_in = usb_endpoint_open(device, ep_in_desc);
                struct usb_endpoint *ep_out = usb_endpoint_open(device, ep_out_desc);
                struct usb_endpoint *ep_intr = usb_endpoint_open(device, ep_intr_desc);

                if (usb_device_claim_interface(device, interface->bInterfaceNumber)) {
                    fprintf(stderr, "usb_device_claim_interface failed\n");
                    usb_endpoint_close(ep_in);
                    usb_endpoint_close(ep_out);
                    usb_endpoint_close(ep_intr);
                    return;
                }

                if (sCameraDevice) {
                    usb_device_release_interface(sCameraDevice, sCameraInterface);
                    usb_device_close(sCameraDevice);
                }
                sCameraDevice = device;
                start_session(ep_in, ep_out, ep_intr);
            }
        }
    }

    if (device != sCameraDevice)
        usb_device_close(device);
}

static void usb_device_removed(const char *devname, void *client_data)
{
    if (sCameraDevice && !strcmp(devname, usb_device_get_name(sCameraDevice))) {
        delete sClient;
        printf("Camera removed!\n");
        usb_device_release_interface(sCameraDevice, sCameraInterface);
        usb_device_close(sCameraDevice);
        sCameraDevice = NULL;
    }
}

int main(int argc, char* argv[])
{
    if (usb_host_init(usb_device_added, usb_device_removed, NULL)) {
        fprintf(stderr, "usb_host_init failed\n");
        return -1;
    }

    while (1) {
        sleep(1);
    }

    return 0;
}
