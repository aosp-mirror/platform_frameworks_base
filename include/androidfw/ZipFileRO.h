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

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>

namespace android {

/*
 * Trivial typedef to ensure that ZipEntryRO is not treated as a simple
 * integer.  We use NULL to indicate an invalid value.
 */
typedef void* ZipEntryRO;

/*
 * Open a Zip archive for reading.
 *
 * We want "open" and "find entry by name" to be fast operations, and we
 * want to use as little memory as possible.  We memory-map the file,
 * and load a hash table with pointers to the filenames (which aren't
 * null-terminated).  The other fields are at a fixed offset from the
 * filename, so we don't need to extract those (but we do need to byte-read
 * and endian-swap them every time we want them).
 *
 * To speed comparisons when doing a lookup by name, we could make the mapping
 * "private" (copy-on-write) and null-terminate the filenames after verifying
 * the record structure.  However, this requires a private mapping of
 * every page that the Central Directory touches.  Easier to tuck a copy
 * of the string length into the hash table entry.
 *
 * NOTE: If this is used on file descriptors inherited from a fork() operation,
 * you must be on a platform that implements pread() to guarantee correctness
 * on the shared file descriptors.
 */
class ZipFileRO {
public:
    ZipFileRO()
        : mFd(-1), mFileName(NULL), mFileLength(-1),
          mDirectoryMap(NULL),
          mNumEntries(-1), mDirectoryOffset(-1),
          mHashTableSize(-1), mHashTable(NULL)
        {}

    ~ZipFileRO();

    /*
     * Open an archive.
     */
    status_t open(const char* zipFileName);

    /*
     * Find an entry, by name.  Returns the entry identifier, or NULL if
     * not found.
     *
     * If two entries have the same name, one will be chosen at semi-random.
     */
    ZipEntryRO findEntryByName(const char* fileName) const;

    /*
     * Return the #of entries in the Zip archive.
     */
    int getNumEntries(void) const {
        return mNumEntries;
    }

    /*
     * Return the Nth entry.  Zip file entries are not stored in sorted
     * order, and updated entries may appear at the end, so anyone walking
     * the archive needs to avoid making ordering assumptions.  We take
     * that further by returning the Nth non-empty entry in the hash table
     * rather than the Nth entry in the archive.
     *
     * Valid values are [0..numEntries).
     *
     * [This is currently O(n).  If it needs to be fast we can allocate an
     * additional data structure or provide an iterator interface.]
     */
    ZipEntryRO findEntryByIndex(int idx) const;

    /*
     * Copy the filename into the supplied buffer.  Returns 0 on success,
     * -1 if "entry" is invalid, or the filename length if it didn't fit.  The
     * length, and the returned string, include the null-termination.
     */
    int getEntryFileName(ZipEntryRO entry, char* buffer, int bufLen) const;

    /*
     * Get the vital stats for an entry.  Pass in NULL pointers for anything
     * you don't need.
     *
     * "*pOffset" holds the Zip file offset of the entry's data.
     *
     * Returns "false" if "entry" is bogus or if the data in the Zip file
     * appears to be bad.
     */
    bool getEntryInfo(ZipEntryRO entry, int* pMethod, size_t* pUncompLen,
        size_t* pCompLen, off64_t* pOffset, long* pModWhen, long* pCrc32) const;

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
    bool uncompressEntry(ZipEntryRO entry, void* buffer) const;

    /*
     * Uncompress the data to an open file descriptor.
     */
    bool uncompressEntry(ZipEntryRO entry, int fd) const;

    /* Zip compression methods we support */
    enum {
        kCompressStored     = 0,        // no compression
        kCompressDeflated   = 8,        // standard deflate
    };

    /*
     * Utility function: uncompress deflated data, buffer to buffer.
     */
    static bool inflateBuffer(void* outBuf, const void* inBuf,
        size_t uncompLen, size_t compLen);

    /*
     * Utility function: uncompress deflated data, buffer to fd.
     */
    static bool inflateBuffer(int fd, const void* inBuf,
        size_t uncompLen, size_t compLen);

    /*
     * Utility function to convert ZIP's time format to a timespec struct.
     */
    static inline void zipTimeToTimespec(long when, struct tm* timespec) {
        const long date = when >> 16;
        timespec->tm_year = ((date >> 9) & 0x7F) + 80; // Zip is years since 1980
        timespec->tm_mon = (date >> 5) & 0x0F;
        timespec->tm_mday = date & 0x1F;

        timespec->tm_hour = (when >> 11) & 0x1F;
        timespec->tm_min = (when >> 5) & 0x3F;
        timespec->tm_sec = (when & 0x1F) << 1;
    }

    /*
     * Some basic functions for raw data manipulation.  "LE" means
     * Little Endian.
     */
    static inline unsigned short get2LE(const unsigned char* buf) {
        return buf[0] | (buf[1] << 8);
    }
    static inline unsigned long get4LE(const unsigned char* buf) {
        return buf[0] | (buf[1] << 8) | (buf[2] << 16) | (buf[3] << 24);
    }

private:
    /* these are private and not defined */ 
    ZipFileRO(const ZipFileRO& src);
    ZipFileRO& operator=(const ZipFileRO& src);

    /* locate and parse the central directory */
    bool mapCentralDirectory(void);

    /* parse the archive, prepping internal structures */
    bool parseZipArchive(void);

    /* add a new entry to the hash table */
    void addToHash(const char* str, int strLen, unsigned int hash);

    /* compute string hash code */
    static unsigned int computeHash(const char* str, int len);

    /* convert a ZipEntryRO back to a hash table index */
    int entryToIndex(const ZipEntryRO entry) const;

    /*
     * One entry in the hash table.
     */
    typedef struct HashEntry {
        const char*     name;
        unsigned short  nameLen;
        //unsigned int    hash;
    } HashEntry;

    /* open Zip archive */
    int         mFd;

    /* Lock for handling the file descriptor (seeks, etc) */
    mutable Mutex mFdLock;

    /* zip file name */
    char*       mFileName;

    /* length of file */
    size_t      mFileLength;

    /* mapped file */
    FileMap*    mDirectoryMap;

    /* number of entries in the Zip archive */
    int         mNumEntries;

    /* CD directory offset in the Zip archive */
    off64_t     mDirectoryOffset;

    /*
     * We know how many entries are in the Zip archive, so we have a
     * fixed-size hash table.  We probe for an empty slot.
     */
    int         mHashTableSize;
    HashEntry*  mHashTable;
};

}; // namespace android

#endif /*__LIBS_ZIPFILERO_H*/
