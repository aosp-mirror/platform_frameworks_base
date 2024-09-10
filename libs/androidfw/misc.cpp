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

#include "android-base/logging.h"

#ifdef __linux__
#include <sys/statvfs.h>
#include <sys/vfs.h>
#endif  // __linux__

#include <cstring>
#include <cstdio>
#include <errno.h>
#include <sys/stat.h>

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
            PLOG(ERROR) << "getFileType(): stat(" << fileName << ") failed";
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
time_t getFileModDate(const char* fileName) {
    struct stat sb;
    if (stat(fileName, &sb) < 0) {
        return (time_t)-1;
    }
    return sb.st_mtime;
}

time_t getFileModDate(int fd) {
    struct stat sb;
    if (fstat(fd, &sb) < 0) {
        return (time_t)-1;
    }
    if (sb.st_nlink <= 0) {
        errno = ENOENT;
        return (time_t)-1;
    }
    return sb.st_mtime;
}

#ifndef __linux__
// No need to implement these on the host, the functions only matter on a device.
bool isReadonlyFilesystem(const char*) {
    return false;
}
bool isReadonlyFilesystem(int) {
    return false;
}
#else   // __linux__
bool isReadonlyFilesystem(const char* path) {
    struct statfs sfs;
    if (::statfs(path, &sfs)) {
        PLOG(ERROR) << "isReadonlyFilesystem(): statfs(" << path << ") failed";
        return false;
    }
    return (sfs.f_flags & ST_RDONLY) != 0;
}

bool isReadonlyFilesystem(int fd) {
    struct statfs sfs;
    if (::fstatfs(fd, &sfs)) {
        PLOG(ERROR) << "isReadonlyFilesystem(): fstatfs(" << fd << ") failed";
        return false;
    }
    return (sfs.f_flags & ST_RDONLY) != 0;
}
#endif  // __linux__

}; // namespace android
