/*
 * Copyright (C) 2008 The Android Open Source Project
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

//#define LOG_NDEBUG 0

#include "JNIHelp.h"

#include <fcntl.h>
#include <sys/stat.h>
#include <stdio.h>

#include <utils/Log.h>

#include <android_runtime/AndroidRuntime.h>

namespace android
{

static struct parcel_file_descriptor_offsets_t
{
    jfieldID mFileDescriptor;
} gParcelFileDescriptorOffsets;

static jobject android_os_ParcelFileDescriptor_getFileDescriptorFromFd(JNIEnv* env,
    jobject clazz, jint origfd)
{
    int fd = dup(origfd);
    if (fd < 0) {
        jniThrowException(env, "java/io/IOException", strerror(errno));
        return NULL;
    }
    return jniCreateFileDescriptor(env, fd);
}

static jobject android_os_ParcelFileDescriptor_getFileDescriptorFromFdNoDup(JNIEnv* env,
    jobject clazz, jint fd)
{
    return jniCreateFileDescriptor(env, fd);
}

static void android_os_ParcelFileDescriptor_createPipeNative(JNIEnv* env,
    jobject clazz, jobjectArray outFds)
{
    int fds[2];
    if (pipe(fds) < 0) {
        int therr = errno;
        jniThrowException(env, "java/io/IOException", strerror(therr));
        return;
    }

    for (int i=0; i<2; i++) {
        jobject fdObj = jniCreateFileDescriptor(env, fds[i]);
        env->SetObjectArrayElement(outFds, i, fdObj);
    }
}

static jint getFd(JNIEnv* env, jobject clazz)
{
    jobject descriptor = env->GetObjectField(clazz, gParcelFileDescriptorOffsets.mFileDescriptor);
    if (descriptor == NULL) return -1;
    return jniGetFDFromFileDescriptor(env, descriptor);
}

static jlong android_os_ParcelFileDescriptor_getStatSize(JNIEnv* env,
    jobject clazz)
{
    jint fd = getFd(env, clazz);
    if (fd < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "bad file descriptor");
        return -1;
    }

    struct stat st;
    if (fstat(fd, &st) != 0) {
        return -1;
    }

    if (S_ISREG(st.st_mode) || S_ISLNK(st.st_mode)) {
        return st.st_size;
    }

    return -1;
}

static jlong android_os_ParcelFileDescriptor_seekTo(JNIEnv* env,
    jobject clazz, jlong pos)
{
    jint fd = getFd(env, clazz);
    if (fd < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "bad file descriptor");
        return -1;
    }

    return lseek(fd, pos, SEEK_SET);
}

static jlong android_os_ParcelFileDescriptor_getFdNative(JNIEnv* env, jobject clazz)
{
    jint fd = getFd(env, clazz);
    if (fd < 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "bad file descriptor");
        return -1;
    }

    return fd;
}

static const JNINativeMethod gParcelFileDescriptorMethods[] = {
    {"getFileDescriptorFromFd", "(I)Ljava/io/FileDescriptor;",
        (void*)android_os_ParcelFileDescriptor_getFileDescriptorFromFd},
    {"getFileDescriptorFromFdNoDup", "(I)Ljava/io/FileDescriptor;",
        (void*)android_os_ParcelFileDescriptor_getFileDescriptorFromFdNoDup},
    {"createPipeNative", "([Ljava/io/FileDescriptor;)V",
        (void*)android_os_ParcelFileDescriptor_createPipeNative},
    {"getStatSize", "()J",
        (void*)android_os_ParcelFileDescriptor_getStatSize},
    {"seekTo", "(J)J",
        (void*)android_os_ParcelFileDescriptor_seekTo},
    {"getFdNative", "()I",
        (void*)android_os_ParcelFileDescriptor_getFdNative}
};

const char* const kParcelFileDescriptorPathName = "android/os/ParcelFileDescriptor";

int register_android_os_ParcelFileDescriptor(JNIEnv* env)
{
    jclass clazz = env->FindClass(kParcelFileDescriptorPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.ParcelFileDescriptor");
    gParcelFileDescriptorOffsets.mFileDescriptor = env->GetFieldID(clazz, "mFileDescriptor", "Ljava/io/FileDescriptor;");
    LOG_FATAL_IF(gParcelFileDescriptorOffsets.mFileDescriptor == NULL,
                 "Unable to find mFileDescriptor field in android.os.ParcelFileDescriptor");

    return AndroidRuntime::registerNativeMethods(
        env, kParcelFileDescriptorPathName,
        gParcelFileDescriptorMethods, NELEM(gParcelFileDescriptorMethods));
}

}
