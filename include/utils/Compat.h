/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef __LIB_UTILS_COMPAT_H
#define __LIB_UTILS_COMPAT_H

#include <unistd.h>

/* Compatibility definitions for non-Linux (i.e., BSD-based) hosts. */
#ifndef HAVE_OFF64_T
#if _FILE_OFFSET_BITS < 64
#error "_FILE_OFFSET_BITS < 64; large files are not supported on this platform"
#endif /* _FILE_OFFSET_BITS < 64 */

typedef off_t off64_t;

static inline off64_t lseek64(int fd, off64_t offset, int whence) {
    return lseek(fd, offset, whence);
}

#ifdef HAVE_PREAD
static inline ssize_t pread64(int fd, void* buf, size_t nbytes, off64_t offset) {
    return pread(fd, buf, nbytes, offset);
}
#endif

#endif /* !HAVE_OFF64_T */

#endif /* __LIB_UTILS_COMPAT_H */
