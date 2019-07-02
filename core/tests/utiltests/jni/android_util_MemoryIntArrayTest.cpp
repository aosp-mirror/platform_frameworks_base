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

#include <atomic>
#include <jni.h>
#include <cutils/ashmem.h>
#include <linux/ashmem.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

jint android_util_MemoryIntArrayTest_createAshmem(__attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jobject clazz,
        jstring name, jint size)
{

    if (name == NULL) {
        return -1;
    }

    if (size < 0) {
        return -1;
    }

    const char* nameStr = env->GetStringUTFChars(name, NULL);
    const int ashmemSize = sizeof(std::atomic_int) * size;
    int fd = ashmem_create_region(nameStr, ashmemSize);
    env->ReleaseStringUTFChars(name, nameStr);

    if (fd < 0) {
        return -1;
    }

    int setProtResult = ashmem_set_prot_region(fd, PROT_READ | PROT_WRITE);
    if (setProtResult < 0) {
        return -1;
    }

    return fd;
}

void android_util_MemoryIntArrayTest_setAshmemSize(__attribute__((unused)) JNIEnv* env,
        __attribute__((unused)) jobject clazz, jint fd, jint size)
{
    if (fd < 0) {
        return;
    }

    if (size < 0) {
        return;
    }

    ioctl(fd, ASHMEM_SET_SIZE, size);
}
