/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <signal.h>
#include <pthread.h>
#include <errno.h>
#include <string.h>

#include <usbhost/usbhost.h>
#include "f_accessory.h"

#include "accessory.h"

static struct usb_device *current_device = NULL;
static uint8_t read_ep;
static uint8_t write_ep;

static pthread_mutex_t device_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t device_cond = PTHREAD_COND_INITIALIZER;

static void milli_sleep(int millis) {
    struct timespec tm;

    tm.tv_sec = 0;
    tm.tv_nsec = millis * 1000000;
    nanosleep(&tm, NULL);
}

static void* read_thread(void* arg) {
    int ret = 0;

    while (current_device && ret >= 0) {
        char    buffer[16384];

        ret = usb_device_bulk_transfer(current_device, read_ep, buffer, sizeof(buffer), 1000);
        if (ret < 0 && errno == ETIMEDOUT)
            ret = 0;
        if (ret > 0) {
            fwrite(buffer, 1, ret, stdout);
            fprintf(stderr, "\n");
            fflush(stdout);
        }
    }

    return NULL;
}

static void* write_thread(void* arg) {
    int ret = 0;

    while (ret >= 0) {
        char    buffer[16384];
        char *line = fgets(buffer, sizeof(buffer), stdin);
        if (!line || !current_device)
            break;
        ret = usb_device_bulk_transfer(current_device, write_ep, line, strlen(line), 1000);
    }

    return NULL;
}

static void send_string(struct usb_device *device, int index, const char* string) {
    usb_device_control_transfer(device, USB_DIR_OUT | USB_TYPE_VENDOR,
            ACCESSORY_SEND_STRING, 0, index, (void *)string, strlen(string) + 1, 0);

    // some devices can't handle back-to-back requests, so delay a bit
    milli_sleep(10);
}

static int usb_device_added(const char *devname, void* client_data) {
    uint16_t vendorId, productId;
    int ret;
    int enable_accessory = (int)client_data;

    struct usb_device *device = usb_device_open(devname);
    if (!device) {
        fprintf(stderr, "usb_device_open failed\n");
        return 0;
    }

    vendorId = usb_device_get_vendor_id(device);
    productId = usb_device_get_product_id(device);

    if (!current_device && vendorId == 0x18D1 && productId >= 0x2D00 && productId <= 0x2D05) {

        pthread_mutex_lock(&device_mutex);
        fprintf(stderr, "Found android device in accessory mode\n");
        current_device = device;
        pthread_cond_broadcast(&device_cond);
        pthread_mutex_unlock(&device_mutex);

        if (enable_accessory) {
            struct usb_descriptor_header* desc;
            struct usb_descriptor_iter iter;
            struct usb_interface_descriptor *intf = NULL;
            struct usb_endpoint_descriptor *ep1 = NULL;
            struct usb_endpoint_descriptor *ep2 = NULL;
            pthread_t th;

            usb_descriptor_iter_init(device, &iter);
            while ((desc = usb_descriptor_iter_next(&iter)) != NULL && (!intf || !ep1 || !ep2)) {
                if (desc->bDescriptorType == USB_DT_INTERFACE) {
                    intf = (struct usb_interface_descriptor *)desc;
                } else if (desc->bDescriptorType == USB_DT_ENDPOINT) {
                    if (ep1)
                        ep2 = (struct usb_endpoint_descriptor *)desc;
                    else
                        ep1 = (struct usb_endpoint_descriptor *)desc;
                }
            }

            if (!intf) {
                fprintf(stderr, "interface not found\n");
                exit(1);
            }
            if (!ep1 || !ep2) {
                fprintf(stderr, "endpoints not found\n");
                exit(1);
            }

            if (usb_device_claim_interface(device, intf->bInterfaceNumber)) {
                fprintf(stderr, "usb_device_claim_interface failed errno: %d\n", errno);
                exit(1);
            }

            if ((ep1->bEndpointAddress & USB_ENDPOINT_DIR_MASK) == USB_DIR_IN) {
                read_ep = ep1->bEndpointAddress;
                write_ep = ep2->bEndpointAddress;
            } else {
                read_ep = ep2->bEndpointAddress;
                write_ep = ep1->bEndpointAddress;
            }

            pthread_create(&th, NULL, read_thread, NULL);
            pthread_create(&th, NULL, write_thread, NULL);
        }
    } else {
//        fprintf(stderr, "Found new device - attempting to switch to accessory mode\n");

        uint16_t protocol = -1;
        ret = usb_device_control_transfer(device, USB_DIR_IN | USB_TYPE_VENDOR,
                ACCESSORY_GET_PROTOCOL, 0, 0, &protocol, sizeof(protocol), 1000);
        if (ret < 0) {
 //           fprintf(stderr, "ACCESSORY_GET_PROTOCOL returned %d errno: %d\n", ret, errno);
        } else {
            fprintf(stderr, "device supports protocol version %d\n", protocol);
            if (protocol >= 2) {
                if (enable_accessory) {
                    send_string(device, ACCESSORY_STRING_MANUFACTURER, "Google, Inc.");
                    send_string(device, ACCESSORY_STRING_MODEL, "AccessoryChat");
                    send_string(device, ACCESSORY_STRING_DESCRIPTION, "Accessory Chat");
                    send_string(device, ACCESSORY_STRING_VERSION, "1.0");
                    send_string(device, ACCESSORY_STRING_URI, "http://www.android.com");
                    send_string(device, ACCESSORY_STRING_SERIAL, "1234567890");
                }

                fprintf(stderr, "sending ACCESSORY_SET_AUDIO_MODE\n");
                ret = usb_device_control_transfer(device, USB_DIR_OUT | USB_TYPE_VENDOR,
                        ACCESSORY_SET_AUDIO_MODE, 1, 0, NULL, 0, 1000);
                if (ret < 0)
                    fprintf(stderr, "ACCESSORY_SET_AUDIO_MODE returned %d errno: %d\n", ret, errno);

                fprintf(stderr, "sending ACCESSORY_START\n");
                ret = usb_device_control_transfer(device, USB_DIR_OUT | USB_TYPE_VENDOR,
                        ACCESSORY_START, 0, 0, NULL, 0, 1000);
                fprintf(stderr, "did ACCESSORY_START\n");
                if (ret < 0)
                    fprintf(stderr, "ACCESSORY_START returned %d errno: %d\n", ret, errno);
            }
        }

        return 0;
    }

    if (device != current_device)
        usb_device_close(device);

    return 0;
}

static int usb_device_removed(const char *devname, void* client_data) {
    pthread_mutex_lock(&device_mutex);

    if (current_device && !strcmp(usb_device_get_name(current_device), devname)) {
        fprintf(stderr, "current device disconnected\n");
        usb_device_close(current_device);
        current_device = NULL;
    }

    pthread_mutex_unlock(&device_mutex);
    return 0;
}

struct usb_device* usb_wait_for_device() {
    struct usb_device* device = NULL;

    pthread_mutex_lock(&device_mutex);
    while (!current_device)
         pthread_cond_wait(&device_cond, &device_mutex);
    device = current_device;
    pthread_mutex_unlock(&device_mutex);

    return device;
}

void usb_run(uintptr_t enable_accessory) {
    struct usb_host_context* context = usb_host_init();

    usb_host_run(context, usb_device_added, usb_device_removed, NULL, (void *)enable_accessory);
}

