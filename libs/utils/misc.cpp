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
// Miscellaneous utility functions.
//
#include <utils/misc.h>

#include <sys/stat.h>
#include <string.h>
#include <errno.h>
#include <assert.h>
#include <stdio.h>

using namespace android;

namespace android {

/*
 * Like strdup(), but uses C++ "new" operator instead of malloc.
 */
char* strdupNew(const char* str)
{
    char* newStr;
    int len;

    if (str == NULL)
        return NULL;

    len = strlen(str);
    newStr = new char[len+1];
    memcpy(newStr, str, len+1);

    return newStr;
}

/*
 * Concatenate an argument vector.
 */
char* concatArgv(int argc, const char* const argv[])
{
    char* newStr = NULL;
    int len, totalLen, posn, idx;

    /*
     * First, figure out the total length.
     */
    totalLen = idx = 0;
    while (1) {
        if (idx == argc || argv[idx] == NULL)
            break;
        if (idx)
            totalLen++;  // leave a space between args
        totalLen += strlen(argv[idx]);
        idx++;
    }

    /*
     * Alloc the string.
     */
    newStr = new char[totalLen +1];
    if (newStr == NULL)
        return NULL;

    /*
     * Finally, allocate the string and copy data over.
     */
    idx = posn = 0;
    while (1) {
        if (idx == argc || argv[idx] == NULL)
            break;
        if (idx)
            newStr[posn++] = ' ';

        len = strlen(argv[idx]);
        memcpy(&newStr[posn], argv[idx], len);
        posn += len;

        idx++;
    }

    assert(posn == totalLen);
    newStr[posn] = '\0';

    return newStr;
}

/*
 * Count the #of args in an argument vector.  Don't count the final NULL.
 */
int countArgv(const char* const argv[])
{
    int count = 0;

    while (argv[count] != NULL)
        count++;

    return count;
}


#include <stdio.h>
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
#ifdef HAVE_SYMLINKS            
        else if (S_ISLNK(sb.st_mode))
            return kFileTypeSymlink;
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

/*
 * Round up to the next highest power of 2.
 *
 * Found on http://graphics.stanford.edu/~seander/bithacks.html.
 */
unsigned int roundUpPower2(unsigned int val)
{
    val--;
    val |= val >> 1;
    val |= val >> 2;
    val |= val >> 4;
    val |= val >> 8;
    val |= val >> 16;
    val++;

    return val;
}

}; // namespace android

