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

#define LOG_TAG "MemoryFile"
#include <utils/Log.h>

#include <cutils/ashmem.h>
#include "core_jni_helpers.h"
#include <nativehelper/JNIHelp.h>
#include <unistd.h>
#include <sys/mman.h>


namespace android {

static jboolean android_os_MemoryFile_pin(JNIEnv* env, jobject clazz, jobject fileDescriptor,
        jboolean pin) {
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    int result = (pin ? ashmem_pin_region(fd, 0, 0) : ashmem_unpin_region(fd, 0, 0));
    if (result < 0) {
        jniThrowException(env, "java/io/IOException", NULL);
    }
    return result == ASHMEM_WAS_PURGED;
}

static jint android_os_MemoryFile_get_size(JNIEnv* env, jobject clazz,
        jobject fileDescriptor) {
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    // Use ASHMEM_GET_SIZE to find out if the fd refers to an ashmem region.
    // ASHMEM_GET_SIZE should succeed for all ashmem regions, and the kernel
    // should return ENOTTY for all other valid file descriptors
    int result = ashmem_get_size_region(fd);
    if (result < 0) {
        if (errno == ENOTTY) {
            // ENOTTY means that the ioctl does not apply to this object,
            // i.e., it is not an ashmem region.
            return (jint) -1;
        }
        // Some other error, throw exception
        jniThrowIOException(env, errno);
        return (jint) -1;
    }
    return (jint) result;
}

static const JNINativeMethod methods[] = {
    {"native_pin",   "(Ljava/io/FileDescriptor;Z)Z", (void*)android_os_MemoryFile_pin},
    {"native_get_size", "(Ljava/io/FileDescriptor;)I",
            (void*)android_os_MemoryFile_get_size}
};

int register_android_os_MemoryFile(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/os/MemoryFile", methods, NELEM(methods));
}

}
