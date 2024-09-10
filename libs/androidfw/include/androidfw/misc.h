/*
 * Copyright (C) 2005 The Android Open Source Project
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

#include <sys/types.h>

//
// Handy utility functions and portability code.
//
#ifndef _LIBS_ANDROID_FW_MISC_H
#define _LIBS_ANDROID_FW_MISC_H

namespace android {

/*
 * Some utility functions for working with files.  These could be made
 * part of a "File" class.
 */
typedef enum FileType {
    kFileTypeUnknown = 0,
    kFileTypeNonexistent,       // i.e. ENOENT
    kFileTypeRegular,
    kFileTypeDirectory,
    kFileTypeCharDev,
    kFileTypeBlockDev,
    kFileTypeFifo,
    kFileTypeSymlink,
    kFileTypeSocket,
} FileType;
/* get the file's type; follows symlinks */
FileType getFileType(const char* fileName);
/* get the file's modification date; returns -1 w/errno set on failure */
time_t getFileModDate(const char* fileName);
/* same, but also returns -1 if the file has already been deleted */
time_t getFileModDate(int fd);

// Check if |path| or |fd| resides on a readonly filesystem.
bool isReadonlyFilesystem(const char* path);
bool isReadonlyFilesystem(int fd);

}; // namespace android

#endif // _LIBS_ANDROID_FW_MISC_H
