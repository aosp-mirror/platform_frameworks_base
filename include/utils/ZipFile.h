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
// General-purpose Zip archive access.  This class allows both reading and
// writing to Zip archives, including deletion of existing entries.
//
#ifndef __LIBS_ZIPFILE_H
#define __LIBS_ZIPFILE_H

#include "ZipEntry.h"
#include "Vector.h"
#include "Errors.h"
#include <stdio.h>

namespace android {

/*
 * Manipulate a Zip archive.
 *
 * Some changes will not be visible in the until until "flush" is called.
 *
 * The correct way to update a file archive is to make all changes to a
 * copy of the archive in a temporary file, and then unlink/rename over
 * the original after everything completes.  Because we're only interested
 * in using this for packaging, we don't worry about such things.  Crashing
 * after making changes and before flush() completes could leave us with
 * an unusable Zip archive.
 */
class ZipFile {
public:
    ZipFile(void)
      : mZipFp(NULL), mReadOnly(false), mNeedCDRewrite(false)
      {}
    ~ZipFile(void) {
        if (!mReadOnly)
            flush();
        if (mZipFp != NULL)
            fclose(mZipFp);
        discardEntries();
    }

    /*
     * Open a new or existing archive.
     */
    typedef enum {
        kOpenReadOnly   = 0x01,
        kOpenReadWrite  = 0x02,
        kOpenCreate     = 0x04,     // create if it doesn't exist
        kOpenTruncate   = 0x08,     // if it exists, empty it
    };
    status_t open(const char* zipFileName, int flags);

    /*
     * Add a file to the end of the archive.  Specify whether you want the
     * library to try to store it compressed.
     *
     * If "storageName" is specified, the archive will use that instead
     * of "fileName".
     *
     * If there is already an entry with the same name, the call fails.
     * Existing entries with the same name must be removed first.
     *
     * If "ppEntry" is non-NULL, a pointer to the new entry will be returned.
     */
    status_t add(const char* fileName, int compressionMethod,
        ZipEntry** ppEntry)
    {
        return add(fileName, fileName, compressionMethod, ppEntry);
    }
    status_t add(const char* fileName, const char* storageName,
        int compressionMethod, ZipEntry** ppEntry)
    {
        return addCommon(fileName, NULL, 0, storageName,
                         ZipEntry::kCompressStored,
                         compressionMethod, ppEntry);
    }

    /*
     * Add a file that is already compressed with gzip.
     *
     * If "ppEntry" is non-NULL, a pointer to the new entry will be returned.
     */
    status_t addGzip(const char* fileName, const char* storageName,
        ZipEntry** ppEntry)
    {
        return addCommon(fileName, NULL, 0, storageName,
                         ZipEntry::kCompressDeflated,
                         ZipEntry::kCompressDeflated, ppEntry);
    }

    /*
     * Add a file from an in-memory data buffer.
     *
     * If "ppEntry" is non-NULL, a pointer to the new entry will be returned.
     */
    status_t add(const void* data, size_t size, const char* storageName,
        int compressionMethod, ZipEntry** ppEntry)
    {
        return addCommon(NULL, data, size, storageName,
                         ZipEntry::kCompressStored,
                         compressionMethod, ppEntry);
    }

    /*
     * Add an entry by copying it from another zip file.  If "padding" is
     * nonzero, the specified number of bytes will be added to the "extra"
     * field in the header.
     *
     * If "ppEntry" is non-NULL, a pointer to the new entry will be returned.
     */
    status_t add(const ZipFile* pSourceZip, const ZipEntry* pSourceEntry,
        int padding, ZipEntry** ppEntry);

    /*
     * Mark an entry as having been removed.  It is not actually deleted
     * from the archive or our internal data structures until flush() is
     * called.
     */
    status_t remove(ZipEntry* pEntry);

    /*
     * Flush changes.  If mNeedCDRewrite is set, this writes the central dir.
     */
    status_t flush(void);

    /*
     * Expand the data into the buffer provided.  The buffer must hold
     * at least <uncompressed len> bytes.  Variation expands directly
     * to a file.
     *
     * Returns "false" if an error was encountered in the compressed data.
     */
    //bool uncompress(const ZipEntry* pEntry, void* buf) const;
    //bool uncompress(const ZipEntry* pEntry, FILE* fp) const;
    void* uncompress(const ZipEntry* pEntry);

    /*
     * Get an entry, by name.  Returns NULL if not found.
     *
     * Does not return entries pending deletion.
     */
    ZipEntry* getEntryByName(const char* fileName) const;

    /*
     * Get the Nth entry in the archive.
     *
     * This will return an entry that is pending deletion.
     */
    int getNumEntries(void) const { return mEntries.size(); }
    ZipEntry* getEntryByIndex(int idx) const;

private:
    /* these are private and not defined */
    ZipFile(const ZipFile& src);
    ZipFile& operator=(const ZipFile& src);

    class EndOfCentralDir {
    public:
        EndOfCentralDir(void) :
            mDiskNumber(0),
            mDiskWithCentralDir(0),
            mNumEntries(0),
            mTotalNumEntries(0),
            mCentralDirSize(0),
            mCentralDirOffset(0),
            mCommentLen(0),
            mComment(NULL)
            {}
        virtual ~EndOfCentralDir(void) {
            delete[] mComment;
        }

        status_t readBuf(const unsigned char* buf, int len);
        status_t write(FILE* fp);

        //unsigned long   mSignature;
        unsigned short  mDiskNumber;
        unsigned short  mDiskWithCentralDir;
        unsigned short  mNumEntries;
        unsigned short  mTotalNumEntries;
        unsigned long   mCentralDirSize;
        unsigned long   mCentralDirOffset;      // offset from first disk
        unsigned short  mCommentLen;
        unsigned char*  mComment;

        enum {
            kSignature      = 0x06054b50,
            kEOCDLen        = 22,       // EndOfCentralDir len, excl. comment

            kMaxCommentLen  = 65535,    // longest possible in ushort
            kMaxEOCDSearch  = kMaxCommentLen + EndOfCentralDir::kEOCDLen,

        };

        void dump(void) const;
    };


    /* read all entries in the central dir */
    status_t readCentralDir(void);

    /* crunch deleted entries out */
    status_t crunchArchive(void);

    /* clean up mEntries */
    void discardEntries(void);

    /* common handler for all "add" functions */
    status_t addCommon(const char* fileName, const void* data, size_t size,
        const char* storageName, int sourceType, int compressionMethod,
        ZipEntry** ppEntry);

    /* copy all of "srcFp" into "dstFp" */
    status_t copyFpToFp(FILE* dstFp, FILE* srcFp, unsigned long* pCRC32);
    /* copy all of "data" into "dstFp" */
    status_t copyDataToFp(FILE* dstFp,
        const void* data, size_t size, unsigned long* pCRC32);
    /* copy some of "srcFp" into "dstFp" */
    status_t copyPartialFpToFp(FILE* dstFp, FILE* srcFp, long length,
        unsigned long* pCRC32);
    /* like memmove(), but on parts of a single file */
    status_t filemove(FILE* fp, off_t dest, off_t src, size_t n);
    /* compress all of "srcFp" into "dstFp", using Deflate */
    status_t compressFpToFp(FILE* dstFp, FILE* srcFp,
        const void* data, size_t size, unsigned long* pCRC32);

    /* get modification date from a file descriptor */
    time_t getModTime(int fd);

    /*
     * We use stdio FILE*, which gives us buffering but makes dealing
     * with files >2GB awkward.  Until we support Zip64, we're fine.
     */
    FILE*           mZipFp;             // Zip file pointer

    /* one of these per file */
    EndOfCentralDir mEOCD;

    /* did we open this read-only? */
    bool            mReadOnly;

    /* set this when we trash the central dir */
    bool            mNeedCDRewrite;

    /*
     * One ZipEntry per entry in the zip file.  I'm using pointers instead
     * of objects because it's easier than making operator= work for the
     * classes and sub-classes.
     */
    Vector<ZipEntry*>   mEntries;
};

}; // namespace android

#endif // __LIBS_ZIPFILE_H
