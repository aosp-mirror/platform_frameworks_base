/*
 * Copyright (C) 2011 The Android Open Source Project
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
#define LOG_TAG "AACExtractor"
#include <utils/Log.h>

#include "include/AACExtractor.h"
#include "include/avc_utils.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <utils/String8.h>

namespace android {

#define ADTS_HEADER_LENGTH 7

class AACSource : public MediaSource {
public:
    AACSource(const sp<DataSource> &source,
              const sp<MetaData> &meta,
              const Vector<uint64_t> &offset_vector,
              int64_t frame_duration_us);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~AACSource();

private:
    static const size_t kMaxFrameSize;
    sp<DataSource> mDataSource;
    sp<MetaData> mMeta;

    off64_t mOffset;
    int64_t mCurrentTimeUs;
    bool mStarted;
    MediaBufferGroup *mGroup;

    Vector<uint64_t> mOffsetVector;
    int64_t mFrameDurationUs;

    AACSource(const AACSource &);
    AACSource &operator=(const AACSource &);
};

////////////////////////////////////////////////////////////////////////////////

// Returns the sample rate based on the sampling frequency index
uint32_t get_sample_rate(const uint8_t sf_index)
{
    static const uint32_t sample_rates[] =
    {
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000
    };

    if (sf_index < sizeof(sample_rates) / sizeof(sample_rates[0])) {
        return sample_rates[sf_index];
    }

    return 0;
}

static size_t getFrameSize(const sp<DataSource> &source, off64_t offset) {
    size_t frameSize = 0;

    uint8_t syncword[2];
    if (source->readAt(offset, &syncword, 2) != 2) {
        return 0;
    }
    if ((syncword[0] != 0xff) || ((syncword[1] & 0xf6) != 0xf0)) {
        return 0;
    }

    uint8_t protectionAbsent;
    if (source->readAt(offset + 1, &protectionAbsent, 1) < 1) {
        return 0;
    }
    protectionAbsent &= 0x1;

    uint8_t header[3];
    if (source->readAt(offset + 3, &header, 3) < 3) {
        return 0;
    }

    frameSize = (header[0] & 0x3) << 11 | header[1] << 3 | header[2] >> 5;
    frameSize += ADTS_HEADER_LENGTH + protectionAbsent ? 0 : 2;

    return frameSize;
}

AACExtractor::AACExtractor(const sp<DataSource> &source)
    : mDataSource(source),
      mInitCheck(NO_INIT),
      mFrameDurationUs(0) {
    String8 mimeType;
    float confidence;
    if (!SniffAAC(mDataSource, &mimeType, &confidence, NULL)) {
        return;
    }

    uint8_t profile, sf_index, channel, header[2];
    if (mDataSource->readAt(2, &header, 2) < 2) {
        return;
    }

    profile = (header[0] >> 6) & 0x3;
    sf_index = (header[0] >> 2) & 0xf;
    uint32_t sr = get_sample_rate(sf_index);
    if (sr == 0) {
        return;
    }
    channel = (header[0] & 0x1) << 2 | (header[1] >> 6);

    mMeta = MakeAACCodecSpecificData(profile, sf_index, channel);

    off64_t offset = 0;
    off64_t streamSize, numFrames = 0;
    size_t frameSize = 0;
    int64_t duration = 0;

    if (mDataSource->getSize(&streamSize) == OK) {
         while (offset < streamSize) {
            if ((frameSize = getFrameSize(source, offset)) == 0) {
                return;
            }

            mOffsetVector.push(offset);

            offset += frameSize;
            numFrames ++;
        }

        // Round up and get the duration
        mFrameDurationUs = (1024 * 1000000ll + (sr - 1)) / sr;
        duration = numFrames * mFrameDurationUs;
        mMeta->setInt64(kKeyDuration, duration);
    }

    mInitCheck = OK;
}

AACExtractor::~AACExtractor() {
}

sp<MetaData> AACExtractor::getMetaData() {
    sp<MetaData> meta = new MetaData;

    if (mInitCheck != OK) {
        return meta;
    }

    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AAC_ADTS);

    return meta;
}

size_t AACExtractor::countTracks() {
    return mInitCheck == OK ? 1 : 0;
}

sp<MediaSource> AACExtractor::getTrack(size_t index) {
    if (mInitCheck != OK || index != 0) {
        return NULL;
    }

    return new AACSource(mDataSource, mMeta, mOffsetVector, mFrameDurationUs);
}

sp<MetaData> AACExtractor::getTrackMetaData(size_t index, uint32_t flags) {
    if (mInitCheck != OK || index != 0) {
        return NULL;
    }

    return mMeta;
}

////////////////////////////////////////////////////////////////////////////////

// 8192 = 2^13, 13bit AAC frame size (in bytes)
const size_t AACSource::kMaxFrameSize = 8192;

AACSource::AACSource(
        const sp<DataSource> &source, const sp<MetaData> &meta,
        const Vector<uint64_t> &offset_vector,
        int64_t frame_duration_us)
    : mDataSource(source),
      mMeta(meta),
      mOffset(0),
      mCurrentTimeUs(0),
      mStarted(false),
      mGroup(NULL),
      mOffsetVector(offset_vector),
      mFrameDurationUs(frame_duration_us) {
}

AACSource::~AACSource() {
    if (mStarted) {
        stop();
    }
}

status_t AACSource::start(MetaData *params) {
    CHECK(!mStarted);

    mOffset = 0;
    mCurrentTimeUs = 0;
    mGroup = new MediaBufferGroup;
    mGroup->add_buffer(new MediaBuffer(kMaxFrameSize));
    mStarted = true;

    return OK;
}

status_t AACSource::stop() {
    CHECK(mStarted);

    delete mGroup;
    mGroup = NULL;

    mStarted = false;
    return OK;
}

sp<MetaData> AACSource::getFormat() {
    return mMeta;
}

status_t AACSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        if (mFrameDurationUs > 0) {
            int64_t seekFrame = seekTimeUs / mFrameDurationUs;
            mCurrentTimeUs = seekFrame * mFrameDurationUs;

            mOffset = mOffsetVector.itemAt(seekFrame);
        }
    }

    size_t frameSize, frameSizeWithoutHeader;
    if ((frameSize = getFrameSize(mDataSource, mOffset)) == 0) {
        return ERROR_END_OF_STREAM;
    }

    MediaBuffer *buffer;
    status_t err = mGroup->acquire_buffer(&buffer);
    if (err != OK) {
        return err;
    }

    frameSizeWithoutHeader = frameSize - ADTS_HEADER_LENGTH;
    if (mDataSource->readAt(mOffset + ADTS_HEADER_LENGTH, buffer->data(),
                frameSizeWithoutHeader) != (ssize_t)frameSizeWithoutHeader) {
        buffer->release();
        buffer = NULL;

        return ERROR_IO;
    }

    buffer->set_range(0, frameSizeWithoutHeader);
    buffer->meta_data()->setInt64(kKeyTime, mCurrentTimeUs);
    buffer->meta_data()->setInt32(kKeyIsSyncFrame, 1);

    mOffset += frameSize;
    mCurrentTimeUs += mFrameDurationUs;

    *out = buffer;
    return OK;
}

////////////////////////////////////////////////////////////////////////////////

bool SniffAAC(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *) {
    uint8_t header[2];

    if (source->readAt(0, &header, 2) != 2) {
        return false;
    }

    // ADTS syncword
    if ((header[0] == 0xff) && ((header[1] & 0xf6) == 0xf0)) {
        *mimeType = MEDIA_MIMETYPE_AUDIO_AAC_ADTS;
        *confidence = 0.2;
        return true;
    }

    return false;
}

}  // namespace android
