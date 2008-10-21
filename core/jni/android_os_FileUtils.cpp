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

#if HAVE_ANDROID_OS
#include <sys/ioctl.h>
#include <linux/msdos_fs.h>
#endif

namespace android {

static jclass gFileStatusClass;
static jfieldID gFileStatusDevFieldID;
static jfieldID gFileStatusInoFieldID;
static jfieldID gFileStatusModeFieldID;
static jfieldID gFileStatusNlinkFieldID;
static jfieldID gFileStatusUidFieldID;
static jfieldID gFileStatusGidFieldID;
static jfieldID gFileStatusSizeFieldID;
static jfieldID gFileStatusBlksizeFieldID;
static jfieldID gFileStatusBlocksFieldID;
static jfieldID gFileStatusAtimeFieldID;
static jfieldID gFileStatusMtimeFieldID;
static jfieldID gFileStatusCtimeFieldID;

jint android_os_FileUtils_setPermissions(JNIEnv* env, jobject clazz,
                                         jstring file, jint mode,
                                         jint uid, jint gid)
{
    #if HAVE_ANDROID_OS
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
    #else
    return ENOSYS;
    #endif
}

jint android_os_FileUtils_getPermissions(JNIEnv* env, jobject clazz,
                                         jstring file, jintArray outArray)
{
    #if HAVE_ANDROID_OS
    const jchar* str = env->GetStringCritical(file, 0);
    String8 file8;
    if (str) {
        file8 = String8(str, env->GetStringLength(file));
        env->ReleaseStringCritical(file, str);
    }
    if (file8.size() <= 0) {
        return ENOENT;
    }
    struct stat st;
    if (stat(file8.string(), &st) != 0) {
        return errno;
    }
    jint* array = (jint*)env->GetPrimitiveArrayCritical(outArray, 0);
    if (array) {
        int len = env->GetArrayLength(outArray);
        if (len >= 1) {
            array[0] = st.st_mode;
        }
        if (len >= 2) {
            array[1] = st.st_uid;
        }
        if (len >= 3) {
            array[2] = st.st_gid;
        }
    }
    env->ReleasePrimitiveArrayCritical(outArray, array, 0);
    return 0;
    #else
    return ENOSYS;
    #endif
}

jint android_os_FileUtils_getFatVolumeId(JNIEnv* env, jobject clazz, jstring path)
{
    #if HAVE_ANDROID_OS
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
    #else
    return -1;
    #endif
}

jboolean android_os_FileUtils_getFileStatus(JNIEnv* env, jobject clazz, jstring path, jobject fileStatus) {
    const char* pathStr = env->GetStringUTFChars(path, NULL);
    jboolean ret = false;
    
    struct stat s;
    int res = stat(pathStr, &s);
    if (res == 0) {
        ret = true;
        if (fileStatus != NULL) {
            env->SetIntField(fileStatus, gFileStatusDevFieldID, s.st_dev);
            env->SetIntField(fileStatus, gFileStatusInoFieldID, s.st_ino);
            env->SetIntField(fileStatus, gFileStatusModeFieldID, s.st_mode);
            env->SetIntField(fileStatus, gFileStatusNlinkFieldID, s.st_nlink);
            env->SetIntField(fileStatus, gFileStatusUidFieldID, s.st_uid);
            env->SetIntField(fileStatus, gFileStatusGidFieldID, s.st_gid);
            env->SetLongField(fileStatus, gFileStatusSizeFieldID, s.st_size);
            env->SetIntField(fileStatus, gFileStatusBlksizeFieldID, s.st_blksize);
            env->SetLongField(fileStatus, gFileStatusBlocksFieldID, s.st_blocks);
            env->SetLongField(fileStatus, gFileStatusAtimeFieldID, s.st_atime);
            env->SetLongField(fileStatus, gFileStatusMtimeFieldID, s.st_mtime);
            env->SetLongField(fileStatus, gFileStatusCtimeFieldID, s.st_ctime);
        }
    }
    
    env->ReleaseStringUTFChars(path, pathStr);
    
    return ret;
}

static const JNINativeMethod methods[] = {
    {"setPermissions",  "(Ljava/lang/String;III)I", (void*)android_os_FileUtils_setPermissions},
    {"getPermissions",  "(Ljava/lang/String;[I)I", (void*)android_os_FileUtils_getPermissions},
    {"getFatVolumeId",  "(Ljava/lang/String;)I", (void*)android_os_FileUtils_getFatVolumeId},
    {"getFileStatus",  "(Ljava/lang/String;Landroid/os/FileUtils$FileStatus;)Z", (void*)android_os_FileUtils_getFileStatus},
};

static const char* const kFileUtilsPathName = "android/os/FileUtils";

int register_android_os_FileUtils(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass(kFileUtilsPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.FileUtils");
    
    gFileStatusClass = env->FindClass("android/os/FileUtils$FileStatus");
    LOG_FATAL_IF(gFileStatusClass == NULL, "Unable to find class android.os.FileUtils$FileStatus");

    gFileStatusDevFieldID = env->GetFieldID(gFileStatusClass, "dev", "I");
    gFileStatusInoFieldID = env->GetFieldID(gFileStatusClass, "ino", "I");
    gFileStatusModeFieldID = env->GetFieldID(gFileStatusClass, "mode", "I");
    gFileStatusNlinkFieldID = env->GetFieldID(gFileStatusClass, "nlink", "I");
    gFileStatusUidFieldID = env->GetFieldID(gFileStatusClass, "uid", "I");
    gFileStatusGidFieldID = env->GetFieldID(gFileStatusClass, "gid", "I");
    gFileStatusSizeFieldID = env->GetFieldID(gFileStatusClass, "size", "J");
    gFileStatusBlksizeFieldID = env->GetFieldID(gFileStatusClass, "blksize", "I");
    gFileStatusBlocksFieldID = env->GetFieldID(gFileStatusClass, "blocks", "J");
    gFileStatusAtimeFieldID = env->GetFieldID(gFileStatusClass, "atime", "J");
    gFileStatusMtimeFieldID = env->GetFieldID(gFileStatusClass, "mtime", "J");
    gFileStatusCtimeFieldID = env->GetFieldID(gFileStatusClass, "ctime", "J");

    return AndroidRuntime::registerNativeMethods(
        env, kFileUtilsPathName,
        methods, NELEM(methods));
}

}

