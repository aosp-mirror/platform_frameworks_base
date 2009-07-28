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

#include <arpa/inet.h>

#undef NDEBUG
#include <assert.h>

#include <ctype.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MPEG4Extractor.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/SampleTable.h>
#include <media/stagefright/Utils.h>
#include <utils/String8.h>

namespace android {

class MPEG4Source : public MediaSource {
public:
    // Caller retains ownership of both "dataSource" and "sampleTable".
    MPEG4Source(const sp<MetaData> &format, DataSource *dataSource,
                SampleTable *sampleTable);

    virtual ~MPEG4Source();

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

private:
    sp<MetaData> mFormat;
    DataSource *mDataSource;
    int32_t mTimescale;
    SampleTable *mSampleTable;
    uint32_t mCurrentSampleIndex;

    bool mIsAVC;
    bool mStarted;

    MediaBufferGroup *mGroup;

    MediaBuffer *mBuffer;
    size_t mBufferOffset;
    size_t mBufferSizeRemaining;

    bool mNeedsNALFraming;

    uint8_t *mSrcBuffer;

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

static const char *const FourCC2MIME(uint32_t fourcc) {
    switch (fourcc) {
        case FOURCC('m', 'p', '4', 'a'):
            return "audio/mp4a-latm";

        case FOURCC('s', 'a', 'm', 'r'):
            return "audio/3gpp";

        case FOURCC('m', 'p', '4', 'v'):
            return "video/mp4v-es";

        case FOURCC('s', '2', '6', '3'):
            return "video/3gpp";

        case FOURCC('a', 'v', 'c', '1'):
            return "video/avc";

        default:
            assert(!"should not be here.");
            return NULL;
    }
}

MPEG4Extractor::MPEG4Extractor(DataSource *source)
    : mDataSource(source),
      mHaveMetadata(false),
      mFirstTrack(NULL),
      mLastTrack(NULL) {
}

MPEG4Extractor::~MPEG4Extractor() {
    Track *track = mFirstTrack;
    while (track) {
        Track *next = track->next;

        delete track->sampleTable;
        track->sampleTable = NULL;

        delete track;
        track = next;
    }
    mFirstTrack = mLastTrack = NULL;

    delete mDataSource;
    mDataSource = NULL;
}

status_t MPEG4Extractor::countTracks(int *num_tracks) {
    status_t err;
    if ((err = readMetaData()) != OK) {
        return err;
    }

    *num_tracks = 0;
    Track *track = mFirstTrack;
    while (track) {
        ++*num_tracks;
        track = track->next;
    }

    return OK;
}

sp<MetaData> MPEG4Extractor::getTrackMetaData(int index) {
    if (index < 0) {
        return NULL;
    }

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
    if (mDataSource->read_at(*offset, hdr, 8) < 8) {
        return ERROR_IO;
    }
    uint64_t chunk_size = ntohl(hdr[0]);
    uint32_t chunk_type = ntohl(hdr[1]);
    off_t data_offset = *offset + 8;

    if (chunk_size == 1) {
        if (mDataSource->read_at(*offset + 8, &chunk_size, 8) < 8) {
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
        if (mDataSource->read_at(*offset, buffer, chunk_size) < chunk_size) {
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
            assert(*offset == stop_offset);

            if (chunk_type == FOURCC('m', 'o', 'o', 'v')) {
                mHaveMetadata = true;

                return UNKNOWN_ERROR;  // Return a dummy error.
            }
            break;
        }

        case FOURCC('t', 'k', 'h', 'd'):
        {
            assert(chunk_data_size >= 4);

            uint8_t version;
            if (mDataSource->read_at(data_offset, &version, 1) < 1) {
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
                if (mDataSource->read_at(
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
                if (mDataSource->read_at(
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
            if (mDataSource->read_at(
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
            if (mDataSource->read_at(
                        timescale_offset, &timescale, sizeof(timescale))
                    < (ssize_t)sizeof(timescale)) {
                return ERROR_IO;
            }

            mLastTrack->timescale = ntohl(timescale);
            mLastTrack->meta->setInt32(kKeyTimeScale, mLastTrack->timescale);

            int64_t duration;
            if (version == 1) {
                if (mDataSource->read_at(
                            timescale_offset + 4, &duration, sizeof(duration))
                        < (ssize_t)sizeof(duration)) {
                    return ERROR_IO;
                }
                duration = ntoh64(duration);
            } else {
                int32_t duration32;
                if (mDataSource->read_at(
                            timescale_offset + 4, &duration32, sizeof(duration32))
                        < (ssize_t)sizeof(duration32)) {
                    return ERROR_IO;
                }
                duration = ntohl(duration32);
            }
            mLastTrack->meta->setInt32(kKeyDuration, duration);

            *offset += chunk_size;
            break;
        }

        case FOURCC('h', 'd', 'l', 'r'):
        {
            if (chunk_data_size < 25) {
                return ERROR_MALFORMED;
            }

            uint8_t buffer[24];
            if (mDataSource->read_at(data_offset, buffer, 24) < 24) {
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
            assert(chunk_data_size >= (off_t)sizeof(buffer));
            if (mDataSource->read_at(
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
            assert(*offset == stop_offset);
            break;
        }

        case FOURCC('m', 'p', '4', 'a'):
        case FOURCC('s', 'a', 'm', 'r'):
        {
            if (mHandlerType != FOURCC('s', 'o', 'u', 'n')) {
                return ERROR_MALFORMED;
            }

            uint8_t buffer[8 + 20];
            if (chunk_data_size < (ssize_t)sizeof(buffer)) {
                // Basic AudioSampleEntry size.
                return ERROR_MALFORMED;
            }

            if (mDataSource->read_at(
                        data_offset, buffer, sizeof(buffer)) < (ssize_t)sizeof(buffer)) {
                return ERROR_IO;
            }

            uint16_t data_ref_index = U16_AT(&buffer[6]);
            uint16_t num_channels = U16_AT(&buffer[16]);

            if (!strcasecmp("audio/3gpp", FourCC2MIME(chunk_type))) {
                // AMR audio is always mono.
                num_channels = 1;
            }

            uint16_t sample_size = U16_AT(&buffer[18]);
            uint32_t sample_rate = U32_AT(&buffer[24]) >> 16;

            printf("*** coding='%s' %d channels, size %d, rate %d\n",
                   chunk, num_channels, sample_size, sample_rate);

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
            assert(*offset == stop_offset);
            break;
        }

        case FOURCC('m', 'p', '4', 'v'):
        case FOURCC('s', '2', '6', '3'):
        case FOURCC('a', 'v', 'c', '1'):
        {
            if (mHandlerType != FOURCC('v', 'i', 'd', 'e')) {
                return ERROR_MALFORMED;
            }

            uint8_t buffer[78];
            if (chunk_data_size < (ssize_t)sizeof(buffer)) {
                // Basic VideoSampleEntry size.
                return ERROR_MALFORMED;
            }

            if (mDataSource->read_at(
                        data_offset, buffer, sizeof(buffer)) < (ssize_t)sizeof(buffer)) {
                return ERROR_IO;
            }

            uint16_t data_ref_index = U16_AT(&buffer[6]);
            uint16_t width = U16_AT(&buffer[6 + 18]);
            uint16_t height = U16_AT(&buffer[6 + 20]);

            printf("*** coding='%s' width=%d height=%d\n",
                   chunk, width, height);

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
            assert(*offset == stop_offset);
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

            if (mDataSource->read_at(
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

            if (mDataSource->read_at(
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

status_t MPEG4Extractor::getTrack(int index, MediaSource **source) {
    *source = NULL;

    if (index < 0) {
        return ERROR_OUT_OF_RANGE;
    }

    status_t err;
    if ((err = readMetaData()) != OK) {
        return err;
    }

    Track *track = mFirstTrack;
    while (index > 0) {
        if (track == NULL) {
            return ERROR_OUT_OF_RANGE;
        }

        track = track->next;
        --index;
    }

    *source = new MPEG4Source(
            track->meta, mDataSource, track->sampleTable);

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

MPEG4Source::MPEG4Source(
        const sp<MetaData> &format,
        DataSource *dataSource, SampleTable *sampleTable)
    : mFormat(format),
      mDataSource(dataSource),
      mTimescale(0),
      mSampleTable(sampleTable),
      mCurrentSampleIndex(0),
      mIsAVC(false),
      mStarted(false),
      mGroup(NULL),
      mBuffer(NULL),
      mBufferOffset(0),
      mBufferSizeRemaining(0),
      mNeedsNALFraming(false),
      mSrcBuffer(NULL) {
    const char *mime;
    bool success = mFormat->findCString(kKeyMIMEType, &mime);
    assert(success);

    success = mFormat->findInt32(kKeyTimeScale, &mTimescale);
    assert(success);

    mIsAVC = !strcasecmp(mime, "video/avc");
}

MPEG4Source::~MPEG4Source() {
    if (mStarted) {
        stop();
    }
}

status_t MPEG4Source::start(MetaData *params) {
    assert(!mStarted);

    int32_t val;
    if (mIsAVC && params && params->findInt32(kKeyNeedsNALFraming, &val)
        && val != 0) {
        mNeedsNALFraming = true;
    } else {
        mNeedsNALFraming = false;
    }

    mGroup = new MediaBufferGroup;

    size_t max_size;
    status_t err = mSampleTable->getMaxSampleSize(&max_size);
    assert(err == OK);

    // Assume that a given buffer only contains at most 10 fragments,
    // each fragment originally prefixed with a 2 byte length will
    // have a 4 byte header (0x00 0x00 0x00 0x01) after conversion,
    // and thus will grow by 2 bytes per fragment.
    mGroup->add_buffer(new MediaBuffer(max_size + 10 * 2));

    mSrcBuffer = new uint8_t[max_size];

    mStarted = true;

    return OK;
}

status_t MPEG4Source::stop() {
    assert(mStarted);

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

status_t MPEG4Source::read(
        MediaBuffer **out, const ReadOptions *options) {
    assert(mStarted);

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
    status_t err = mSampleTable->getSampleOffsetAndSize(
            mCurrentSampleIndex, &offset, &size);

    if (err != OK) {
        return err;
    }

    uint32_t dts;
    err = mSampleTable->getDecodingTime(mCurrentSampleIndex, &dts);

    if (err != OK) {
        return err;
    }

    err = mGroup->acquire_buffer(&mBuffer);
    if (err != OK) {
        assert(mBuffer == NULL);
        return err;
    }

    if (!mIsAVC || !mNeedsNALFraming) {
        ssize_t num_bytes_read =
            mDataSource->read_at(offset, (uint8_t *)mBuffer->data(), size);

        if (num_bytes_read < (ssize_t)size) {
            mBuffer->release();
            mBuffer = NULL;

            return err;
        }

        mBuffer->set_range(0, size);
        mBuffer->meta_data()->clear();
        mBuffer->meta_data()->setInt32(kKeyTimeUnits, dts);
        mBuffer->meta_data()->setInt32(kKeyTimeScale, mTimescale);
        ++mCurrentSampleIndex;

        *out = mBuffer;
        mBuffer = NULL;

        return OK;
    }

    ssize_t num_bytes_read =
        mDataSource->read_at(offset, mSrcBuffer, size);

    if (num_bytes_read < (ssize_t)size) {
        mBuffer->release();
        mBuffer = NULL;

        return err;
    }

    uint8_t *dstData = (uint8_t *)mBuffer->data();
    size_t srcOffset = 0;
    size_t dstOffset = 0;
    while (srcOffset < size) {
        assert(srcOffset + 1 < size);
        size_t nalLength =
            (mSrcBuffer[srcOffset] << 8) | mSrcBuffer[srcOffset + 1];
        assert(srcOffset + 1 + nalLength < size);
        srcOffset += 2;

        if (nalLength == 0) {
            continue;
        }

        assert(dstOffset + 4 <= mBuffer->size());

        dstData[dstOffset++] = 0;
        dstData[dstOffset++] = 0;
        dstData[dstOffset++] = 0;
        dstData[dstOffset++] = 1;
        memcpy(&dstData[dstOffset], &mSrcBuffer[srcOffset], nalLength);
        srcOffset += nalLength;
        dstOffset += nalLength;
    }

    mBuffer->set_range(0, dstOffset);
    mBuffer->meta_data()->clear();
    mBuffer->meta_data()->setInt32(kKeyTimeUnits, dts);
    mBuffer->meta_data()->setInt32(kKeyTimeScale, mTimescale);
    ++mCurrentSampleIndex;

    *out = mBuffer;
    mBuffer = NULL;

    return OK;
}

bool SniffMPEG4(DataSource *source, String8 *mimeType, float *confidence) {
    uint8_t header[8];

    ssize_t n = source->read_at(4, header, sizeof(header));
    if (n < (ssize_t)sizeof(header)) {
        return false;
    }

    if (!memcmp(header, "ftyp3gp", 7) || !memcmp(header, "ftypmp42", 8)
        || !memcmp(header, "ftypisom", 8) || !memcmp(header, "ftypM4V ", 8)) {
        *mimeType = "video/mp4";
        *confidence = 0.1;

        return true;
    }

    return false;
}

}  // namespace android

