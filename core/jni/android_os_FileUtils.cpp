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

#include <sys/errno.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <linux/msdos_fs.h>

namespace android {

jint android_os_FileUtils_setPermissions(JNIEnv* env, jobject clazz,
                                         jstring file, jint mode,
                                         jint uid, jint gid)
{
    const jchar* str = env->GetStringCritical(file, 0);
    String8 file8;
    if (str) {
        file8 = String8(str, env->GetStringLength(file));
        env->ReleaseStringCritical(file, str);
    }
    if (file8.size() <= 0) {
        return ENOENT;
    }
    if (uid >= 0 || gid >= 0) {
        int res = chown(file8.string(), uid, gid);
        if (res != 0) {
            return errno;
        }
    }
    return chmod(file8.string(), mode) == 0 ? 0 : errno;
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
    {"setPermissions",  "(Ljava/lang/String;III)I", (void*)android_os_FileUtils_setPermissions},
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
