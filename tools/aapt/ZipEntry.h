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
// Zip archive entries.
//
// The ZipEntry class is tightly meshed with the ZipFile class.
//
#ifndef __LIBS_ZIPENTRY_H
#define __LIBS_ZIPENTRY_H

#include <utils/Errors.h>

#include <stdlib.h>
#include <stdio.h>

namespace android {

class ZipFile;

/*
 * ZipEntry objects represent a single entry in a Zip archive.
 *
 * You can use one of these to get or set information about an entry, but
 * there are no functions here for accessing the data itself.  (We could
 * tuck a pointer to the ZipFile in here for convenience, but that raises
 * the likelihood of using ZipEntry objects after discarding the ZipFile.)
 *
 * File information is stored in two places: next to the file data (the Local
 * File Header, and possibly a Data Descriptor), and at the end of the file
 * (the Central Directory Entry).  The two must be kept in sync.
 */
class ZipEntry {
public:
    friend class ZipFile;

    ZipEntry(void)
        : mDeleted(false), mMarked(false)
        {}
    ~ZipEntry(void) {}

    /*
     * Returns "true" if the data is compressed.
     */
    bool isCompressed(void) const {
        return mCDE.mCompressionMethod != kCompressStored;
    }
    int getCompressionMethod(void) const { return mCDE.mCompressionMethod; }

    /*
     * Return the uncompressed length.
     */
    off_t getUncompressedLen(void) const { return mCDE.mUncompressedSize; }

    /*
     * Return the compressed length.  For uncompressed data, this returns
     * the same thing as getUncompresesdLen().
     */
    off_t getCompressedLen(void) const { return mCDE.mCompressedSize; }

    /*
     * Return the offset of the local file header.
     */
    off_t getLFHOffset(void) const { return mCDE.mLocalHeaderRelOffset; }

    /*
     * Return the absolute file offset of the start of the compressed or
     * uncompressed data.
     */
    off_t getFileOffset(void) const {
        return mCDE.mLocalHeaderRelOffset +
                LocalFileHeader::kLFHLen +
                mLFH.mFileNameLength +
                mLFH.mExtraFieldLength;
    }

    /*
     * Return the data CRC.
     */
    unsigned long getCRC32(void) const { return mCDE.mCRC32; }

    /*
     * Return file modification time in UNIX seconds-since-epoch.
     */
    time_t getModWhen(void) const;

    /*
     * Return the archived file name.
     */
    const char* getFileName(void) const { return (const char*) mCDE.mFileName; }

    /*
     * Application-defined "mark".  Can be useful when synchronizing the
     * contents of an archive with contents on disk.
     */
    bool getMarked(void) const { return mMarked; }
    void setMarked(bool val) { mMarked = val; }

    /*
     * Some basic functions for raw data manipulation.  "LE" means
     * Little Endian.
     */
    static inline unsigned short getShortLE(const unsigned char* buf) {
        return buf[0] | (buf[1] << 8);
    }
    static inline unsigned long getLongLE(const unsigned char* buf) {
        return buf[0] | (buf[1] << 8) | (buf[2] << 16) | (buf[3] << 24);
    }
    static inline void putShortLE(unsigned char* buf, short val) {
        buf[0] = (unsigned char) val;
        buf[1] = (unsigned char) (val >> 8);
    }
    static inline void putLongLE(unsigned char* buf, long val) {
        buf[0] = (unsigned char) val;
        buf[1] = (unsigned char) (val >> 8);
        buf[2] = (unsigned char) (val >> 16);
        buf[3] = (unsigned char) (val >> 24);
    }

    /* defined for Zip archives */
    enum {
        kCompressStored     = 0,        // no compression
        // shrunk           = 1,
        // reduced 1        = 2,
        // reduced 2        = 3,
        // reduced 3        = 4,
        // reduced 4        = 5,
        // imploded         = 6,
        // tokenized        = 7,
        kCompressDeflated   = 8,        // standard deflate
        // Deflate64        = 9,
        // lib imploded     = 10,
        // reserved         = 11,
        // bzip2            = 12,
    };

    /*
     * Deletion flag.  If set, the entry will be removed on the next
     * call to "flush".
     */
    bool getDeleted(void) const { return mDeleted; }

protected:
    /*
     * Initialize the structure from the file, which is pointing at
     * our Central Directory entry.
     */
    status_t initFromCDE(FILE* fp);

    /*
     * Initialize the structure for a new file.  We need the filename
     * and comment so that we can properly size the LFH area.  The
     * filename is mandatory, the comment is optional.
     */
    void initNew(const char* fileName, const char* comment);

    /*
     * Initialize the structure with the contents of a ZipEntry from
     * another file.
     */
    status_t initFromExternal(const ZipFile* pZipFile, const ZipEntry* pEntry);

    /*
     * Add some pad bytes to the LFH.  We do this by adding or resizing
     * the "extra" field.
     */
    status_t addPadding(int padding);

    /*
     * Set information about the data for this entry.
     */
    void setDataInfo(long uncompLen, long compLen, unsigned long crc32,
        int compressionMethod);

    /*
     * Set the modification date.
     */
    void setModWhen(time_t when);

    /*
     * Set the offset of the local file header, relative to the start of
     * the current file.
     */
    void setLFHOffset(off_t offset) {
        mCDE.mLocalHeaderRelOffset = (long) offset;
    }

    /* mark for deletion; used by ZipFile::remove() */
    void setDeleted(void) { mDeleted = true; }

private:
    /* these are private and not defined */
    ZipEntry(const ZipEntry& src);
    ZipEntry& operator=(const ZipEntry& src);

    /* returns "true" if the CDE and the LFH agree */
    bool compareHeaders(void) const;
    void copyCDEtoLFH(void);

    bool        mDeleted;       // set if entry is pending deletion
    bool        mMarked;        // app-defined marker

    /*
     * Every entry in the Zip archive starts off with one of these.
     */
    class LocalFileHeader {
    public:
        LocalFileHeader(void) :
            mVersionToExtract(0),
            mGPBitFlag(0),
            mCompressionMethod(0),
            mLastModFileTime(0),
            mLastModFileDate(0),
            mCRC32(0),
            mCompressedSize(0),
            mUncompressedSize(0),
            mFileNameLength(0),
            mExtraFieldLength(0),
            mFileName(NULL),
            mExtraField(NULL)
        {}
        virtual ~LocalFileHeader(void) {
            delete[] mFileName;
            delete[] mExtraField;
        }

        status_t read(FILE* fp);
        status_t write(FILE* fp);

        // unsigned long mSignature;
        unsigned short  mVersionToExtract;
        unsigned short  mGPBitFlag;
        unsigned short  mCompressionMethod;
        unsigned short  mLastModFileTime;
        unsigned short  mLastModFileDate;
        unsigned long   mCRC32;
        unsigned long   mCompressedSize;
        unsigned long   mUncompressedSize;
        unsigned short  mFileNameLength;
        unsigned short  mExtraFieldLength;
        unsigned char*  mFileName;
        unsigned char*  mExtraField;

        enum {
            kSignature      = 0x04034b50,
            kLFHLen         = 30,       // LocalFileHdr len, excl. var fields
        };

        void dump(void) const;
    };

    /*
     * Every entry in the Zip archive has one of these in the "central
     * directory" at the end of the file.
     */
    class CentralDirEntry {
    public:
        CentralDirEntry(void) :
            mVersionMadeBy(0),
            mVersionToExtract(0),
            mGPBitFlag(0),
            mCompressionMethod(0),
            mLastModFileTime(0),
            mLastModFileDate(0),
            mCRC32(0),
            mCompressedSize(0),
            mUncompressedSize(0),
            mFileNameLength(0),
            mExtraFieldLength(0),
            mFileCommentLength(0),
            mDiskNumberStart(0),
            mInternalAttrs(0),
            mExternalAttrs(0),
            mLocalHeaderRelOffset(0),
            mFileName(NULL),
            mExtraField(NULL),
            mFileComment(NULL)
        {}
        virtual ~CentralDirEntry(void) {
            delete[] mFileName;
            delete[] mExtraField;
            delete[] mFileComment;
        }

        status_t read(FILE* fp);
        status_t write(FILE* fp);

        CentralDirEntry& operator=(const CentralDirEntry& src);

        // unsigned long mSignature;
        unsigned short  mVersionMadeBy;
        unsigned short  mVersionToExtract;
        unsigned short  mGPBitFlag;
        unsigned short  mCompressionMethod;
        unsigned short  mLastModFileTime;
        unsigned short  mLastModFileDate;
        unsigned long   mCRC32;
        unsigned long   mCompressedSize;
        unsigned long   mUncompressedSize;
        unsigned short  mFileNameLength;
        unsigned short  mExtraFieldLength;
        unsigned short  mFileCommentLength;
        unsigned short  mDiskNumberStart;
        unsigned short  mInternalAttrs;
        unsigned long   mExternalAttrs;
        unsigned long   mLocalHeaderRelOffset;
        unsigned char*  mFileName;
        unsigned char*  mExtraField;
        unsigned char*  mFileComment;

        void dump(void) const;

        enum {
            kSignature      = 0x02014b50,
            kCDELen         = 46,       // CentralDirEnt len, excl. var fields
        };
    };

    enum {
        //kDataDescriptorSignature  = 0x08074b50,   // currently unused
        kDataDescriptorLen  = 16,           // four 32-bit fields

        kDefaultVersion     = 20,           // need deflate, nothing much else
        kDefaultMadeBy      = 0x0317,       // 03=UNIX, 17=spec v2.3
        kUsesDataDescr      = 0x0008,       // GPBitFlag bit 3
    };

    LocalFileHeader     mLFH;
    CentralDirEntry     mCDE;
};

}; // namespace android

#endif // __LIBS_ZIPENTRY_H
