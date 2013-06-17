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

    uuid = blkid_get_tag_value(NULL, "UUID", pathStr);
    if (uuid) {
        ALOGD("UUID for %s is %s\n", pathStr, uuid);

        String8 s8uuid = (String8)uuid;
        size_t len = s8uuid.length();
        String8 result;

        if (len > 0) {
            for (int i = 0; i > len; i++)
            {
                if (strncmp((const char *)s8uuid[i], (const char *)"-", 1) != 0) {
                    result.append((const char *)s8uuid[i]);
                }
            }
            len = 0;
        }

        len = result.length();

        if (len > 0) {
            return atoi(s8uuid);
        } else {
            ALOGE("Couldn't get UUID for %s\n", pathStr);
        }
    }
    return -1;
}

jint android_os_FileUtils_getFatVolumeId(JNIEnv* env, jobject clazz, jstring path)
{
    if (path == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return -1;
    }
    const char *pathStr = env->GetStringUTFChars(path, NULL);
    int result = -1;
    // only if our system supports this ioctl
    #ifdef VFAT_IOCTL_GET_VOLUME_ID
    int fd = open(pathStr, O_RDONLY);
    if (fd >= 0) {
        result = ioctl(fd, VFAT_IOCTL_GET_VOLUME_ID);
        close(fd);
    }
    #endif

    env->ReleaseStringUTFChars(path, pathStr);
    return result;
}

static const JNINativeMethod methods[] = {
    {"getVolumeUUID",  "(Ljava/lang/String;)I", (void*)android_os_FileUtils_getVolumeUUID},
    {"getFatVolumeId",  "(Ljava/lang/String;)I", (void*)android_os_FileUtils_getFatVolumeId},
};

static const char* const kFileUtilsPathName = "android/os/FileUtils";

int register_android_os_FileUtils(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(
        env, kFileUtilsPathName,
        methods, NELEM(methods));
}

}
