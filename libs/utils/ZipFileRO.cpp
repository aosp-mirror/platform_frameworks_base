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

//
// Read-only access to Zip archives, with minimal heap allocation.
//
#define LOG_TAG "zipro"
//#define LOG_NDEBUG 0
#include <utils/ZipFileRO.h>
#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/threads.h>

#include <zlib.h>

#include <string.h>
#include <fcntl.h>
#include <errno.h>
#include <assert.h>
#include <unistd.h>

#if HAVE_PRINTF_ZD
#  define ZD "%zd"
#  define ZD_TYPE ssize_t
#else
#  define ZD "%ld"
#  define ZD_TYPE long
#endif

/*
 * We must open binary files using open(path, ... | O_BINARY) under Windows.
 * Otherwise strange read errors will happen.
 */
#ifndef O_BINARY
#  define O_BINARY  0
#endif

/*
 * TEMP_FAILURE_RETRY is defined by some, but not all, versions of
 * <unistd.h>. (Alas, it is not as standard as we'd hoped!) So, if it's
 * not already defined, then define it here.
 */
#ifndef TEMP_FAILURE_RETRY
/* Used to retry syscalls that can return EINTR. */
#define TEMP_FAILURE_RETRY(exp) ({         \
    typeof (exp) _rc;                      \
    do {                                   \
        _rc = (exp);                       \
    } while (_rc == -1 && errno == EINTR); \
    _rc; })
#endif

using namespace android;

/*
 * Zip file constants.
 */
#define kEOCDSignature      0x06054b50
#define kEOCDLen            22
#define kEOCDNumEntries     8               // offset to #of entries in file
#define kEOCDSize           12              // size of the central directory
#define kEOCDFileOffset     16              // offset to central directory

#define kMaxCommentLen      65535           // longest possible in ushort
#define kMaxEOCDSearch      (kMaxCommentLen + kEOCDLen)

#define kLFHSignature       0x04034b50
#define kLFHLen             30              // excluding variable-len fields
#define kLFHNameLen         26              // offset to filename length
#define kLFHExtraLen        28              // offset to extra length

#define kCDESignature       0x02014b50
#define kCDELen             46              // excluding variable-len fields
#define kCDEMethod          10              // offset to compression method
#define kCDEModWhen         12              // offset to modification timestamp
#define kCDECRC             16              // offset to entry CRC
#define kCDECompLen         20              // offset to compressed length
#define kCDEUncompLen       24              // offset to uncompressed length
#define kCDENameLen         28              // offset to filename length
#define kCDEExtraLen        30              // offset to extra length
#define kCDECommentLen      32              // offset to comment length
#define kCDELocalOffset     42              // offset to local hdr

/*
 * The values we return for ZipEntryRO use 0 as an invalid value, so we
 * want to adjust the hash table index by a fixed amount.  Using a large
 * value helps insure that people don't mix & match arguments, e.g. to
 * findEntryByIndex().
 */
#define kZipEntryAdj        10000

ZipFileRO::~ZipFileRO() {
    free(mHashTable);
    if (mDirectoryMap)
        mDirectoryMap->release();
    if (mFd >= 0)
        TEMP_FAILURE_RETRY(close(mFd));
    if (mFileName)
        free(mFileName);
}

/*
 * Convert a ZipEntryRO to a hash table index, verifying that it's in a
 * valid range.
 */
int ZipFileRO::entryToIndex(const ZipEntryRO entry) const
{
    long ent = ((long) entry) - kZipEntryAdj;
    if (ent < 0 || ent >= mHashTableSize || mHashTable[ent].name == NULL) {
        LOGW("Invalid ZipEntryRO %p (%ld)\n", entry, ent);
        return -1;
    }
    return ent;
}


/*
 * Open the specified file read-only.  We memory-map the entire thing and
 * close the file before returning.
 */
status_t ZipFileRO::open(const char* zipFileName)
{
    int fd = -1;

    assert(mDirectoryMap == NULL);

    /*
     * Open and map the specified file.
     */
    fd = ::open(zipFileName, O_RDONLY | O_BINARY);
    if (fd < 0) {
        LOGW("Unable to open zip '%s': %s\n", zipFileName, strerror(errno));
        return NAME_NOT_FOUND;
    }

    mFileLength = lseek(fd, 0, SEEK_END);
    if (mFileLength < kEOCDLen) {
        TEMP_FAILURE_RETRY(close(fd));
        return UNKNOWN_ERROR;
    }

    if (mFileName != NULL) {
        free(mFileName);
    }
    mFileName = strdup(zipFileName);

    mFd = fd;

    /*
     * Find the Central Directory and store its size and number of entries.
     */
    if (!mapCentralDirectory()) {
        goto bail;
    }

    /*
     * Verify Central Directory and create data structures for fast access.
     */
    if (!parseZipArchive()) {
        goto bail;
    }

    return OK;

bail:
    free(mFileName);
    mFileName = NULL;
    TEMP_FAILURE_RETRY(close(fd));
    return UNKNOWN_ERROR;
}

/*
 * Parse the Zip archive, verifying its contents and initializing internal
 * data structures.
 */
bool ZipFileRO::mapCentralDirectory(void)
{
    ssize_t readAmount = kMaxEOCDSearch;
    if (readAmount > (ssize_t) mFileLength)
        readAmount = mFileLength;

    unsigned char* scanBuf = (unsigned char*) malloc(readAmount);
    if (scanBuf == NULL) {
        LOGW("couldn't allocate scanBuf: %s", strerror(errno));
        free(scanBuf);
        return false;
    }

    /*
     * Make sure this is a Zip archive.
     */
    if (lseek(mFd, 0, SEEK_SET) != 0) {
        LOGW("seek to start failed: %s", strerror(errno));
        free(scanBuf);
        return false;
    }

    ssize_t actual = TEMP_FAILURE_RETRY(read(mFd, scanBuf, sizeof(int32_t)));
    if (actual != (ssize_t) sizeof(int32_t)) {
        LOGI("couldn't read first signature from zip archive: %s", strerror(errno));
        free(scanBuf);
        return false;
    }

    {
        unsigned int header = get4LE(scanBuf);
        if (header == kEOCDSignature) {
            LOGI("Found Zip archive, but it looks empty\n");
            free(scanBuf);
            return false;
        } else if (header != kLFHSignature) {
            LOGV("Not a Zip archive (found 0x%08x)\n", header);
            free(scanBuf);
            return false;
        }
    }

    /*
     * Perform the traditional EOCD snipe hunt.
     *
     * We're searching for the End of Central Directory magic number,
     * which appears at the start of the EOCD block.  It's followed by
     * 18 bytes of EOCD stuff and up to 64KB of archive comment.  We
     * need to read the last part of the file into a buffer, dig through
     * it to find the magic number, parse some values out, and use those
     * to determine the extent of the CD.
     *
     * We start by pulling in the last part of the file.
     */
    off_t searchStart = mFileLength - readAmount;

    if (lseek(mFd, searchStart, SEEK_SET) != searchStart) {
        LOGW("seek %ld failed: %s\n",  (long) searchStart, strerror(errno));
        free(scanBuf);
        return false;
    }
    actual = TEMP_FAILURE_RETRY(read(mFd, scanBuf, readAmount));
    if (actual != (ssize_t) readAmount) {
        LOGW("Zip: read " ZD ", expected " ZD ". Failed: %s\n",
            (ZD_TYPE) actual, (ZD_TYPE) readAmount, strerror(errno));
        free(scanBuf);
        return false;
    }

    /*
     * Scan backward for the EOCD magic.  In an archive without a trailing
     * comment, we'll find it on the first try.  (We may want to consider
     * doing an initial minimal read; if we don't find it, retry with a
     * second read as above.)
     */
    int i;
    for (i = readAmount - kEOCDLen; i >= 0; i--) {
        if (scanBuf[i] == 0x50 && get4LE(&scanBuf[i]) == kEOCDSignature) {
            LOGV("+++ Found EOCD at buf+%d\n", i);
            break;
        }
    }
    if (i < 0) {
        LOGD("Zip: EOCD not found, %s is not zip\n", mFileName);
        free(scanBuf);
        return false;
    }

    off_t eocdOffset = searchStart + i;
    const unsigned char* eocdPtr = scanBuf + i;

    assert(eocdOffset < mFileLength);

    /*
     * Grab the CD offset and size, and the number of entries in the
     * archive. After that, we can release our EOCD hunt buffer.
     */
    unsigned int numEntries = get2LE(eocdPtr + kEOCDNumEntries);
    unsigned int dirSize = get4LE(eocdPtr + kEOCDSize);
    unsigned int dirOffset = get4LE(eocdPtr + kEOCDFileOffset);
    free(scanBuf);

    // Verify that they look reasonable.
    if ((long long) dirOffset + (long long) dirSize > (long long) eocdOffset) {
        LOGW("bad offsets (dir %ld, size %u, eocd %ld)\n",
            (long) dirOffset, dirSize, (long) eocdOffset);
        return false;
    }
    if (numEntries == 0) {
        LOGW("empty archive?\n");
        return false;
    }

    LOGV("+++ numEntries=%d dirSize=%d dirOffset=%d\n",
        numEntries, dirSize, dirOffset);

    mDirectoryMap = new FileMap();
    if (mDirectoryMap == NULL) {
        LOGW("Unable to create directory map: %s", strerror(errno));
        return false;
    }

    if (!mDirectoryMap->create(mFileName, mFd, dirOffset, dirSize, true)) {
        LOGW("Unable to map '%s' (" ZD " to " ZD "): %s\n", mFileName,
                (ZD_TYPE) dirOffset, (ZD_TYPE) (dirOffset + dirSize), strerror(errno));
        return false;
    }

    mNumEntries = numEntries;
    mDirectoryOffset = dirOffset;

    return true;
}

bool ZipFileRO::parseZipArchive(void)
{
    bool result = false;
    const unsigned char* cdPtr = (const unsigned char*) mDirectoryMap->getDataPtr();
    size_t cdLength = mDirectoryMap->getDataLength();
    int numEntries = mNumEntries;

    /*
     * Create hash table.  We have a minimum 75% load factor, possibly as
     * low as 50% after we round off to a power of 2.
     */
    mHashTableSize = roundUpPower2(1 + (numEntries * 4) / 3);
    mHashTable = (HashEntry*) calloc(mHashTableSize, sizeof(HashEntry));

    /*
     * Walk through the central directory, adding entries to the hash
     * table.
     */
    const unsigned char* ptr = cdPtr;
    for (int i = 0; i < numEntries; i++) {
        if (get4LE(ptr) != kCDESignature) {
            LOGW("Missed a central dir sig (at %d)\n", i);
            goto bail;
        }
        if (ptr + kCDELen > cdPtr + cdLength) {
            LOGW("Ran off the end (at %d)\n", i);
            goto bail;
        }

        long localHdrOffset = (long) get4LE(ptr + kCDELocalOffset);
        if (localHdrOffset >= mDirectoryOffset) {
            LOGW("bad LFH offset %ld at entry %d\n", localHdrOffset, i);
            goto bail;
        }

        unsigned int fileNameLen, extraLen, commentLen, hash;

        fileNameLen = get2LE(ptr + kCDENameLen);
        extraLen = get2LE(ptr + kCDEExtraLen);
        commentLen = get2LE(ptr + kCDECommentLen);

        /* add the CDE filename to the hash table */
        hash = computeHash((const char*)ptr + kCDELen, fileNameLen);
        addToHash((const char*)ptr + kCDELen, fileNameLen, hash);

        ptr += kCDELen + fileNameLen + extraLen + commentLen;
        if ((size_t)(ptr - cdPtr) > cdLength) {
            LOGW("bad CD advance (%d vs " ZD ") at entry %d\n",
                (int) (ptr - cdPtr), (ZD_TYPE) cdLength, i);
            goto bail;
        }
    }
    LOGV("+++ zip good scan %d entries\n", numEntries);
    result = true;

bail:
    return result;
}

/*
 * Simple string hash function for non-null-terminated strings.
 */
/*static*/ unsigned int ZipFileRO::computeHash(const char* str, int len)
{
    unsigned int hash = 0;

    while (len--)
        hash = hash * 31 + *str++;

    return hash;
}

/*
 * Add a new entry to the hash table.
 */
void ZipFileRO::addToHash(const char* str, int strLen, unsigned int hash)
{
    int ent = hash & (mHashTableSize-1);

    /*
     * We over-allocate the table, so we're guaranteed to find an empty slot.
     */
    while (mHashTable[ent].name != NULL)
        ent = (ent + 1) & (mHashTableSize-1);

    mHashTable[ent].name = str;
    mHashTable[ent].nameLen = strLen;
}

/*
 * Find a matching entry.
 *
 * Returns NULL if not found.
 */
ZipEntryRO ZipFileRO::findEntryByName(const char* fileName) const
{
    /*
     * If the ZipFileRO instance is not initialized, the entry number will
     * end up being garbage since mHashTableSize is -1.
     */
    if (mHashTableSize <= 0) {
        return NULL;
    }

    int nameLen = strlen(fileName);
    unsigned int hash = computeHash(fileName, nameLen);
    int ent = hash & (mHashTableSize-1);

    while (mHashTable[ent].name != NULL) {
        if (mHashTable[ent].nameLen == nameLen &&
            memcmp(mHashTable[ent].name, fileName, nameLen) == 0)
        {
            /* match */
            return (ZipEntryRO)(long)(ent + kZipEntryAdj);
        }

        ent = (ent + 1) & (mHashTableSize-1);
    }

    return NULL;
}

/*
 * Find the Nth entry.
 *
 * This currently involves walking through the sparse hash table, counting
 * non-empty entries.  If we need to speed this up we can either allocate
 * a parallel lookup table or (perhaps better) provide an iterator interface.
 */
ZipEntryRO ZipFileRO::findEntryByIndex(int idx) const
{
    if (idx < 0 || idx >= mNumEntries) {
        LOGW("Invalid index %d\n", idx);
        return NULL;
    }

    for (int ent = 0; ent < mHashTableSize; ent++) {
        if (mHashTable[ent].name != NULL) {
            if (idx-- == 0)
                return (ZipEntryRO) (ent + kZipEntryAdj);
        }
    }

    return NULL;
}

/*
 * Get the useful fields from the zip entry.
 *
 * Returns "false" if the offsets to the fields or the contents of the fields
 * appear to be bogus.
 */
bool ZipFileRO::getEntryInfo(ZipEntryRO entry, int* pMethod, size_t* pUncompLen,
    size_t* pCompLen, off_t* pOffset, long* pModWhen, long* pCrc32) const
{
    bool ret = false;

    const int ent = entryToIndex(entry);
    if (ent < 0)
        return false;

    HashEntry hashEntry = mHashTable[ent];

    /*
     * Recover the start of the central directory entry from the filename
     * pointer.  The filename is the first entry past the fixed-size data,
     * so we can just subtract back from that.
     */
    const unsigned char* ptr = (const unsigned char*) hashEntry.name;
    off_t cdOffset = mDirectoryOffset;

    ptr -= kCDELen;

    int method = get2LE(ptr + kCDEMethod);
    if (pMethod != NULL)
        *pMethod = method;

    if (pModWhen != NULL)
        *pModWhen = get4LE(ptr + kCDEModWhen);
    if (pCrc32 != NULL)
        *pCrc32 = get4LE(ptr + kCDECRC);

    size_t compLen = get4LE(ptr + kCDECompLen);
    if (pCompLen != NULL)
        *pCompLen = compLen;
    size_t uncompLen = get4LE(ptr + kCDEUncompLen);
    if (pUncompLen != NULL)
        *pUncompLen = uncompLen;

    /*
     * If requested, determine the offset of the start of the data.  All we
     * have is the offset to the Local File Header, which is variable size,
     * so we have to read the contents of the struct to figure out where
     * the actual data starts.
     *
     * We also need to make sure that the lengths are not so large that
     * somebody trying to map the compressed or uncompressed data runs
     * off the end of the mapped region.
     *
     * Note we don't verify compLen/uncompLen if they don't request the
     * dataOffset, because dataOffset is expensive to determine.  However,
     * if they don't have the file offset, they're not likely to be doing
     * anything with the contents.
     */
    if (pOffset != NULL) {
        long localHdrOffset = get4LE(ptr + kCDELocalOffset);
        if (localHdrOffset + kLFHLen >= cdOffset) {
            LOGE("ERROR: bad local hdr offset in zip\n");
            return false;
        }

        unsigned char lfhBuf[kLFHLen];

#ifdef HAVE_PREAD
        /*
         * This file descriptor might be from zygote's preloaded assets,
         * so we need to do an pread() instead of a lseek() + read() to
         * guarantee atomicity across the processes with the shared file
         * descriptors.
         */
        ssize_t actual =
                TEMP_FAILURE_RETRY(pread(mFd, lfhBuf, sizeof(lfhBuf), localHdrOffset));

        if (actual != sizeof(lfhBuf)) {
            LOGW("failed reading lfh from offset %ld\n", localHdrOffset);
            return false;
        }

        if (get4LE(lfhBuf) != kLFHSignature) {
            LOGW("didn't find signature at start of lfh; wanted: offset=%ld data=0x%08x; "
                    "got: data=0x%08lx\n",
                    localHdrOffset, kLFHSignature, get4LE(lfhBuf));
            return false;
        }
#else /* HAVE_PREAD */
        /*
         * For hosts don't have pread() we cannot guarantee atomic reads from
         * an offset in a file. Android should never run on those platforms.
         * File descriptors inherited from a fork() share file offsets and
         * there would be nothing to protect from two different processes
         * calling lseek() concurrently.
         */

        {
            AutoMutex _l(mFdLock);

            if (lseek(mFd, localHdrOffset, SEEK_SET) != localHdrOffset) {
                LOGW("failed seeking to lfh at offset %ld\n", localHdrOffset);
                return false;
            }

            ssize_t actual =
                    TEMP_FAILURE_RETRY(read(mFd, lfhBuf, sizeof(lfhBuf)));
            if (actual != sizeof(lfhBuf)) {
                LOGW("failed reading lfh from offset %ld\n", localHdrOffset);
                return false;
            }

            if (get4LE(lfhBuf) != kLFHSignature) {
                off_t actualOffset = lseek(mFd, 0, SEEK_CUR);
                LOGW("didn't find signature at start of lfh; wanted: offset=%ld data=0x%08x; "
                        "got: offset=" ZD " data=0x%08lx\n",
                        localHdrOffset, kLFHSignature, (ZD_TYPE) actualOffset, get4LE(lfhBuf));
                return false;
            }
        }
#endif /* HAVE_PREAD */

        off_t dataOffset = localHdrOffset + kLFHLen
            + get2LE(lfhBuf + kLFHNameLen) + get2LE(lfhBuf + kLFHExtraLen);
        if (dataOffset >= cdOffset) {
            LOGW("bad data offset %ld in zip\n", (long) dataOffset);
            return false;
        }

        /* check lengths */
        if ((off_t)(dataOffset + compLen) > cdOffset) {
            LOGW("bad compressed length in zip (%ld + " ZD " > %ld)\n",
                (long) dataOffset, (ZD_TYPE) compLen, (long) cdOffset);
            return false;
        }

        if (method == kCompressStored &&
            (off_t)(dataOffset + uncompLen) > cdOffset)
        {
            LOGE("ERROR: bad uncompressed length in zip (%ld + " ZD " > %ld)\n",
                (long) dataOffset, (ZD_TYPE) uncompLen, (long) cdOffset);
            return false;
        }

        *pOffset = dataOffset;
    }

    return true;
}

/*
 * Copy the entry's filename to the buffer.
 */
int ZipFileRO::getEntryFileName(ZipEntryRO entry, char* buffer, int bufLen)
    const
{
    int ent = entryToIndex(entry);
    if (ent < 0)
        return -1;

    int nameLen = mHashTable[ent].nameLen;
    if (bufLen < nameLen+1)
        return nameLen+1;

    memcpy(buffer, mHashTable[ent].name, nameLen);
    buffer[nameLen] = '\0';
    return 0;
}

/*
 * Create a new FileMap object that spans the data in "entry".
 */
FileMap* ZipFileRO::createEntryFileMap(ZipEntryRO entry) const
{
    /*
     * TODO: the efficient way to do this is to modify FileMap to allow
     * sub-regions of a file to be mapped.  A reference-counting scheme
     * can manage the base memory mapping.  For now, we just create a brand
     * new mapping off of the Zip archive file descriptor.
     */

    FileMap* newMap;
    size_t compLen;
    off_t offset;

    if (!getEntryInfo(entry, NULL, NULL, &compLen, &offset, NULL, NULL))
        return NULL;

    newMap = new FileMap();
    if (!newMap->create(mFileName, mFd, offset, compLen, true)) {
        newMap->release();
        return NULL;
    }

    return newMap;
}

/*
 * Uncompress an entry, in its entirety, into the provided output buffer.
 *
 * This doesn't verify the data's CRC, which might be useful for
 * uncompressed data.  The caller should be able to manage it.
 */
bool ZipFileRO::uncompressEntry(ZipEntryRO entry, void* buffer) const
{
    const size_t kSequentialMin = 32768;
    bool result = false;
    int ent = entryToIndex(entry);
    if (ent < 0)
        return -1;

    int method;
    size_t uncompLen, compLen;
    off_t offset;
    const unsigned char* ptr;

    getEntryInfo(entry, &method, &uncompLen, &compLen, &offset, NULL, NULL);

    FileMap* file = createEntryFileMap(entry);
    if (file == NULL) {
        goto bail;
    }

    ptr = (const unsigned char*) file->getDataPtr();

    /*
     * Experiment with madvise hint.  When we want to uncompress a file,
     * we pull some stuff out of the central dir entry and then hit a
     * bunch of compressed or uncompressed data sequentially.  The CDE
     * visit will cause a limited amount of read-ahead because it's at
     * the end of the file.  We could end up doing lots of extra disk
     * access if the file we're prying open is small.  Bottom line is we
     * probably don't want to turn MADV_SEQUENTIAL on and leave it on.
     *
     * So, if the compressed size of the file is above a certain minimum
     * size, temporarily boost the read-ahead in the hope that the extra
     * pair of system calls are negated by a reduction in page faults.
     */
    if (compLen > kSequentialMin)
        file->advise(FileMap::SEQUENTIAL);

    if (method == kCompressStored) {
        memcpy(buffer, ptr, uncompLen);
    } else {
        if (!inflateBuffer(buffer, ptr, uncompLen, compLen))
            goto unmap;
    }

    if (compLen > kSequentialMin)
        file->advise(FileMap::NORMAL);

    result = true;

unmap:
    file->release();
bail:
    return result;
}

/*
 * Uncompress an entry, in its entirety, to an open file descriptor.
 *
 * This doesn't verify the data's CRC, but probably should.
 */
bool ZipFileRO::uncompressEntry(ZipEntryRO entry, int fd) const
{
    bool result = false;
    int ent = entryToIndex(entry);
    if (ent < 0)
        return -1;

    int method;
    size_t uncompLen, compLen;
    off_t offset;
    const unsigned char* ptr;

    getEntryInfo(entry, &method, &uncompLen, &compLen, &offset, NULL, NULL);

    FileMap* file = createEntryFileMap(entry);
    if (file == NULL) {
        goto bail;
    }

    ptr = (const unsigned char*) file->getDataPtr();

    if (method == kCompressStored) {
        ssize_t actual = write(fd, ptr, uncompLen);
        if (actual < 0) {
            LOGE("Write failed: %s\n", strerror(errno));
            goto unmap;
        } else if ((size_t) actual != uncompLen) {
            LOGE("Partial write during uncompress (" ZD " of " ZD ")\n",
                (ZD_TYPE) actual, (ZD_TYPE) uncompLen);
            goto unmap;
        } else {
            LOGI("+++ successful write\n");
        }
    } else {
        if (!inflateBuffer(fd, ptr, uncompLen, compLen))
            goto unmap;
    }

    result = true;

unmap:
    file->release();
bail:
    return result;
}

/*
 * Uncompress "deflate" data from one buffer to another.
 */
/*static*/ bool ZipFileRO::inflateBuffer(void* outBuf, const void* inBuf,
    size_t uncompLen, size_t compLen)
{
    bool result = false;
    z_stream zstream;
    int zerr;

    /*
     * Initialize the zlib stream struct.
     */
    memset(&zstream, 0, sizeof(zstream));
    zstream.zalloc = Z_NULL;
    zstream.zfree = Z_NULL;
    zstream.opaque = Z_NULL;
    zstream.next_in = (Bytef*)inBuf;
    zstream.avail_in = compLen;
    zstream.next_out = (Bytef*) outBuf;
    zstream.avail_out = uncompLen;
    zstream.data_type = Z_UNKNOWN;

    /*
     * Use the undocumented "negative window bits" feature to tell zlib
     * that there's no zlib header waiting for it.
     */
    zerr = inflateInit2(&zstream, -MAX_WBITS);
    if (zerr != Z_OK) {
        if (zerr == Z_VERSION_ERROR) {
            LOGE("Installed zlib is not compatible with linked version (%s)\n",
                ZLIB_VERSION);
        } else {
            LOGE("Call to inflateInit2 failed (zerr=%d)\n", zerr);
        }
        goto bail;
    }

    /*
     * Expand data.
     */
    zerr = inflate(&zstream, Z_FINISH);
    if (zerr != Z_STREAM_END) {
        LOGW("Zip inflate failed, zerr=%d (nIn=%p aIn=%u nOut=%p aOut=%u)\n",
            zerr, zstream.next_in, zstream.avail_in,
            zstream.next_out, zstream.avail_out);
        goto z_bail;
    }

    /* paranoia */
    if (zstream.total_out != uncompLen) {
        LOGW("Size mismatch on inflated file (%ld vs " ZD ")\n",
            zstream.total_out, (ZD_TYPE) uncompLen);
        goto z_bail;
    }

    result = true;

z_bail:
    inflateEnd(&zstream);        /* free up any allocated structures */

bail:
    return result;
}

/*
 * Uncompress "deflate" data from one buffer to an open file descriptor.
 */
/*static*/ bool ZipFileRO::inflateBuffer(int fd, const void* inBuf,
    size_t uncompLen, size_t compLen)
{
    bool result = false;
    const size_t kWriteBufSize = 32768;
    unsigned char writeBuf[kWriteBufSize];
    z_stream zstream;
    int zerr;

    /*
     * Initialize the zlib stream struct.
     */
    memset(&zstream, 0, sizeof(zstream));
    zstream.zalloc = Z_NULL;
    zstream.zfree = Z_NULL;
    zstream.opaque = Z_NULL;
    zstream.next_in = (Bytef*)inBuf;
    zstream.avail_in = compLen;
    zstream.next_out = (Bytef*) writeBuf;
    zstream.avail_out = sizeof(writeBuf);
    zstream.data_type = Z_UNKNOWN;

    /*
     * Use the undocumented "negative window bits" feature to tell zlib
     * that there's no zlib header waiting for it.
     */
    zerr = inflateInit2(&zstream, -MAX_WBITS);
    if (zerr != Z_OK) {
        if (zerr == Z_VERSION_ERROR) {
            LOGE("Installed zlib is not compatible with linked version (%s)\n",
                ZLIB_VERSION);
        } else {
            LOGE("Call to inflateInit2 failed (zerr=%d)\n", zerr);
        }
        goto bail;
    }

    /*
     * Loop while we have more to do.
     */
    do {
        /*
         * Expand data.
         */
        zerr = inflate(&zstream, Z_NO_FLUSH);
        if (zerr != Z_OK && zerr != Z_STREAM_END) {
            LOGW("zlib inflate: zerr=%d (nIn=%p aIn=%u nOut=%p aOut=%u)\n",
                zerr, zstream.next_in, zstream.avail_in,
                zstream.next_out, zstream.avail_out);
            goto z_bail;
        }

        /* write when we're full or when we're done */
        if (zstream.avail_out == 0 ||
            (zerr == Z_STREAM_END && zstream.avail_out != sizeof(writeBuf)))
        {
            long writeSize = zstream.next_out - writeBuf;
            int cc = write(fd, writeBuf, writeSize);
            if (cc != (int) writeSize) {
                LOGW("write failed in inflate (%d vs %ld)\n", cc, writeSize);
                goto z_bail;
            }

            zstream.next_out = writeBuf;
            zstream.avail_out = sizeof(writeBuf);
        }
    } while (zerr == Z_OK);

    assert(zerr == Z_STREAM_END);       /* other errors should've been caught */

    /* paranoia */
    if (zstream.total_out != uncompLen) {
        LOGW("Size mismatch on inflated file (%ld vs " ZD ")\n",
            zstream.total_out, (ZD_TYPE) uncompLen);
        goto z_bail;
    }

    result = true;

z_bail:
    inflateEnd(&zstream);        /* free up any allocated structures */

bail:
    return result;
}
