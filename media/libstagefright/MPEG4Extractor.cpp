/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "MPEG4Extractor"
#include <utils/Log.h>

#include "include/MPEG4Extractor.h"
#include "include/SampleTable.h"

#include <arpa/inet.h>

#include <ctype.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>
#include <utils/String8.h>

namespace android {

class MPEG4Source : public MediaSource {
public:
    // Caller retains ownership of both "dataSource" and "sampleTable".
    MPEG4Source(const sp<MetaData> &format,
                const sp<DataSource> &dataSource,
                int32_t timeScale,
                const sp<SampleTable> &sampleTable);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~MPEG4Source();

private:
    sp<MetaData> mFormat;
    sp<DataSource> mDataSource;
    int32_t mTimescale;
    sp<SampleTable> mSampleTable;
    uint32_t mCurrentSampleIndex;

    bool mIsAVC;
    size_t mNALLengthSize;

    bool mStarted;

    MediaBufferGroup *mGroup;

    MediaBuffer *mBuffer;

    bool mWantsNALFragments;

    uint8_t *mSrcBuffer;

    size_t parseNALSize(const uint8_t *data) const;

    MPEG4Source(const MPEG4Source &);
    MPEG4Source &operator=(const MPEG4Source &);
};

static void hexdump(const void *_data, size_t size) {
    const uint8_t *data = (const uint8_t *)_data;
    size_t offset = 0;
    while (offset < size) {
        printf("0x%04x  ", offset);

        size_t n = size - offset;
        if (n > 16) {
            n = 16;
        }

        for (size_t i = 0; i < 16; ++i) {
            if (i == 8) {
                printf(" ");
            }

            if (offset + i < size) {
                printf("%02x ", data[offset + i]);
            } else {
                printf("   ");
            }
        }

        printf(" ");

        for (size_t i = 0; i < n; ++i) {
            if (isprint(data[offset + i])) {
                printf("%c", data[offset + i]);
            } else {
                printf(".");
            }
        }

        printf("\n");

        offset += 16;
    }
}

static const char *FourCC2MIME(uint32_t fourcc) {
    switch (fourcc) {
        case FOURCC('m', 'p', '4', 'a'):
            return MEDIA_MIMETYPE_AUDIO_AAC;

        case FOURCC('s', 'a', 'm', 'r'):
            return MEDIA_MIMETYPE_AUDIO_AMR_NB;

        case FOURCC('s', 'a', 'w', 'b'):
            return MEDIA_MIMETYPE_AUDIO_AMR_WB;

        case FOURCC('m', 'p', '4', 'v'):
            return MEDIA_MIMETYPE_VIDEO_MPEG4;

        case FOURCC('s', '2', '6', '3'):
            return MEDIA_MIMETYPE_VIDEO_H263;

        case FOURCC('a', 'v', 'c', '1'):
            return MEDIA_MIMETYPE_VIDEO_AVC;

        default:
            CHECK(!"should not be here.");
            return NULL;
    }
}

MPEG4Extractor::MPEG4Extractor(const sp<DataSource> &source)
    : mDataSource(source),
      mHaveMetadata(false),
      mHasVideo(false),
      mFirstTrack(NULL),
      mLastTrack(NULL) {
}

MPEG4Extractor::~MPEG4Extractor() {
    Track *track = mFirstTrack;
    while (track) {
        Track *next = track->next;

        delete track;
        track = next;
    }
    mFirstTrack = mLastTrack = NULL;
}

sp<MetaData> MPEG4Extractor::getMetaData() {
    sp<MetaData> meta = new MetaData;

    status_t err;
    if ((err = readMetaData()) != OK) {
        return meta;
    }

    if (mHasVideo) {
        meta->setCString(kKeyMIMEType, "video/mp4");
    } else {
        meta->setCString(kKeyMIMEType, "audio/mp4");
    }

    return meta;
}

size_t MPEG4Extractor::countTracks() {
    status_t err;
    if ((err = readMetaData()) != OK) {
        return 0;
    }

    size_t n = 0;
    Track *track = mFirstTrack;
    while (track) {
        ++n;
        track = track->next;
    }

    return n;
}

sp<MetaData> MPEG4Extractor::getTrackMetaData(
        size_t index, uint32_t flags) {
    status_t err;
    if ((err = readMetaData()) != OK) {
        return NULL;
    }

    Track *track = mFirstTrack;
    while (index > 0) {
        if (track == NULL) {
            return NULL;
        }

        track = track->next;
        --index;
    }

    if (track == NULL) {
        return NULL;
    }

    if ((flags & kIncludeExtensiveMetaData)
            && !track->includes_expensive_metadata) {
        track->includes_expensive_metadata = true;

        const char *mime;
        CHECK(track->meta->findCString(kKeyMIMEType, &mime));
        if (!strncasecmp("video/", mime, 6)) {
            uint32_t sampleIndex;
            uint32_t sampleTime;
            if (track->sampleTable->findThumbnailSample(&sampleIndex) == OK
                    && track->sampleTable->getDecodingTime(
                        sampleIndex, &sampleTime) == OK) {
                track->meta->setInt64(
                        kKeyThumbnailTime,
                        ((int64_t)sampleTime * 1000000) / track->timescale);
            }
        }
    }

    return track->meta;
}

status_t MPEG4Extractor::readMetaData() {
    if (mHaveMetadata) {
        return OK;
    }

    off_t offset = 0;
    status_t err;
    while ((err = parseChunk(&offset, 0)) == OK) {
    }

    if (mHaveMetadata) {
        return OK;
    }

    return err;
}

static void MakeFourCCString(uint32_t x, char *s) {
    s[0] = x >> 24;
    s[1] = (x >> 16) & 0xff;
    s[2] = (x >> 8) & 0xff;
    s[3] = x & 0xff;
    s[4] = '\0';
}

status_t MPEG4Extractor::parseChunk(off_t *offset, int depth) {
    uint32_t hdr[2];
    if (mDataSource->readAt(*offset, hdr, 8) < 8) {
        return ERROR_IO;
    }
    uint64_t chunk_size = ntohl(hdr[0]);
    uint32_t chunk_type = ntohl(hdr[1]);
    off_t data_offset = *offset + 8;

    if (chunk_size == 1) {
        if (mDataSource->readAt(*offset + 8, &chunk_size, 8) < 8) {
            return ERROR_IO;
        }
        chunk_size = ntoh64(chunk_size);
        data_offset += 8;
    }

    char chunk[5];
    MakeFourCCString(chunk_type, chunk);

#if 0
    static const char kWhitespace[] = "                                        ";
    const char *indent = &kWhitespace[sizeof(kWhitespace) - 1 - 2 * depth];
    printf("%sfound chunk '%s' of size %lld\n", indent, chunk, chunk_size);

    char buffer[256];
    if (chunk_size <= sizeof(buffer)) {
        if (mDataSource->readAt(*offset, buffer, chunk_size) < chunk_size) {
            return ERROR_IO;
        }

        hexdump(buffer, chunk_size);
    }
#endif

    off_t chunk_data_size = *offset + chunk_size - data_offset;

    switch(chunk_type) {
        case FOURCC('m', 'o', 'o', 'v'):
        case FOURCC('t', 'r', 'a', 'k'):
        case FOURCC('m', 'd', 'i', 'a'):
        case FOURCC('m', 'i', 'n', 'f'):
        case FOURCC('d', 'i', 'n', 'f'):
        case FOURCC('s', 't', 'b', 'l'):
        case FOURCC('m', 'v', 'e', 'x'):
        case FOURCC('m', 'o', 'o', 'f'):
        case FOURCC('t', 'r', 'a', 'f'):
        case FOURCC('m', 'f', 'r', 'a'):
        case FOURCC('s', 'k', 'i' ,'p'):
        {
            off_t stop_offset = *offset + chunk_size;
            *offset = data_offset;
            while (*offset < stop_offset) {
                status_t err = parseChunk(offset, depth + 1);
                if (err != OK) {
                    return err;
                }
            }
            CHECK_EQ(*offset, stop_offset);

            if (chunk_type == FOURCC('m', 'o', 'o', 'v')) {
                mHaveMetadata = true;

                return UNKNOWN_ERROR;  // Return a dummy error.
            }
            break;
        }

        case FOURCC('t', 'k', 'h', 'd'):
        {
            CHECK(chunk_data_size >= 4);

            uint8_t version;
            if (mDataSource->readAt(data_offset, &version, 1) < 1) {
                return ERROR_IO;
            }

            uint64_t ctime, mtime, duration;
            int32_t id;
            uint32_t width, height;

            if (version == 1) {
                if (chunk_data_size != 36 + 60) {
                    return ERROR_MALFORMED;
                }

                uint8_t buffer[36 + 60];
                if (mDataSource->readAt(
                            data_offset, buffer, sizeof(buffer)) < (ssize_t)sizeof(buffer)) {
                    return ERROR_IO;
                }

                ctime = U64_AT(&buffer[4]);
                mtime = U64_AT(&buffer[12]);
                id = U32_AT(&buffer[20]);
                duration = U64_AT(&buffer[28]);
                width = U32_AT(&buffer[88]);
                height = U32_AT(&buffer[92]);
            } else if (version == 0) {
                if (chunk_data_size != 24 + 60) {
                    return ERROR_MALFORMED;
                }

                uint8_t buffer[24 + 60];
                if (mDataSource->readAt(
                            data_offset, buffer, sizeof(buffer)) < (ssize_t)sizeof(buffer)) {
                    return ERROR_IO;
                }
                ctime = U32_AT(&buffer[4]);
                mtime = U32_AT(&buffer[8]);
                id = U32_AT(&buffer[12]);
                duration = U32_AT(&buffer[20]);
                width = U32_AT(&buffer[76]);
                height = U32_AT(&buffer[80]);
            }

            Track *track = new Track;
            track->next = NULL;
            if (mLastTrack) {
                mLastTrack->next = track;
            } else {
                mFirstTrack = track;
            }
            mLastTrack = track;

            track->meta = new MetaData;
            track->includes_expensive_metadata = false;
            track->timescale = 0;
            track->sampleTable = new SampleTable(mDataSource);
            track->meta->setCString(kKeyMIMEType, "application/octet-stream");

            *offset += chunk_size;
            break;
        }

        case FOURCC('m', 'd', 'h', 'd'):
        {
            if (chunk_data_size < 4) {
                return ERROR_MALFORMED;
            }

            uint8_t version;
            if (mDataSource->readAt(
                        data_offset, &version, sizeof(version))
                    < (ssize_t)sizeof(version)) {
                return ERROR_IO;
            }

            off_t timescale_offset;

            if (version == 1) {
                timescale_offset = data_offset + 4 + 16;
            } else if (version == 0) {
                timescale_offset = data_offset + 4 + 8;
            } else {
                return ERROR_IO;
            }

            uint32_t timescale;
            if (mDataSource->readAt(
                        timescale_offset, &timescale, sizeof(timescale))
                    < (ssize_t)sizeof(timescale)) {
                return ERROR_IO;
            }

            mLastTrack->timescale = ntohl(timescale);

            int64_t duration;
            if (version == 1) {
                if (mDataSource->readAt(
                            timescale_offset + 4, &duration, sizeof(duration))
                        < (ssize_t)sizeof(duration)) {
                    return ERROR_IO;
                }
                duration = ntoh64(duration);
            } else {
                int32_t duration32;
                if (mDataSource->readAt(
                            timescale_offset + 4, &duration32, sizeof(duration32))
                        < (ssize_t)sizeof(duration32)) {
                    return ERROR_IO;
                }
                duration = ntohl(duration32);
            }
            mLastTrack->meta->setInt64(
                    kKeyDuration, (duration * 1000000) / mLastTrack->timescale);

            *offset += chunk_size;
            break;
        }

        case FOURCC('h', 'd', 'l', 'r'):
        {
            if (chunk_data_size < 25) {
                return ERROR_MALFORMED;
            }

            uint8_t buffer[24];
            if (mDataSource->readAt(data_offset, buffer, 24) < 24) {
                return ERROR_IO;
            }

            if (U32_AT(buffer) != 0) {
                // Should be version 0, flags 0.
                return ERROR_MALFORMED;
            }

            if (U32_AT(&buffer[4]) != 0) {
                // pre_defined should be 0.
                return ERROR_MALFORMED;
            }

            mHandlerType = U32_AT(&buffer[8]);
            *offset += chunk_size;
            break;
        }

        case FOURCC('s', 't', 's', 'd'):
        {
            if (chunk_data_size < 8) {
                return ERROR_MALFORMED;
            }

            uint8_t buffer[8];
            CHECK(chunk_data_size >= (off_t)sizeof(buffer));
            if (mDataSource->readAt(
                        data_offset, buffer, 8) < 8) {
                return ERROR_IO;
            }

            if (U32_AT(buffer) != 0) {
                // Should be version 0, flags 0.
                return ERROR_MALFORMED;
            }

            uint32_t entry_count = U32_AT(&buffer[4]);

            if (entry_count > 1) {
                // For now we only support a single type of media per track.
                return ERROR_UNSUPPORTED;
            }

            off_t stop_offset = *offset + chunk_size;
            *offset = data_offset + 8;
            for (uint32_t i = 0; i < entry_count; ++i) {
                status_t err = parseChunk(offset, depth + 1);
                if (err != OK) {
                    return err;
                }
            }
            CHECK_EQ(*offset, stop_offset);
            break;
        }

        case FOURCC('m', 'p', '4', 'a'):
        case FOURCC('s', 'a', 'm', 'r'):
        case FOURCC('s', 'a', 'w', 'b'):
        {
            if (mHandlerType != FOURCC('s', 'o', 'u', 'n')) {
                return ERROR_MALFORMED;
            }

            uint8_t buffer[8 + 20];
            if (chunk_data_size < (ssize_t)sizeof(buffer)) {
                // Basic AudioSampleEntry size.
                return ERROR_MALFORMED;
            }

            if (mDataSource->readAt(
                        data_offset, buffer, sizeof(buffer)) < (ssize_t)sizeof(buffer)) {
                return ERROR_IO;
            }

            uint16_t data_ref_index = U16_AT(&buffer[6]);
            uint16_t num_channels = U16_AT(&buffer[16]);

            if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_NB,
                            FourCC2MIME(chunk_type))
                || !strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_WB,
                               FourCC2MIME(chunk_type))) {
                // AMR audio is always mono.
                num_channels = 1;
            }

            uint16_t sample_size = U16_AT(&buffer[18]);
            uint32_t sample_rate = U32_AT(&buffer[24]) >> 16;

            // printf("*** coding='%s' %d channels, size %d, rate %d\n",
            //        chunk, num_channels, sample_size, sample_rate);

            mLastTrack->meta->setCString(kKeyMIMEType, FourCC2MIME(chunk_type));
            mLastTrack->meta->setInt32(kKeyChannelCount, num_channels);
            mLastTrack->meta->setInt32(kKeySampleRate, sample_rate);

            off_t stop_offset = *offset + chunk_size;
            *offset = data_offset + sizeof(buffer);
            while (*offset < stop_offset) {
                status_t err = parseChunk(offset, depth + 1);
                if (err != OK) {
                    return err;
                }
            }
            CHECK_EQ(*offset, stop_offset);
            break;
        }

        case FOURCC('m', 'p', '4', 'v'):
        case FOURCC('s', '2', '6', '3'):
        case FOURCC('a', 'v', 'c', '1'):
        {
            mHasVideo = true;

            if (mHandlerType != FOURCC('v', 'i', 'd', 'e')) {
                return ERROR_MALFORMED;
            }

            uint8_t buffer[78];
            if (chunk_data_size < (ssize_t)sizeof(buffer)) {
                // Basic VideoSampleEntry size.
                return ERROR_MALFORMED;
            }

            if (mDataSource->readAt(
                        data_offset, buffer, sizeof(buffer)) < (ssize_t)sizeof(buffer)) {
                return ERROR_IO;
            }

            uint16_t data_ref_index = U16_AT(&buffer[6]);
            uint16_t width = U16_AT(&buffer[6 + 18]);
            uint16_t height = U16_AT(&buffer[6 + 20]);

            // printf("*** coding='%s' width=%d height=%d\n",
            //        chunk, width, height);

            mLastTrack->meta->setCString(kKeyMIMEType, FourCC2MIME(chunk_type));
            mLastTrack->meta->setInt32(kKeyWidth, width);
            mLastTrack->meta->setInt32(kKeyHeight, height);

            off_t stop_offset = *offset + chunk_size;
            *offset = data_offset + sizeof(buffer);
            while (*offset < stop_offset) {
                status_t err = parseChunk(offset, depth + 1);
                if (err != OK) {
                    return err;
                }
            }
            CHECK_EQ(*offset, stop_offset);
            break;
        }

        case FOURCC('s', 't', 'c', 'o'):
        case FOURCC('c', 'o', '6', '4'):
        {
            status_t err =
                mLastTrack->sampleTable->setChunkOffsetParams(
                        chunk_type, data_offset, chunk_data_size);

            if (err != OK) {
                return err;
            }

            *offset += chunk_size;
            break;
        }

        case FOURCC('s', 't', 's', 'c'):
        {
            status_t err =
                mLastTrack->sampleTable->setSampleToChunkParams(
                        data_offset, chunk_data_size);

            if (err != OK) {
                return err;
            }

            *offset += chunk_size;
            break;
        }

        case FOURCC('s', 't', 's', 'z'):
        case FOURCC('s', 't', 'z', '2'):
        {
            status_t err =
                mLastTrack->sampleTable->setSampleSizeParams(
                        chunk_type, data_offset, chunk_data_size);

            if (err != OK) {
                return err;
            }

            size_t max_size;
            CHECK_EQ(mLastTrack->sampleTable->getMaxSampleSize(&max_size), OK);

            // Assume that a given buffer only contains at most 10 fragments,
            // each fragment originally prefixed with a 2 byte length will
            // have a 4 byte header (0x00 0x00 0x00 0x01) after conversion,
            // and thus will grow by 2 bytes per fragment.
            mLastTrack->meta->setInt32(kKeyMaxInputSize, max_size + 10 * 2);

            *offset += chunk_size;
            break;
        }

        case FOURCC('s', 't', 't', 's'):
        {
            status_t err =
                mLastTrack->sampleTable->setTimeToSampleParams(
                        data_offset, chunk_data_size);

            if (err != OK) {
                return err;
            }

            *offset += chunk_size;
            break;
        }

        case FOURCC('s', 't', 's', 's'):
        {
            status_t err =
                mLastTrack->sampleTable->setSyncSampleParams(
                        data_offset, chunk_data_size);

            if (err != OK) {
                return err;
            }

            *offset += chunk_size;
            break;
        }

        case FOURCC('e', 's', 'd', 's'):
        {
            if (chunk_data_size < 4) {
                return ERROR_MALFORMED;
            }

            uint8_t buffer[256];
            if (chunk_data_size > (off_t)sizeof(buffer)) {
                return ERROR_BUFFER_TOO_SMALL;
            }

            if (mDataSource->readAt(
                        data_offset, buffer, chunk_data_size) < chunk_data_size) {
                return ERROR_IO;
            }

            if (U32_AT(buffer) != 0) {
                // Should be version 0, flags 0.
                return ERROR_MALFORMED;
            }

            mLastTrack->meta->setData(
                    kKeyESDS, kTypeESDS, &buffer[4], chunk_data_size - 4);

            *offset += chunk_size;
            break;
        }

        case FOURCC('a', 'v', 'c', 'C'):
        {
            char buffer[256];
            if (chunk_data_size > (off_t)sizeof(buffer)) {
                return ERROR_BUFFER_TOO_SMALL;
            }

            if (mDataSource->readAt(
                        data_offset, buffer, chunk_data_size) < chunk_data_size) {
                return ERROR_IO;
            }

            mLastTrack->meta->setData(
                    kKeyAVCC, kTypeAVCC, buffer, chunk_data_size);

            *offset += chunk_size;
            break;
        }

        default:
        {
            *offset += chunk_size;
            break;
        }
    }

    return OK;
}

sp<MediaSource> MPEG4Extractor::getTrack(size_t index) {
    status_t err;
    if ((err = readMetaData()) != OK) {
        return NULL;
    }

    Track *track = mFirstTrack;
    while (index > 0) {
        if (track == NULL) {
            return NULL;
        }

        track = track->next;
        --index;
    }

    if (track == NULL) {
        return NULL;
    }

    return new MPEG4Source(
            track->meta, mDataSource, track->timescale, track->sampleTable);
}

////////////////////////////////////////////////////////////////////////////////

MPEG4Source::MPEG4Source(
        const sp<MetaData> &format,
        const sp<DataSource> &dataSource,
        int32_t timeScale,
        const sp<SampleTable> &sampleTable)
    : mFormat(format),
      mDataSource(dataSource),
      mTimescale(timeScale),
      mSampleTable(sampleTable),
      mCurrentSampleIndex(0),
      mIsAVC(false),
      mNALLengthSize(0),
      mStarted(false),
      mGroup(NULL),
      mBuffer(NULL),
      mWantsNALFragments(false),
      mSrcBuffer(NULL) {
    const char *mime;
    bool success = mFormat->findCString(kKeyMIMEType, &mime);
    CHECK(success);

    mIsAVC = !strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC);

    if (mIsAVC) {
        uint32_t type;
        const void *data;
        size_t size;
        CHECK(format->findData(kKeyAVCC, &type, &data, &size));

        const uint8_t *ptr = (const uint8_t *)data;

        CHECK(size >= 7);
        CHECK_EQ(ptr[0], 1);  // configurationVersion == 1

        // The number of bytes used to encode the length of a NAL unit.
        mNALLengthSize = 1 + (ptr[4] & 3);
    }
}

MPEG4Source::~MPEG4Source() {
    if (mStarted) {
        stop();
    }
}

status_t MPEG4Source::start(MetaData *params) {
    CHECK(!mStarted);

    int32_t val;
    if (params && params->findInt32(kKeyWantsNALFragments, &val)
        && val != 0) {
        mWantsNALFragments = true;
    } else {
        mWantsNALFragments = false;
    }

    mGroup = new MediaBufferGroup;

    int32_t max_size;
    CHECK(mFormat->findInt32(kKeyMaxInputSize, &max_size));

    mGroup->add_buffer(new MediaBuffer(max_size));

    mSrcBuffer = new uint8_t[max_size];

    mStarted = true;

    return OK;
}

status_t MPEG4Source::stop() {
    CHECK(mStarted);

    if (mBuffer != NULL) {
        mBuffer->release();
        mBuffer = NULL;
    }

    delete[] mSrcBuffer;
    mSrcBuffer = NULL;

    delete mGroup;
    mGroup = NULL;

    mStarted = false;
    mCurrentSampleIndex = 0;

    return OK;
}

sp<MetaData> MPEG4Source::getFormat() {
    return mFormat;
}

size_t MPEG4Source::parseNALSize(const uint8_t *data) const {
    switch (mNALLengthSize) {
        case 1:
            return *data;
        case 2:
            return U16_AT(data);
        case 3:
            return ((size_t)data[0] << 16) | U16_AT(&data[1]);
        case 4:
            return U32_AT(data);
    }

    // This cannot happen, mNALLengthSize springs to life by adding 1 to
    // a 2-bit integer.
    CHECK(!"Should not be here.");

    return 0;
}

status_t MPEG4Source::read(
        MediaBuffer **out, const ReadOptions *options) {
    CHECK(mStarted);

    *out = NULL;

    int64_t seekTimeUs;
    if (options && options->getSeekTo(&seekTimeUs)) {
        uint32_t sampleIndex;
        status_t err = mSampleTable->findClosestSample(
                seekTimeUs * mTimescale / 1000000,
                &sampleIndex, SampleTable::kSyncSample_Flag);

        if (err != OK) {
            return err;
        }

        mCurrentSampleIndex = sampleIndex;
        if (mBuffer != NULL) {
            mBuffer->release();
            mBuffer = NULL;
        }

        // fall through
    }

    off_t offset;
    size_t size;
    uint32_t dts;
    bool newBuffer = false;
    if (mBuffer == NULL) {
        newBuffer = true;

        status_t err = mSampleTable->getSampleOffsetAndSize(
                mCurrentSampleIndex, &offset, &size);

        if (err != OK) {
            return err;
        }

        err = mSampleTable->getDecodingTime(mCurrentSampleIndex, &dts);

        if (err != OK) {
            return err;
        }

        err = mGroup->acquire_buffer(&mBuffer);
        if (err != OK) {
            CHECK_EQ(mBuffer, NULL);
            return err;
        }
    }

    if (!mIsAVC || mWantsNALFragments) {
        if (newBuffer) {
            ssize_t num_bytes_read =
                mDataSource->readAt(offset, (uint8_t *)mBuffer->data(), size);

            if (num_bytes_read < (ssize_t)size) {
                mBuffer->release();
                mBuffer = NULL;

                return ERROR_IO;
            }

            mBuffer->set_range(0, size);
            mBuffer->meta_data()->clear();
            mBuffer->meta_data()->setInt64(
                    kKeyTime, ((int64_t)dts * 1000000) / mTimescale);
            ++mCurrentSampleIndex;
        }

        if (!mIsAVC) {
            *out = mBuffer;
            mBuffer = NULL;

            return OK;
        }

        // Each NAL unit is split up into its constituent fragments and
        // each one of them returned in its own buffer.

        CHECK(mBuffer->range_length() >= mNALLengthSize);

        const uint8_t *src =
            (const uint8_t *)mBuffer->data() + mBuffer->range_offset();

        size_t nal_size = parseNALSize(src);
        CHECK(mBuffer->range_length() >= mNALLengthSize + nal_size);

        MediaBuffer *clone = mBuffer->clone();
        clone->set_range(mBuffer->range_offset() + mNALLengthSize, nal_size);

        mBuffer->set_range(
                mBuffer->range_offset() + mNALLengthSize + nal_size,
                mBuffer->range_length() - mNALLengthSize - nal_size);

        if (mBuffer->range_length() == 0) {
            mBuffer->release();
            mBuffer = NULL;
        }

        *out = clone;

        return OK;
    } else {
        // Whole NAL units are returned but each fragment is prefixed by
        // the start code (0x00 00 00 01).

        ssize_t num_bytes_read =
            mDataSource->readAt(offset, mSrcBuffer, size);

        if (num_bytes_read < (ssize_t)size) {
            mBuffer->release();
            mBuffer = NULL;

            return ERROR_IO;
        }

        uint8_t *dstData = (uint8_t *)mBuffer->data();
        size_t srcOffset = 0;
        size_t dstOffset = 0;

        while (srcOffset < size) {
            CHECK(srcOffset + mNALLengthSize <= size);
            size_t nalLength = parseNALSize(&mSrcBuffer[srcOffset]);
            srcOffset += mNALLengthSize;
            CHECK(srcOffset + nalLength <= size);

            if (nalLength == 0) {
                continue;
            }

            CHECK(dstOffset + 4 <= mBuffer->size());

            dstData[dstOffset++] = 0;
            dstData[dstOffset++] = 0;
            dstData[dstOffset++] = 0;
            dstData[dstOffset++] = 1;
            memcpy(&dstData[dstOffset], &mSrcBuffer[srcOffset], nalLength);
            srcOffset += nalLength;
            dstOffset += nalLength;
        }
        CHECK_EQ(srcOffset, size);

        mBuffer->set_range(0, dstOffset);
        mBuffer->meta_data()->clear();
        mBuffer->meta_data()->setInt64(
                kKeyTime, ((int64_t)dts * 1000000) / mTimescale);
        ++mCurrentSampleIndex;

        *out = mBuffer;
        mBuffer = NULL;

        return OK;
    }
}

bool SniffMPEG4(
        const sp<DataSource> &source, String8 *mimeType, float *confidence) {
    uint8_t header[8];

    ssize_t n = source->readAt(4, header, sizeof(header));
    if (n < (ssize_t)sizeof(header)) {
        return false;
    }

    if (!memcmp(header, "ftyp3gp", 7) || !memcmp(header, "ftypmp42", 8)
        || !memcmp(header, "ftypisom", 8) || !memcmp(header, "ftypM4V ", 8)
        || !memcmp(header, "ftypM4A ", 8)) {
        *mimeType = MEDIA_MIMETYPE_CONTAINER_MPEG4;
        *confidence = 0.1;

        return true;
    }

    return false;
}

}  // namespace android

