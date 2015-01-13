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

#define LOG_TAG "misc"

//
// Miscellaneous utility functions.
//
#include <androidfw/misc.h>

#include <sys/stat.h>
#include <cstring>
#include <errno.h>
#include <cstdio>

using namespace android;

namespace android {

/*
 * Get a file's type.
 */
FileType getFileType(const char* fileName)
{
    struct stat sb;

    if (stat(fileName, &sb) < 0) {
        if (errno == ENOENT || errno == ENOTDIR)
            return kFileTypeNonexistent;
        else {
            fprintf(stderr, "getFileType got errno=%d on '%s'\n",
                errno, fileName);
            return kFileTypeUnknown;
        }
    } else {
        if (S_ISREG(sb.st_mode))
            return kFileTypeRegular;
        else if (S_ISDIR(sb.st_mode))
            return kFileTypeDirectory;
        else if (S_ISCHR(sb.st_mode))
            return kFileTypeCharDev;
        else if (S_ISBLK(sb.st_mode))
            return kFileTypeBlockDev;
        else if (S_ISFIFO(sb.st_mode))
            return kFileTypeFifo;
#if defined(S_ISLNK)
        else if (S_ISLNK(sb.st_mode))
            return kFileTypeSymlink;
#endif
#if defined(S_ISSOCK)
        else if (S_ISSOCK(sb.st_mode))
            return kFileTypeSocket;
#endif
        else
            return kFileTypeUnknown;
    }
}

/*
 * Get a file's modification date.
 */
time_t getFileModDate(const char* fileName)
{
    struct stat sb;

    if (stat(fileName, &sb) < 0)
        return (time_t) -1;

    return sb.st_mtime;
}

}; // namespace android
