/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <jni.h>

#include <android/sharedmem.h>
#include <android/sharedmem_jni.h>
#include <cutils/ashmem.h>
#include <log/log.h>
#include <utils/Errors.h>

#include <mutex>
#include <unistd.h>

static struct {
    jclass clazz;
    jmethodID getFd;
} sSharedMemory;

static void jniInit(JNIEnv* env) {
    static std::once_flag sJniInitialized;
    std::call_once(sJniInitialized, [](JNIEnv* env) {
        jclass clazz = env->FindClass("android/os/SharedMemory");
        LOG_ALWAYS_FATAL_IF(clazz == nullptr, "Failed to find android.os.SharedMemory");
        sSharedMemory.clazz = (jclass) env->NewGlobalRef(clazz);
        LOG_ALWAYS_FATAL_IF(sSharedMemory.clazz == nullptr,
                "Failed to create global ref of android.os.SharedMemory");
        sSharedMemory.getFd = env->GetMethodID(sSharedMemory.clazz, "getFd", "()I");
        LOG_ALWAYS_FATAL_IF(sSharedMemory.getFd == nullptr,
                "Failed to find method SharedMemory#getFd()");
    }, env);
}

int ASharedMemory_create(const char *name, size_t size) {
    if (size == 0) {
        return android::BAD_VALUE;
    }
    return ashmem_create_region(name, size);
}

size_t ASharedMemory_getSize(int fd) {
    return ashmem_valid(fd) ? ashmem_get_size_region(fd) : 0;
}

int ASharedMemory_setProt(int fd, int prot) {
    return ashmem_set_prot_region(fd, prot);
}

int ASharedMemory_dupFromJava(JNIEnv* env, jobject javaSharedMemory) {
    if (env == nullptr || javaSharedMemory == nullptr) {
        return -1;
    }
    jniInit(env);
    if (!env->IsInstanceOf(javaSharedMemory, sSharedMemory.clazz)) {
        ALOGW("ASharedMemory_dupFromJava called with object "
                "that's not an instanceof android.os.SharedMemory");
        return -1;
    }
    int fd = env->CallIntMethod(javaSharedMemory, sSharedMemory.getFd);
    if (fd != -1) {
        fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    }
    return fd;
}
