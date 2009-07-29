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

#include <utils/ZipUtils.h>
#include <utils/ZipFileRO.h>
#include <utils/Log.h>

#include <stdlib.h>
#include <string.h>
#include <assert.h>

#include <zlib.h>

using namespace android;

/*
 * Utility function that expands zip/gzip "deflate" compressed data
 * into a buffer.
 *
 * "fd" is an open file positioned at the start of the "deflate" data
 * "buf" must hold at least "uncompressedLen" bytes.
 */
/*static*/ bool ZipUtils::inflateToBuffer(int fd, void* buf,
    long uncompressedLen, long compressedLen)
{
    bool result = false;
	const unsigned long kReadBufSize = 32768;
	unsigned char* readBuf = NULL;
    z_stream zstream;
    int zerr;
    unsigned long compRemaining;

    assert(uncompressedLen >= 0);
    assert(compressedLen >= 0);

	readBuf = new unsigned char[kReadBufSize];
	if (readBuf == NULL)
        goto bail;
    compRemaining = compressedLen;

    /*
     * Initialize the zlib stream.
     */
	memset(&zstream, 0, sizeof(zstream));
    zstream.zalloc = Z_NULL;
    zstream.zfree = Z_NULL;
    zstream.opaque = Z_NULL;
    zstream.next_in = NULL;
    zstream.avail_in = 0;
    zstream.next_out = (Bytef*) buf;
    zstream.avail_out = uncompressedLen;
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
     * Loop while we have data.
     */
    do {
        unsigned long getSize;

        /* read as much as we can */
        if (zstream.avail_in == 0) {
            getSize = (compRemaining > kReadBufSize) ?
                        kReadBufSize : compRemaining;
            LOGV("+++ reading %ld bytes (%ld left)\n",
                getSize, compRemaining);

            int cc = read(fd, readBuf, getSize);
            if (cc != (int) getSize) {
                LOGD("inflate read failed (%d vs %ld)\n",
                    cc, getSize);
                goto z_bail;
            }

            compRemaining -= getSize;

            zstream.next_in = readBuf;
            zstream.avail_in = getSize;
        }

        /* uncompress the data */
        zerr = inflate(&zstream, Z_NO_FLUSH);
        if (zerr != Z_OK && zerr != Z_STREAM_END) {
            LOGD("zlib inflate call failed (zerr=%d)\n", zerr);
            goto z_bail;
        }

		/* output buffer holds all, so no need to write the output */
    } while (zerr == Z_OK);

    assert(zerr == Z_STREAM_END);       /* other errors should've been caught */

    if ((long) zstream.total_out != uncompressedLen) {
        LOGW("Size mismatch on inflated file (%ld vs %ld)\n",
            zstream.total_out, uncompressedLen);
        goto z_bail;
    }

    // success!
    result = true;

z_bail:
    inflateEnd(&zstream);        /* free up any allocated structures */

bail:
	delete[] readBuf;
    return result;
}

/*
 * Utility function that expands zip/gzip "deflate" compressed data
 * into a buffer.
 *
 * (This is a clone of the previous function, but it takes a FILE* instead
 * of an fd.  We could pass fileno(fd) to the above, but we can run into
 * trouble when "fp" has a different notion of what fd's file position is.)
 *
 * "fp" is an open file positioned at the start of the "deflate" data
 * "buf" must hold at least "uncompressedLen" bytes.
 */
/*static*/ bool ZipUtils::inflateToBuffer(FILE* fp, void* buf,
    long uncompressedLen, long compressedLen)
{
    bool result = false;
	const unsigned long kReadBufSize = 32768;
	unsigned char* readBuf = NULL;
    z_stream zstream;
    int zerr;
    unsigned long compRemaining;

    assert(uncompressedLen >= 0);
    assert(compressedLen >= 0);

	readBuf = new unsigned char[kReadBufSize];
	if (readBuf == NULL)
        goto bail;
    compRemaining = compressedLen;

    /*
     * Initialize the zlib stream.
     */
	memset(&zstream, 0, sizeof(zstream));
    zstream.zalloc = Z_NULL;
    zstream.zfree = Z_NULL;
    zstream.opaque = Z_NULL;
    zstream.next_in = NULL;
    zstream.avail_in = 0;
    zstream.next_out = (Bytef*) buf;
    zstream.avail_out = uncompressedLen;
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
     * Loop while we have data.
     */
    do {
        unsigned long getSize;

        /* read as much as we can */
        if (zstream.avail_in == 0) {
            getSize = (compRemaining > kReadBufSize) ?
                        kReadBufSize : compRemaining;
            LOGV("+++ reading %ld bytes (%ld left)\n",
                getSize, compRemaining);

            int cc = fread(readBuf, 1, getSize, fp);
            if (cc != (int) getSize) {
                LOGD("inflate read failed (%d vs %ld)\n",
                    cc, getSize);
                goto z_bail;
            }

            compRemaining -= getSize;

            zstream.next_in = readBuf;
            zstream.avail_in = getSize;
        }

        /* uncompress the data */
        zerr = inflate(&zstream, Z_NO_FLUSH);
        if (zerr != Z_OK && zerr != Z_STREAM_END) {
            LOGD("zlib inflate call failed (zerr=%d)\n", zerr);
            goto z_bail;
        }

		/* output buffer holds all, so no need to write the output */
    } while (zerr == Z_OK);

    assert(zerr == Z_STREAM_END);       /* other errors should've been caught */

    if ((long) zstream.total_out != uncompressedLen) {
        LOGW("Size mismatch on inflated file (%ld vs %ld)\n",
            zstream.total_out, uncompressedLen);
        goto z_bail;
    }

    // success!
    result = true;

z_bail:
    inflateEnd(&zstream);        /* free up any allocated structures */

bail:
	delete[] readBuf;
    return result;
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
    if (method != ZipFileRO::kCompressDeflated)
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
    *pCRC32 = ZipFileRO::get4LE(&buf[0]);
    *pUncompressedLen = ZipFileRO::get4LE(&buf[4]);

    return true;
}
