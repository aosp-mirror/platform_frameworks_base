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

//
// Handy utility functions and portability code.
//
#ifndef _LIBS_UTILS_MISC_H
#define _LIBS_UTILS_MISC_H

#include <sys/time.h>
#include "utils/Endian.h"

namespace android {

/* get #of elements in a static array */
#ifndef NELEM
# define NELEM(x) ((int) (sizeof(x) / sizeof((x)[0])))
#endif

/*
 * Make a copy of the string, using "new[]" instead of "malloc".  Free the
 * string with delete[].
 *
 * Returns NULL if "str" is NULL.
 */
char* strdupNew(const char* str);

/*
 * Concatenate an argument vector into a single string.  If argc is >= 0
 * it will be used; if it's < 0 then the last element in the arg vector
 * must be NULL.
 *
 * This inserts a space between each argument.
 *
 * This does not automatically add double quotes around arguments with
 * spaces in them.  This practice is necessary for Win32, because Win32's
 * CreateProcess call is stupid.
 *
 * The caller should delete[] the returned string.
 */
char* concatArgv(int argc, const char* const argv[]);

/*
 * Count up the number of arguments in "argv".  The count does not include
 * the final NULL entry.
 */
int countArgv(const char* const argv[]);

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

/*
 * Round up to the nearest power of 2.  Handy for hash tables.
 */
unsigned int roundUpPower2(unsigned int val);

void strreverse(char* begin, char* end);
void k_itoa(int value, char* str, int base);
char* itoa(int val, int base);

}; // namespace android

#endif // _LIBS_UTILS_MISC_H
