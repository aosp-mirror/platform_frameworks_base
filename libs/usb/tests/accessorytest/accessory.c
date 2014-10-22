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
#include <string.h>

#include "accessory.h"

static void usage(char* name) {
    fprintf(stderr, "Usage: %s [-a] [-h] [-ic input card] [-id input device] "
                    "[-oc output card] [-d output device] [-a] [-h] [-i]\n\n"
                    "\t-ic, -id, -oc and -od specify ALSA card and device numbers\n"
                    "\t-a : enables AccessoryChat mode\n"
                    "\t-i : enables HID pass through (requires running as root\n"
                    "\t-h : prints this usage message\n", name);
}

int main(int argc, char* argv[])
{
    unsigned int input_card = 2;
    unsigned int input_device = 0;
    unsigned int output_card = 0;
    unsigned int output_device = 0;
    unsigned int enable_accessory = 0;
    unsigned int enable_hid = 0;

    /* parse command line arguments */
    argv += 1;
    while (*argv) {
        if (strcmp(*argv, "-ic") == 0) {
            argv++;
            if (*argv)
                input_card = atoi(*argv);
        } else if (strcmp(*argv, "-id") == 0) {
            argv++;
            if (*argv)
                input_device = atoi(*argv);
        } else if (strcmp(*argv, "-oc") == 0) {
            argv++;
            if (*argv)
                output_card = atoi(*argv);
        } else if (strcmp(*argv, "-od") == 0) {
            argv++;
            if (*argv)
                output_device = atoi(*argv);
        } else if (strcmp(*argv, "-a") == 0) {
            enable_accessory = 1;
        } else if (strcmp(*argv, "-h") == 0) {
            usage(argv[0]);
            return 1;
        } else if (strcmp(*argv, "-i") == 0) {
           enable_hid = 1;
        }
        if (*argv)
            argv++;
    }

    init_audio(input_card, input_device, output_card, output_device);
    if (enable_hid)
        init_hid();
    usb_run(enable_accessory);

    return 0;
}
