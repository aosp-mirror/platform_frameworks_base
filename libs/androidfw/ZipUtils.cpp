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
// Misc zip/gzip utility functions.
//

#define LOG_TAG "ziputil"

#include "android-base/file.h"
#include <androidfw/ZipUtils.h>
#include <utils/Log.h>
#include <utils/Compat.h>
#include <ziparchive/zip_archive.h>

#include <stdlib.h>
#include <string.h>
#include <assert.h>

#include <zlib.h>

using namespace android;

// TODO: This can go away once the only remaining usage in aapt goes away.
class FileReader : public zip_archive::Reader {
  public:
    FileReader(FILE* fp) : Reader(), mFp(fp), mCurrentOffset(0) {
    }

    bool ReadAtOffset(uint8_t* buf, size_t len, uint32_t offset) const {
        // Data is usually requested sequentially, so this helps avoid pointless
        // fseeks every time we perform a read. There's an impedence mismatch
        // here because the original API was designed around pread and pwrite.
        if (offset != mCurrentOffset) {
            if (fseek(mFp, offset, SEEK_SET) != 0) {
                return false;
            }

            mCurrentOffset = offset;
        }

        size_t read = fread(buf, 1, len, mFp);
        if (read != len) {
            return false;
        }

        mCurrentOffset += read;
        return true;
    }

  private:
    FILE* mFp;
    mutable uint32_t mCurrentOffset;
};

class FdReader : public zip_archive::Reader {
  public:
    explicit FdReader(int fd) : mFd(fd) {
    }

    bool ReadAtOffset(uint8_t* buf, size_t len, uint32_t offset) const {
      return android::base::ReadFullyAtOffset(mFd, buf, len, static_cast<off_t>(offset));
    }

  private:
    const int mFd;
};

class BufferReader : public zip_archive::Reader {
  public:
    BufferReader(const void* input, size_t inputSize) : Reader(),
        mInput(reinterpret_cast<const uint8_t*>(input)),
        mInputSize(inputSize) {
    }

    bool ReadAtOffset(uint8_t* buf, size_t len, uint32_t offset) const {
        if (offset + len > mInputSize) {
            return false;
        }

        memcpy(buf, mInput + offset, len);
        return true;
    }

  private:
    const uint8_t* mInput;
    const size_t mInputSize;
};

class BufferWriter : public zip_archive::Writer {
  public:
    BufferWriter(void* output, size_t outputSize) : Writer(),
        mOutput(reinterpret_cast<uint8_t*>(output)), mOutputSize(outputSize), mBytesWritten(0) {
    }

    bool Append(uint8_t* buf, size_t bufSize) override {
        if (mBytesWritten + bufSize > mOutputSize) {
            return false;
        }

        memcpy(mOutput + mBytesWritten, buf, bufSize);
        mBytesWritten += bufSize;
        return true;
    }

  private:
    uint8_t* const mOutput;
    const size_t mOutputSize;
    size_t mBytesWritten;
};

/*static*/ bool ZipUtils::inflateToBuffer(FILE* fp, void* buf,
    long uncompressedLen, long compressedLen)
{
    FileReader reader(fp);
    BufferWriter writer(buf, uncompressedLen);
    return (zip_archive::Inflate(reader, compressedLen, uncompressedLen, &writer, nullptr) == 0);
}

/*static*/ bool ZipUtils::inflateToBuffer(int fd, void* buf,
    long uncompressedLen, long compressedLen)
{
    FdReader reader(fd);
    BufferWriter writer(buf, uncompressedLen);
    return (zip_archive::Inflate(reader, compressedLen, uncompressedLen, &writer, nullptr) == 0);
}

/*static*/ bool ZipUtils::inflateToBuffer(const void* in, void* buf,
    long uncompressedLen, long compressedLen)
{
    BufferReader reader(in, compressedLen);
    BufferWriter writer(buf, uncompressedLen);
    return (zip_archive::Inflate(reader, compressedLen, uncompressedLen, &writer, nullptr) == 0);
}

static inline unsigned long get4LE(const unsigned char* buf) {
    return buf[0] | (buf[1] << 8) | (buf[2] << 16) | (buf[3] << 24);
}

/*
 * Look at the contents of a gzip archive.  We want to know where the
 * data starts, and how long it will be after it is uncompressed.
 *
 * We expect to find the CRC and length as the last 8 bytes on the file.
 * This is a pretty reasonable thing to expect for locally-compressed
 * files, but there's a small chance that some extra padding got thrown
 * on (the man page talks about compressed data written to tape).  We
 * don't currently deal with that here.  If "gzip -l" whines, we're going
 * to fail too.
 *
 * On exit, "fp" is pointing at the start of the compressed data.
 */
/*static*/ bool ZipUtils::examineGzip(FILE* fp, int* pCompressionMethod,
    long* pUncompressedLen, long* pCompressedLen, unsigned long* pCRC32)
{
    enum {  // flags
        FTEXT       = 0x01,
        FHCRC       = 0x02,
        FEXTRA      = 0x04,
        FNAME       = 0x08,
        FCOMMENT    = 0x10,
    };
    int ic;
    int method, flags;
    int i;

    ic = getc(fp);
    if (ic != 0x1f || getc(fp) != 0x8b)
        return false;       // not gzip
    method = getc(fp);
    flags = getc(fp);

    /* quick sanity checks */
    if (method == EOF || flags == EOF)
        return false;
    if (method != kCompressDeflated)
        return false;

    /* skip over 4 bytes of mod time, 1 byte XFL, 1 byte OS */
    for (i = 0; i < 6; i++)
        (void) getc(fp);
    /* consume "extra" field, if present */
    if ((flags & FEXTRA) != 0) {
        int len;

        len = getc(fp);
        len |= getc(fp) << 8;
        while (len-- && getc(fp) != EOF)
            ;
    }
    /* consume filename, if present */
    if ((flags & FNAME) != 0) {
        do {
            ic = getc(fp);
        } while (ic != 0 && ic != EOF);
    }
    /* consume comment, if present */
    if ((flags & FCOMMENT) != 0) {
        do {
            ic = getc(fp);
        } while (ic != 0 && ic != EOF);
    }
    /* consume 16-bit header CRC, if present */
    if ((flags & FHCRC) != 0) {
        (void) getc(fp);
        (void) getc(fp);
    }

    if (feof(fp) || ferror(fp))
        return false;

    /* seek to the end; CRC and length are in the last 8 bytes */
    long curPosn = ftell(fp);
    unsigned char buf[8];
    fseek(fp, -8, SEEK_END);
    *pCompressedLen = ftell(fp) - curPosn;

    if (fread(buf, 1, 8, fp) != 8)
        return false;
    /* seek back to start of compressed data */
    fseek(fp, curPosn, SEEK_SET);

    *pCompressionMethod = method;
    *pCRC32 = get4LE(&buf[0]);
    *pUncompressedLen = get4LE(&buf[4]);

    return true;
}
