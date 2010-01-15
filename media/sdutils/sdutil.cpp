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
#include <binder/BpBinder.h>
#include <binder/IServiceManager.h>

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
    gMountService->mountVolume(string);
    
    for (int i = 0; i < 60; i++) {
        if (isMounted(path)) {
            return 0;
        }
        millisecondSleep(500);
    }
        
    fprintf(stderr, "failed to mount %s\n", path);   
    return -1;
}

static int asec_create(const char *id, int sizeMb, const char *fstype,
                       const char *key, int ownerUid) {
    String16 sId(id);
    String16 sFstype(fstype);
    String16 sKey(key);

    String16 r = gMountService->createSecureContainer(sId, sizeMb, sFstype,
                                                      sKey, ownerUid);
    return 0;
}

static int asec_finalize(const char *id) {
    String16 sId(id);
    gMountService->finalizeSecureContainer(sId);
    return 0;
}

static int asec_destroy(const char *id) {
    String16 sId(id);
    gMountService->destroySecureContainer(sId);
    return 0;
}

static int asec_mount(const char *id, const char *key, int ownerUid) {
    String16 sId(id);
    String16 sKey(key);
    gMountService->mountSecureContainer(sId, sKey, ownerUid);
    return 0;
}

static int asec_path(const char *id) {
    String16 sId(id);
    gMountService->getSecureContainerPath(sId);
    return 0;
}

static int unmount(const char* path) {
    String16 string(path);
    gMountService->unmountVolume(string);

    for (int i = 0; i < 20; i++) {
        if (!isMounted(path)) {
            return 0;
        }
        millisecondSleep(500);
    }
        
    fprintf(stderr, "failed to unmount %s\n", path);   
    return -1;
}

static int format(const char* path) {
    String16 string(path);

    if (isMounted(path))
        return -EBUSY;
    gMountService->formatVolume(string);

    return 0;
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
    } else if (strcmp(command, "format") == 0) {
        android::init();
        return android::format(argument);
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
    } else if (!strcmp(command, "asec")) {
        const char* id = (argc > 3 ? argv[3] : NULL);

        if (!id)
            goto usage;

        android::init();
        if (!strcmp(argument, "create")) {

            if (argc != 8)
                goto usage;
            return android::asec_create(id, atoi(argv[4]), argv[5], argv[6],
                                        atoi(argv[7]));
        } else if (!strcmp(argument, "finalize")) {
            return android::asec_finalize(id);
        } else if (!strcmp(argument, "destroy")) {
            return android::asec_destroy(id);
        } else if (!strcmp(argument, "mount")) {
            return android::asec_mount(id, argv[4], atoi(argv[5]));
        } else if (!strcmp(argument, "path")) {
            return android::asec_path(id);
        }
    }
    
usage:
    fprintf(stderr, "usage:\n"
                    "    sdutil mount <mount path>          - mounts the SD card at the given mount point\n"
                    "    sdutil unmount <mount path>        - unmounts the SD card at the given mount point\n"
                    "    sdutil format <mount path>         - formats the SD card at the given mount point\n"
                    "    sdutil ums enable                  - enables USB mass storage\n"
                    "    sdutil ums disable                 - disables USB mass storage\n"
                    "    sdutil asec create <id> <sizeMb> <fstype> <key> <ownerUid>\n"
                    "    sdutil asec finalize <id>\n"
                    "    sdutil asec destroy <id>\n"
                    "    sdutil asec mount <id> <key> <ownerUid>\n"
                    "    sdutil asec path <id>\n"
                    );
    return -1;
}
