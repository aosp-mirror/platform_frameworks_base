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

static int mount(const char* path) {
    String16 string(path);
    return gMountService->mountVolume(string);
}

static int share(const char *path, const char *method) {
    String16 sPath(path);
    String16 sMethod(method);
    return gMountService->shareVolume(sPath, sMethod);
}

static int unshare(const char *path, const char *method) {
    String16 sPath(path);
    String16 sMethod(method);
    return gMountService->unshareVolume(sPath, sMethod);
}

static bool shared(const char *path, const char *method) {
    String16 sPath(path);
    String16 sMethod(method);
    return gMountService->getVolumeShared(sPath, sMethod);
}

static int asec_create(const char *id, int sizeMb, const char *fstype,
                       const char *key, int ownerUid) {
    String16 sId(id);
    String16 sFstype(fstype);
    String16 sKey(key);

    return gMountService->createSecureContainer(
            sId, sizeMb, sFstype, sKey, ownerUid);
}

static int asec_finalize(const char *id) {
    String16 sId(id);
    return gMountService->finalizeSecureContainer(sId);
}

static int asec_destroy(const char *id) {
    String16 sId(id);
    return gMountService->destroySecureContainer(sId);
}

static int asec_mount(const char *id, const char *key, int ownerUid) {
    String16 sId(id);
    String16 sKey(key);
    return gMountService->mountSecureContainer(sId, sKey, ownerUid);
}

static int asec_unmount(const char *id) {
    String16 sId(id);
    return gMountService->unmountSecureContainer(sId);
}

static int asec_rename(const char *oldId, const char *newId) {
    String16 sOldId(oldId);
    String16 sNewId(newId);
    return gMountService->renameSecureContainer(sOldId, sNewId);
}

static int asec_path(const char *id) {
    String16 sId(id);
    gMountService->getSecureContainerPath(sId);
    return 0;
}

static int unmount(const char* path) {
    String16 string(path);
    return gMountService->unmountVolume(string);
}

static int format(const char* path) {
    String16 string(path);
    return gMountService->formatVolume(string);
}

};

static void usage(void);

int main(int argc, char **argv)
{
    if (argc < 2)
        usage();

    android::init();
    int rc = 0;
    
    if (strcmp(argv[1], "mount") == 0) {
        rc = android::mount(argv[2]);
    } else if (strcmp(argv[1], "format") == 0) {
        rc = android::format(argv[2]);
    } else if (strcmp(argv[1], "unmount") == 0) {
        rc = android::unmount(argv[2]);
    } else if (strcmp(argv[1], "share") == 0) {
        if (argc != 3)
            usage();
        rc = android::share(argv[2], argv[3]);
    } else if (strcmp(argv[1], "unshare") == 0) {
        if (argc != 3)
            usage();
        rc = android::unshare(argv[2], argv[3]);
    } else if (strcmp(argv[1], "shared") == 0) {
        if (argc != 3)
            usage();
        fprintf(stdout, "%s\n", (android::shared(argv[2], argv[3]) ? "true" : "false"));
    } else if (!strcmp(argv[1], "asec")) {
        if (argc < 3)
            usage();

        if (!strcmp(argv[2], "create")) {

            if (argc != 8)
                usage();
            rc = android::asec_create(argv[3], atoi(argv[4]), argv[5], argv[6], atoi(argv[7]));
        } else if (!strcmp(argv[3], "finalize")) {
            rc = android::asec_finalize(argv[3]);
        } else if (!strcmp(argv[3], "destroy")) {
            return android::asec_destroy(argv[3]);
        } else if (!strcmp(argv[3], "mount")) {
            if (argc != 6)
                usage();
            rc = android::asec_mount(argv[3], argv[4], atoi(argv[5]));
        } else if (!strcmp(argv[3], "rename")) {
            if (argc != 5)
                usage();
            rc = android::asec_rename(argv[3], argv[4]);
        } else if (!strcmp(argv[3], "unmount")) {
            rc = android::asec_unmount(argv[3]);
        } else if (!strcmp(argv[3], "path")) {
            rc = android::asec_path(argv[3]);
        }
    }

    fprintf(stdout, "Operation completed with code %d\n", rc);
    return rc;
}

static void usage()
{
    fprintf(stderr, "usage:\n"
                    "    sdutil mount <mount path>          - mounts the SD card at the given mount point\n"
                    "    sdutil unmount <mount path>        - unmounts the SD card at the given mount point\n"
                    "    sdutil format <mount path>         - formats the SD card at the given mount point\n"
                    "    sdutil share <path> <method>       - shares a volume\n"
                    "    sdutil unshare <path> <method>     - unshares a volume\n"
                    "    sdutil shared <path> <method>      - Queries volume share state\n"
                    "    sdutil asec create <id> <sizeMb> <fstype> <key> <ownerUid>\n"
                    "    sdutil asec finalize <id>\n"
                    "    sdutil asec destroy <id>\n"
                    "    sdutil asec mount <id> <key> <ownerUid>\n"
                    "    sdutil asec unmount <id>\n"
                    "    sdutil asec rename <oldId, newId>\n"
                    "    sdutil asec path <id>\n"
                    );
    exit(1);
}
