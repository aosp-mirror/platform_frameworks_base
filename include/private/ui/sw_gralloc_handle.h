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

#ifndef ANDROID_UI_PRIVATE_SW_GRALLOC_HANDLE_H
#define ANDROID_UI_PRIVATE_SW_GRALLOC_HANDLE_H

#include <stdint.h>
#include <limits.h>
#include <sys/cdefs.h>
#include <hardware/gralloc.h>
#include <errno.h>

#include <cutils/native_handle.h>

namespace android {

/*****************************************************************************/

struct sw_gralloc_handle_t : public native_handle 
{
    // file-descriptors
    int     fd;
    // ints
    int     magic;
    int     size;
    int     base;
    int     prot;
    int     pid;

    static const int sNumInts = 5;
    static const int sNumFds = 1;
    static const int sMagic = '_sgh';

    sw_gralloc_handle_t() :
        fd(-1), magic(sMagic), size(0), base(0), prot(0), pid(getpid())
    {
        version = sizeof(native_handle);
        numInts = sNumInts;
        numFds = sNumFds;
    }
    ~sw_gralloc_handle_t() {
        magic = 0;
    }

    static int validate(const native_handle* h) {
        const sw_gralloc_handle_t* hnd = (const sw_gralloc_handle_t*)h;
        if (!h || h->version != sizeof(native_handle) ||
                h->numInts != sNumInts || h->numFds != sNumFds ||
                hnd->magic != sMagic) 
        {
            return -EINVAL;
        }
        return 0;
    }

    static status_t alloc(uint32_t w, uint32_t h, int format,
            int usage, buffer_handle_t* handle, int32_t* stride);
    static status_t free(sw_gralloc_handle_t* hnd);
    static status_t registerBuffer(sw_gralloc_handle_t* hnd);
    static status_t unregisterBuffer(sw_gralloc_handle_t* hnd);
    static status_t lock(sw_gralloc_handle_t* hnd, int usage,
            int l, int t, int w, int h, void** vaddr);
    static status_t unlock(sw_gralloc_handle_t* hnd);
};

/*****************************************************************************/

}; // namespace android

#endif /* ANDROID_UI_PRIVATE_SW_GRALLOC_HANDLE_H */
