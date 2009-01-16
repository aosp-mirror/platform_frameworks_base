/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include <hardware_legacy/IMountService.h>
#include <utils/BpBinder.h>
#include <utils/IServiceManager.h>

#include <stdio.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

namespace android {

static sp<IMountService> gMountService;

static void init() {
    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> binder = sm->getService(String16("mount"));
    gMountService = interface_cast<IMountService>(binder);
    if (gMountService == 0) {
        fprintf(stderr, "could not get MountService\n");
        exit(1);
    }
}

static bool isMounted(const char* mountPoint) {
    char s[2000];
    FILE *f = fopen("/proc/mounts", "r");
    bool mounted = false;

    while (fgets(s, sizeof(s), f))
    {
        char *c, *path = NULL;

        for (c = s; *c; c++) 
        {
            if (*c == ' ') 
            {
                *c = 0;
                path = c + 1;
                break;
            }
        }

        for (c = path; *c; c++) 
        {
            if (*c == ' ') 
            {
                *c = '\0';
                break;
            }
        }

        if (strcmp(mountPoint, path) == 0) {
            mounted = true;
            break;
        }
    }

    fclose(f);
    return mounted;
}

static void millisecondSleep(int milliseconds) {
	struct timespec reqt, remt;	
	reqt.tv_sec = milliseconds / 1000;
	reqt.tv_nsec = 1000000 * (milliseconds % 1000);
	nanosleep(&reqt, &remt) ;

}

static int mount(const char* path) {
    String16 string(path);
    gMountService->mountMedia(string);
    
    for (int i = 0; i < 10; i++) {
        if (isMounted(path)) {
            return 0;
        }
        millisecondSleep(500);
    }
        
    fprintf(stderr, "failed to mount %s\n", path);   
    return -1;
}

static int unmount(const char* path) {
    String16 string(path);
    gMountService->unmountMedia(string);

    for (int i = 0; i < 10; i++) {
        if (!isMounted(path)) {
            return 0;
        }
        millisecondSleep(500);
    }
        
    fprintf(stderr, "failed to unmount %s\n", path);   
    return -1;
}

static int umsEnable(bool enable) {
    gMountService->setMassStorageEnabled(enable);
    return 0;
}

};

int main(int argc, char **argv)
{
    const char* command = (argc > 1 ? argv[1] : "");
    const char* argument = (argc > 2 ? argv[2] : "");
    
    if (strcmp(command, "mount") == 0) {
        android::init();
        return android::mount(argument);
    } else if (strcmp(command, "unmount") == 0) {
        android::init();
        return android::unmount(argument);
    } else if (strcmp(command, "ums") == 0) {
        if (strcmp(argument, "enable") == 0) {
            android::init();
            return android::umsEnable(true);
        } else if (strcmp(argument, "disable") == 0) {
            android::init();
            return android::umsEnable(false);
        }
    }
    
    fprintf(stderr, "usage:\n"
                    "    sdutil mount <mount path>          - mounts the SD card at the given mount point\n"
                    "    sdutil unmount <mount path>        - unmounts the SD card at the given mount point\n"
                    "    sdutil ums enable                  - enables USB mass storage\n"
                    "    sdutil ums disable                 - disnables USB mass storage\n"
                    );
    return -1;
}
