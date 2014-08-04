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

#include <unistd.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <time.h>

#include <sys/inotify.h>
#include <linux/hidraw.h>
#include <usbhost/usbhost.h>

#include "f_accessory.h"
#include "accessory.h"

static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;

static int next_id = 1;

static void milli_sleep(int millis) {
    struct timespec tm;

    tm.tv_sec = 0;
    tm.tv_nsec = millis * 1000000;
    nanosleep(&tm, NULL);
}

static void* hid_thread(void* arg) {
    int fd = (int)arg;
    char buffer[4096];
    int id, ret, offset;
    struct usb_device *device;
    struct usb_device_descriptor *device_desc;
    int max_packet;
    struct hidraw_report_descriptor desc;
    int desc_length;

    fprintf(stderr, "hid_thread start fd: %d\n", fd);

    if (ioctl(fd, HIDIOCGRDESCSIZE, &desc_length)) {
        fprintf(stderr, "HIDIOCGRDESCSIZE failed\n");
        close(fd);
        goto err;
    }

    desc.size = HID_MAX_DESCRIPTOR_SIZE - 1;
    if (ioctl(fd, HIDIOCGRDESC, &desc)) {
        fprintf(stderr, "HIDIOCGRDESC failed\n");
        close(fd);
        goto err;
    }

wait_for_device:
    fprintf(stderr, "waiting for device fd: %d\n", fd);
    device = usb_wait_for_device();
    max_packet = usb_device_get_device_descriptor(device)->bMaxPacketSize0;
    // FIXME
    max_packet--;

    // FIXME
    milli_sleep(500);

    pthread_mutex_lock(&mutex);
    id = next_id++;

    ret = usb_device_control_transfer(device, USB_DIR_OUT | USB_TYPE_VENDOR,
            ACCESSORY_REGISTER_HID, id, desc_length, NULL, 0, 1000);
    fprintf(stderr, "ACCESSORY_REGISTER_HID returned %d\n", ret);

    // FIXME
    milli_sleep(500);

    for (offset = 0; offset < desc_length; ) {
        int count = desc_length - offset;
        if (count > max_packet) count = max_packet;

    fprintf(stderr, "sending ACCESSORY_SET_HID_REPORT_DESC offset: %d count: %d desc_length: %d\n",
            offset, count, desc_length);
        ret = usb_device_control_transfer(device, USB_DIR_OUT | USB_TYPE_VENDOR,
                ACCESSORY_SET_HID_REPORT_DESC, id, offset, &desc.value[offset], count, 1000);
    fprintf(stderr, "ACCESSORY_SET_HID_REPORT_DESC returned %d errno %d\n", ret, errno);
        offset += count;
    }

    pthread_mutex_unlock(&mutex);

    while (1) {
        ret = read(fd, buffer, sizeof(buffer));
        if (ret < 0) {
fprintf(stderr, "read failed, errno: %d, fd: %d\n", errno, fd);
            break;
        }

        ret = usb_device_control_transfer(device, USB_DIR_OUT | USB_TYPE_VENDOR,
                    ACCESSORY_SEND_HID_EVENT, id, 0, buffer, ret, 1000);
        if (ret < 0 && errno != EPIPE) {
fprintf(stderr, "ACCESSORY_SEND_HID_EVENT returned %d errno: %d\n", ret, errno);
            goto wait_for_device;
        }
    }

fprintf(stderr, "ACCESSORY_UNREGISTER_HID\n");
    ret = usb_device_control_transfer(device, USB_DIR_OUT | USB_TYPE_VENDOR,
            ACCESSORY_UNREGISTER_HID, id, 0, NULL, 0, 1000);

fprintf(stderr, "hid thread exiting\n");
err:
    return NULL;
}

static void open_hid(const char* name)
{
    char path[100];

    snprintf(path, sizeof(path), "/dev/%s", name);
    int fd = open(path, O_RDWR);
    if (fd < 0) return;

    fprintf(stderr, "opened /dev/%s\n", name);
    pthread_t th;
    pthread_create(&th, NULL, hid_thread, (void *)fd);
}

static void* inotify_thread(void* arg)
{
    open_hid("hidraw0");
    open_hid("hidraw1");
    open_hid("hidraw2");
    open_hid("hidraw3");
    open_hid("hidraw4");
    open_hid("hidraw5");
    open_hid("hidraw6");
    open_hid("hidraw7");
    open_hid("hidraw8");
    open_hid("hidraw9");

    int inotify_fd = inotify_init();
    inotify_add_watch(inotify_fd, "/dev", IN_DELETE | IN_CREATE);

    while (1) {
        char event_buf[512];
        struct inotify_event *event;
        int event_pos = 0;
        int event_size;

        int count = read(inotify_fd, event_buf, sizeof(event_buf));
        if (count < (int)sizeof(*event)) {
            if(errno == EINTR)
                continue;
            fprintf(stderr, "could not get event, %s\n", strerror(errno));
            break;
        }
        while (count >= (int)sizeof(*event)) {
            event = (struct inotify_event *)(event_buf + event_pos);
            //fprintf(stderr, "%d: %08x \"%s\"\n", event->wd, event->mask,
            //        event->len ? event->name : "");
            if (event->len) {
                if(event->mask & IN_CREATE) {
                    fprintf(stderr, "created %s\n", event->name);
                    // FIXME
                    milli_sleep(50);
                    open_hid(event->name);
                } else {
                    fprintf(stderr, "lost %s\n", event->name);
                }
            }
            event_size = sizeof(*event) + event->len;
            count -= event_size;
            event_pos += event_size;
        }
    }

    close(inotify_fd);
    return NULL;
}

void init_hid()
{
    pthread_t th;
    pthread_create(&th, NULL, inotify_thread, NULL);
}
