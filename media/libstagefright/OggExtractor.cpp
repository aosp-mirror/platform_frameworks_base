/*
 * Copyright (C) 2010 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "OggExtractor"
#include <utils/Log.h>

#include "include/OggExtractor.h"

#include <cutils/properties.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>
#include <utils/String8.h>

extern "C" {
    #include <Tremolo/codec_internal.h>

    int _vorbis_unpack_books(vorbis_info *vi,oggpack_buffer *opb);
    int _vorbis_unpack_info(vorbis_info *vi,oggpack_buffer *opb);
    int _vorbis_unpack_comment(vorbis_comment *vc,oggpack_buffer *opb);
}

namespace android {

struct OggSource : public MediaSource {
    OggSource(const sp<OggExtractor> &extractor);

    virtual sp<MetaData> getFormat();

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~OggSource();

private:
    sp<OggExtractor> mExtractor;
    bool mStarted;

    OggSource(const OggSource &);
    OggSource &operator=(const OggSource &);
};

struct MyVorbisExtractor {
    MyVorbisExtractor(const sp<DataSource> &source);
    virtual ~MyVorbisExtractor();

    sp<MetaData> getFormat() const;

    // Returns an approximate bitrate in bits per second.
    uint64_t approxBitrate();

    status_t seekToTime(int64_t timeUs);
    status_t seekToOffset(off64_t offset);
    status_t readNextPacket(MediaBuffer **buffer);

    status_t init();

    sp<MetaData> getFileMetaData() { return mFileMeta; }

private:
    struct Page {
        uint64_t mGranulePosition;
        uint32_t mSerialNo;
        uint32_t mPageNo;
        uint8_t mFlags;
        uint8_t mNumSegments;
        uint8_t mLace[255];
    };

    struct TOCEntry {
        off64_t mPageOffset;
        int64_t mTimeUs;
    };

    sp<DataSource> mSource;
    off64_t mOffset;
    Page mCurrentPage;
    uint64_t mPrevGranulePosition;
    size_t mCurrentPageSize;
    bool mFirstPacketInPage;
    uint64_t mCurrentPageSamples;
    size_t mNextLaceIndex;

    off64_t mFirstDataOffset;

    vorbis_info mVi;
    vorbis_comment mVc;

    sp<MetaData> mMeta;
    sp<MetaData> mFileMeta;

    Vector<TOCEntry> mTableOfContents;

    ssize_t readPage(off64_t offset, Page *page);
    status_t findNextPage(off64_t startOffset, off64_t *pageOffset);

    status_t verifyHeader(
            MediaBuffer *buffer, uint8_t type);

    void parseFileMetaData();

    status_t findPrevGranulePosition(off64_t pageOffset, uint64_t *granulePos);

    void buildTableOfContents();

    MyVorbisExtractor(const MyVorbisExtractor &);
    MyVorbisExtractor &operator=(const MyVorbisExtractor &);
};

static void extractAlbumArt(
        const sp<MetaData> &fileMeta, const void *data, size_t size);

////////////////////////////////////////////////////////////////////////////////

OggSource::OggSource(const sp<OggExtractor> &extractor)
    : mExtractor(extractor),
      mStarted(false) {
}

OggSource::~OggSource() {
    if (mStarted) {
        stop();
    }
}

sp<MetaData> OggSource::getFormat() {
    return mExtractor->mImpl->getFormat();
}

status_t OggSource::start(MetaData *params) {
    if (mStarted) {
        return INVALID_OPERATION;
    }

    mStarted = true;

    return OK;
}

status_t OggSource::stop() {
    mStarted = false;

    return OK;
}

status_t OggSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        if (mExtractor->mImpl->seekToTime(seekTimeUs) != OK) {
            return ERROR_END_OF_STREAM;
        }
    }

    MediaBuffer *packet;
    status_t err = mExtractor->mImpl->readNextPacket(&packet);

    if (err != OK) {
        return err;
    }

#if 0
    int64_t timeUs;
    if (packet->meta_data()->findInt64(kKeyTime, &timeUs)) {
        LOGI("found time = %lld us", timeUs);
    } else {
        LOGI("NO time");
    }
#endif

    packet->meta_data()->setInt32(kKeyIsSyncFrame, 1);

    *out = packet;

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

MyVorbisExtractor::MyVorbisExtractor(const sp<DataSource> &source)
    : mSource(source),
      mOffset(0),
      mPrevGranulePosition(0),
      mCurrentPageSize(0),
      mFirstPacketInPage(true),
      mCurrentPageSamples(0),
      mNextLaceIndex(0),
      mFirstDataOffset(-1) {
    mCurrentPage.mNumSegments = 0;

    vorbis_info_init(&mVi);
    vorbis_comment_init(&mVc);
}

MyVorbisExtractor::~MyVorbisExtractor() {
    vorbis_comment_clear(&mVc);
    vorbis_info_clear(&mVi);
}

sp<MetaData> MyVorbisExtractor::getFormat() const {
    return mMeta;
}

status_t MyVorbisExtractor::findNextPage(
        off64_t startOffset, off64_t *pageOffset) {
    *pageOffset = startOffset;

    for (;;) {
        char signature[4];
        ssize_t n = mSource->readAt(*pageOffset, &signature, 4);

        if (n < 4) {
            *pageOffset = 0;

            return (n < 0) ? n : (status_t)ERROR_END_OF_STREAM;
        }

        if (!memcmp(signature, "OggS", 4)) {
            if (*pageOffset > startOffset) {
                ALOGV("skipped %lld bytes of junk to reach next frame",
                     *pageOffset - startOffset);
            }

            return OK;
        }

        ++*pageOffset;
    }
}

// Given the offset of the "current" page, find the page immediately preceding
// it (if any) and return its granule position.
// To do this we back up from the "current" page's offset until we find any
// page preceding it and then scan forward to just before the current page.
status_t MyVorbisExtractor::findPrevGranulePosition(
        off64_t pageOffset, uint64_t *granulePos) {
    *granulePos = 0;

    off64_t prevPageOffset = 0;
    off64_t prevGuess = pageOffset;
    for (;;) {
        if (prevGuess >= 5000) {
            prevGuess -= 5000;
        } else {
            prevGuess = 0;
        }

        ALOGV("backing up %lld bytes", pageOffset - prevGuess);

        status_t err = findNextPage(prevGuess, &prevPageOffset);
        if (err != OK) {
            return err;
        }

        if (prevPageOffset < pageOffset || prevGuess == 0) {
            break;
        }
    }

    if (prevPageOffset == pageOffset) {
        // We did not find a page preceding this one.
        return UNKNOWN_ERROR;
    }

    ALOGV("prevPageOffset at %lld, pageOffset at %lld",
         prevPageOffset, pageOffset);

    for (;;) {
        Page prevPage;
        ssize_t n = readPage(prevPageOffset, &prevPage);

        if (n <= 0) {
            return (status_t)n;
        }

        prevPageOffset += n;

        if (prevPageOffset == pageOffset) {
            *granulePos = prevPage.mGranulePosition;
            return OK;
        }
    }
}

status_t MyVorbisExtractor::seekToTime(int64_t timeUs) {
    if (mTableOfContents.isEmpty()) {
        // Perform approximate seeking based on avg. bitrate.

        off64_t pos = timeUs * approxBitrate() / 8000000ll;

        ALOGV("seeking to offset %lld", pos);
        return seekToOffset(pos);
    }

    size_t left = 0;
    size_t right = mTableOfContents.size();
    while (left < right) {
        size_t center = left / 2 + right / 2 + (left & right & 1);

        const TOCEntry &entry = mTableOfContents.itemAt(center);

        if (timeUs < entry.mTimeUs) {
            right = center;
        } else if (timeUs > entry.mTimeUs) {
            left = center + 1;
        } else {
            left = right = center;
            break;
        }
    }

    const TOCEntry &entry = mTableOfContents.itemAt(left);

    ALOGV("seeking to entry %d / %d at offset %lld",
         left, mTableOfContents.size(), entry.mPageOffset);

    return seekToOffset(entry.mPageOffset);
}

status_t MyVorbisExtractor::seekToOffset(off64_t offset) {
    if (mFirstDataOffset >= 0 && offset < mFirstDataOffset) {
        // Once we know where the actual audio data starts (past the headers)
        // don't ever seek to anywhere before that.
        offset = mFirstDataOffset;
    }

    off64_t pageOffset;
    status_t err = findNextPage(offset, &pageOffset);

    if (err != OK) {
        return err;
    }

    // We found the page we wanted to seek to, but we'll also need
    // the page preceding it to determine how many valid samples are on
    // this page.
    findPrevGranulePosition(pageOffset, &mPrevGranulePosition);

    mOffset = pageOffset;

    mCurrentPageSize = 0;
    mFirstPacketInPage = true;
    mCurrentPageSamples = 0;
    mCurrentPage.mNumSegments = 0;
    mNextLaceIndex = 0;

    // XXX what if new page continues packet from last???

    return OK;
}

ssize_t MyVorbisExtractor::readPage(off64_t offset, Page *page) {
    uint8_t header[27];
    ssize_t n;
    if ((n = mSource->readAt(offset, header, sizeof(header)))
            < (ssize_t)sizeof(header)) {
        ALOGV("failed to read %d bytes at offset 0x%016llx, got %ld bytes",
             sizeof(header), offset, n);

        if (n < 0) {
            return n;
        } else if (n == 0) {
            return ERROR_END_OF_STREAM;
        } else {
            return ERROR_IO;
        }
    }

    if (memcmp(header, "OggS", 4)) {
        return ERROR_MALFORMED;
    }

    if (header[4] != 0) {
        // Wrong version.

        return ERROR_UNSUPPORTED;
    }

    page->mFlags = header[5];

    if (page->mFlags & ~7) {
        // Only bits 0-2 are defined in version 0.
        return ERROR_MALFORMED;
    }

    page->mGranulePosition = U64LE_AT(&header[6]);

#if 0
    printf("granulePosition = %llu (0x%llx)\n",
           page->mGranulePosition, page->mGranulePosition);
#endif

    page->mSerialNo = U32LE_AT(&header[14]);
    page->mPageNo = U32LE_AT(&header[18]);

    page->mNumSegments = header[26];
    if (mSource->readAt(
                offset + sizeof(header), page->mLace, page->mNumSegments)
            < (ssize_t)page->mNumSegments) {
        return ERROR_IO;
    }

    size_t totalSize = 0;;
    for (size_t i = 0; i < page->mNumSegments; ++i) {
        totalSize += page->mLace[i];
    }

#if 0
    String8 tmp;
    for (size_t i = 0; i < page->mNumSegments; ++i) {
        char x[32];
        sprintf(x, "%s%u", i > 0 ? ", " : "", (unsigned)page->mLace[i]);

        tmp.append(x);
    }

    ALOGV("%c %s", page->mFlags & 1 ? '+' : ' ', tmp.string());
#endif

    return sizeof(header) + page->mNumSegments + totalSize;
}

status_t MyVorbisExtractor::readNextPacket(MediaBuffer **out) {
    *out = NULL;

    MediaBuffer *buffer = NULL;
    int64_t timeUs = -1;

    for (;;) {
        size_t i;
        size_t packetSize = 0;
        bool gotFullPacket = false;
        for (i = mNextLaceIndex; i < mCurrentPage.mNumSegments; ++i) {
            uint8_t lace = mCurrentPage.mLace[i];

            packetSize += lace;

            if (lace < 255) {
                gotFullPacket = true;
                ++i;
                break;
            }
        }

        if (mNextLaceIndex < mCurrentPage.mNumSegments) {
            off64_t dataOffset = mOffset + 27 + mCurrentPage.mNumSegments;
            for (size_t j = 0; j < mNextLaceIndex; ++j) {
                dataOffset += mCurrentPage.mLace[j];
            }

            size_t fullSize = packetSize;
            if (buffer != NULL) {
                fullSize += buffer->range_length();
            }
            MediaBuffer *tmp = new MediaBuffer(fullSize);
            if (buffer != NULL) {
                memcpy(tmp->data(), buffer->data(), buffer->range_length());
                tmp->set_range(0, buffer->range_length());
                buffer->release();
            } else {
                // XXX Not only is this not technically the correct time for
                // this packet, we also stamp every packet in this page
                // with the same time. This needs fixing later.

                if (mVi.rate) {
                    // Rate may not have been initialized yet if we're currently
                    // reading the configuration packets...
                    // Fortunately, the timestamp doesn't matter for those.
                    timeUs = mCurrentPage.mGranulePosition * 1000000ll / mVi.rate;
                }
                tmp->set_range(0, 0);
            }
            buffer = tmp;

            ssize_t n = mSource->readAt(
                    dataOffset,
                    (uint8_t *)buffer->data() + buffer->range_length(),
                    packetSize);

            if (n < (ssize_t)packetSize) {
                ALOGV("failed to read %d bytes at 0x%016llx, got %ld bytes",
                     packetSize, dataOffset, n);
                return ERROR_IO;
            }

            buffer->set_range(0, fullSize);

            mNextLaceIndex = i;

            if (gotFullPacket) {
                // We've just read the entire packet.

                if (timeUs >= 0) {
                    buffer->meta_data()->setInt64(kKeyTime, timeUs);
                }

                if (mFirstPacketInPage) {
                    buffer->meta_data()->setInt32(
                            kKeyValidSamples, mCurrentPageSamples);
                    mFirstPacketInPage = false;
                }

                *out = buffer;

                return OK;
            }

            // fall through, the buffer now contains the start of the packet.
        }

        CHECK_EQ(mNextLaceIndex, mCurrentPage.mNumSegments);

        mOffset += mCurrentPageSize;
        ssize_t n = readPage(mOffset, &mCurrentPage);

        if (n <= 0) {
            if (buffer) {
                buffer->release();
                buffer = NULL;
            }

            ALOGV("readPage returned %ld", n);

            return n < 0 ? n : (status_t)ERROR_END_OF_STREAM;
        }

        mCurrentPageSamples =
            mCurrentPage.mGranulePosition - mPrevGranulePosition;
        mFirstPacketInPage = true;

        mPrevGranulePosition = mCurrentPage.mGranulePosition;

        mCurrentPageSize = n;
        mNextLaceIndex = 0;

        if (buffer != NULL) {
            if ((mCurrentPage.mFlags & 1) == 0) {
                // This page does not continue the packet, i.e. the packet
                // is already complete.

                if (timeUs >= 0) {
                    buffer->meta_data()->setInt64(kKeyTime, timeUs);
                }

                buffer->meta_data()->setInt32(
                        kKeyValidSamples, mCurrentPageSamples);
                mFirstPacketInPage = false;

                *out = buffer;

                return OK;
            }
        }
    }
}

status_t MyVorbisExtractor::init() {
    mMeta = new MetaData;
    mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_VORBIS);

    MediaBuffer *packet;
    status_t err;
    if ((err = readNextPacket(&packet)) != OK) {
        return err;
    }
    ALOGV("read packet of size %d\n", packet->range_length());
    err = verifyHeader(packet, 1);
    packet->release();
    packet = NULL;
    if (err != OK) {
        return err;
    }

    if ((err = readNextPacket(&packet)) != OK) {
        return err;
    }
    ALOGV("read packet of size %d\n", packet->range_length());
    err = verifyHeader(packet, 3);
    packet->release();
    packet = NULL;
    if (err != OK) {
        return err;
    }

    if ((err = readNextPacket(&packet)) != OK) {
        return err;
    }
    ALOGV("read packet of size %d\n", packet->range_length());
    err = verifyHeader(packet, 5);
    packet->release();
    packet = NULL;
    if (err != OK) {
        return err;
    }

    mFirstDataOffset = mOffset + mCurrentPageSize;

    off64_t size;
    uint64_t lastGranulePosition;
    if (!(mSource->flags() & DataSource::kIsCachingDataSource)
            && mSource->getSize(&size) == OK
            && findPrevGranulePosition(size, &lastGranulePosition) == OK) {
        // Let's assume it's cheap to seek to the end.
        // The granule position of the final page in the stream will
        // give us the exact duration of the content, something that
        // we can only approximate using avg. bitrate if seeking to
        // the end is too expensive or impossible (live streaming).

        int64_t durationUs = lastGranulePosition * 1000000ll / mVi.rate;

        mMeta->setInt64(kKeyDuration, durationUs);

        buildTableOfContents();
    }

    return OK;
}

void MyVorbisExtractor::buildTableOfContents() {
    off64_t offset = mFirstDataOffset;
    Page page;
    ssize_t pageSize;
    while ((pageSize = readPage(offset, &page)) > 0) {
        mTableOfContents.push();

        TOCEntry &entry =
            mTableOfContents.editItemAt(mTableOfContents.size() - 1);

        entry.mPageOffset = offset;
        entry.mTimeUs = page.mGranulePosition * 1000000ll / mVi.rate;

        offset += (size_t)pageSize;
    }

    // Limit the maximum amount of RAM we spend on the table of contents,
    // if necessary thin out the table evenly to trim it down to maximum
    // size.

    static const size_t kMaxTOCSize = 8192;
    static const size_t kMaxNumTOCEntries = kMaxTOCSize / sizeof(TOCEntry);

    size_t numerator = mTableOfContents.size();

    if (numerator > kMaxNumTOCEntries) {
        size_t denom = numerator - kMaxNumTOCEntries;

        size_t accum = 0;
        for (ssize_t i = mTableOfContents.size() - 1; i >= 0; --i) {
            accum += denom;
            if (accum >= numerator) {
                mTableOfContents.removeAt(i);
                accum -= numerator;
            }
        }
    }
}

status_t MyVorbisExtractor::verifyHeader(
        MediaBuffer *buffer, uint8_t type) {
    const uint8_t *data =
        (const uint8_t *)buffer->data() + buffer->range_offset();

    size_t size = buffer->range_length();

    if (size < 7 || data[0] != type || memcmp(&data[1], "vorbis", 6)) {
        return ERROR_MALFORMED;
    }

    ogg_buffer buf;
    buf.data = (uint8_t *)data;
    buf.size = size;
    buf.refcount = 1;
    buf.ptr.owner = NULL;

    ogg_reference ref;
    ref.buffer = &buf;
    ref.begin = 0;
    ref.length = size;
    ref.next = NULL;

    oggpack_buffer bits;
    oggpack_readinit(&bits, &ref);

    CHECK_EQ(oggpack_read(&bits, 8), type);
    for (size_t i = 0; i < 6; ++i) {
        oggpack_read(&bits, 8);  // skip 'vorbis'
    }

    switch (type) {
        case 1:
        {
            CHECK_EQ(0, _vorbis_unpack_info(&mVi, &bits));

            mMeta->setData(kKeyVorbisInfo, 0, data, size);
            mMeta->setInt32(kKeySampleRate, mVi.rate);
            mMeta->setInt32(kKeyChannelCount, mVi.channels);

            ALOGV("lower-bitrate = %ld", mVi.bitrate_lower);
            ALOGV("upper-bitrate = %ld", mVi.bitrate_upper);
            ALOGV("nominal-bitrate = %ld", mVi.bitrate_nominal);
            ALOGV("window-bitrate = %ld", mVi.bitrate_window);

            off64_t size;
            if (mSource->getSize(&size) == OK) {
                uint64_t bps = approxBitrate();
                if (bps != 0) {
                    mMeta->setInt64(kKeyDuration, size * 8000000ll / bps);
                }
            }
            break;
        }

        case 3:
        {
            if (0 != _vorbis_unpack_comment(&mVc, &bits)) {
                return ERROR_MALFORMED;
            }

            parseFileMetaData();
            break;
        }

        case 5:
        {
            if (0 != _vorbis_unpack_books(&mVi, &bits)) {
                return ERROR_MALFORMED;
            }

            mMeta->setData(kKeyVorbisBooks, 0, data, size);
            break;
        }
    }

    return OK;
}

uint64_t MyVorbisExtractor::approxBitrate() {
    if (mVi.bitrate_nominal != 0) {
        return mVi.bitrate_nominal;
    }

    return (mVi.bitrate_lower + mVi.bitrate_upper) / 2;
}

void MyVorbisExtractor::parseFileMetaData() {
    mFileMeta = new MetaData;
    mFileMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_CONTAINER_OGG);

    for (int i = 0; i < mVc.comments; ++i) {
        const char *comment = mVc.user_comments[i];
        size_t commentLength = mVc.comment_lengths[i];
        parseVorbisComment(mFileMeta, comment, commentLength);
        //LOGI("comment #%d: '%s'", i + 1, mVc.user_comments[i]);
    }
}

void parseVorbisComment(
        const sp<MetaData> &fileMeta, const char *comment, size_t commentLength)
{
    struct {
        const char *const mTag;
        uint32_t mKey;
    } kMap[] = {
        { "TITLE", kKeyTitle },
        { "ARTIST", kKeyArtist },
        { "ALBUMARTIST", kKeyAlbumArtist },
        { "ALBUM ARTIST", kKeyAlbumArtist },
        { "COMPILATION", kKeyCompilation },
        { "ALBUM", kKeyAlbum },
        { "COMPOSER", kKeyComposer },
        { "GENRE", kKeyGenre },
        { "AUTHOR", kKeyAuthor },
        { "TRACKNUMBER", kKeyCDTrackNumber },
        { "DISCNUMBER", kKeyDiscNumber },
        { "DATE", kKeyDate },
        { "LYRICIST", kKeyWriter },
        { "METADATA_BLOCK_PICTURE", kKeyAlbumArt },
        { "ANDROID_LOOP", kKeyAutoLoop },
    };

        for (size_t j = 0; j < sizeof(kMap) / sizeof(kMap[0]); ++j) {
            size_t tagLen = strlen(kMap[j].mTag);
            if (!strncasecmp(kMap[j].mTag, comment, tagLen)
                    && comment[tagLen] == '=') {
                if (kMap[j].mKey == kKeyAlbumArt) {
                    extractAlbumArt(
                            fileMeta,
                            &comment[tagLen + 1],
                            commentLength - tagLen - 1);
                } else if (kMap[j].mKey == kKeyAutoLoop) {
                    if (!strcasecmp(&comment[tagLen + 1], "true")) {
                        fileMeta->setInt32(kKeyAutoLoop, true);
                    }
                } else {
                    fileMeta->setCString(kMap[j].mKey, &comment[tagLen + 1]);
                }
            }
        }

}

// The returned buffer should be free()d.
static uint8_t *DecodeBase64(const char *s, size_t size, size_t *outSize) {
    *outSize = 0;

    if ((size % 4) != 0) {
        return NULL;
    }

    size_t n = size;
    size_t padding = 0;
    if (n >= 1 && s[n - 1] == '=') {
        padding = 1;

        if (n >= 2 && s[n - 2] == '=') {
            padding = 2;
        }
    }

    size_t outLen = 3 * size / 4 - padding;

    *outSize = outLen;

    void *buffer = malloc(outLen);

    uint8_t *out = (uint8_t *)buffer;
    size_t j = 0;
    uint32_t accum = 0;
    for (size_t i = 0; i < n; ++i) {
        char c = s[i];
        unsigned value;
        if (c >= 'A' && c <= 'Z') {
            value = c - 'A';
        } else if (c >= 'a' && c <= 'z') {
            value = 26 + c - 'a';
        } else if (c >= '0' && c <= '9') {
            value = 52 + c - '0';
        } else if (c == '+') {
            value = 62;
        } else if (c == '/') {
            value = 63;
        } else if (c != '=') {
            return NULL;
        } else {
            if (i < n - padding) {
                return NULL;
            }

            value = 0;
        }

        accum = (accum << 6) | value;

        if (((i + 1) % 4) == 0) {
            out[j++] = (accum >> 16);

            if (j < outLen) { out[j++] = (accum >> 8) & 0xff; }
            if (j < outLen) { out[j++] = accum & 0xff; }

            accum = 0;
        }
    }

    return (uint8_t *)buffer;
}

static void extractAlbumArt(
        const sp<MetaData> &fileMeta, const void *data, size_t size) {
    ALOGV("extractAlbumArt from '%s'", (const char *)data);

    size_t flacSize;
    uint8_t *flac = DecodeBase64((const char *)data, size, &flacSize);

    if (flac == NULL) {
        LOGE("malformed base64 encoded data.");
        return;
    }

    ALOGV("got flac of size %d", flacSize);

    uint32_t picType;
    uint32_t typeLen;
    uint32_t descLen;
    uint32_t dataLen;
    char type[128];

    if (flacSize < 8) {
        goto exit;
    }

    picType = U32_AT(flac);

    if (picType != 3) {
        // This is not a front cover.
        goto exit;
    }

    typeLen = U32_AT(&flac[4]);
    if (typeLen + 1 > sizeof(type)) {
        goto exit;
    }

    if (flacSize < 8 + typeLen) {
        goto exit;
    }

    memcpy(type, &flac[8], typeLen);
    type[typeLen] = '\0';

    ALOGV("picType = %d, type = '%s'", picType, type);

    if (!strcmp(type, "-->")) {
        // This is not inline cover art, but an external url instead.
        goto exit;
    }

    descLen = U32_AT(&flac[8 + typeLen]);

    if (flacSize < 32 + typeLen + descLen) {
        goto exit;
    }

    dataLen = U32_AT(&flac[8 + typeLen + 4 + descLen + 16]);

    if (flacSize < 32 + typeLen + descLen + dataLen) {
        goto exit;
    }

    ALOGV("got image data, %d trailing bytes",
         flacSize - 32 - typeLen - descLen - dataLen);

    fileMeta->setData(
            kKeyAlbumArt, 0, &flac[8 + typeLen + 4 + descLen + 20], dataLen);

    fileMeta->setCString(kKeyAlbumArtMIME, type);

exit:
    free(flac);
    flac = NULL;
}

////////////////////////////////////////////////////////////////////////////////

OggExtractor::OggExtractor(const sp<DataSource> &source)
    : mDataSource(source),
      mInitCheck(NO_INIT),
      mImpl(NULL) {
    mImpl = new MyVorbisExtractor(mDataSource);
    mInitCheck = mImpl->seekToOffset(0);

    if (mInitCheck == OK) {
        mInitCheck = mImpl->init();
    }
}

OggExtractor::~OggExtractor() {
    delete mImpl;
    mImpl = NULL;
}

size_t OggExtractor::countTracks() {
    return mInitCheck != OK ? 0 : 1;
}

sp<MediaSource> OggExtractor::getTrack(size_t index) {
    if (index >= 1) {
        return NULL;
    }

    return new OggSource(this);
}

sp<MetaData> OggExtractor::getTrackMetaData(
        size_t index, uint32_t flags) {
    if (index >= 1) {
        return NULL;
    }

    return mImpl->getFormat();
}

sp<MetaData> OggExtractor::getMetaData() {
    return mImpl->getFileMetaData();
}

bool SniffOgg(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *) {
    char tmp[4];
    if (source->readAt(0, tmp, 4) < 4 || memcmp(tmp, "OggS", 4)) {
        return false;
    }

    mimeType->setTo(MEDIA_MIMETYPE_CONTAINER_OGG);
    *confidence = 0.2f;

    return true;
}

}  // namespace android
