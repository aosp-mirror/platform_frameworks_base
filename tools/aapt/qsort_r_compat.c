/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <stdlib.h>
#include "qsort_r_compat.h"

/*
 * Note: This code is only used on the host, and is primarily here for
 * Mac OS compatibility. Apparently, glibc and Apple's libc disagree on
 * the parameter order for qsort_r.
 */

#if HAVE_BSD_QSORT_R

/*
 * BSD qsort_r parameter order is as we have defined here.
 */

void qsort_r_compat(void* base, size_t nel, size_t width, void* thunk,
        int (*compar)(void*, const void* , const void*)) {
    qsort_r(base, nel, width, thunk, compar);
}

#elif HAVE_GNU_QSORT_R

/*
 * GNU qsort_r parameter order places the thunk parameter last.
 */

struct compar_data {
    void* thunk;
    int (*compar)(void*, const void* , const void*);
};

static int compar_wrapper(const void* a, const void* b, void* data) {
    struct compar_data* compar_data = (struct compar_data*)data;
    return compar_data->compar(compar_data->thunk, a, b);
}

void qsort_r_compat(void* base, size_t nel, size_t width, void* thunk,
        int (*compar)(void*, const void* , const void*)) {
    struct compar_data compar_data;
    compar_data.thunk = thunk;
    compar_data.compar = compar;
    qsort_r(base, nel, width, compar_wrapper, &compar_data);
}

#else

/*
 * Emulate qsort_r using thread local storage to access the thunk data.
 */

#include <cutils/threads.h>

static thread_store_t compar_data_key = THREAD_STORE_INITIALIZER;

struct compar_data {
    void* thunk;
    int (*compar)(void*, const void* , const void*);
};

static int compar_wrapper(const void* a, const void* b) {
    struct compar_data* compar_data = (struct compar_data*)thread_store_get(&compar_data_key);
    return compar_data->compar(compar_data->thunk, a, b);
}

void qsort_r_compat(void* base, size_t nel, size_t width, void* thunk,
        int (*compar)(void*, const void* , const void*)) {
    struct compar_data compar_data;
    compar_data.thunk = thunk;
    compar_data.compar = compar;
    thread_store_set(&compar_data_key, &compar_data, NULL);
    qsort(base, nel, width, compar_wrapper);
}

#endif
