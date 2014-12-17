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

#define LOG_TAG "Zygote"

#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>
#include <utils/misc.h>
#include <errno.h>
#include <sys/select.h>

#include "jni.h"
#include <JNIHelp.h>
#include "core_jni_helpers.h"

#include <sys/capability.h>
#include <sys/prctl.h>

namespace android {

/*
 * In class com.android.internal.os.ZygoteInit:
 * private static native boolean setreuid(int ruid, int euid)
 */
static jint com_android_internal_os_ZygoteInit_setreuid(
    JNIEnv* env, jobject clazz, jint ruid, jint euid)
{
    if (setreuid(ruid, euid) < 0) {
        return errno;
    }
    return 0;
}

/*
 * In class com.android.internal.os.ZygoteInit:
 * private static native int setregid(int rgid, int egid)
 */
static jint com_android_internal_os_ZygoteInit_setregid(
    JNIEnv* env, jobject clazz, jint rgid, jint egid)
{
    if (setregid(rgid, egid) < 0) {
        return errno;
    }
    return 0;
}

/*
 * In class com.android.internal.os.ZygoteInit:
 * private static native int setpgid(int rgid, int egid)
 */
static jint com_android_internal_os_ZygoteInit_setpgid(
    JNIEnv* env, jobject clazz, jint pid, jint pgid)
{
    if (setpgid(pid, pgid) < 0) {
        return errno;
    }
    return 0;
}

/*
 * In class com.android.internal.os.ZygoteInit:
 * private static native int getpgid(int pid)
 */
static jint com_android_internal_os_ZygoteInit_getpgid(
    JNIEnv* env, jobject clazz, jint pid)
{
    pid_t ret;
    ret = getpgid(pid);

    if (ret < 0) {
        jniThrowIOException(env, errno);
    }

    return ret;
}

/*
 * JNI registration.
 */
static JNINativeMethod gMethods[] = {
    /* name, signature, funcPtr */
    { "setreuid", "(II)I",
      (void*) com_android_internal_os_ZygoteInit_setreuid },
    { "setregid", "(II)I",
      (void*) com_android_internal_os_ZygoteInit_setregid },
    { "setpgid", "(II)I",
      (void *) com_android_internal_os_ZygoteInit_setpgid },
    { "getpgid", "(I)I",
      (void *) com_android_internal_os_ZygoteInit_getpgid },
};
int register_com_android_internal_os_ZygoteInit(JNIEnv* env)
{
    return RegisterMethodsOrDie(env,
            "com/android/internal/os/ZygoteInit", gMethods, NELEM(gMethods));
}

}; // namespace android
