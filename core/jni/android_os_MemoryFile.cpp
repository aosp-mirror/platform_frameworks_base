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
#include <android_runtime/AndroidRuntime.h>
#include "JNIHelp.h"
#include <unistd.h>
#include <sys/mman.h>


namespace android {

static jobject android_os_MemoryFile_open(JNIEnv* env, jobject clazz, jstring name, jint length)
{
    const char* namestr = (name ? env->GetStringUTFChars(name, NULL) : NULL);

    int result = ashmem_create_region(namestr, length);

    if (name)
        env->ReleaseStringUTFChars(name, namestr);

    if (result < 0) {
        jniThrowException(env, "java/io/IOException", "ashmem_create_region failed");
        return NULL;
    }

    return jniCreateFileDescriptor(env, result);
}

static jint android_os_MemoryFile_mmap(JNIEnv* env, jobject clazz, jobject fileDescriptor,
        jint length, jint prot)
{
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    jint result = (jint)mmap(NULL, length, prot, MAP_SHARED, fd, 0);
    if (!result)
        jniThrowException(env, "java/io/IOException", "mmap failed");
    return result;
}

static void android_os_MemoryFile_munmap(JNIEnv* env, jobject clazz, jint addr, jint length)
{
    int result = munmap((void *)addr, length);
    if (result < 0)
        jniThrowException(env, "java/io/IOException", "munmap failed");
}

static void android_os_MemoryFile_close(JNIEnv* env, jobject clazz, jobject fileDescriptor)
{
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (fd >= 0) {
        jniSetFileDescriptorOfFD(env, fileDescriptor, -1);
        close(fd);
    }
}

static jint android_os_MemoryFile_read(JNIEnv* env, jobject clazz,
        jobject fileDescriptor, jint address, jbyteArray buffer, jint srcOffset, jint destOffset,
        jint count, jboolean unpinned)
{
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (unpinned && ashmem_pin_region(fd, 0, 0) == ASHMEM_WAS_PURGED) {
        ashmem_unpin_region(fd, 0, 0);
        jniThrowException(env, "java/io/IOException", "ashmem region was purged");
        return -1;
    }

    env->SetByteArrayRegion(buffer, destOffset, count, (const jbyte *)address + srcOffset);

    if (unpinned) {
        ashmem_unpin_region(fd, 0, 0);
    }
    return count;
}

static jint android_os_MemoryFile_write(JNIEnv* env, jobject clazz,
        jobject fileDescriptor, jint address, jbyteArray buffer, jint srcOffset, jint destOffset,
        jint count, jboolean unpinned)
{
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (unpinned && ashmem_pin_region(fd, 0, 0) == ASHMEM_WAS_PURGED) {
        ashmem_unpin_region(fd, 0, 0);
        jniThrowException(env, "java/io/IOException", "ashmem region was purged");
        return -1;
    }

    env->GetByteArrayRegion(buffer, srcOffset, count, (jbyte *)address + destOffset);

    if (unpinned) {
        ashmem_unpin_region(fd, 0, 0);
    }
    return count;
}

static void android_os_MemoryFile_pin(JNIEnv* env, jobject clazz, jobject fileDescriptor, jboolean pin)
{
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    int result = (pin ? ashmem_pin_region(fd, 0, 0) : ashmem_unpin_region(fd, 0, 0));
    if (result < 0) {
        jniThrowException(env, "java/io/IOException", NULL);
    }
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
    {"native_open",  "(Ljava/lang/String;I)Ljava/io/FileDescriptor;", (void*)android_os_MemoryFile_open},
    {"native_mmap",  "(Ljava/io/FileDescriptor;II)I", (void*)android_os_MemoryFile_mmap},
    {"native_munmap", "(II)V", (void*)android_os_MemoryFile_munmap},
    {"native_close", "(Ljava/io/FileDescriptor;)V", (void*)android_os_MemoryFile_close},
    {"native_read",  "(Ljava/io/FileDescriptor;I[BIIIZ)I", (void*)android_os_MemoryFile_read},
    {"native_write", "(Ljava/io/FileDescriptor;I[BIIIZ)V", (void*)android_os_MemoryFile_write},
    {"native_pin",   "(Ljava/io/FileDescriptor;Z)V", (void*)android_os_MemoryFile_pin},
    {"native_get_size", "(Ljava/io/FileDescriptor;)I",
            (void*)android_os_MemoryFile_get_size}
};

int register_android_os_MemoryFile(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(
        env, "android/os/MemoryFile",
        methods, NELEM(methods));
}

}
