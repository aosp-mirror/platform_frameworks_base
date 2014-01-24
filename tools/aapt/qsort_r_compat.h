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

/*
 * Provides a portable version of qsort_r, called qsort_r_compat, which is a
 * reentrant variant of qsort that passes a user data pointer to its comparator.
 * This implementation follows the BSD parameter convention.
 */

#ifndef ___QSORT_R_COMPAT_H
#define ___QSORT_R_COMPAT_H

#include <stdlib.h>

#ifdef __cplusplus
extern "C" {
#endif

void qsort_r_compat(void* base, size_t nel, size_t width, void* thunk,
        int (*compar)(void*, const void* , const void* ));

#ifdef __cplusplus
}
#endif

#endif // ___QSORT_R_COMPAT_H
