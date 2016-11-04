/*
 * Copyright (C) 2007 The Android Open Source Project
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
 * Read-only access to Zip archives, with minimal heap allocation.
 *
 * This is similar to the more-complete ZipFile class, but no attempt
 * has been made to make them interchangeable.  This class operates under
 * a very different set of assumptions and constraints.
 *
 * One such assumption is that if you're getting file descriptors for
 * use with this class as a child of a fork() operation, you must be on
 * a pread() to guarantee correct operation. This is because pread() can
 * atomically read at a file offset without worrying about a lock around an
 * lseek() + read() pair.
 */
#ifndef __LIBS_ZIPFILERO_H
#define __LIBS_ZIPFILERO_H

#include <utils/Compat.h>
#include <utils/Errors.h>
#include <utils/FileMap.h>
#include <utils/threads.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>

typedef void* ZipArchiveHandle;

namespace android {

/*
 * Trivial typedef to ensure that ZipEntryRO is not treated as a simple
 * integer.  We use NULL to indicate an invalid value.
 */
typedef void* ZipEntryRO;

/*
 * Open a Zip archive for reading.
 *
 * Implemented as a thin wrapper over system/core/libziparchive.
 *
 * "open" and "find entry by name" are fast operations and use as little
 * memory as possible.
 *
 * We also support fast iteration over all entries in the file (with a
 * stable, but unspecified iteration order).
 *
 * NOTE: If this is used on file descriptors inherited from a fork() operation,
 * you must be on a platform that implements pread() to guarantee correctness
 * on the shared file descriptors.
 */
class ZipFileRO {
public:
    /* Zip compression methods we support */
    enum : uint16_t {
        kCompressStored = 0,
        kCompressDeflated = 8
    };

    /*
     * Open an archive.
     */
    static ZipFileRO* open(const char* zipFileName);

    /*
     * Find an entry, by name.  Returns the entry identifier, or NULL if
     * not found.
     */
    ZipEntryRO findEntryByName(const char* entryName) const;


    /*
     * Start iterating over the list of entries in the zip file. Requires
     * a matching call to endIteration with the same cookie.
     */
    bool startIteration(void** cookie);
    bool startIteration(void** cookie, const char* prefix, const char* suffix);

    /**
     * Return the next entry in iteration order, or NULL if there are no more
     * entries in this archive.
     */
    ZipEntryRO nextEntry(void* cookie);

    void endIteration(void* cookie);

    void releaseEntry(ZipEntryRO entry) const;

    /*
     * Return the #of entries in the Zip archive.
     */
    int getNumEntries();

    /*
     * Copy the filename into the supplied buffer.  Returns 0 on success,
     * -1 if "entry" is invalid, or the filename length if it didn't fit. The
     * length, and the returned string, include the null-termination.
     */
    int getEntryFileName(ZipEntryRO entry, char* buffer, size_t bufLen) const;

    /*
     * Get the vital stats for an entry.  Pass in NULL pointers for anything
     * you don't need.
     *
     * "*pOffset" holds the Zip file offset of the entry's data.
     *
     * Returns "false" if "entry" is bogus or if the data in the Zip file
     * appears to be bad.
     */
    bool getEntryInfo(ZipEntryRO entry, uint16_t* pMethod, uint32_t* pUncompLen,
        uint32_t* pCompLen, off64_t* pOffset, uint32_t* pModWhen,
        uint32_t* pCrc32) const;

    /*
     * Create a new FileMap object that maps a subset of the archive.  For
     * an uncompressed entry this effectively provides a pointer to the
     * actual data, for a compressed entry this provides the input buffer
     * for inflate().
     */
    FileMap* createEntryFileMap(ZipEntryRO entry) const;

    /*
     * Uncompress the data into a buffer.  Depending on the compression
     * format, this is either an "inflate" operation or a memcpy.
     *
     * Use "uncompLen" from getEntryInfo() to determine the required
     * buffer size.
     *
     * Returns "true" on success.
     */
    bool uncompressEntry(ZipEntryRO entry, void* buffer, size_t size) const;

    /*
     * Uncompress the data to an open file descriptor.
     */
    bool uncompressEntry(ZipEntryRO entry, int fd) const;

    ~ZipFileRO();

private:
    /* these are private and not defined */
    ZipFileRO(const ZipFileRO& src);
    ZipFileRO& operator=(const ZipFileRO& src);

    ZipFileRO(ZipArchiveHandle handle, char* fileName) : mHandle(handle),
        mFileName(fileName)
    {
    }

    const ZipArchiveHandle mHandle;
    char* mFileName;
};

}; // namespace android

#endif /*__LIBS_ZIPFILERO_H*/
