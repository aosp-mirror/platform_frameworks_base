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
// Class providing access to a read-only asset.  Asset objects are NOT
// thread-safe, and should not be shared across threads.
//
#ifndef __LIBS_ASSET_H
#define __LIBS_ASSET_H

#include <stdio.h>
#include <sys/types.h>
#include "FileMap.h"
#include "String8.h"
#include "Errors.h"

namespace android {

/*
 * Instances of this class provide read-only operations on a byte stream.
 *
 * Access may be optimized for streaming, random, or whole buffer modes.  All
 * operations are supported regardless of how the file was opened, but some
 * things will be less efficient.  [pass that in??]
 *
 * "Asset" is the base class for all types of assets.  The classes below
 * provide most of the implementation.  The AssetManager uses one of the
 * static "create" functions defined here to create a new instance.
 */
class Asset {
public:
    virtual ~Asset(void);

    static int32_t getGlobalCount();
    static String8 getAssetAllocations();
    
    /* used when opening an asset */
    typedef enum AccessMode {
        ACCESS_UNKNOWN = 0,

        /* read chunks, and seek forward and backward */
        ACCESS_RANDOM,

        /* read sequentially, with an occasional forward seek */
        ACCESS_STREAMING,

        /* caller plans to ask for a read-only buffer with all data */
        ACCESS_BUFFER,
    } AccessMode;

    /*
     * Read data from the current offset.  Returns the actual number of
     * bytes read, 0 on EOF, or -1 on error.
     */
    virtual ssize_t read(void* buf, size_t count) = 0;

    /*
     * Seek to the specified offset.  "whence" uses the same values as
     * lseek/fseek.  Returns the new position on success, or (off_t) -1
     * on failure.
     */
    virtual off_t seek(off_t offset, int whence) = 0;

    /*
     * Close the asset, freeing all associated resources.
     */
    virtual void close(void) = 0;

    /*
     * Get a pointer to a buffer with the entire contents of the file.
     */
    virtual const void* getBuffer(bool wordAligned) = 0;

    /*
     * Get the total amount of data that can be read.
     */
    virtual off_t getLength(void) const = 0;

    /*
     * Get the total amount of data that can be read from the current position.
     */
    virtual off_t getRemainingLength(void) const = 0;

    /*
     * Open a new file descriptor that can be used to read this asset.
     * Returns -1 if you can not use the file descriptor (for example if the
     * asset is compressed).
     */
    virtual int openFileDescriptor(off_t* outStart, off_t* outLength) const = 0;
    
    /*
     * Return whether this asset's buffer is allocated in RAM (not mmapped).
     * Note: not virtual so it is safe to call even when being destroyed.
     */
    virtual bool isAllocated(void) const { return false; }
    
    /*
     * Get a string identifying the asset's source.  This might be a full
     * path, it might be a colon-separated list of identifiers.
     *
     * This is NOT intended to be used for anything except debug output.
     * DO NOT try to parse this or use it to open a file.
     */
    const char* getAssetSource(void) const { return mAssetSource.string(); }

protected:
    Asset(void);        // constructor; only invoked indirectly

    /* handle common seek() housekeeping */
    off_t handleSeek(off_t offset, int whence, off_t curPosn, off_t maxPosn);

    /* set the asset source string */
    void setAssetSource(const String8& path) { mAssetSource = path; }

    AccessMode getAccessMode(void) const { return mAccessMode; }

private:
    /* these operations are not implemented */
    Asset(const Asset& src);
    Asset& operator=(const Asset& src);

    /* AssetManager needs access to our "create" functions */
    friend class AssetManager;

    /*
     * Create the asset from a named file on disk.
     */
    static Asset* createFromFile(const char* fileName, AccessMode mode);

    /*
     * Create the asset from a named, compressed file on disk (e.g. ".gz").
     */
    static Asset* createFromCompressedFile(const char* fileName,
        AccessMode mode);

#if 0
    /*
     * Create the asset from a segment of an open file.  This will fail
     * if "offset" and "length" don't fit within the bounds of the file.
     *
     * The asset takes ownership of the file descriptor.
     */
    static Asset* createFromFileSegment(int fd, off_t offset, size_t length,
        AccessMode mode);

    /*
     * Create from compressed data.  "fd" should be seeked to the start of
     * the compressed data.  This could be inside a gzip file or part of a
     * Zip archive.
     *
     * The asset takes ownership of the file descriptor.
     *
     * This may not verify the validity of the compressed data until first
     * use.
     */
    static Asset* createFromCompressedData(int fd, off_t offset,
        int compressionMethod, size_t compressedLength,
        size_t uncompressedLength, AccessMode mode);
#endif

    /*
     * Create the asset from a memory-mapped file segment.
     *
     * The asset takes ownership of the FileMap.
     */
    static Asset* createFromUncompressedMap(FileMap* dataMap, AccessMode mode);

    /*
     * Create the asset from a memory-mapped file segment with compressed
     * data.  "method" is a Zip archive compression method constant.
     *
     * The asset takes ownership of the FileMap.
     */
    static Asset* createFromCompressedMap(FileMap* dataMap, int method,
        size_t uncompressedLen, AccessMode mode);


    /*
     * Create from a reference-counted chunk of shared memory.
     */
    // TODO

    AccessMode  mAccessMode;        // how the asset was opened
    String8    mAssetSource;       // debug string
    
    Asset*		mNext;				// linked list.
    Asset*		mPrev;
};


/*
 * ===========================================================================
 *
 * Innards follow.  Do not use these classes directly.
 */

/*
 * An asset based on an uncompressed file on disk.  It may encompass the
 * entire file or just a piece of it.  Access is through fread/fseek.
 */
class _FileAsset : public Asset {
public:
    _FileAsset(void);
    virtual ~_FileAsset(void);

    /*
     * Use a piece of an already-open file.
     *
     * On success, the object takes ownership of "fd".
     */
    status_t openChunk(const char* fileName, int fd, off_t offset, size_t length);

    /*
     * Use a memory-mapped region.
     *
     * On success, the object takes ownership of "dataMap".
     */
    status_t openChunk(FileMap* dataMap);

    /*
     * Standard Asset interfaces.
     */
    virtual ssize_t read(void* buf, size_t count);
    virtual off_t seek(off_t offset, int whence);
    virtual void close(void);
    virtual const void* getBuffer(bool wordAligned);
    virtual off_t getLength(void) const { return mLength; }
    virtual off_t getRemainingLength(void) const { return mLength-mOffset; }
    virtual int openFileDescriptor(off_t* outStart, off_t* outLength) const;
    virtual bool isAllocated(void) const { return mBuf != NULL; }

private:
    off_t       mStart;         // absolute file offset of start of chunk
    off_t       mLength;        // length of the chunk
    off_t       mOffset;        // current local offset, 0 == mStart
    FILE*       mFp;            // for read/seek
    char*       mFileName;      // for opening

    /*
     * To support getBuffer() we either need to read the entire thing into
     * a buffer or memory-map it.  For small files it's probably best to
     * just read them in.
     */
    enum { kReadVsMapThreshold = 4096 };

    FileMap*    mMap;           // for memory map
    unsigned char* mBuf;        // for read
    
    const void* ensureAlignment(FileMap* map);
};


/*
 * An asset based on compressed data in a file.
 */
class _CompressedAsset : public Asset {
public:
    _CompressedAsset(void);
    virtual ~_CompressedAsset(void);

    /*
     * Use a piece of an already-open file.
     *
     * On success, the object takes ownership of "fd".
     */
    status_t openChunk(int fd, off_t offset, int compressionMethod,
        size_t uncompressedLen, size_t compressedLen);

    /*
     * Use a memory-mapped region.
     *
     * On success, the object takes ownership of "fd".
     */
    status_t openChunk(FileMap* dataMap, int compressionMethod,
        size_t uncompressedLen);

    /*
     * Standard Asset interfaces.
     */
    virtual ssize_t read(void* buf, size_t count);
    virtual off_t seek(off_t offset, int whence);
    virtual void close(void);
    virtual const void* getBuffer(bool wordAligned);
    virtual off_t getLength(void) const { return mUncompressedLen; }
    virtual off_t getRemainingLength(void) const { return mUncompressedLen-mOffset; }
    virtual int openFileDescriptor(off_t* outStart, off_t* outLength) const { return -1; }
    virtual bool isAllocated(void) const { return mBuf != NULL; }

private:
    off_t       mStart;         // offset to start of compressed data
    off_t       mCompressedLen; // length of the compressed data
    off_t       mUncompressedLen; // length of the uncompressed data
    off_t       mOffset;        // current offset, 0 == start of uncomp data

    FileMap*    mMap;           // for memory-mapped input
    int         mFd;            // for file input

    class StreamingZipInflater* mZipInflater;  // for streaming large compressed assets

    unsigned char*  mBuf;       // for getBuffer()
};

// need: shared mmap version?

}; // namespace android

#endif // __LIBS_ASSET_H
