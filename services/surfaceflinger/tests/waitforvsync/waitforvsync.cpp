/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <stdint.h>
#include <sys/types.h>

#include <fcntl.h>
#include <sys/ioctl.h>
#include <linux/fb.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>

#ifndef FBIO_WAITFORVSYNC
#define FBIO_WAITFORVSYNC   _IOW('F', 0x20, __u32)
#endif

int main(int argc, char** argv) {
    int fd = open("/dev/graphics/fb0", O_RDWR);
    if (fd >= 0) {
        do {
            uint32_t crt = 0;
           int err = ioctl(fd, FBIO_WAITFORVSYNC, &crt);
           if (err < 0) {
               printf("FBIO_WAITFORVSYNC error: %s\n", strerror(errno));
               break;
           }
        } while(1);
        close(fd);
    }
    return 0;
}
