/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "core_jni_helpers.h"
#include <cutils/ashmem.h>
#include <linux/ashmem.h>
#include <sys/mman.h>

namespace android {

static jint android_util_MemoryIntArray_create(JNIEnv* env, jobject clazz, jstring name,
        jint size)
{
    if (name == NULL) {
        jniThrowException(env, "java/io/IOException", "bad name");
        return -1;
    }

    if (size <= 0) {
        jniThrowException(env, "java/io/IOException", "bad size");
        return -1;
    }

    const char* nameStr = env->GetStringUTFChars(name, NULL);
    const int ashmemSize = sizeof(std::atomic_int) * size;
    int fd = ashmem_create_region(nameStr, ashmemSize);
    env->ReleaseStringUTFChars(name, nameStr);

    if (fd < 0) {
        jniThrowException(env, "java/io/IOException", "ashmem creation failed");
        return -1;
    }

    int setProtResult = ashmem_set_prot_region(fd, PROT_READ | PROT_WRITE);
    if (setProtResult < 0) {
        jniThrowException(env, "java/io/IOException", "cannot set ashmem prot mode");
        return -1;
    }

    return fd;
}

static jlong android_util_MemoryIntArray_open(JNIEnv* env, jobject clazz, jint fd,
    jboolean owner, jboolean writable)
{
    if (fd < 0) {
        jniThrowException(env, "java/io/IOException", "bad file descriptor");
        return -1;
    }

    int ashmemSize = ashmem_get_size_region(fd);
    if (ashmemSize <= 0) {
        jniThrowException(env, "java/io/IOException", "bad ashmem size");
        return -1;
    }

    int protMode = (owner || writable) ? (PROT_READ | PROT_WRITE) : PROT_READ;
    void* ashmemAddr = mmap(NULL, ashmemSize, protMode, MAP_SHARED, fd, 0);
    if (ashmemAddr == MAP_FAILED) {
        jniThrowException(env, "java/io/IOException", "cannot mmap ashmem");
        return -1;
    }

    if (owner) {
        int size = ashmemSize / sizeof(std::atomic_int);
        new (ashmemAddr) std::atomic_int[size];
    }

    if (owner && !writable) {
        int setProtResult = ashmem_set_prot_region(fd, PROT_READ);
        if (setProtResult < 0) {
            jniThrowException(env, "java/io/IOException", "cannot set ashmem prot mode");
            return -1;
        }
    }

    return reinterpret_cast<jlong>(ashmemAddr);
}

static void android_util_MemoryIntArray_close(JNIEnv* env, jobject clazz, jint fd,
    jlong ashmemAddr, jboolean owner)
{
    if (fd < 0) {
        jniThrowException(env, "java/io/IOException", "bad file descriptor");
        return;
    }

    int ashmemSize = ashmem_get_size_region(fd);
    if (ashmemSize <= 0) {
        jniThrowException(env, "java/io/IOException", "bad ashmem size");
        return;
    }

    int unmapResult = munmap(reinterpret_cast<void *>(ashmemAddr), ashmemSize);
    if (unmapResult < 0) {
        jniThrowException(env, "java/io/IOException", "munmap failed");
        return;
    }

    // We don't deallocate the atomic ints we created with placement new in the ashmem
    // region as the kernel we reclaim all pages when the ashmem region is destroyed.
    if (owner && (ashmem_unpin_region(fd, 0, 0) != ASHMEM_IS_UNPINNED)) {
        jniThrowException(env, "java/io/IOException", "ashmem unpinning failed");
        return;
    }

    close(fd);
}

static jint android_util_MemoryIntArray_get(JNIEnv* env, jobject clazz,
        jint fd, jlong address, jint index, jboolean owner)
{
    if (fd < 0) {
        jniThrowException(env, "java/io/IOException", "bad file descriptor");
        return -1;
    }

    if (ashmem_pin_region(fd, 0, 0) == ASHMEM_WAS_PURGED) {
        jniThrowException(env, "java/io/IOException", "ashmem region was purged");
        return -1;
    }

    std::atomic_int* value = reinterpret_cast<std::atomic_int*>(address) + index;
    return value->load(std::memory_order_relaxed);
}

static void android_util_MemoryIntArray_set(JNIEnv* env, jobject clazz,
        jint fd, jlong address, jint index, jint newValue, jboolean owner)
{
    if (fd < 0) {
        jniThrowException(env, "java/io/IOException", "bad file descriptor");
        return;
    }

    if (ashmem_pin_region(fd, 0, 0) == ASHMEM_WAS_PURGED) {
        jniThrowException(env, "java/io/IOException", "ashmem region was purged");
        return;
    }

    std::atomic_int* value = reinterpret_cast<std::atomic_int*>(address) + index;
    value->store(newValue, std::memory_order_relaxed);
}

static jint android_util_MemoryIntArray_size(JNIEnv* env, jobject clazz, jint fd) {
    if (fd < 0) {
        jniThrowException(env, "java/io/IOException", "bad file descriptor");
        return -1;
    }

    int ashmemSize = ashmem_get_size_region(fd);
    if (ashmemSize < 0) {
        // Some other error, throw exception
        jniThrowIOException(env, errno);
        return -1;
    }
    return ashmemSize / sizeof(std::atomic_int);
}

static const JNINativeMethod methods[] = {
    {"nativeCreate",  "(Ljava/lang/String;I)I", (void*)android_util_MemoryIntArray_create},
    {"nativeOpen",  "(IZZ)J", (void*)android_util_MemoryIntArray_open},
    {"nativeClose", "(IJZ)V", (void*)android_util_MemoryIntArray_close},
    {"nativeGet",  "(IJIZ)I", (void*)android_util_MemoryIntArray_get},
    {"nativeSet", "(IJIIZ)V", (void*) android_util_MemoryIntArray_set},
    {"nativeSize", "(I)I", (void*) android_util_MemoryIntArray_size},
};

int register_android_util_MemoryIntArray(JNIEnv* env)
{
    return RegisterMethodsOrDie(env, "android/util/MemoryIntArray", methods, NELEM(methods));
}

}
