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

#include <android/sharedmem.h>
#include <cutils/ashmem.h>
#include <utils/Errors.h>

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
