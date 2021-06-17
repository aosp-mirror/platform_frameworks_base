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
#include <memory>
#include <optional>

#include <android-base/unique_fd.h>
#include <util/map_ptr.h>

#include <utils/Compat.h>
#include <utils/Errors.h>
#include <utils/String8.h>

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
    virtual ~Asset(void) = default;
    Asset(const Asset& src) = delete;
    Asset& operator=(const Asset& src) = delete;

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
     * lseek/fseek.  Returns the new position on success, or (off64_t) -1
     * on failure.
     */
    virtual off64_t seek(off64_t offset, int whence) = 0;

    /*
     * Close the asset, freeing all associated resources.
     */
    virtual void close(void) = 0;

    /*
     * Get a pointer to a buffer with the entire contents of the file.
     * If `aligned` is true, the buffer data will be aligned to a 4-byte boundary.
     *
     * If the buffer contents reside on IncFs, the entire buffer will be scanned to ensure the
     * presence of the data before returning a raw pointer to the buffer.
     */
    virtual const void* getBuffer(bool aligned) = 0;

    /*
     * Get a incfs::map_ptr<void> to a buffer with the entire contents of the file.
     * If `aligned` is true, the buffer data will be aligned to a 4-byte boundary.
     *
     * Use this function if the asset can potentially reside on IncFs to avoid the scanning of the
     * buffer contents done in Asset::getBuffer.
     */
    virtual incfs::map_ptr<void> getIncFsBuffer(bool aligned) = 0;

    /*
     * Get the total amount of data that can be read.
     */
    virtual off64_t getLength(void) const = 0;

    /*
     * Get the total amount of data that can be read from the current position.
     */
    virtual off64_t getRemainingLength(void) const = 0;

    /*
     * Open a new file descriptor that can be used to read this asset.
     * Returns -1 if you can not use the file descriptor (for example if the
     * asset is compressed).
     */
    virtual int openFileDescriptor(off64_t* outStart, off64_t* outLength) const = 0;

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

    /*
     * Create the asset from a file descriptor.
     */
    static Asset* createFromFd(const int fd, const char* fileName, AccessMode mode);

protected:
    /*
     * Adds this Asset to the global Asset list for debugging and
     * accounting.
     * Concrete subclasses must call this in their constructor.
     */
    static void registerAsset(Asset* asset);

    /*
     * Removes this Asset from the global Asset list.
     * Concrete subclasses must call this in their destructor.
     */
    static void unregisterAsset(Asset* asset);

    Asset(void);        // constructor; only invoked indirectly

    /* handle common seek() housekeeping */
    off64_t handleSeek(off64_t offset, int whence, off64_t curPosn, off64_t maxPosn);

    /* set the asset source string */
    void setAssetSource(const String8& path) { mAssetSource = path; }

    AccessMode getAccessMode(void) const { return mAccessMode; }

private:
    /* AssetManager needs access to our "create" functions */
    friend class AssetManager;
    friend struct ZipAssetsProvider;
    friend struct AssetsProvider;

    /*
     * Create the asset from a named file on disk.
     */
    static Asset* createFromFile(const char* fileName, AccessMode mode);

    /*
     * Create the asset from a named, compressed file on disk (e.g. ".gz").
     */
    static Asset* createFromCompressedFile(const char* fileName, AccessMode mode);

#if 0
    /*
     * Create the asset from a segment of an open file.  This will fail
     * if "offset" and "length" don't fit within the bounds of the file.
     *
     * The asset takes ownership of the file descriptor.
     */
    static Asset* createFromFileSegment(int fd, off64_t offset, size_t length,
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
    static Asset* createFromCompressedData(int fd, off64_t offset,
        int compressionMethod, size_t compressedLength,
        size_t uncompressedLength, AccessMode mode);
#endif

    /*
     * Create the asset from a memory-mapped file segment.
     *
     * The asset takes ownership of the incfs::IncFsFileMap and the file descriptor "fd". The
     * file descriptor is used to request new file descriptors using "openFileDescriptor".
     */
    static std::unique_ptr<Asset> createFromUncompressedMap(incfs::IncFsFileMap&& dataMap,
                                                            AccessMode mode,
                                                            base::unique_fd fd = {});

    /*
     * Create the asset from a memory-mapped file segment with compressed
     * data.
     *
     * The asset takes ownership of the incfs::IncFsFileMap.
     */
    static std::unique_ptr<Asset> createFromCompressedMap(incfs::IncFsFileMap&& dataMap,
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
    ~_FileAsset(void) override;

    /*
     * Use a piece of an already-open file.
     *
     * On success, the object takes ownership of "fd".
     */
    status_t openChunk(const char* fileName, int fd, off64_t offset, size_t length);

    /*
     * Use a memory-mapped region.
     *
     * On success, the object takes ownership of "dataMap" and "fd".
     */
    status_t openChunk(incfs::IncFsFileMap&& dataMap, base::unique_fd fd);

    /*
     * Standard Asset interfaces.
     */
    ssize_t read(void* buf, size_t count) override;
    off64_t seek(off64_t offset, int whence) override;
    void close(void) override;
    const void* getBuffer(bool aligned) override;
    incfs::map_ptr<void> getIncFsBuffer(bool aligned) override;
    off64_t getLength(void) const override { return mLength; }
    off64_t getRemainingLength(void) const override { return mLength-mOffset; }
    int openFileDescriptor(off64_t* outStart, off64_t* outLength) const override;
    bool isAllocated(void) const override { return mBuf != NULL; }

private:
    incfs::map_ptr<void> ensureAlignment(const incfs::IncFsFileMap& map);

    off64_t         mStart;         // absolute file offset of start of chunk
    off64_t         mLength;        // length of the chunk
    off64_t         mOffset;        // current local offset, 0 == mStart
    FILE*           mFp;            // for read/seek
    char*           mFileName;      // for opening
    base::unique_fd mFd;            // for opening file descriptors

    /*
     * To support getBuffer() we either need to read the entire thing into
     * a buffer or memory-map it.  For small files it's probably best to
     * just read them in.
     */
    enum { kReadVsMapThreshold = 4096 };

    unsigned char*                      mBuf;     // for read
    std::optional<incfs::IncFsFileMap>  mMap;     // for memory map
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
    status_t openChunk(int fd, off64_t offset, int compressionMethod,
        size_t uncompressedLen, size_t compressedLen);

    /*
     * Use a memory-mapped region.
     *
     * On success, the object takes ownership of "fd".
     */
    status_t openChunk(incfs::IncFsFileMap&& dataMap, size_t uncompressedLen);

    /*
     * Standard Asset interfaces.
     */
    virtual ssize_t read(void* buf, size_t count);
    virtual off64_t seek(off64_t offset, int whence);
    virtual void close(void);
    virtual const void* getBuffer(bool aligned);
    virtual incfs::map_ptr<void> getIncFsBuffer(bool aligned);
    virtual off64_t getLength(void) const { return mUncompressedLen; }
    virtual off64_t getRemainingLength(void) const { return mUncompressedLen-mOffset; }
    virtual int openFileDescriptor(off64_t* /* outStart */, off64_t* /* outLength */) const { return -1; }
    virtual bool isAllocated(void) const { return mBuf != NULL; }

private:
    off64_t mStart;           // offset to start of compressed data
    off64_t mCompressedLen;   // length of the compressed data
    off64_t mUncompressedLen; // length of the uncompressed data
    off64_t mOffset;          // current offset, 0 == start of uncomp data
    int     mFd;              // for file input

    class StreamingZipInflater*         mZipInflater; // for streaming large compressed assets
    unsigned char*                      mBuf;         // for getBuffer()
    std::optional<incfs::IncFsFileMap>  mMap;         // for memory-mapped input
};

// need: shared mmap version?

}; // namespace android

#endif // __LIBS_ASSET_H
