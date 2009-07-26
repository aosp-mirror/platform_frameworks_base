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

#include <zlib.h>

#include <string.h>
#include <fcntl.h>
#include <errno.h>
#include <assert.h>

using namespace android;

/*
 * Zip file constants.
 */
#define kEOCDSignature      0x06054b50
#define kEOCDLen            22
#define kEOCDNumEntries     8               // offset to #of entries in file
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
    off_t length;

    assert(mFileMap == NULL);

    /*
     * Open and map the specified file.
     */
    fd = ::open(zipFileName, O_RDONLY);
    if (fd < 0) {
        LOGW("Unable to open zip '%s': %s\n", zipFileName, strerror(errno));
        return NAME_NOT_FOUND;
    }

    length = lseek(fd, 0, SEEK_END);
    if (length < 0) {
        close(fd);
        return UNKNOWN_ERROR;
    }

    mFileMap = new FileMap();
    if (mFileMap == NULL) {
        close(fd);
        return NO_MEMORY;
    }
    if (!mFileMap->create(zipFileName, fd, 0, length, true)) {
        LOGW("Unable to map '%s': %s\n", zipFileName, strerror(errno));
        close(fd);
        return UNKNOWN_ERROR;
    }

    mFd = fd;

    /*
     * Got it mapped, verify it and create data structures for fast access.
     */
    if (!parseZipArchive()) {
        mFileMap->release();
        mFileMap = NULL;
        return UNKNOWN_ERROR;
    }

    return OK;
}

/*
 * Parse the Zip archive, verifying its contents and initializing internal
 * data structures.
 */
bool ZipFileRO::parseZipArchive(void)
{
#define CHECK_OFFSET(_off) {                                                \
        if ((unsigned int) (_off) >= maxOffset) {                           \
            LOGE("ERROR: bad offset %u (max %d): %s\n",                     \
                (unsigned int) (_off), maxOffset, #_off);                   \
            goto bail;                                                      \
        }                                                                   \
    }
    const unsigned char* basePtr = (const unsigned char*)mFileMap->getDataPtr();
    const unsigned char* ptr;
    size_t length = mFileMap->getDataLength();
    bool result = false;
    unsigned int i, numEntries, cdOffset;
    unsigned int val;

    /*
     * The first 4 bytes of the file will either be the local header
     * signature for the first file (kLFHSignature) or, if the archive doesn't
     * have any files in it, the end-of-central-directory signature
     * (kEOCDSignature).
     */
    val = get4LE(basePtr);
    if (val == kEOCDSignature) {
        LOGI("Found Zip archive, but it looks empty\n");
        goto bail;
    } else if (val != kLFHSignature) {
        LOGV("Not a Zip archive (found 0x%08x)\n", val);
        goto bail;
    }

    /*
     * Find the EOCD.  We'll find it immediately unless they have a file
     * comment.
     */
    ptr = basePtr + length - kEOCDLen;

    while (ptr >= basePtr) {
        if (*ptr == (kEOCDSignature & 0xff) && get4LE(ptr) == kEOCDSignature)
            break;
        ptr--;
    }
    if (ptr < basePtr) {
        LOGI("Could not find end-of-central-directory in Zip\n");
        goto bail;
    }

    /*
     * There are two interesting items in the EOCD block: the number of
     * entries in the file, and the file offset of the start of the
     * central directory.
     *
     * (There's actually a count of the #of entries in this file, and for
     * all files which comprise a spanned archive, but for our purposes
     * we're only interested in the current file.  Besides, we expect the
     * two to be equivalent for our stuff.)
     */
    numEntries = get2LE(ptr + kEOCDNumEntries);
    cdOffset = get4LE(ptr + kEOCDFileOffset);

    /* valid offsets are [0,EOCD] */
    unsigned int maxOffset;
    maxOffset = (ptr - basePtr) +1;

    LOGV("+++ numEntries=%d cdOffset=%d\n", numEntries, cdOffset);
    if (numEntries == 0 || cdOffset >= length) {
        LOGW("Invalid entries=%d offset=%d (len=%zd)\n",
            numEntries, cdOffset, length);
        goto bail;
    }

    /*
     * Create hash table.  We have a minimum 75% load factor, possibly as
     * low as 50% after we round off to a power of 2.
     */
    mNumEntries = numEntries;
    mHashTableSize = roundUpPower2(1 + ((numEntries * 4) / 3));
    mHashTable = (HashEntry*) calloc(1, sizeof(HashEntry) * mHashTableSize);

    /*
     * Walk through the central directory, adding entries to the hash
     * table.
     */
    ptr = basePtr + cdOffset;
    for (i = 0; i < numEntries; i++) {
        unsigned int fileNameLen, extraLen, commentLen, localHdrOffset;
        const unsigned char* localHdr;
        unsigned int hash;

        if (get4LE(ptr) != kCDESignature) {
            LOGW("Missed a central dir sig (at %d)\n", i);
            goto bail;
        }
        if (ptr + kCDELen > basePtr + length) {
            LOGW("Ran off the end (at %d)\n", i);
            goto bail;
        }

        localHdrOffset = get4LE(ptr + kCDELocalOffset);
        CHECK_OFFSET(localHdrOffset);
        fileNameLen = get2LE(ptr + kCDENameLen);
        extraLen = get2LE(ptr + kCDEExtraLen);
        commentLen = get2LE(ptr + kCDECommentLen);

        //LOGV("+++ %d: localHdr=%d fnl=%d el=%d cl=%d\n",
        //    i, localHdrOffset, fileNameLen, extraLen, commentLen);
        //LOGV(" '%.*s'\n", fileNameLen, ptr + kCDELen);

        /* add the CDE filename to the hash table */
        hash = computeHash((const char*)ptr + kCDELen, fileNameLen);
        addToHash((const char*)ptr + kCDELen, fileNameLen, hash);

        localHdr = basePtr + localHdrOffset;
        if (get4LE(localHdr) != kLFHSignature) {
            LOGW("Bad offset to local header: %d (at %d)\n",
                localHdrOffset, i);
            goto bail;
        }

        ptr += kCDELen + fileNameLen + extraLen + commentLen;
        CHECK_OFFSET(ptr - basePtr);
    }

    result = true;

bail:
    return result;
#undef CHECK_OFFSET
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
 * Returns 0 if not found.
 */
ZipEntryRO ZipFileRO::findEntryByName(const char* fileName) const
{
    int nameLen = strlen(fileName);
    unsigned int hash = computeHash(fileName, nameLen);
    int ent = hash & (mHashTableSize-1);

    while (mHashTable[ent].name != NULL) {
        if (mHashTable[ent].nameLen == nameLen &&
            memcmp(mHashTable[ent].name, fileName, nameLen) == 0)
        {
            /* match */
            return (ZipEntryRO) (ent + kZipEntryAdj);
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
bool ZipFileRO::getEntryInfo(ZipEntryRO entry, int* pMethod, long* pUncompLen,
    long* pCompLen, off_t* pOffset, long* pModWhen, long* pCrc32) const
{
    int ent = entryToIndex(entry);
    if (ent < 0)
        return false;

    /*
     * Recover the start of the central directory entry from the filename
     * pointer.
     */
    const unsigned char* basePtr = (const unsigned char*)mFileMap->getDataPtr();
    const unsigned char* ptr = (const unsigned char*) mHashTable[ent].name;
    size_t zipLength = mFileMap->getDataLength();

    ptr -= kCDELen;

    int method = get2LE(ptr + kCDEMethod);
    if (pMethod != NULL)
        *pMethod = method;

    if (pModWhen != NULL)
        *pModWhen = get4LE(ptr + kCDEModWhen);
    if (pCrc32 != NULL)
        *pCrc32 = get4LE(ptr + kCDECRC);

    /*
     * We need to make sure that the lengths are not so large that somebody
     * trying to map the compressed or uncompressed data runs off the end
     * of the mapped region.
     */
    unsigned long localHdrOffset = get4LE(ptr + kCDELocalOffset);
    if (localHdrOffset + kLFHLen >= zipLength) {
        LOGE("ERROR: bad local hdr offset in zip\n");
        return false;
    }
    const unsigned char* localHdr = basePtr + localHdrOffset;
    off_t dataOffset = localHdrOffset + kLFHLen
        + get2LE(localHdr + kLFHNameLen) + get2LE(localHdr + kLFHExtraLen);
    if ((unsigned long) dataOffset >= zipLength) {
        LOGE("ERROR: bad data offset in zip\n");
        return false;
    }

    if (pCompLen != NULL) {
        *pCompLen = get4LE(ptr + kCDECompLen);
        if (*pCompLen < 0 || (size_t)(dataOffset + *pCompLen) >= zipLength) {
            LOGE("ERROR: bad compressed length in zip\n");
            return false;
        }
    }
    if (pUncompLen != NULL) {
        *pUncompLen = get4LE(ptr + kCDEUncompLen);
        if (*pUncompLen < 0) {
            LOGE("ERROR: negative uncompressed length in zip\n");
            return false;
        }
        if (method == kCompressStored &&
            (size_t)(dataOffset + *pUncompLen) >= zipLength)
        {
            LOGE("ERROR: bad uncompressed length in zip\n");
            return false;
        }
    }

    if (pOffset != NULL) {
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
    long compLen;
    off_t offset;

    if (!getEntryInfo(entry, NULL, NULL, &compLen, &offset, NULL, NULL))
        return NULL;

    newMap = new FileMap();
    if (!newMap->create(mFileMap->getFileName(), mFd, offset, compLen, true)) {
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
    const int kSequentialMin = 32768;
    bool result = false;
    int ent = entryToIndex(entry);
    if (ent < 0)
        return -1;

    const unsigned char* basePtr = (const unsigned char*)mFileMap->getDataPtr();
    int method;
    long uncompLen, compLen;
    off_t offset;

    getEntryInfo(entry, &method, &uncompLen, &compLen, &offset, NULL, NULL);

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
        mFileMap->advise(FileMap::SEQUENTIAL);

    if (method == kCompressStored) {
        memcpy(buffer, basePtr + offset, uncompLen);
    } else {
        if (!inflateBuffer(buffer, basePtr + offset, uncompLen, compLen))
            goto bail;
    }

    if (compLen > kSequentialMin)
        mFileMap->advise(FileMap::NORMAL);

    result = true;

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

    const unsigned char* basePtr = (const unsigned char*)mFileMap->getDataPtr();
    int method;
    long uncompLen, compLen;
    off_t offset;

    getEntryInfo(entry, &method, &uncompLen, &compLen, &offset, NULL, NULL);

    if (method == kCompressStored) {
        ssize_t actual;

        actual = write(fd, basePtr + offset, uncompLen);
        if (actual < 0) {
            LOGE("Write failed: %s\n", strerror(errno));
            goto bail;
        } else if (actual != uncompLen) {
            LOGE("Partial write during uncompress (%d of %ld)\n",
                (int)actual, uncompLen);
            goto bail;
        } else {
            LOGI("+++ successful write\n");
        }
    } else {
        if (!inflateBuffer(fd, basePtr+offset, uncompLen, compLen))
            goto bail;
    }

    result = true;

bail:
    return result;
}

/*
 * Uncompress "deflate" data from one buffer to another.
 */
/*static*/ bool ZipFileRO::inflateBuffer(void* outBuf, const void* inBuf,
    long uncompLen, long compLen)
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
    if ((long) zstream.total_out != uncompLen) {
        LOGW("Size mismatch on inflated file (%ld vs %ld)\n",
            zstream.total_out, uncompLen);
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
    long uncompLen, long compLen)
{
    bool result = false;
    const int kWriteBufSize = 32768;
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
    if ((long) zstream.total_out != uncompLen) {
        LOGW("Size mismatch on inflated file (%ld vs %ld)\n",
            zstream.total_out, uncompLen);
        goto z_bail;
    }

    result = true;

z_bail:
    inflateEnd(&zstream);        /* free up any allocated structures */

bail:
    return result;
}
