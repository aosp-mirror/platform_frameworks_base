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
