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

//
// C API for ead-only access to Zip archives, with minimal heap allocation.
//
#ifndef __LIBS_ZIPFILECRO_H
#define __LIBS_ZIPFILECRO_H

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include <utils/Compat.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Trivial typedef to ensure that ZipFileCRO is not treated as a simple integer.
 */
typedef void* ZipFileCRO;

/*
 * Trivial typedef to ensure that ZipEntryCRO is not treated as a simple
 * integer.  We use NULL to indicate an invalid value.
 */
typedef void* ZipEntryCRO;

extern ZipFileCRO ZipFileXRO_open(const char* path);

extern void ZipFileCRO_destroy(ZipFileCRO zip);

extern ZipEntryCRO ZipFileCRO_findEntryByName(ZipFileCRO zip,
        const char* fileName);

extern bool ZipFileCRO_getEntryInfo(ZipFileCRO zip, ZipEntryCRO entry,
        int* pMethod, size_t* pUncompLen,
        size_t* pCompLen, off64_t* pOffset, long* pModWhen, long* pCrc32);

extern bool ZipFileCRO_uncompressEntry(ZipFileCRO zip, ZipEntryCRO entry, int fd);

#ifdef __cplusplus
}
#endif

#endif /*__LIBS_ZIPFILECRO_H*/
