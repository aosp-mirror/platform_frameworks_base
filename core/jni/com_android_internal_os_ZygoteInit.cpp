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
#include "android_runtime/AndroidRuntime.h"

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

static void com_android_internal_os_ZygoteInit_reopenStdio(JNIEnv* env,
        jobject clazz, jobject in, jobject out, jobject errfd)
{
    int fd;
    int err;

    fd = jniGetFDFromFileDescriptor(env, in);

    if  (env->ExceptionCheck()) {
        return;
    }

    do {
        err = dup2(fd, STDIN_FILENO);
    } while (err < 0 && errno == EINTR);

    fd = jniGetFDFromFileDescriptor(env, out);

    if  (env->ExceptionCheck()) {
        return;
    }

    do {
        err = dup2(fd, STDOUT_FILENO);
    } while (err < 0 && errno == EINTR);

    fd = jniGetFDFromFileDescriptor(env, errfd);

    if  (env->ExceptionCheck()) {
        return;
    }

    do {
        err = dup2(fd, STDERR_FILENO);
    } while (err < 0 && errno == EINTR);
}

static void com_android_internal_os_ZygoteInit_setCloseOnExec (JNIEnv *env,
    jobject clazz, jobject descriptor, jboolean flag)
{
    int fd;
    int err;
    int fdFlags;

    fd = jniGetFDFromFileDescriptor(env, descriptor);

    if  (env->ExceptionCheck()) {
        return;
    }

    fdFlags = fcntl(fd, F_GETFD);

    if (fdFlags < 0) {
        jniThrowIOException(env, errno);
        return;
    }

    if (flag) {
        fdFlags |= FD_CLOEXEC;
    } else {
        fdFlags &= ~FD_CLOEXEC;
    }

    err = fcntl(fd, F_SETFD, fdFlags);

    if (err < 0) {
        jniThrowIOException(env, errno);
        return;
    }
}

static jint com_android_internal_os_ZygoteInit_selectReadable (
        JNIEnv *env, jobject clazz, jobjectArray fds)
{
    if (fds == NULL) {
        jniThrowNullPointerException(env, "fds == null");
        return -1;
    }

    jsize length = env->GetArrayLength(fds);
    fd_set fdset;

    if (env->ExceptionCheck()) {
        return -1;
    }

    FD_ZERO(&fdset);

    int nfds = 0;
    for (jsize i = 0; i < length; i++) {
        jobject fdObj = env->GetObjectArrayElement(fds, i);
        if  (env->ExceptionCheck()) {
            return -1;
        }
        if (fdObj == NULL) {
            continue;
        }
        int fd = jniGetFDFromFileDescriptor(env, fdObj);
        if  (env->ExceptionCheck()) {
            return -1;
        }

        FD_SET(fd, &fdset);

        if (fd >= nfds) {
            nfds = fd + 1;
        }
    }

    int err;
    do {
        err = select (nfds, &fdset, NULL, NULL, NULL);
    } while (err < 0 && errno == EINTR);

    if (err < 0) {
        jniThrowIOException(env, errno);
        return -1;
    }

    for (jsize i = 0; i < length; i++) {
        jobject fdObj = env->GetObjectArrayElement(fds, i);
        if  (env->ExceptionCheck()) {
            return -1;
        }
        if (fdObj == NULL) {
            continue;
        }
        int fd = jniGetFDFromFileDescriptor(env, fdObj);
        if  (env->ExceptionCheck()) {
            return -1;
        }
        if (FD_ISSET(fd, &fdset)) {
            return (jint)i;
        }
    }
    return -1;
}

static jobject com_android_internal_os_ZygoteInit_createFileDescriptor (
        JNIEnv *env, jobject clazz, jint fd)
{
    return jniCreateFileDescriptor(env, fd);
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
    { "reopenStdio",
        "(Ljava/io/FileDescriptor;Ljava/io/FileDescriptor;"
        "Ljava/io/FileDescriptor;)V",
            (void *) com_android_internal_os_ZygoteInit_reopenStdio},
    { "setCloseOnExec", "(Ljava/io/FileDescriptor;Z)V",
        (void *)  com_android_internal_os_ZygoteInit_setCloseOnExec},
    { "selectReadable", "([Ljava/io/FileDescriptor;)I",
        (void *) com_android_internal_os_ZygoteInit_selectReadable },
    { "createFileDescriptor", "(I)Ljava/io/FileDescriptor;",
        (void *) com_android_internal_os_ZygoteInit_createFileDescriptor }
};
int register_com_android_internal_os_ZygoteInit(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            "com/android/internal/os/ZygoteInit", gMethods, NELEM(gMethods));
}

}; // namespace android
