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

    status_t seekToOffset(off_t offset);
    status_t readNextPacket(MediaBuffer **buffer);

    void init();

private:
    struct Page {
        uint64_t mGranulePosition;
        uint32_t mSerialNo;
        uint32_t mPageNo;
        uint8_t mFlags;
        uint8_t mNumSegments;
        uint8_t mLace[255];
    };

    sp<DataSource> mSource;
    off_t mOffset;
    Page mCurrentPage;
    size_t mCurrentPageSize;
    size_t mNextLaceIndex;

    vorbis_info mVi;
    vorbis_comment mVc;

    sp<MetaData> mMeta;

    ssize_t readPage(off_t offset, Page *page);
    status_t findNextPage(off_t startOffset, off_t *pageOffset);

    void verifyHeader(
            MediaBuffer *buffer, uint8_t type);

    MyVorbisExtractor(const MyVorbisExtractor &);
    MyVorbisExtractor &operator=(const MyVorbisExtractor &);
};

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
    if (options && options->getSeekTo(&seekTimeUs)) {
        off_t pos = seekTimeUs * mExtractor->mImpl->approxBitrate() / 8000000ll;
        LOGI("seeking to offset %ld", pos);

        if (mExtractor->mImpl->seekToOffset(pos) != OK) {
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

    *out = packet;

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

MyVorbisExtractor::MyVorbisExtractor(const sp<DataSource> &source)
    : mSource(source),
      mOffset(0),
      mCurrentPageSize(0),
      mNextLaceIndex(0) {
    mCurrentPage.mNumSegments = 0;
}

MyVorbisExtractor::~MyVorbisExtractor() {
}

sp<MetaData> MyVorbisExtractor::getFormat() const {
    return mMeta;
}

status_t MyVorbisExtractor::findNextPage(
        off_t startOffset, off_t *pageOffset) {
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
                LOGV("skipped %ld bytes of junk to reach next frame",
                     *pageOffset - startOffset);
            }

            return OK;
        }

        ++*pageOffset;
    }
}

status_t MyVorbisExtractor::seekToOffset(off_t offset) {
    off_t pageOffset;
    status_t err = findNextPage(offset, &pageOffset);

    if (err != OK) {
        return err;
    }

    mOffset = pageOffset;

    mCurrentPageSize = 0;
    mCurrentPage.mNumSegments = 0;
    mNextLaceIndex = 0;

    // XXX what if new page continues packet from last???

    return OK;
}

ssize_t MyVorbisExtractor::readPage(off_t offset, Page *page) {
    uint8_t header[27];
    if (mSource->readAt(offset, header, sizeof(header))
            < (ssize_t)sizeof(header)) {
        LOGE("failed to read %d bytes at offset 0x%08lx", sizeof(header), offset);

        return ERROR_IO;
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

    String8 tmp;
    for (size_t i = 0; i < page->mNumSegments; ++i) {
        char x[32];
        sprintf(x, "%s%u", i > 0 ? ", " : "", (unsigned)page->mLace[i]);

        tmp.append(x);
    }

    LOGV("%c %s", page->mFlags & 1 ? '+' : ' ', tmp.string());

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
            off_t dataOffset = mOffset + 27 + mCurrentPage.mNumSegments;
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
                timeUs = mCurrentPage.mGranulePosition * 1000000ll / mVi.rate;
                tmp->set_range(0, 0);
            }
            buffer = tmp;

            ssize_t n = mSource->readAt(
                    dataOffset,
                    (uint8_t *)buffer->data() + buffer->range_length(),
                    packetSize);

            if (n < (ssize_t)packetSize) {
                LOGE("failed to read %d bytes at 0x%08lx", packetSize, dataOffset);
                return ERROR_IO;
            }

            buffer->set_range(0, fullSize);

            mNextLaceIndex = i;

            if (gotFullPacket) {
                // We've just read the entire packet.

                if (timeUs >= 0) {
                    buffer->meta_data()->setInt64(kKeyTime, timeUs);
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

            LOGE("readPage returned %ld", n);

            return n < 0 ? n : (status_t)ERROR_END_OF_STREAM;
        }

        mCurrentPageSize = n;
        mNextLaceIndex = 0;

        if (buffer != NULL) {
            if ((mCurrentPage.mFlags & 1) == 0) {
                // This page does not continue the packet, i.e. the packet
                // is already complete.

                if (timeUs >= 0) {
                    buffer->meta_data()->setInt64(kKeyTime, timeUs);
                }

                *out = buffer;

                return OK;
            }
        }
    }
}

void MyVorbisExtractor::init() {
    vorbis_info_init(&mVi);

    vorbis_comment mVc;

    mMeta = new MetaData;
    mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_VORBIS);

    MediaBuffer *packet;
    CHECK_EQ(readNextPacket(&packet), OK);
    LOGV("read packet of size %d\n", packet->range_length());
    verifyHeader(packet, 1);
    packet->release();
    packet = NULL;

    CHECK_EQ(readNextPacket(&packet), OK);
    LOGV("read packet of size %d\n", packet->range_length());
    verifyHeader(packet, 3);
    packet->release();
    packet = NULL;

    CHECK_EQ(readNextPacket(&packet), OK);
    LOGV("read packet of size %d\n", packet->range_length());
    verifyHeader(packet, 5);
    packet->release();
    packet = NULL;
}

void MyVorbisExtractor::verifyHeader(
        MediaBuffer *buffer, uint8_t type) {
    const uint8_t *data =
        (const uint8_t *)buffer->data() + buffer->range_offset();

    size_t size = buffer->range_length();

    CHECK(size >= 7);

    CHECK_EQ(data[0], type);
    CHECK(!memcmp(&data[1], "vorbis", 6));

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

            LOGV("lower-bitrate = %ld", mVi.bitrate_lower);
            LOGV("upper-bitrate = %ld", mVi.bitrate_upper);
            LOGV("nominal-bitrate = %ld", mVi.bitrate_nominal);
            LOGV("window-bitrate = %ld", mVi.bitrate_window);

            off_t size;
            if (mSource->getSize(&size) == OK) {
                uint64_t bps = approxBitrate();

                mMeta->setInt64(kKeyDuration, size * 8000000ll / bps);
            }
            break;
        }

        case 3:
        {
            CHECK_EQ(0, _vorbis_unpack_comment(&mVc, &bits));
            break;
        }

        case 5:
        {
            CHECK_EQ(0, _vorbis_unpack_books(&mVi, &bits));

            mMeta->setData(kKeyVorbisBooks, 0, data, size);
            break;
        }
    }
}

uint64_t MyVorbisExtractor::approxBitrate() {
    if (mVi.bitrate_nominal != 0) {
        return mVi.bitrate_nominal;
    }

    return (mVi.bitrate_lower + mVi.bitrate_upper) / 2;
}

////////////////////////////////////////////////////////////////////////////////

OggExtractor::OggExtractor(const sp<DataSource> &source)
    : mDataSource(source),
      mInitCheck(NO_INIT),
      mImpl(NULL) {
    mImpl = new MyVorbisExtractor(mDataSource);
    CHECK_EQ(mImpl->seekToOffset(0), OK);
    mImpl->init();

    mInitCheck = OK;
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
    sp<MetaData> meta = new MetaData;

    if (mInitCheck != OK) {
        return meta;
    }

    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_CONTAINER_OGG);

    return meta;
}

bool SniffOgg(
        const sp<DataSource> &source, String8 *mimeType, float *confidence) {
    char tmp[4];
    if (source->readAt(0, tmp, 4) < 4 || memcmp(tmp, "OggS", 4)) {
        return false;
    }

    mimeType->setTo(MEDIA_MIMETYPE_CONTAINER_OGG);
    *confidence = 0.2f;

    return true;
}

}  // namespace android
