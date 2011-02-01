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

//#define LOG_NDEBUG 0
#define LOG_TAG "AMRExtractor"
#include <utils/Log.h>

#include "include/AMRExtractor.h"

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <utils/String8.h>

namespace android {

class AMRSource : public MediaSource {
public:
    AMRSource(const sp<DataSource> &source,
              const sp<MetaData> &meta,
              bool isWide,
              const off64_t *offset_table,
              size_t offset_table_length);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~AMRSource();

private:
    sp<DataSource> mDataSource;
    sp<MetaData> mMeta;
    bool mIsWide;

    off64_t mOffset;
    int64_t mCurrentTimeUs;
    bool mStarted;
    MediaBufferGroup *mGroup;

    off64_t mOffsetTable[OFFSET_TABLE_LEN];
    size_t mOffsetTableLength;

    AMRSource(const AMRSource &);
    AMRSource &operator=(const AMRSource &);
};

////////////////////////////////////////////////////////////////////////////////

static size_t getFrameSize(bool isWide, unsigned FT) {
    static const size_t kFrameSizeNB[16] = {
        95, 103, 118, 134, 148, 159, 204, 244,
        39, 43, 38, 37, // SID
        0, 0, 0, // future use
        0 // no data
    };
    static const size_t kFrameSizeWB[16] = {
        132, 177, 253, 285, 317, 365, 397, 461, 477,
        40, // SID
        0, 0, 0, 0, // future use
        0, // speech lost
        0 // no data
    };

    if (FT > 15 || (isWide && FT > 9 && FT < 14) || (!isWide && FT > 11 && FT < 15)) {
        LOGE("illegal AMR frame type %d", FT);
        return 0;
    }

    size_t frameSize = isWide ? kFrameSizeWB[FT] : kFrameSizeNB[FT];

    // Round up bits to bytes and add 1 for the header byte.
    frameSize = (frameSize + 7) / 8 + 1;

    return frameSize;
}

static status_t getFrameSizeByOffset(const sp<DataSource> &source,
        off64_t offset, bool isWide, size_t *frameSize) {
    uint8_t header;
    if (source->readAt(offset, &header, 1) < 1) {
        return ERROR_IO;
    }

    unsigned FT = (header >> 3) & 0x0f;

    *frameSize = getFrameSize(isWide, FT);
    if (*frameSize == 0) {
        return ERROR_MALFORMED;
    }
    return OK;
}

AMRExtractor::AMRExtractor(const sp<DataSource> &source)
    : mDataSource(source),
      mInitCheck(NO_INIT),
      mOffsetTableLength(0) {
    String8 mimeType;
    float confidence;
    if (!SniffAMR(mDataSource, &mimeType, &confidence, NULL)) {
        return;
    }

    mIsWide = (mimeType == MEDIA_MIMETYPE_AUDIO_AMR_WB);

    mMeta = new MetaData;
    mMeta->setCString(
            kKeyMIMEType, mIsWide ? MEDIA_MIMETYPE_AUDIO_AMR_WB
                                  : MEDIA_MIMETYPE_AUDIO_AMR_NB);

    mMeta->setInt32(kKeyChannelCount, 1);
    mMeta->setInt32(kKeySampleRate, mIsWide ? 16000 : 8000);

    off64_t offset = mIsWide ? 9 : 6;
    off64_t streamSize;
    size_t frameSize, numFrames = 0;
    int64_t duration = 0;

    if (mDataSource->getSize(&streamSize) == OK) {
         while (offset < streamSize) {
            if (getFrameSizeByOffset(source, offset, mIsWide, &frameSize) != OK) {
                return;
            }

            if ((numFrames % 50 == 0) && (numFrames / 50 < OFFSET_TABLE_LEN)) {
                CHECK_EQ(mOffsetTableLength, numFrames / 50);
                mOffsetTable[mOffsetTableLength] = offset - (mIsWide ? 9: 6);
                mOffsetTableLength ++;
            }

            offset += frameSize;
            duration += 20000;  // Each frame is 20ms
            numFrames ++;
        }

        mMeta->setInt64(kKeyDuration, duration);
    }

    mInitCheck = OK;
}

AMRExtractor::~AMRExtractor() {
}

sp<MetaData> AMRExtractor::getMetaData() {
    sp<MetaData> meta = new MetaData;

    if (mInitCheck != OK) {
        return meta;
    }

    meta->setCString(kKeyMIMEType, mIsWide ? "audio/amr-wb" : "audio/amr");

    return meta;
}

size_t AMRExtractor::countTracks() {
    return mInitCheck == OK ? 1 : 0;
}

sp<MediaSource> AMRExtractor::getTrack(size_t index) {
    if (mInitCheck != OK || index != 0) {
        return NULL;
    }

    return new AMRSource(mDataSource, mMeta, mIsWide,
            mOffsetTable, mOffsetTableLength);
}

sp<MetaData> AMRExtractor::getTrackMetaData(size_t index, uint32_t flags) {
    if (mInitCheck != OK || index != 0) {
        return NULL;
    }

    return mMeta;
}

////////////////////////////////////////////////////////////////////////////////

AMRSource::AMRSource(
        const sp<DataSource> &source, const sp<MetaData> &meta,
        bool isWide, const off64_t *offset_table, size_t offset_table_length)
    : mDataSource(source),
      mMeta(meta),
      mIsWide(isWide),
      mOffset(mIsWide ? 9 : 6),
      mCurrentTimeUs(0),
      mStarted(false),
      mGroup(NULL),
      mOffsetTableLength(offset_table_length) {
    if (mOffsetTableLength > 0 && mOffsetTableLength <= OFFSET_TABLE_LEN) {
        memcpy ((char*)mOffsetTable, (char*)offset_table, sizeof(off64_t) * mOffsetTableLength);
    }
}

AMRSource::~AMRSource() {
    if (mStarted) {
        stop();
    }
}

status_t AMRSource::start(MetaData *params) {
    CHECK(!mStarted);

    mOffset = mIsWide ? 9 : 6;
    mCurrentTimeUs = 0;
    mGroup = new MediaBufferGroup;
    mGroup->add_buffer(new MediaBuffer(128));
    mStarted = true;

    return OK;
}

status_t AMRSource::stop() {
    CHECK(mStarted);

    delete mGroup;
    mGroup = NULL;

    mStarted = false;
    return OK;
}

sp<MetaData> AMRSource::getFormat() {
    return mMeta;
}

status_t AMRSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        size_t size;
        int64_t seekFrame = seekTimeUs / 20000ll;  // 20ms per frame.
        mCurrentTimeUs = seekFrame * 20000ll;

        int index = seekFrame / 50;
        if (index >= mOffsetTableLength) {
            index = mOffsetTableLength - 1;
        }

        mOffset = mOffsetTable[index] + (mIsWide ? 9 : 6);

        for (int i = 0; i< seekFrame - index * 50; i++) {
            status_t err;
            if ((err = getFrameSizeByOffset(mDataSource, mOffset,
                            mIsWide, &size)) != OK) {
                return err;
            }
            mOffset += size;
        }
    }

    uint8_t header;
    ssize_t n = mDataSource->readAt(mOffset, &header, 1);

    if (n < 1) {
        return ERROR_END_OF_STREAM;
    }

    if (header & 0x83) {
        // Padding bits must be 0.

        LOGE("padding bits must be 0, header is 0x%02x", header);

        return ERROR_MALFORMED;
    }

    unsigned FT = (header >> 3) & 0x0f;

    size_t frameSize = getFrameSize(mIsWide, FT);
    if (frameSize == 0) {
        return ERROR_MALFORMED;
    }

    MediaBuffer *buffer;
    status_t err = mGroup->acquire_buffer(&buffer);
    if (err != OK) {
        return err;
    }

    n = mDataSource->readAt(mOffset, buffer->data(), frameSize);

    if (n != (ssize_t)frameSize) {
        buffer->release();
        buffer = NULL;

        return ERROR_IO;
    }

    buffer->set_range(0, frameSize);
    buffer->meta_data()->setInt64(kKeyTime, mCurrentTimeUs);
    buffer->meta_data()->setInt32(kKeyIsSyncFrame, 1);

    mOffset += frameSize;
    mCurrentTimeUs += 20000;  // Each frame is 20ms

    *out = buffer;

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

bool SniffAMR(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *) {
    char header[9];

    if (source->readAt(0, header, sizeof(header)) != sizeof(header)) {
        return false;
    }

    if (!memcmp(header, "#!AMR\n", 6)) {
        *mimeType = MEDIA_MIMETYPE_AUDIO_AMR_NB;
        *confidence = 0.5;

        return true;
    } else if (!memcmp(header, "#!AMR-WB\n", 9)) {
        *mimeType = MEDIA_MIMETYPE_AUDIO_AMR_WB;
        *confidence = 0.5;

        return true;
    }

    return false;
}

}  // namespace android
