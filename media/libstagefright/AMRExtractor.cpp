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
              size_t frameSize,
              bool isWide);

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
    size_t mFrameSize;
    bool mIsWide;

    off_t mOffset;
    int64_t mCurrentTimeUs;
    bool mStarted;
    MediaBufferGroup *mGroup;

    AMRSource(const AMRSource &);
    AMRSource &operator=(const AMRSource &);
};

////////////////////////////////////////////////////////////////////////////////

static size_t getFrameSize(bool isWide, unsigned FT) {
    static const size_t kFrameSizeNB[8] = {
        95, 103, 118, 134, 148, 159, 204, 244
    };
    static const size_t kFrameSizeWB[9] = {
        132, 177, 253, 285, 317, 365, 397, 461, 477
    };

    size_t frameSize = isWide ? kFrameSizeWB[FT] : kFrameSizeNB[FT];

    // Round up bits to bytes and add 1 for the header byte.
    frameSize = (frameSize + 7) / 8 + 1;

    return frameSize;
}

AMRExtractor::AMRExtractor(const sp<DataSource> &source)
    : mDataSource(source),
      mInitCheck(NO_INIT) {
    String8 mimeType;
    float confidence;
    if (!SniffAMR(mDataSource, &mimeType, &confidence)) {
        return;
    }

    mIsWide = (mimeType == MEDIA_MIMETYPE_AUDIO_AMR_WB);

    mMeta = new MetaData;
    mMeta->setCString(
            kKeyMIMEType, mIsWide ? MEDIA_MIMETYPE_AUDIO_AMR_WB
                                  : MEDIA_MIMETYPE_AUDIO_AMR_NB);

    mMeta->setInt32(kKeyChannelCount, 1);
    mMeta->setInt32(kKeySampleRate, mIsWide ? 16000 : 8000);

    size_t offset = mIsWide ? 9 : 6;
    uint8_t header;
    if (mDataSource->readAt(offset, &header, 1) != 1) {
        return;
    }

    unsigned FT = (header >> 3) & 0x0f;

    if (FT > 8 || (!mIsWide && FT > 7)) {
        return;
    }

    mFrameSize = getFrameSize(mIsWide, FT);

    off_t streamSize;
    if (mDataSource->getSize(&streamSize) == OK) {
        off_t numFrames = streamSize / mFrameSize;

        mMeta->setInt64(kKeyDuration, 20000ll * numFrames);
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

    return new AMRSource(mDataSource, mMeta, mFrameSize, mIsWide);
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
        size_t frameSize, bool isWide)
    : mDataSource(source),
      mMeta(meta),
      mFrameSize(frameSize),
      mIsWide(isWide),
      mOffset(mIsWide ? 9 : 6),
      mCurrentTimeUs(0),
      mStarted(false),
      mGroup(NULL) {
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
        int64_t seekFrame = seekTimeUs / 20000ll;  // 20ms per frame.
        mCurrentTimeUs = seekFrame * 20000ll;
        mOffset = seekFrame * mFrameSize + (mIsWide ? 9 : 6);
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

    if (FT > 8 || (!mIsWide && FT > 7)) {

        LOGE("illegal AMR frame type %d", FT);

        return ERROR_MALFORMED;
    }

    size_t frameSize = getFrameSize(mIsWide, FT);
    CHECK_EQ(frameSize, mFrameSize);

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
        const sp<DataSource> &source, String8 *mimeType, float *confidence) {
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
