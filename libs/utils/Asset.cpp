/*
 * Copyright (C) 2006 The Android Open Source Project
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
// Provide access to a read-only asset.
//

#define LOG_TAG "asset"
//#define NDEBUG 0

#include <utils/Asset.h>
#include <utils/Atomic.h>
#include <utils/FileMap.h>
#include <utils/StreamingZipInflater.h>
#include <utils/ZipUtils.h>
#include <utils/ZipFileRO.h>
#include <utils/Log.h>
#include <utils/threads.h>

#include <string.h>
#include <memory.h>
#include <fcntl.h>
#include <errno.h>
#include <assert.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>

using namespace android;

#ifndef O_BINARY
# define O_BINARY 0
#endif

static Mutex gAssetLock;
static int32_t gCount = 0;
static Asset* gHead = NULL;
static Asset* gTail = NULL;

int32_t Asset::getGlobalCount()
{
    AutoMutex _l(gAssetLock);
    return gCount;
}

String8 Asset::getAssetAllocations()
{
    AutoMutex _l(gAssetLock);
    String8 res;
    Asset* cur = gHead;
    while (cur != NULL) {
        if (cur->isAllocated()) {
            res.append("    ");
            res.append(cur->getAssetSource());
            off64_t size = (cur->getLength()+512)/1024;
            char buf[64];
            sprintf(buf, ": %dK\n", (int)size);
            res.append(buf);
        }
        cur = cur->mNext;
    }
    
    return res;
}

Asset::Asset(void)
    : mAccessMode(ACCESS_UNKNOWN)
{
    AutoMutex _l(gAssetLock);
    gCount++;
    mNext = mPrev = NULL;
    if (gTail == NULL) {
        gHead = gTail = this;
  	} else {
  	    mPrev = gTail;
  	    gTail->mNext = this;
  	    gTail = this;
  	}
    //ALOGI("Creating Asset %p #%d\n", this, gCount);
}

Asset::~Asset(void)
{
    AutoMutex _l(gAssetLock);
	gCount--;
    if (gHead == this) {
        gHead = mNext;
    }
    if (gTail == this) {
        gTail = mPrev;
    }
    if (mNext != NULL) {
        mNext->mPrev = mPrev;
    }
    if (mPrev != NULL) {
        mPrev->mNext = mNext;
    }
    mNext = mPrev = NULL;
    //ALOGI("Destroying Asset in %p #%d\n", this, gCount);
}

/*
 * Create a new Asset from a file on disk.  There is a fair chance that
 * the file doesn't actually exist.
 *
 * We can use "mode" to decide how we want to go about it.
 */
/*static*/ Asset* Asset::createFromFile(const char* fileName, AccessMode mode)
{
    _FileAsset* pAsset;
    status_t result;
    off64_t length;
    int fd;

    fd = open(fileName, O_RDONLY | O_BINARY);
    if (fd < 0)
        return NULL;

    /*
     * Under Linux, the lseek fails if we actually opened a directory.  To
     * be correct we should test the file type explicitly, but since we
     * always open things read-only it doesn't really matter, so there's
     * no value in incurring the extra overhead of an fstat() call.
     */
    // TODO(kroot): replace this with fstat despite the plea above.
#if 1
    length = lseek64(fd, 0, SEEK_END);
    if (length < 0) {
        ::close(fd);
        return NULL;
    }
    (void) lseek64(fd, 0, SEEK_SET);
#else
    struct stat st;
    if (fstat(fd, &st) < 0) {
        ::close(fd);
        return NULL;
    }

    if (!S_ISREG(st.st_mode)) {
        ::close(fd);
        return NULL;
    }
#endif

    pAsset = new _FileAsset;
    result = pAsset->openChunk(fileName, fd, 0, length);
    if (result != NO_ERROR) {
        delete pAsset;
        return NULL;
    }

    pAsset->mAccessMode = mode;
    return pAsset;
}


/*
 * Create a new Asset from a compressed file on disk.  There is a fair chance
 * that the file doesn't actually exist.
 *
 * We currently support gzip files.  We might want to handle .bz2 someday.
 */
/*static*/ Asset* Asset::createFromCompressedFile(const char* fileName,
    AccessMode mode)
{
    _CompressedAsset* pAsset;
    status_t result;
    off64_t fileLen;
    bool scanResult;
    long offset;
    int method;
    long uncompressedLen, compressedLen;
    int fd;

    fd = open(fileName, O_RDONLY | O_BINARY);
    if (fd < 0)
        return NULL;

    fileLen = lseek(fd, 0, SEEK_END);
    if (fileLen < 0) {
        ::close(fd);
        return NULL;
    }
    (void) lseek(fd, 0, SEEK_SET);

    /* want buffered I/O for the file scan; must dup so fclose() is safe */
    FILE* fp = fdopen(dup(fd), "rb");
    if (fp == NULL) {
        ::close(fd);
        return NULL;
    }

    unsigned long crc32;
    scanResult = ZipUtils::examineGzip(fp, &method, &uncompressedLen,
                    &compressedLen, &crc32);
    offset = ftell(fp);
    fclose(fp);
    if (!scanResult) {
        ALOGD("File '%s' is not in gzip format\n", fileName);
        ::close(fd);
        return NULL;
    }

    pAsset = new _CompressedAsset;
    result = pAsset->openChunk(fd, offset, method, uncompressedLen,
                compressedLen);
    if (result != NO_ERROR) {
        delete pAsset;
        return NULL;
    }

    pAsset->mAccessMode = mode;
    return pAsset;
}


#if 0
/*
 * Create a new Asset from part of an open file.
 */
/*static*/ Asset* Asset::createFromFileSegment(int fd, off64_t offset,
    size_t length, AccessMode mode)
{
    _FileAsset* pAsset;
    status_t result;

    pAsset = new _FileAsset;
    result = pAsset->openChunk(NULL, fd, offset, length);
    if (result != NO_ERROR)
        return NULL;

    pAsset->mAccessMode = mode;
    return pAsset;
}

/*
 * Create a new Asset from compressed data in an open file.
 */
/*static*/ Asset* Asset::createFromCompressedData(int fd, off64_t offset,
    int compressionMethod, size_t uncompressedLen, size_t compressedLen,
    AccessMode mode)
{
    _CompressedAsset* pAsset;
    status_t result;

    pAsset = new _CompressedAsset;
    result = pAsset->openChunk(fd, offset, compressionMethod,
                uncompressedLen, compressedLen);
    if (result != NO_ERROR)
        return NULL;

    pAsset->mAccessMode = mode;
    return pAsset;
}
#endif

/*
 * Create a new Asset from a memory mapping.
 */
/*static*/ Asset* Asset::createFromUncompressedMap(FileMap* dataMap,
    AccessMode mode)
{
    _FileAsset* pAsset;
    status_t result;

    pAsset = new _FileAsset;
    result = pAsset->openChunk(dataMap);
    if (result != NO_ERROR)
        return NULL;

    pAsset->mAccessMode = mode;
    return pAsset;
}

/*
 * Create a new Asset from compressed data in a memory mapping.
 */
/*static*/ Asset* Asset::createFromCompressedMap(FileMap* dataMap,
    int method, size_t uncompressedLen, AccessMode mode)
{
    _CompressedAsset* pAsset;
    status_t result;

    pAsset = new _CompressedAsset;
    result = pAsset->openChunk(dataMap, method, uncompressedLen);
    if (result != NO_ERROR)
        return NULL;

    pAsset->mAccessMode = mode;
    return pAsset;
}


/*
 * Do generic seek() housekeeping.  Pass in the offset/whence values from
 * the seek request, along with the current chunk offset and the chunk
 * length.
 *
 * Returns the new chunk offset, or -1 if the seek is illegal.
 */
off64_t Asset::handleSeek(off64_t offset, int whence, off64_t curPosn, off64_t maxPosn)
{
    off64_t newOffset;

    switch (whence) {
    case SEEK_SET:
        newOffset = offset;
        break;
    case SEEK_CUR:
        newOffset = curPosn + offset;
        break;
    case SEEK_END:
        newOffset = maxPosn + offset;
        break;
    default:
        LOGW("unexpected whence %d\n", whence);
        // this was happening due to an off64_t size mismatch
        assert(false);
        return (off64_t) -1;
    }

    if (newOffset < 0 || newOffset > maxPosn) {
        LOGW("seek out of range: want %ld, end=%ld\n",
            (long) newOffset, (long) maxPosn);
        return (off64_t) -1;
    }

    return newOffset;
}


/*
 * ===========================================================================
 *      _FileAsset
 * ===========================================================================
 */

/*
 * Constructor.
 */
_FileAsset::_FileAsset(void)
    : mStart(0), mLength(0), mOffset(0), mFp(NULL), mFileName(NULL), mMap(NULL), mBuf(NULL)
{
}

/*
 * Destructor.  Release resources.
 */
_FileAsset::~_FileAsset(void)
{
    close();
}

/*
 * Operate on a chunk of an uncompressed file.
 *
 * Zero-length chunks are allowed.
 */
status_t _FileAsset::openChunk(const char* fileName, int fd, off64_t offset, size_t length)
{
    assert(mFp == NULL);    // no reopen
    assert(mMap == NULL);
    assert(fd >= 0);
    assert(offset >= 0);

    /*
     * Seek to end to get file length.
     */
    off64_t fileLength;
    fileLength = lseek64(fd, 0, SEEK_END);
    if (fileLength == (off64_t) -1) {
        // probably a bad file descriptor
        ALOGD("failed lseek (errno=%d)\n", errno);
        return UNKNOWN_ERROR;
    }

    if ((off64_t) (offset + length) > fileLength) {
        ALOGD("start (%ld) + len (%ld) > end (%ld)\n",
            (long) offset, (long) length, (long) fileLength);
        return BAD_INDEX;
    }

    /* after fdopen, the fd will be closed on fclose() */
    mFp = fdopen(fd, "rb");
    if (mFp == NULL)
        return UNKNOWN_ERROR;

    mStart = offset;
    mLength = length;
    assert(mOffset == 0);

    /* seek the FILE* to the start of chunk */
    if (fseek(mFp, mStart, SEEK_SET) != 0) {
        assert(false);
    }

    mFileName = fileName != NULL ? strdup(fileName) : NULL;
    
    return NO_ERROR;
}

/*
 * Create the chunk from the map.
 */
status_t _FileAsset::openChunk(FileMap* dataMap)
{
    assert(mFp == NULL);    // no reopen
    assert(mMap == NULL);
    assert(dataMap != NULL);

    mMap = dataMap;
    mStart = -1;            // not used
    mLength = dataMap->getDataLength();
    assert(mOffset == 0);

    return NO_ERROR;
}

/*
 * Read a chunk of data.
 */
ssize_t _FileAsset::read(void* buf, size_t count)
{
    size_t maxLen;
    size_t actual;

    assert(mOffset >= 0 && mOffset <= mLength);

    if (getAccessMode() == ACCESS_BUFFER) {
        /*
         * On first access, read or map the entire file.  The caller has
         * requested buffer access, either because they're going to be
         * using the buffer or because what they're doing has appropriate
         * performance needs and access patterns.
         */
        if (mBuf == NULL)
            getBuffer(false);
    }

    /* adjust count if we're near EOF */
    maxLen = mLength - mOffset;
    if (count > maxLen)
        count = maxLen;

    if (!count)
        return 0;

    if (mMap != NULL) {
        /* copy from mapped area */
        //printf("map read\n");
        memcpy(buf, (char*)mMap->getDataPtr() + mOffset, count);
        actual = count;
    } else if (mBuf != NULL) {
        /* copy from buffer */
        //printf("buf read\n");
        memcpy(buf, (char*)mBuf + mOffset, count);
        actual = count;
    } else {
        /* read from the file */
        //printf("file read\n");
        if (ftell(mFp) != mStart + mOffset) {
            LOGE("Hosed: %ld != %ld+%ld\n",
                ftell(mFp), (long) mStart, (long) mOffset);
            assert(false);
        }

        /*
         * This returns 0 on error or eof.  We need to use ferror() or feof()
         * to tell the difference, but we don't currently have those on the
         * device.  However, we know how much data is *supposed* to be in the
         * file, so if we don't read the full amount we know something is
         * hosed.
         */
        actual = fread(buf, 1, count, mFp);
        if (actual == 0)        // something failed -- I/O error?
            return -1;

        assert(actual == count);
    }

    mOffset += actual;
    return actual;
}

/*
 * Seek to a new position.
 */
off64_t _FileAsset::seek(off64_t offset, int whence)
{
    off64_t newPosn;
    off64_t actualOffset;

    // compute new position within chunk
    newPosn = handleSeek(offset, whence, mOffset, mLength);
    if (newPosn == (off64_t) -1)
        return newPosn;

    actualOffset = mStart + newPosn;

    if (mFp != NULL) {
        if (fseek(mFp, (long) actualOffset, SEEK_SET) != 0)
            return (off64_t) -1;
    }

    mOffset = actualOffset - mStart;
    return mOffset;
}

/*
 * Close the asset.
 */
void _FileAsset::close(void)
{
    if (mMap != NULL) {
        mMap->release();
        mMap = NULL;
    }
    if (mBuf != NULL) {
        delete[] mBuf;
        mBuf = NULL;
    }

    if (mFileName != NULL) {
        free(mFileName);
        mFileName = NULL;
    }
    
    if (mFp != NULL) {
        // can only be NULL when called from destructor
        // (otherwise we would never return this object)
        fclose(mFp);
        mFp = NULL;
    }
}

/*
 * Return a read-only pointer to a buffer.
 *
 * We can either read the whole thing in or map the relevant piece of
 * the source file.  Ideally a map would be established at a higher
 * level and we'd be using a different object, but we didn't, so we
 * deal with it here.
 */
const void* _FileAsset::getBuffer(bool wordAligned)
{
    /* subsequent requests just use what we did previously */
    if (mBuf != NULL)
        return mBuf;
    if (mMap != NULL) {
        if (!wordAligned) {
            return  mMap->getDataPtr();
        }
        return ensureAlignment(mMap);
    }

    assert(mFp != NULL);

    if (mLength < kReadVsMapThreshold) {
        unsigned char* buf;
        long allocLen;

        /* zero-length files are allowed; not sure about zero-len allocs */
        /* (works fine with gcc + x86linux) */
        allocLen = mLength;
        if (mLength == 0)
            allocLen = 1;

        buf = new unsigned char[allocLen];
        if (buf == NULL) {
            LOGE("alloc of %ld bytes failed\n", (long) allocLen);
            return NULL;
        }

        ALOGV("Asset %p allocating buffer size %d (smaller than threshold)", this, (int)allocLen);
        if (mLength > 0) {
            long oldPosn = ftell(mFp);
            fseek(mFp, mStart, SEEK_SET);
            if (fread(buf, 1, mLength, mFp) != (size_t) mLength) {
                LOGE("failed reading %ld bytes\n", (long) mLength);
                delete[] buf;
                return NULL;
            }
            fseek(mFp, oldPosn, SEEK_SET);
        }

        ALOGV(" getBuffer: loaded into buffer\n");

        mBuf = buf;
        return mBuf;
    } else {
        FileMap* map;

        map = new FileMap;
        if (!map->create(NULL, fileno(mFp), mStart, mLength, true)) {
            map->release();
            return NULL;
        }

        ALOGV(" getBuffer: mapped\n");

        mMap = map;
        if (!wordAligned) {
            return  mMap->getDataPtr();
        }
        return ensureAlignment(mMap);
    }
}

int _FileAsset::openFileDescriptor(off64_t* outStart, off64_t* outLength) const
{
    if (mMap != NULL) {
        const char* fname = mMap->getFileName();
        if (fname == NULL) {
            fname = mFileName;
        }
        if (fname == NULL) {
            return -1;
        }
        *outStart = mMap->getDataOffset();
        *outLength = mMap->getDataLength();
        return open(fname, O_RDONLY | O_BINARY);
    }
    if (mFileName == NULL) {
        return -1;
    }
    *outStart = mStart;
    *outLength = mLength;
    return open(mFileName, O_RDONLY | O_BINARY);
}

const void* _FileAsset::ensureAlignment(FileMap* map)
{
    void* data = map->getDataPtr();
    if ((((size_t)data)&0x3) == 0) {
        // We can return this directly if it is aligned on a word
        // boundary.
        ALOGV("Returning aligned FileAsset %p (%s).", this,
                getAssetSource());
        return data;
    }
    // If not aligned on a word boundary, then we need to copy it into
    // our own buffer.
    ALOGV("Copying FileAsset %p (%s) to buffer size %d to make it aligned.", this,
            getAssetSource(), (int)mLength);
    unsigned char* buf = new unsigned char[mLength];
    if (buf == NULL) {
        LOGE("alloc of %ld bytes failed\n", (long) mLength);
        return NULL;
    }
    memcpy(buf, data, mLength);
    mBuf = buf;
    return buf;
}

/*
 * ===========================================================================
 *      _CompressedAsset
 * ===========================================================================
 */

/*
 * Constructor.
 */
_CompressedAsset::_CompressedAsset(void)
    : mStart(0), mCompressedLen(0), mUncompressedLen(0), mOffset(0),
      mMap(NULL), mFd(-1), mZipInflater(NULL), mBuf(NULL)
{
}

/*
 * Destructor.  Release resources.
 */
_CompressedAsset::~_CompressedAsset(void)
{
    close();
}

/*
 * Open a chunk of compressed data inside a file.
 *
 * This currently just sets up some values and returns.  On the first
 * read, we expand the entire file into a buffer and return data from it.
 */
status_t _CompressedAsset::openChunk(int fd, off64_t offset,
    int compressionMethod, size_t uncompressedLen, size_t compressedLen)
{
    assert(mFd < 0);        // no re-open
    assert(mMap == NULL);
    assert(fd >= 0);
    assert(offset >= 0);
    assert(compressedLen > 0);

    if (compressionMethod != ZipFileRO::kCompressDeflated) {
        assert(false);
        return UNKNOWN_ERROR;
    }

    mStart = offset;
    mCompressedLen = compressedLen;
    mUncompressedLen = uncompressedLen;
    assert(mOffset == 0);
    mFd = fd;
    assert(mBuf == NULL);

    if (uncompressedLen > StreamingZipInflater::OUTPUT_CHUNK_SIZE) {
        mZipInflater = new StreamingZipInflater(mFd, offset, uncompressedLen, compressedLen);
    }

    return NO_ERROR;
}

/*
 * Open a chunk of compressed data in a mapped region.
 *
 * Nothing is expanded until the first read call.
 */
status_t _CompressedAsset::openChunk(FileMap* dataMap, int compressionMethod,
    size_t uncompressedLen)
{
    assert(mFd < 0);        // no re-open
    assert(mMap == NULL);
    assert(dataMap != NULL);

    if (compressionMethod != ZipFileRO::kCompressDeflated) {
        assert(false);
        return UNKNOWN_ERROR;
    }

    mMap = dataMap;
    mStart = -1;        // not used
    mCompressedLen = dataMap->getDataLength();
    mUncompressedLen = uncompressedLen;
    assert(mOffset == 0);

    if (uncompressedLen > StreamingZipInflater::OUTPUT_CHUNK_SIZE) {
        mZipInflater = new StreamingZipInflater(dataMap, uncompressedLen);
    }
    return NO_ERROR;
}

/*
 * Read data from a chunk of compressed data.
 *
 * [For now, that's just copying data out of a buffer.]
 */
ssize_t _CompressedAsset::read(void* buf, size_t count)
{
    size_t maxLen;
    size_t actual;

    assert(mOffset >= 0 && mOffset <= mUncompressedLen);

    /* If we're relying on a streaming inflater, go through that */
    if (mZipInflater) {
        actual = mZipInflater->read(buf, count);
    } else {
        if (mBuf == NULL) {
            if (getBuffer(false) == NULL)
                return -1;
        }
        assert(mBuf != NULL);

        /* adjust count if we're near EOF */
        maxLen = mUncompressedLen - mOffset;
        if (count > maxLen)
            count = maxLen;

        if (!count)
            return 0;

        /* copy from buffer */
        //printf("comp buf read\n");
        memcpy(buf, (char*)mBuf + mOffset, count);
        actual = count;
    }

    mOffset += actual;
    return actual;
}

/*
 * Handle a seek request.
 *
 * If we're working in a streaming mode, this is going to be fairly
 * expensive, because it requires plowing through a bunch of compressed
 * data.
 */
off64_t _CompressedAsset::seek(off64_t offset, int whence)
{
    off64_t newPosn;

    // compute new position within chunk
    newPosn = handleSeek(offset, whence, mOffset, mUncompressedLen);
    if (newPosn == (off64_t) -1)
        return newPosn;

    if (mZipInflater) {
        mZipInflater->seekAbsolute(newPosn);
    }
    mOffset = newPosn;
    return mOffset;
}

/*
 * Close the asset.
 */
void _CompressedAsset::close(void)
{
    if (mMap != NULL) {
        mMap->release();
        mMap = NULL;
    }

    delete[] mBuf;
    mBuf = NULL;

    delete mZipInflater;
    mZipInflater = NULL;

    if (mFd > 0) {
        ::close(mFd);
        mFd = -1;
    }
}

/*
 * Get a pointer to a read-only buffer of data.
 *
 * The first time this is called, we expand the compressed data into a
 * buffer.
 */
const void* _CompressedAsset::getBuffer(bool wordAligned)
{
    unsigned char* buf = NULL;

    if (mBuf != NULL)
        return mBuf;

    /*
     * Allocate a buffer and read the file into it.
     */
    buf = new unsigned char[mUncompressedLen];
    if (buf == NULL) {
        LOGW("alloc %ld bytes failed\n", (long) mUncompressedLen);
        goto bail;
    }

    if (mMap != NULL) {
        if (!ZipFileRO::inflateBuffer(buf, mMap->getDataPtr(),
                mUncompressedLen, mCompressedLen))
            goto bail;
    } else {
        assert(mFd >= 0);

        /*
         * Seek to the start of the compressed data.
         */
        if (lseek(mFd, mStart, SEEK_SET) != mStart)
            goto bail;

        /*
         * Expand the data into it.
         */
        if (!ZipUtils::inflateToBuffer(mFd, buf, mUncompressedLen,
                mCompressedLen))
            goto bail;
    }

    /*
     * Success - now that we have the full asset in RAM we
     * no longer need the streaming inflater
     */
    delete mZipInflater;
    mZipInflater = NULL;

    mBuf = buf;
    buf = NULL;

bail:
    delete[] buf;
    return mBuf;
}

