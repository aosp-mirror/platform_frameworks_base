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
#pragma once

#include <sys/stat.h>
#include <time.h>

//
// Handy utility functions and portability code.
//

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

// MinGW doesn't support nanosecond resolution in stat() modification time, and given
// that it only matters on the device it's ok to keep it at a seconds level there.
#ifdef _WIN32
using ModDate = time_t;
inline constexpr ModDate kInvalidModDate = ModDate(-1);
inline constexpr unsigned long long kModDateResolutionNs = 1ull * 1000 * 1000 * 1000;
inline time_t toTimeT(ModDate m) {
  return m;
}
#else
using ModDate = timespec;
inline constexpr ModDate kInvalidModDate = {-1, -1};
inline constexpr unsigned long long kModDateResolutionNs = 1;
inline time_t toTimeT(ModDate m) {
  return m.tv_sec;
}
#endif

/* get the file's modification date; returns kInvalidModDate w/errno set on failure */
ModDate getFileModDate(const char* fileName);
/* same, but also returns -1 if the file has already been deleted */
ModDate getFileModDate(int fd);

// Extract the modification date from the stat structure.
ModDate getModDate(const struct ::stat& st);

// Check if |path| or |fd| resides on a readonly filesystem.
bool isReadonlyFilesystem(const char* path);
bool isReadonlyFilesystem(int fd);

bool isKnownWritablePath(const char* path);

}  // namespace android

// Whoever uses getFileModDate() will need this as well
bool operator==(const timespec& l, const timespec& r);
