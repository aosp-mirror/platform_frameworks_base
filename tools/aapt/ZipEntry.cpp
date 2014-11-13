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
// Access to entries in a Zip archive.
//

#define LOG_TAG "zip"

#include "ZipEntry.h"
#include <utils/Log.h>

#include <stdio.h>
#include <string.h>
#include <assert.h>

using namespace android;

/*
 * Initialize a new ZipEntry structure from a FILE* positioned at a
 * CentralDirectoryEntry.
 *
 * On exit, the file pointer will be at the start of the next CDE or
 * at the EOCD.
 */
status_t ZipEntry::initFromCDE(FILE* fp)
{
    status_t result;
    long posn;
    bool hasDD;

    //ALOGV("initFromCDE ---\n");

    /* read the CDE */
    result = mCDE.read(fp);
    if (result != NO_ERROR) {
        ALOGD("mCDE.read failed\n");
        return result;
    }

    //mCDE.dump();

    /* using the info in the CDE, go load up the LFH */
    posn = ftell(fp);
    if (fseek(fp, mCDE.mLocalHeaderRelOffset, SEEK_SET) != 0) {
        ALOGD("local header seek failed (%ld)\n",
            mCDE.mLocalHeaderRelOffset);
        return UNKNOWN_ERROR;
    }

    result = mLFH.read(fp);
    if (result != NO_ERROR) {
        ALOGD("mLFH.read failed\n");
        return result;
    }

    if (fseek(fp, posn, SEEK_SET) != 0)
        return UNKNOWN_ERROR;

    //mLFH.dump();

    /*
     * We *might* need to read the Data Descriptor at this point and
     * integrate it into the LFH.  If this bit is set, the CRC-32,
     * compressed size, and uncompressed size will be zero.  In practice
     * these seem to be rare.
     */
    hasDD = (mLFH.mGPBitFlag & kUsesDataDescr) != 0;
    if (hasDD) {
        // do something clever
        //ALOGD("+++ has data descriptor\n");
    }

    /*
     * Sanity-check the LFH.  Note that this will fail if the "kUsesDataDescr"
     * flag is set, because the LFH is incomplete.  (Not a problem, since we
     * prefer the CDE values.)
     */
    if (!hasDD && !compareHeaders()) {
        ALOGW("warning: header mismatch\n");
        // keep going?
    }

    /*
     * If the mVersionToExtract is greater than 20, we may have an
     * issue unpacking the record -- could be encrypted, compressed
     * with something we don't support, or use Zip64 extensions.  We
     * can defer worrying about that to when we're extracting data.
     */

    return NO_ERROR;
}

/*
 * Initialize a new entry.  Pass in the file name and an optional comment.
 *
 * Initializes the CDE and the LFH.
 */
void ZipEntry::initNew(const char* fileName, const char* comment)
{
    assert(fileName != NULL && *fileName != '\0');  // name required

    /* most fields are properly initialized by constructor */
    mCDE.mVersionMadeBy = kDefaultMadeBy;
    mCDE.mVersionToExtract = kDefaultVersion;
    mCDE.mCompressionMethod = kCompressStored;
    mCDE.mFileNameLength = strlen(fileName);
    if (comment != NULL)
        mCDE.mFileCommentLength = strlen(comment);
    mCDE.mExternalAttrs = 0x81b60020;   // matches what WinZip does

    if (mCDE.mFileNameLength > 0) {
        mCDE.mFileName = new unsigned char[mCDE.mFileNameLength+1];
        strcpy((char*) mCDE.mFileName, fileName);
    }
    if (mCDE.mFileCommentLength > 0) {
        /* TODO: stop assuming null-terminated ASCII here? */
        mCDE.mFileComment = new unsigned char[mCDE.mFileCommentLength+1];
        strcpy((char*) mCDE.mFileComment, comment);
    }

    copyCDEtoLFH();
}

/*
 * Initialize a new entry, starting with the ZipEntry from a different
 * archive.
 *
 * Initializes the CDE and the LFH.
 */
status_t ZipEntry::initFromExternal(const ZipFile* /* pZipFile */,
    const ZipEntry* pEntry)
{
    mCDE = pEntry->mCDE;
    // Check whether we got all the memory needed.
    if ((mCDE.mFileNameLength > 0 && mCDE.mFileName == NULL) ||
            (mCDE.mFileCommentLength > 0 && mCDE.mFileComment == NULL) ||
            (mCDE.mExtraFieldLength > 0 && mCDE.mExtraField == NULL)) {
        return NO_MEMORY;
    }

    /* construct the LFH from the CDE */
    copyCDEtoLFH();

    /*
     * The LFH "extra" field is independent of the CDE "extra", so we
     * handle it here.
     */
    assert(mLFH.mExtraField == NULL);
    mLFH.mExtraFieldLength = pEntry->mLFH.mExtraFieldLength;
    if (mLFH.mExtraFieldLength > 0) {
        mLFH.mExtraField = new unsigned char[mLFH.mExtraFieldLength+1];
        if (mLFH.mExtraField == NULL)
            return NO_MEMORY;
        memcpy(mLFH.mExtraField, pEntry->mLFH.mExtraField,
            mLFH.mExtraFieldLength+1);
    }

    return NO_ERROR;
}

/*
 * Insert pad bytes in the LFH by tweaking the "extra" field.  This will
 * potentially confuse something that put "extra" data in here earlier,
 * but I can't find an actual problem.
 */
status_t ZipEntry::addPadding(int padding)
{
    if (padding <= 0)
        return INVALID_OPERATION;

    //ALOGI("HEY: adding %d pad bytes to existing %d in %s\n",
    //    padding, mLFH.mExtraFieldLength, mCDE.mFileName);

    if (mLFH.mExtraFieldLength > 0) {
        /* extend existing field */
        unsigned char* newExtra;

        newExtra = new unsigned char[mLFH.mExtraFieldLength + padding];
        if (newExtra == NULL)
            return NO_MEMORY;
        memset(newExtra + mLFH.mExtraFieldLength, 0, padding);
        memcpy(newExtra, mLFH.mExtraField, mLFH.mExtraFieldLength);

        delete[] mLFH.mExtraField;
        mLFH.mExtraField = newExtra;
        mLFH.mExtraFieldLength += padding;
    } else {
        /* create new field */
        mLFH.mExtraField = new unsigned char[padding];
        memset(mLFH.mExtraField, 0, padding);
        mLFH.mExtraFieldLength = padding;
    }

    return NO_ERROR;
}

/*
 * Set the fields in the LFH equal to the corresponding fields in the CDE.
 *
 * This does not touch the LFH "extra" field.
 */
void ZipEntry::copyCDEtoLFH(void)
{
    mLFH.mVersionToExtract  = mCDE.mVersionToExtract;
    mLFH.mGPBitFlag         = mCDE.mGPBitFlag;
    mLFH.mCompressionMethod = mCDE.mCompressionMethod;
    mLFH.mLastModFileTime   = mCDE.mLastModFileTime;
    mLFH.mLastModFileDate   = mCDE.mLastModFileDate;
    mLFH.mCRC32             = mCDE.mCRC32;
    mLFH.mCompressedSize    = mCDE.mCompressedSize;
    mLFH.mUncompressedSize  = mCDE.mUncompressedSize;
    mLFH.mFileNameLength    = mCDE.mFileNameLength;
    // the "extra field" is independent

    delete[] mLFH.mFileName;
    if (mLFH.mFileNameLength > 0) {
        mLFH.mFileName = new unsigned char[mLFH.mFileNameLength+1];
        strcpy((char*) mLFH.mFileName, (const char*) mCDE.mFileName);
    } else {
        mLFH.mFileName = NULL;
    }
}

/*
 * Set some information about a file after we add it.
 */
void ZipEntry::setDataInfo(long uncompLen, long compLen, unsigned long crc32,
    int compressionMethod)
{
    mCDE.mCompressionMethod = compressionMethod;
    mCDE.mCRC32 = crc32;
    mCDE.mCompressedSize = compLen;
    mCDE.mUncompressedSize = uncompLen;
    mCDE.mCompressionMethod = compressionMethod;
    if (compressionMethod == kCompressDeflated) {
        mCDE.mGPBitFlag |= 0x0002;      // indicates maximum compression used
    }
    copyCDEtoLFH();
}

/*
 * See if the data in mCDE and mLFH match up.  This is mostly useful for
 * debugging these classes, but it can be used to identify damaged
 * archives.
 *
 * Returns "false" if they differ.
 */
bool ZipEntry::compareHeaders(void) const
{
    if (mCDE.mVersionToExtract != mLFH.mVersionToExtract) {
        ALOGV("cmp: VersionToExtract\n");
        return false;
    }
    if (mCDE.mGPBitFlag != mLFH.mGPBitFlag) {
        ALOGV("cmp: GPBitFlag\n");
        return false;
    }
    if (mCDE.mCompressionMethod != mLFH.mCompressionMethod) {
        ALOGV("cmp: CompressionMethod\n");
        return false;
    }
    if (mCDE.mLastModFileTime != mLFH.mLastModFileTime) {
        ALOGV("cmp: LastModFileTime\n");
        return false;
    }
    if (mCDE.mLastModFileDate != mLFH.mLastModFileDate) {
        ALOGV("cmp: LastModFileDate\n");
        return false;
    }
    if (mCDE.mCRC32 != mLFH.mCRC32) {
        ALOGV("cmp: CRC32\n");
        return false;
    }
    if (mCDE.mCompressedSize != mLFH.mCompressedSize) {
        ALOGV("cmp: CompressedSize\n");
        return false;
    }
    if (mCDE.mUncompressedSize != mLFH.mUncompressedSize) {
        ALOGV("cmp: UncompressedSize\n");
        return false;
    }
    if (mCDE.mFileNameLength != mLFH.mFileNameLength) {
        ALOGV("cmp: FileNameLength\n");
        return false;
    }
#if 0       // this seems to be used for padding, not real data
    if (mCDE.mExtraFieldLength != mLFH.mExtraFieldLength) {
        ALOGV("cmp: ExtraFieldLength\n");
        return false;
    }
#endif
    if (mCDE.mFileName != NULL) {
        if (strcmp((char*) mCDE.mFileName, (char*) mLFH.mFileName) != 0) {
            ALOGV("cmp: FileName\n");
            return false;
        }
    }

    return true;
}


/*
 * Convert the DOS date/time stamp into a UNIX time stamp.
 */
time_t ZipEntry::getModWhen(void) const
{
    struct tm parts;

    parts.tm_sec = (mCDE.mLastModFileTime & 0x001f) << 1;
    parts.tm_min = (mCDE.mLastModFileTime & 0x07e0) >> 5;
    parts.tm_hour = (mCDE.mLastModFileTime & 0xf800) >> 11;
    parts.tm_mday = (mCDE.mLastModFileDate & 0x001f);
    parts.tm_mon = ((mCDE.mLastModFileDate & 0x01e0) >> 5) -1;
    parts.tm_year = ((mCDE.mLastModFileDate & 0xfe00) >> 9) + 80;
    parts.tm_wday = parts.tm_yday = 0;
    parts.tm_isdst = -1;        // DST info "not available"

    return mktime(&parts);
}

/*
 * Set the CDE/LFH timestamp from UNIX time.
 */
void ZipEntry::setModWhen(time_t when)
{
#if !defined(_WIN32)
    struct tm tmResult;
#endif
    time_t even;
    unsigned short zdate, ztime;

    struct tm* ptm;

    /* round up to an even number of seconds */
    even = (time_t)(((unsigned long)(when) + 1) & (~1));

    /* expand */
#if !defined(_WIN32)
    ptm = localtime_r(&even, &tmResult);
#else
    ptm = localtime(&even);
#endif

    int year;
    year = ptm->tm_year;
    if (year < 80)
        year = 80;

    zdate = (year - 80) << 9 | (ptm->tm_mon+1) << 5 | ptm->tm_mday;
    ztime = ptm->tm_hour << 11 | ptm->tm_min << 5 | ptm->tm_sec >> 1;

    mCDE.mLastModFileTime = mLFH.mLastModFileTime = ztime;
    mCDE.mLastModFileDate = mLFH.mLastModFileDate = zdate;
}


/*
 * ===========================================================================
 *      ZipEntry::LocalFileHeader
 * ===========================================================================
 */

/*
 * Read a local file header.
 *
 * On entry, "fp" points to the signature at the start of the header.
 * On exit, "fp" points to the start of data.
 */
status_t ZipEntry::LocalFileHeader::read(FILE* fp)
{
    status_t result = NO_ERROR;
    unsigned char buf[kLFHLen];

    assert(mFileName == NULL);
    assert(mExtraField == NULL);

    if (fread(buf, 1, kLFHLen, fp) != kLFHLen) {
        result = UNKNOWN_ERROR;
        goto bail;
    }

    if (ZipEntry::getLongLE(&buf[0x00]) != kSignature) {
        ALOGD("whoops: didn't find expected signature\n");
        result = UNKNOWN_ERROR;
        goto bail;
    }

    mVersionToExtract = ZipEntry::getShortLE(&buf[0x04]);
    mGPBitFlag = ZipEntry::getShortLE(&buf[0x06]);
    mCompressionMethod = ZipEntry::getShortLE(&buf[0x08]);
    mLastModFileTime = ZipEntry::getShortLE(&buf[0x0a]);
    mLastModFileDate = ZipEntry::getShortLE(&buf[0x0c]);
    mCRC32 = ZipEntry::getLongLE(&buf[0x0e]);
    mCompressedSize = ZipEntry::getLongLE(&buf[0x12]);
    mUncompressedSize = ZipEntry::getLongLE(&buf[0x16]);
    mFileNameLength = ZipEntry::getShortLE(&buf[0x1a]);
    mExtraFieldLength = ZipEntry::getShortLE(&buf[0x1c]);

    // TODO: validate sizes

    /* grab filename */
    if (mFileNameLength != 0) {
        mFileName = new unsigned char[mFileNameLength+1];
        if (mFileName == NULL) {
            result = NO_MEMORY;
            goto bail;
        }
        if (fread(mFileName, 1, mFileNameLength, fp) != mFileNameLength) {
            result = UNKNOWN_ERROR;
            goto bail;
        }
        mFileName[mFileNameLength] = '\0';
    }

    /* grab extra field */
    if (mExtraFieldLength != 0) {
        mExtraField = new unsigned char[mExtraFieldLength+1];
        if (mExtraField == NULL) {
            result = NO_MEMORY;
            goto bail;
        }
        if (fread(mExtraField, 1, mExtraFieldLength, fp) != mExtraFieldLength) {
            result = UNKNOWN_ERROR;
            goto bail;
        }
        mExtraField[mExtraFieldLength] = '\0';
    }

bail:
    return result;
}

/*
 * Write a local file header.
 */
status_t ZipEntry::LocalFileHeader::write(FILE* fp)
{
    unsigned char buf[kLFHLen];

    ZipEntry::putLongLE(&buf[0x00], kSignature);
    ZipEntry::putShortLE(&buf[0x04], mVersionToExtract);
    ZipEntry::putShortLE(&buf[0x06], mGPBitFlag);
    ZipEntry::putShortLE(&buf[0x08], mCompressionMethod);
    ZipEntry::putShortLE(&buf[0x0a], mLastModFileTime);
    ZipEntry::putShortLE(&buf[0x0c], mLastModFileDate);
    ZipEntry::putLongLE(&buf[0x0e], mCRC32);
    ZipEntry::putLongLE(&buf[0x12], mCompressedSize);
    ZipEntry::putLongLE(&buf[0x16], mUncompressedSize);
    ZipEntry::putShortLE(&buf[0x1a], mFileNameLength);
    ZipEntry::putShortLE(&buf[0x1c], mExtraFieldLength);

    if (fwrite(buf, 1, kLFHLen, fp) != kLFHLen)
        return UNKNOWN_ERROR;

    /* write filename */
    if (mFileNameLength != 0) {
        if (fwrite(mFileName, 1, mFileNameLength, fp) != mFileNameLength)
            return UNKNOWN_ERROR;
    }

    /* write "extra field" */
    if (mExtraFieldLength != 0) {
        if (fwrite(mExtraField, 1, mExtraFieldLength, fp) != mExtraFieldLength)
            return UNKNOWN_ERROR;
    }

    return NO_ERROR;
}


/*
 * Dump the contents of a LocalFileHeader object.
 */
void ZipEntry::LocalFileHeader::dump(void) const
{
    ALOGD(" LocalFileHeader contents:\n");
    ALOGD("  versToExt=%u gpBits=0x%04x compression=%u\n",
        mVersionToExtract, mGPBitFlag, mCompressionMethod);
    ALOGD("  modTime=0x%04x modDate=0x%04x crc32=0x%08lx\n",
        mLastModFileTime, mLastModFileDate, mCRC32);
    ALOGD("  compressedSize=%lu uncompressedSize=%lu\n",
        mCompressedSize, mUncompressedSize);
    ALOGD("  filenameLen=%u extraLen=%u\n",
        mFileNameLength, mExtraFieldLength);
    if (mFileName != NULL)
        ALOGD("  filename: '%s'\n", mFileName);
}


/*
 * ===========================================================================
 *      ZipEntry::CentralDirEntry
 * ===========================================================================
 */

/*
 * Read the central dir entry that appears next in the file.
 *
 * On entry, "fp" should be positioned on the signature bytes for the
 * entry.  On exit, "fp" will point at the signature word for the next
 * entry or for the EOCD.
 */
status_t ZipEntry::CentralDirEntry::read(FILE* fp)
{
    status_t result = NO_ERROR;
    unsigned char buf[kCDELen];

    /* no re-use */
    assert(mFileName == NULL);
    assert(mExtraField == NULL);
    assert(mFileComment == NULL);

    if (fread(buf, 1, kCDELen, fp) != kCDELen) {
        result = UNKNOWN_ERROR;
        goto bail;
    }

    if (ZipEntry::getLongLE(&buf[0x00]) != kSignature) {
        ALOGD("Whoops: didn't find expected signature\n");
        result = UNKNOWN_ERROR;
        goto bail;
    }

    mVersionMadeBy = ZipEntry::getShortLE(&buf[0x04]);
    mVersionToExtract = ZipEntry::getShortLE(&buf[0x06]);
    mGPBitFlag = ZipEntry::getShortLE(&buf[0x08]);
    mCompressionMethod = ZipEntry::getShortLE(&buf[0x0a]);
    mLastModFileTime = ZipEntry::getShortLE(&buf[0x0c]);
    mLastModFileDate = ZipEntry::getShortLE(&buf[0x0e]);
    mCRC32 = ZipEntry::getLongLE(&buf[0x10]);
    mCompressedSize = ZipEntry::getLongLE(&buf[0x14]);
    mUncompressedSize = ZipEntry::getLongLE(&buf[0x18]);
    mFileNameLength = ZipEntry::getShortLE(&buf[0x1c]);
    mExtraFieldLength = ZipEntry::getShortLE(&buf[0x1e]);
    mFileCommentLength = ZipEntry::getShortLE(&buf[0x20]);
    mDiskNumberStart = ZipEntry::getShortLE(&buf[0x22]);
    mInternalAttrs = ZipEntry::getShortLE(&buf[0x24]);
    mExternalAttrs = ZipEntry::getLongLE(&buf[0x26]);
    mLocalHeaderRelOffset = ZipEntry::getLongLE(&buf[0x2a]);

    // TODO: validate sizes and offsets

    /* grab filename */
    if (mFileNameLength != 0) {
        mFileName = new unsigned char[mFileNameLength+1];
        if (mFileName == NULL) {
            result = NO_MEMORY;
            goto bail;
        }
        if (fread(mFileName, 1, mFileNameLength, fp) != mFileNameLength) {
            result = UNKNOWN_ERROR;
            goto bail;
        }
        mFileName[mFileNameLength] = '\0';
    }

    /* read "extra field" */
    if (mExtraFieldLength != 0) {
        mExtraField = new unsigned char[mExtraFieldLength+1];
        if (mExtraField == NULL) {
            result = NO_MEMORY;
            goto bail;
        }
        if (fread(mExtraField, 1, mExtraFieldLength, fp) != mExtraFieldLength) {
            result = UNKNOWN_ERROR;
            goto bail;
        }
        mExtraField[mExtraFieldLength] = '\0';
    }


    /* grab comment, if any */
    if (mFileCommentLength != 0) {
        mFileComment = new unsigned char[mFileCommentLength+1];
        if (mFileComment == NULL) {
            result = NO_MEMORY;
            goto bail;
        }
        if (fread(mFileComment, 1, mFileCommentLength, fp) != mFileCommentLength)
        {
            result = UNKNOWN_ERROR;
            goto bail;
        }
        mFileComment[mFileCommentLength] = '\0';
    }

bail:
    return result;
}

/*
 * Write a central dir entry.
 */
status_t ZipEntry::CentralDirEntry::write(FILE* fp)
{
    unsigned char buf[kCDELen];

    ZipEntry::putLongLE(&buf[0x00], kSignature);
    ZipEntry::putShortLE(&buf[0x04], mVersionMadeBy);
    ZipEntry::putShortLE(&buf[0x06], mVersionToExtract);
    ZipEntry::putShortLE(&buf[0x08], mGPBitFlag);
    ZipEntry::putShortLE(&buf[0x0a], mCompressionMethod);
    ZipEntry::putShortLE(&buf[0x0c], mLastModFileTime);
    ZipEntry::putShortLE(&buf[0x0e], mLastModFileDate);
    ZipEntry::putLongLE(&buf[0x10], mCRC32);
    ZipEntry::putLongLE(&buf[0x14], mCompressedSize);
    ZipEntry::putLongLE(&buf[0x18], mUncompressedSize);
    ZipEntry::putShortLE(&buf[0x1c], mFileNameLength);
    ZipEntry::putShortLE(&buf[0x1e], mExtraFieldLength);
    ZipEntry::putShortLE(&buf[0x20], mFileCommentLength);
    ZipEntry::putShortLE(&buf[0x22], mDiskNumberStart);
    ZipEntry::putShortLE(&buf[0x24], mInternalAttrs);
    ZipEntry::putLongLE(&buf[0x26], mExternalAttrs);
    ZipEntry::putLongLE(&buf[0x2a], mLocalHeaderRelOffset);

    if (fwrite(buf, 1, kCDELen, fp) != kCDELen)
        return UNKNOWN_ERROR;

    /* write filename */
    if (mFileNameLength != 0) {
        if (fwrite(mFileName, 1, mFileNameLength, fp) != mFileNameLength)
            return UNKNOWN_ERROR;
    }

    /* write "extra field" */
    if (mExtraFieldLength != 0) {
        if (fwrite(mExtraField, 1, mExtraFieldLength, fp) != mExtraFieldLength)
            return UNKNOWN_ERROR;
    }

    /* write comment */
    if (mFileCommentLength != 0) {
        if (fwrite(mFileComment, 1, mFileCommentLength, fp) != mFileCommentLength)
            return UNKNOWN_ERROR;
    }

    return NO_ERROR;
}

/*
 * Dump the contents of a CentralDirEntry object.
 */
void ZipEntry::CentralDirEntry::dump(void) const
{
    ALOGD(" CentralDirEntry contents:\n");
    ALOGD("  versMadeBy=%u versToExt=%u gpBits=0x%04x compression=%u\n",
        mVersionMadeBy, mVersionToExtract, mGPBitFlag, mCompressionMethod);
    ALOGD("  modTime=0x%04x modDate=0x%04x crc32=0x%08lx\n",
        mLastModFileTime, mLastModFileDate, mCRC32);
    ALOGD("  compressedSize=%lu uncompressedSize=%lu\n",
        mCompressedSize, mUncompressedSize);
    ALOGD("  filenameLen=%u extraLen=%u commentLen=%u\n",
        mFileNameLength, mExtraFieldLength, mFileCommentLength);
    ALOGD("  diskNumStart=%u intAttr=0x%04x extAttr=0x%08lx relOffset=%lu\n",
        mDiskNumberStart, mInternalAttrs, mExternalAttrs,
        mLocalHeaderRelOffset);

    if (mFileName != NULL)
        ALOGD("  filename: '%s'\n", mFileName);
    if (mFileComment != NULL)
        ALOGD("  comment: '%s'\n", mFileComment);
}

/*
 * Copy-assignment operator for CentralDirEntry.
 */
ZipEntry::CentralDirEntry& ZipEntry::CentralDirEntry::operator=(const ZipEntry::CentralDirEntry& src) {
    if (this == &src) {
        return *this;
    }

    // Free up old data.
    delete[] mFileName;
    delete[] mExtraField;
    delete[] mFileComment;

    // Copy scalars.
    mVersionMadeBy = src.mVersionMadeBy;
    mVersionToExtract = src.mVersionToExtract;
    mGPBitFlag = src.mGPBitFlag;
    mCompressionMethod = src.mCompressionMethod;
    mLastModFileTime = src.mLastModFileTime;
    mLastModFileDate = src.mLastModFileDate;
    mCRC32 = src.mCRC32;
    mCompressedSize = src.mCompressedSize;
    mUncompressedSize = src.mUncompressedSize;
    mFileNameLength = src.mFileNameLength;
    mExtraFieldLength = src.mExtraFieldLength;
    mFileCommentLength = src.mFileCommentLength;
    mDiskNumberStart = src.mDiskNumberStart;
    mInternalAttrs = src.mInternalAttrs;
    mExternalAttrs = src.mExternalAttrs;
    mLocalHeaderRelOffset = src.mLocalHeaderRelOffset;

    // Copy strings, if necessary.
    if (mFileNameLength > 0) {
        mFileName = new unsigned char[mFileNameLength + 1];
        if (mFileName != NULL)
            strcpy((char*)mFileName, (char*)src.mFileName);
    } else {
        mFileName = NULL;
    }
    if (mFileCommentLength > 0) {
        mFileComment = new unsigned char[mFileCommentLength + 1];
        if (mFileComment != NULL)
            strcpy((char*)mFileComment, (char*)src.mFileComment);
    } else {
        mFileComment = NULL;
    }
    if (mExtraFieldLength > 0) {
        /* we null-terminate this, though it may not be a string */
        mExtraField = new unsigned char[mExtraFieldLength + 1];
        if (mExtraField != NULL)
            memcpy(mExtraField, src.mExtraField, mExtraFieldLength + 1);
    } else {
        mExtraField = NULL;
    }

    return *this;
}
