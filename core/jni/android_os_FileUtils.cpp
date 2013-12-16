/* //device/libs/android_runtime/android_util_Process.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#define LOG_TAG "FileUtils"

#include <utils/Log.h>

#include <android_runtime/AndroidRuntime.h>

#include "JNIHelp.h"

#include <string.h>
#include <sys/errno.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <linux/msdos_fs.h>
#include <blkid/blkid.h>

namespace android {

jint android_os_FileUtils_getVolumeUUID(JNIEnv* env, jobject clazz, jstring path)
{
    char *uuid = NULL;

    if (path == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return -1;
    }

    const char *pathStr = env->GetStringUTFChars(path, NULL);
    ALOGD("Trying to get UUID for %s \n", pathStr);

    char device[256];
    char mount_path[256];
    char rest[256];
    FILE *fp;
    char line[1024];
    bool findDevice = false;
    if (!(fp = fopen("/proc/mounts", "r"))) {
        SLOGE("Error opening /proc/mounts (%s)", strerror(errno));
        return false;
    }

    while(fgets(line, sizeof(line), fp)) {
        line[strlen(line)-1] = '\0';
        sscanf(line, "%255s %255s %255s\n", device, mount_path, rest);
        if (!strcmp(mount_path, pathStr)) {
            findDevice = true;
            break;
        }
    }

    fclose(fp);

    if (findDevice) {
        uuid = blkid_get_tag_value(NULL, "UUID", device);
    } else {
        uuid = blkid_get_tag_value(NULL, "UUID", pathStr);
    }
    if (uuid) {
        ALOGD("UUID for %s is %s\n", pathStr, uuid);

        int len = strlen(uuid);
        char result[len];

        if (len > 0) {
            char * pCur = uuid;
            int length = 0;
            while (*pCur!='\0' && length < len)
            {
                if ((*pCur) != '-') {
                    result[length] = (*pCur);
                }
                pCur++;
                length++;
            }
            result[length] = '\0';
        }

        len = strlen(result);

        if (len > 0) {
            char *pEnd = NULL;
            return (int)strtol(result, &pEnd, 16);
        } else {
            ALOGE("Couldn't get UUID for %s\n", pathStr);
        }
    }
    return -1;
}

static const JNINativeMethod methods[] = {
    {"getVolumeUUID",  "(Ljava/lang/String;)I", (void*)android_os_FileUtils_getVolumeUUID},
};

static const char* const kFileUtilsPathName = "android/os/FileUtils";

int register_android_os_FileUtils(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(
        env, kFileUtilsPathName,
        methods, NELEM(methods));
}

}
