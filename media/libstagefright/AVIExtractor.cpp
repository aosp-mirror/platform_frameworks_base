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
#define LOG_TAG "AVIExtractor"
#include <utils/Log.h>

#include "include/avc_utils.h"
#include "include/AVIExtractor.h"

#include <binder/ProcessState.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>

namespace android {

struct AVIExtractor::AVISource : public MediaSource {
    AVISource(const sp<AVIExtractor> &extractor, size_t trackIndex);

    virtual status_t start(MetaData *params);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

protected:
    virtual ~AVISource();

private:
    sp<AVIExtractor> mExtractor;
    size_t mTrackIndex;
    const AVIExtractor::Track &mTrack;
    MediaBufferGroup *mBufferGroup;
    size_t mSampleIndex;

    sp<MP3Splitter> mSplitter;

    DISALLOW_EVIL_CONSTRUCTORS(AVISource);
};

////////////////////////////////////////////////////////////////////////////////

struct AVIExtractor::MP3Splitter : public RefBase {
    MP3Splitter();

    void clear();
    void append(MediaBuffer *buffer);
    status_t read(MediaBuffer **buffer);

protected:
    virtual ~MP3Splitter();

private:
    bool mFindSync;
    int64_t mBaseTimeUs;
    int64_t mNumSamplesRead;
    sp<ABuffer> mBuffer;

    bool resync();

    DISALLOW_EVIL_CONSTRUCTORS(MP3Splitter);
};

////////////////////////////////////////////////////////////////////////////////

AVIExtractor::AVISource::AVISource(
        const sp<AVIExtractor> &extractor, size_t trackIndex)
    : mExtractor(extractor),
      mTrackIndex(trackIndex),
      mTrack(mExtractor->mTracks.itemAt(trackIndex)),
      mBufferGroup(NULL) {
}

AVIExtractor::AVISource::~AVISource() {
    if (mBufferGroup) {
        stop();
    }
}

status_t AVIExtractor::AVISource::start(MetaData *params) {
    CHECK(!mBufferGroup);

    mBufferGroup = new MediaBufferGroup;

    mBufferGroup->add_buffer(new MediaBuffer(mTrack.mMaxSampleSize));
    mBufferGroup->add_buffer(new MediaBuffer(mTrack.mMaxSampleSize));
    mSampleIndex = 0;

    const char *mime;
    CHECK(mTrack.mMeta->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_MPEG)) {
        mSplitter = new MP3Splitter;
    } else {
        mSplitter.clear();
    }

    return OK;
}

status_t AVIExtractor::AVISource::stop() {
    CHECK(mBufferGroup);

    delete mBufferGroup;
    mBufferGroup = NULL;

    mSplitter.clear();

    return OK;
}

sp<MetaData> AVIExtractor::AVISource::getFormat() {
    return mTrack.mMeta;
}

status_t AVIExtractor::AVISource::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    CHECK(mBufferGroup);

    *buffer = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode seekMode;
    if (options && options->getSeekTo(&seekTimeUs, &seekMode)) {
        status_t err =
            mExtractor->getSampleIndexAtTime(
                    mTrackIndex, seekTimeUs, seekMode, &mSampleIndex);

        if (err != OK) {
            return ERROR_END_OF_STREAM;
        }

        if (mSplitter != NULL) {
            mSplitter->clear();
        }
    }

    for (;;) {
        if (mSplitter != NULL) {
            status_t err = mSplitter->read(buffer);

            if (err == OK) {
                break;
            } else if (err != -EAGAIN) {
                return err;
            }
        }

        off64_t offset;
        size_t size;
        bool isKey;
        int64_t timeUs;
        status_t err = mExtractor->getSampleInfo(
                mTrackIndex, mSampleIndex, &offset, &size, &isKey, &timeUs);

        ++mSampleIndex;

        if (err != OK) {
            return ERROR_END_OF_STREAM;
        }

        MediaBuffer *out;
        CHECK_EQ(mBufferGroup->acquire_buffer(&out), (status_t)OK);

        ssize_t n = mExtractor->mDataSource->readAt(offset, out->data(), size);

        if (n < (ssize_t)size) {
            return n < 0 ? (status_t)n : (status_t)ERROR_MALFORMED;
        }

        out->set_range(0, size);

        out->meta_data()->setInt64(kKeyTime, timeUs);

        if (isKey) {
            out->meta_data()->setInt32(kKeyIsSyncFrame, 1);
        }

        if (mSplitter == NULL) {
            *buffer = out;
            break;
        }

        mSplitter->append(out);
        out->release();
        out = NULL;
    }

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

AVIExtractor::MP3Splitter::MP3Splitter()
    : mFindSync(true),
      mBaseTimeUs(-1ll),
      mNumSamplesRead(0) {
}

AVIExtractor::MP3Splitter::~MP3Splitter() {
}

void AVIExtractor::MP3Splitter::clear() {
    mFindSync = true;
    mBaseTimeUs = -1ll;
    mNumSamplesRead = 0;

    if (mBuffer != NULL) {
        mBuffer->setRange(0, 0);
    }
}

void AVIExtractor::MP3Splitter::append(MediaBuffer *buffer) {
    size_t prevCapacity = (mBuffer != NULL) ? mBuffer->capacity() : 0;

    if (mBaseTimeUs < 0) {
        CHECK(mBuffer == NULL || mBuffer->size() == 0);
        CHECK(buffer->meta_data()->findInt64(kKeyTime, &mBaseTimeUs));
        mNumSamplesRead = 0;
    }

    if (mBuffer != NULL && mBuffer->offset() > 0) {
        memmove(mBuffer->base(), mBuffer->data(), mBuffer->size());
        mBuffer->setRange(0, mBuffer->size());
    }

    if (mBuffer == NULL
            || mBuffer->size() + buffer->range_length() > prevCapacity) {
        size_t newCapacity =
            (prevCapacity + buffer->range_length() + 1023) & ~1023;

        sp<ABuffer> newBuffer = new ABuffer(newCapacity);
        if (mBuffer != NULL) {
            memcpy(newBuffer->data(), mBuffer->data(), mBuffer->size());
            newBuffer->setRange(0, mBuffer->size());
        } else {
            newBuffer->setRange(0, 0);
        }
        mBuffer = newBuffer;
    }

    memcpy(mBuffer->data() + mBuffer->size(),
           (const uint8_t *)buffer->data() + buffer->range_offset(),
           buffer->range_length());

    mBuffer->setRange(0, mBuffer->size() + buffer->range_length());
}

bool AVIExtractor::MP3Splitter::resync() {
    if (mBuffer == NULL) {
        return false;
    }

    bool foundSync = false;
    for (size_t offset = 0; offset + 3 < mBuffer->size(); ++offset) {
        uint32_t firstHeader = U32_AT(mBuffer->data() + offset);

        size_t frameSize;
        if (!GetMPEGAudioFrameSize(firstHeader, &frameSize)) {
            continue;
        }

        size_t subsequentOffset = offset + frameSize;
        size_t i = 3;
        while (i > 0) {
            if (subsequentOffset + 3 >= mBuffer->size()) {
                break;
            }

            static const uint32_t kMask = 0xfffe0c00;

            uint32_t header = U32_AT(mBuffer->data() + subsequentOffset);
            if ((header & kMask) != (firstHeader & kMask)) {
                break;
            }

            if (!GetMPEGAudioFrameSize(header, &frameSize)) {
                break;
            }

            subsequentOffset += frameSize;
            --i;
        }

        if (i == 0) {
            foundSync = true;
            memmove(mBuffer->data(),
                    mBuffer->data() + offset,
                    mBuffer->size() - offset);

            mBuffer->setRange(0, mBuffer->size() - offset);
            break;
        }
    }

    return foundSync;
}

status_t AVIExtractor::MP3Splitter::read(MediaBuffer **out) {
    *out = NULL;

    if (mFindSync) {
        if (!resync()) {
            return -EAGAIN;
        }

        mFindSync = false;
    }

    if (mBuffer->size() < 4) {
        return -EAGAIN;
    }

    uint32_t header = U32_AT(mBuffer->data());
    size_t frameSize;
    int sampleRate;
    int numSamples;
    if (!GetMPEGAudioFrameSize(
                header, &frameSize, &sampleRate, NULL, NULL, &numSamples)) {
        return ERROR_MALFORMED;
    }

    if (mBuffer->size() < frameSize) {
        return -EAGAIN;
    }

    MediaBuffer *mbuf = new MediaBuffer(frameSize);
    memcpy(mbuf->data(), mBuffer->data(), frameSize);

    int64_t timeUs = mBaseTimeUs + (mNumSamplesRead * 1000000ll) / sampleRate;
    mNumSamplesRead += numSamples;

    mbuf->meta_data()->setInt64(kKeyTime, timeUs);

    mBuffer->setRange(
            mBuffer->offset() + frameSize, mBuffer->size() - frameSize);

    *out = mbuf;

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

AVIExtractor::AVIExtractor(const sp<DataSource> &dataSource)
    : mDataSource(dataSource) {
    mInitCheck = parseHeaders();

    if (mInitCheck != OK) {
        mTracks.clear();
    }
}

AVIExtractor::~AVIExtractor() {
}

size_t AVIExtractor::countTracks() {
    return mTracks.size();
}

sp<MediaSource> AVIExtractor::getTrack(size_t index) {
    return index < mTracks.size() ? new AVISource(this, index) : NULL;
}

sp<MetaData> AVIExtractor::getTrackMetaData(
        size_t index, uint32_t flags) {
    return index < mTracks.size() ? mTracks.editItemAt(index).mMeta : NULL;
}

sp<MetaData> AVIExtractor::getMetaData() {
    sp<MetaData> meta = new MetaData;

    if (mInitCheck == OK) {
        meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_CONTAINER_AVI);
    }

    return meta;
}

status_t AVIExtractor::parseHeaders() {
    mTracks.clear();
    mMovieOffset = 0;
    mFoundIndex = false;
    mOffsetsAreAbsolute = false;

    ssize_t res = parseChunk(0ll, -1ll);

    if (res < 0) {
        return (status_t)res;
    }

    if (mMovieOffset == 0ll || !mFoundIndex) {
        return ERROR_MALFORMED;
    }

    return OK;
}

ssize_t AVIExtractor::parseChunk(off64_t offset, off64_t size, int depth) {
    if (size >= 0 && size < 8) {
        return ERROR_MALFORMED;
    }

    uint8_t tmp[12];
    ssize_t n = mDataSource->readAt(offset, tmp, 8);

    if (n < 8) {
        return (n < 0) ? n : (ssize_t)ERROR_MALFORMED;
    }

    uint32_t fourcc = U32_AT(tmp);
    uint32_t chunkSize = U32LE_AT(&tmp[4]);

    if (size >= 0 && chunkSize + 8 > size) {
        return ERROR_MALFORMED;
    }

    static const char kPrefix[] = "                              ";
    const char *prefix = &kPrefix[strlen(kPrefix) - 2 * depth];

    if (fourcc == FOURCC('L', 'I', 'S', 'T')
            || fourcc == FOURCC('R', 'I', 'F', 'F')) {
        // It's a list of chunks

        if (size >= 0 && size < 12) {
            return ERROR_MALFORMED;
        }

        n = mDataSource->readAt(offset + 8, &tmp[8], 4);

        if (n < 4) {
            return (n < 0) ? n : (ssize_t)ERROR_MALFORMED;
        }

        uint32_t subFourcc = U32_AT(&tmp[8]);

        ALOGV("%s offset 0x%08llx LIST of '%c%c%c%c', size %d",
             prefix,
             offset,
             (char)(subFourcc >> 24),
             (char)((subFourcc >> 16) & 0xff),
             (char)((subFourcc >> 8) & 0xff),
             (char)(subFourcc & 0xff),
             chunkSize - 4);

        if (subFourcc == FOURCC('m', 'o', 'v', 'i')) {
            // We're not going to parse this, but will take note of the
            // offset.

            mMovieOffset = offset;
        } else {
            off64_t subOffset = offset + 12;
            off64_t subOffsetLimit = subOffset + chunkSize - 4;
            while (subOffset < subOffsetLimit) {
                ssize_t res =
                    parseChunk(subOffset, subOffsetLimit - subOffset, depth + 1);

                if (res < 0) {
                    return res;
                }

                subOffset += res;
            }
        }
    } else {
        ALOGV("%s offset 0x%08llx CHUNK '%c%c%c%c'",
             prefix,
             offset,
             (char)(fourcc >> 24),
             (char)((fourcc >> 16) & 0xff),
             (char)((fourcc >> 8) & 0xff),
             (char)(fourcc & 0xff));

        status_t err = OK;

        switch (fourcc) {
            case FOURCC('s', 't', 'r', 'h'):
            {
                err = parseStreamHeader(offset + 8, chunkSize);
                break;
            }

            case FOURCC('s', 't', 'r', 'f'):
            {
                err = parseStreamFormat(offset + 8, chunkSize);
                break;
            }

            case FOURCC('i', 'd', 'x', '1'):
            {
                err = parseIndex(offset + 8, chunkSize);
                break;
            }

            default:
                break;
        }

        if (err != OK) {
            return err;
        }
    }

    if (chunkSize & 1) {
        ++chunkSize;
    }

    return chunkSize + 8;
}

static const char *GetMIMETypeForHandler(uint32_t handler) {
    switch (handler) {
        // Wow... shamelessly copied from
        // http://wiki.multimedia.cx/index.php?title=ISO_MPEG-4

        case FOURCC('3', 'I', 'V', '2'):
        case FOURCC('3', 'i', 'v', '2'):
        case FOURCC('B', 'L', 'Z', '0'):
        case FOURCC('D', 'I', 'G', 'I'):
        case FOURCC('D', 'I', 'V', '1'):
        case FOURCC('d', 'i', 'v', '1'):
        case FOURCC('D', 'I', 'V', 'X'):
        case FOURCC('d', 'i', 'v', 'x'):
        case FOURCC('D', 'X', '5', '0'):
        case FOURCC('d', 'x', '5', '0'):
        case FOURCC('D', 'X', 'G', 'M'):
        case FOURCC('E', 'M', '4', 'A'):
        case FOURCC('E', 'P', 'H', 'V'):
        case FOURCC('F', 'M', 'P', '4'):
        case FOURCC('f', 'm', 'p', '4'):
        case FOURCC('F', 'V', 'F', 'W'):
        case FOURCC('H', 'D', 'X', '4'):
        case FOURCC('h', 'd', 'x', '4'):
        case FOURCC('M', '4', 'C', 'C'):
        case FOURCC('M', '4', 'S', '2'):
        case FOURCC('m', '4', 's', '2'):
        case FOURCC('M', 'P', '4', 'S'):
        case FOURCC('m', 'p', '4', 's'):
        case FOURCC('M', 'P', '4', 'V'):
        case FOURCC('m', 'p', '4', 'v'):
        case FOURCC('M', 'V', 'X', 'M'):
        case FOURCC('R', 'M', 'P', '4'):
        case FOURCC('S', 'E', 'D', 'G'):
        case FOURCC('S', 'M', 'P', '4'):
        case FOURCC('U', 'M', 'P', '4'):
        case FOURCC('W', 'V', '1', 'F'):
        case FOURCC('X', 'V', 'I', 'D'):
        case FOURCC('X', 'v', 'i', 'D'):
        case FOURCC('x', 'v', 'i', 'd'):
        case FOURCC('X', 'V', 'I', 'X'):
            return MEDIA_MIMETYPE_VIDEO_MPEG4;

        // from http://wiki.multimedia.cx/index.php?title=H264
        case FOURCC('a', 'v', 'c', '1'):
        case FOURCC('d', 'a', 'v', 'c'):
        case FOURCC('x', '2', '6', '4'):
        case FOURCC('v', 's', 's', 'h'):
            return MEDIA_MIMETYPE_VIDEO_AVC;

        default:
            return NULL;
    }
}

status_t AVIExtractor::parseStreamHeader(off64_t offset, size_t size) {
    if (size != 56) {
        return ERROR_MALFORMED;
    }

    if (mTracks.size() > 99) {
        return -ERANGE;
    }

    sp<ABuffer> buffer = new ABuffer(size);
    ssize_t n = mDataSource->readAt(offset, buffer->data(), buffer->size());

    if (n < (ssize_t)size) {
        return n < 0 ? (status_t)n : ERROR_MALFORMED;
    }

    const uint8_t *data = buffer->data();

    uint32_t type = U32_AT(data);
    uint32_t handler = U32_AT(&data[4]);
    uint32_t flags = U32LE_AT(&data[8]);

    sp<MetaData> meta = new MetaData;

    uint32_t rate = U32LE_AT(&data[20]);
    uint32_t scale = U32LE_AT(&data[24]);

    uint32_t sampleSize = U32LE_AT(&data[44]);

    const char *mime = NULL;
    Track::Kind kind = Track::OTHER;

    if (type == FOURCC('v', 'i', 'd', 's')) {
        mime = GetMIMETypeForHandler(handler);

        if (mime && strncasecmp(mime, "video/", 6)) {
            return ERROR_MALFORMED;
        }

        if (mime == NULL) {
            ALOGW("Unsupported video format '%c%c%c%c'",
                 (char)(handler >> 24),
                 (char)((handler >> 16) & 0xff),
                 (char)((handler >> 8) & 0xff),
                 (char)(handler & 0xff));
        }

        kind = Track::VIDEO;
    } else if (type == FOURCC('a', 'u', 'd', 's')) {
        if (mime && strncasecmp(mime, "audio/", 6)) {
            return ERROR_MALFORMED;
        }

        kind = Track::AUDIO;
    }

    if (!mime) {
        mime = "application/octet-stream";
    }

    meta->setCString(kKeyMIMEType, mime);

    mTracks.push();
    Track *track = &mTracks.editItemAt(mTracks.size() - 1);

    track->mMeta = meta;
    track->mRate = rate;
    track->mScale = scale;
    track->mBytesPerSample = sampleSize;
    track->mKind = kind;
    track->mNumSyncSamples = 0;
    track->mThumbnailSampleSize = 0;
    track->mThumbnailSampleIndex = -1;
    track->mMaxSampleSize = 0;
    track->mAvgChunkSize = 1.0;
    track->mFirstChunkSize = 0;

    return OK;
}

status_t AVIExtractor::parseStreamFormat(off64_t offset, size_t size) {
    if (mTracks.isEmpty()) {
        return ERROR_MALFORMED;
    }

    Track *track = &mTracks.editItemAt(mTracks.size() - 1);

    if (track->mKind == Track::OTHER) {
        // We don't support this content, but that's not a parsing error.
        return OK;
    }

    bool isVideo = (track->mKind == Track::VIDEO);

    if ((isVideo && size < 40) || (!isVideo && size < 16)) {
        // Expected a BITMAPINFO or WAVEFORMAT(EX) structure, respectively.
        return ERROR_MALFORMED;
    }

    sp<ABuffer> buffer = new ABuffer(size);
    ssize_t n = mDataSource->readAt(offset, buffer->data(), buffer->size());

    if (n < (ssize_t)size) {
        return n < 0 ? (status_t)n : ERROR_MALFORMED;
    }

    const uint8_t *data = buffer->data();

    if (isVideo) {
        uint32_t width = U32LE_AT(&data[4]);
        uint32_t height = U32LE_AT(&data[8]);

        track->mMeta->setInt32(kKeyWidth, width);
        track->mMeta->setInt32(kKeyHeight, height);
    } else {
        uint32_t format = U16LE_AT(data);

        if (format == 0x55) {
            track->mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_MPEG);
        } else {
            ALOGW("Unsupported audio format = 0x%04x", format);
        }

        uint32_t numChannels = U16LE_AT(&data[2]);
        uint32_t sampleRate = U32LE_AT(&data[4]);

        track->mMeta->setInt32(kKeyChannelCount, numChannels);
        track->mMeta->setInt32(kKeySampleRate, sampleRate);
    }

    return OK;
}

// static
bool AVIExtractor::IsCorrectChunkType(
        ssize_t trackIndex, Track::Kind kind, uint32_t chunkType) {
    uint32_t chunkBase = chunkType & 0xffff;

    switch (kind) {
        case Track::VIDEO:
        {
            if (chunkBase != FOURCC(0, 0, 'd', 'c')
                    && chunkBase != FOURCC(0, 0, 'd', 'b')) {
                return false;
            }
            break;
        }

        case Track::AUDIO:
        {
            if (chunkBase != FOURCC(0, 0, 'w', 'b')) {
                return false;
            }
            break;
        }

        default:
            break;
    }

    if (trackIndex < 0) {
        return true;
    }

    uint8_t hi = chunkType >> 24;
    uint8_t lo = (chunkType >> 16) & 0xff;

    if (hi < '0' || hi > '9' || lo < '0' || lo > '9') {
        return false;
    }

    if (trackIndex != (10 * (hi - '0') + (lo - '0'))) {
        return false;
    }

    return true;
}

status_t AVIExtractor::parseIndex(off64_t offset, size_t size) {
    if ((size % 16) != 0) {
        return ERROR_MALFORMED;
    }

    sp<ABuffer> buffer = new ABuffer(size);
    ssize_t n = mDataSource->readAt(offset, buffer->data(), buffer->size());

    if (n < (ssize_t)size) {
        return n < 0 ? (status_t)n : ERROR_MALFORMED;
    }

    const uint8_t *data = buffer->data();

    while (size > 0) {
        uint32_t chunkType = U32_AT(data);

        uint8_t hi = chunkType >> 24;
        uint8_t lo = (chunkType >> 16) & 0xff;

        if (hi < '0' || hi > '9' || lo < '0' || lo > '9') {
            return ERROR_MALFORMED;
        }

        size_t trackIndex = 10 * (hi - '0') + (lo - '0');

        if (trackIndex >= mTracks.size()) {
            return ERROR_MALFORMED;
        }

        Track *track = &mTracks.editItemAt(trackIndex);

        if (!IsCorrectChunkType(-1, track->mKind, chunkType)) {
            return ERROR_MALFORMED;
        }

        if (track->mKind == Track::OTHER) {
            data += 16;
            size -= 16;
            continue;
        }

        uint32_t flags = U32LE_AT(&data[4]);
        uint32_t offset = U32LE_AT(&data[8]);
        uint32_t chunkSize = U32LE_AT(&data[12]);

        if (chunkSize > track->mMaxSampleSize) {
            track->mMaxSampleSize = chunkSize;
        }

        track->mSamples.push();

        SampleInfo *info =
            &track->mSamples.editItemAt(track->mSamples.size() - 1);

        info->mOffset = offset;
        info->mIsKey = (flags & 0x10) != 0;

        if (info->mIsKey) {
            static const size_t kMaxNumSyncSamplesToScan = 20;

            if (track->mNumSyncSamples < kMaxNumSyncSamplesToScan) {
                if (chunkSize > track->mThumbnailSampleSize) {
                    track->mThumbnailSampleSize = chunkSize;

                    track->mThumbnailSampleIndex =
                        track->mSamples.size() - 1;
                }
            }

            ++track->mNumSyncSamples;
        }

        data += 16;
        size -= 16;
    }

    if (!mTracks.isEmpty()) {
        off64_t offset;
        size_t size;
        bool isKey;
        int64_t timeUs;
        status_t err = getSampleInfo(0, 0, &offset, &size, &isKey, &timeUs);

        if (err != OK) {
            mOffsetsAreAbsolute = !mOffsetsAreAbsolute;
            err = getSampleInfo(0, 0, &offset, &size, &isKey, &timeUs);

            if (err != OK) {
                return err;
            }
        }

        ALOGV("Chunk offsets are %s",
             mOffsetsAreAbsolute ? "absolute" : "movie-chunk relative");
    }

    for (size_t i = 0; i < mTracks.size(); ++i) {
        Track *track = &mTracks.editItemAt(i);

        if (track->mBytesPerSample > 0) {
            // Assume all chunks are roughly the same size for now.

            // Compute the avg. size of the first 128 chunks (if there are
            // that many), but exclude the size of the first one, since
            // it may be an outlier.
            size_t numSamplesToAverage = track->mSamples.size();
            if (numSamplesToAverage > 256) {
                numSamplesToAverage = 256;
            }

            double avgChunkSize = 0;
            size_t j;
            for (j = 0; j <= numSamplesToAverage; ++j) {
                off64_t offset;
                size_t size;
                bool isKey;
                int64_t dummy;

                status_t err =
                    getSampleInfo(
                            i, j,
                            &offset, &size, &isKey, &dummy);

                if (err != OK) {
                    return err;
                }

                if (j == 0) {
                    track->mFirstChunkSize = size;
                    continue;
                }

                avgChunkSize += size;
            }

            avgChunkSize /= numSamplesToAverage;

            track->mAvgChunkSize = avgChunkSize;
        }

        int64_t durationUs;
        CHECK_EQ((status_t)OK,
                 getSampleTime(i, track->mSamples.size() - 1, &durationUs));

        ALOGV("track %d duration = %.2f secs", i, durationUs / 1E6);

        track->mMeta->setInt64(kKeyDuration, durationUs);
        track->mMeta->setInt32(kKeyMaxInputSize, track->mMaxSampleSize);

        const char *tmp;
        CHECK(track->mMeta->findCString(kKeyMIMEType, &tmp));

        AString mime = tmp;

        if (!strncasecmp("video/", mime.c_str(), 6)) {
            if (track->mThumbnailSampleIndex >= 0) {
                int64_t thumbnailTimeUs;
                CHECK_EQ((status_t)OK,
                         getSampleTime(i, track->mThumbnailSampleIndex,
                                       &thumbnailTimeUs));

                track->mMeta->setInt64(kKeyThumbnailTime, thumbnailTimeUs);
            }

            status_t err = OK;

            if (!strcasecmp(mime.c_str(), MEDIA_MIMETYPE_VIDEO_MPEG4)) {
                err = addMPEG4CodecSpecificData(i);
            } else if (!strcasecmp(mime.c_str(), MEDIA_MIMETYPE_VIDEO_AVC)) {
                err = addH264CodecSpecificData(i);
            }

            if (err != OK) {
                return err;
            }
        }
    }

    mFoundIndex = true;

    return OK;
}

static size_t GetSizeWidth(size_t x) {
    size_t n = 1;
    while (x > 127) {
        ++n;
        x >>= 7;
    }
    return n;
}

static uint8_t *EncodeSize(uint8_t *dst, size_t x) {
    while (x > 127) {
        *dst++ = (x & 0x7f) | 0x80;
        x >>= 7;
    }
    *dst++ = x;
    return dst;
}

sp<ABuffer> MakeMPEG4VideoCodecSpecificData(const sp<ABuffer> &config) {
    size_t len1 = config->size() + GetSizeWidth(config->size()) + 1;
    size_t len2 = len1 + GetSizeWidth(len1) + 1 + 13;
    size_t len3 = len2 + GetSizeWidth(len2) + 1 + 3;

    sp<ABuffer> csd = new ABuffer(len3);
    uint8_t *dst = csd->data();
    *dst++ = 0x03;
    dst = EncodeSize(dst, len2 + 3);
    *dst++ = 0x00;  // ES_ID
    *dst++ = 0x00;
    *dst++ = 0x00;  // streamDependenceFlag, URL_Flag, OCRstreamFlag

    *dst++ = 0x04;
    dst = EncodeSize(dst, len1 + 13);
    *dst++ = 0x01;  // Video ISO/IEC 14496-2 Simple Profile
    for (size_t i = 0; i < 12; ++i) {
        *dst++ = 0x00;
    }

    *dst++ = 0x05;
    dst = EncodeSize(dst, config->size());
    memcpy(dst, config->data(), config->size());
    dst += config->size();

    // hexdump(csd->data(), csd->size());

    return csd;
}

status_t AVIExtractor::addMPEG4CodecSpecificData(size_t trackIndex) {
    Track *track = &mTracks.editItemAt(trackIndex);

    off64_t offset;
    size_t size;
    bool isKey;
    int64_t timeUs;
    status_t err =
        getSampleInfo(trackIndex, 0, &offset, &size, &isKey, &timeUs);

    if (err != OK) {
        return err;
    }

    sp<ABuffer> buffer = new ABuffer(size);
    ssize_t n = mDataSource->readAt(offset, buffer->data(), buffer->size());

    if (n < (ssize_t)size) {
        return n < 0 ? (status_t)n : ERROR_MALFORMED;
    }

    // Extract everything up to the first VOP start code from the first
    // frame's encoded data and use it to construct an ESDS with the
    // codec specific data.

    size_t i = 0;
    bool found = false;
    while (i + 3 < buffer->size()) {
        if (!memcmp("\x00\x00\x01\xb6", &buffer->data()[i], 4)) {
            found = true;
            break;
        }

        ++i;
    }

    if (!found) {
        return ERROR_MALFORMED;
    }

    buffer->setRange(0, i);

    sp<ABuffer> csd = MakeMPEG4VideoCodecSpecificData(buffer);
    track->mMeta->setData(kKeyESDS, kTypeESDS, csd->data(), csd->size());

    return OK;
}

status_t AVIExtractor::addH264CodecSpecificData(size_t trackIndex) {
    Track *track = &mTracks.editItemAt(trackIndex);

    off64_t offset;
    size_t size;
    bool isKey;
    int64_t timeUs;

    // Extract codec specific data from the first non-empty sample.

    size_t sampleIndex = 0;
    for (;;) {
        status_t err =
            getSampleInfo(
                    trackIndex, sampleIndex, &offset, &size, &isKey, &timeUs);

        if (err != OK) {
            return err;
        }

        if (size > 0) {
            break;
        }

        ++sampleIndex;
    }

    sp<ABuffer> buffer = new ABuffer(size);
    ssize_t n = mDataSource->readAt(offset, buffer->data(), buffer->size());

    if (n < (ssize_t)size) {
        return n < 0 ? (status_t)n : ERROR_MALFORMED;
    }

    sp<MetaData> meta = MakeAVCCodecSpecificData(buffer);

    if (meta == NULL) {
        LOGE("Unable to extract AVC codec specific data");
        return ERROR_MALFORMED;
    }

    int32_t width, height;
    CHECK(meta->findInt32(kKeyWidth, &width));
    CHECK(meta->findInt32(kKeyHeight, &height));

    uint32_t type;
    const void *csd;
    size_t csdSize;
    CHECK(meta->findData(kKeyAVCC, &type, &csd, &csdSize));

    track->mMeta->setInt32(kKeyWidth, width);
    track->mMeta->setInt32(kKeyHeight, height);
    track->mMeta->setData(kKeyAVCC, type, csd, csdSize);

    return OK;
}

status_t AVIExtractor::getSampleInfo(
        size_t trackIndex, size_t sampleIndex,
        off64_t *offset, size_t *size, bool *isKey,
        int64_t *sampleTimeUs) {
    if (trackIndex >= mTracks.size()) {
        return -ERANGE;
    }

    const Track &track = mTracks.itemAt(trackIndex);

    if (sampleIndex >= track.mSamples.size()) {
        return -ERANGE;
    }

    const SampleInfo &info = track.mSamples.itemAt(sampleIndex);

    if (!mOffsetsAreAbsolute) {
        *offset = info.mOffset + mMovieOffset + 8;
    } else {
        *offset = info.mOffset;
    }

    *size = 0;

    uint8_t tmp[8];
    ssize_t n = mDataSource->readAt(*offset, tmp, 8);

    if (n < 8) {
        return n < 0 ? (status_t)n : (status_t)ERROR_MALFORMED;
    }

    uint32_t chunkType = U32_AT(tmp);

    if (!IsCorrectChunkType(trackIndex, track.mKind, chunkType)) {
        return ERROR_MALFORMED;
    }

    *offset += 8;
    *size = U32LE_AT(&tmp[4]);

    *isKey = info.mIsKey;

    if (track.mBytesPerSample > 0) {
        size_t sampleStartInBytes;
        if (sampleIndex == 0) {
            sampleStartInBytes = 0;
        } else {
            sampleStartInBytes =
                track.mFirstChunkSize + track.mAvgChunkSize * (sampleIndex - 1);
        }

        sampleIndex = sampleStartInBytes / track.mBytesPerSample;
    }

    *sampleTimeUs = (sampleIndex * 1000000ll * track.mRate) / track.mScale;

    return OK;
}

status_t AVIExtractor::getSampleTime(
        size_t trackIndex, size_t sampleIndex, int64_t *sampleTimeUs) {
    off64_t offset;
    size_t size;
    bool isKey;
    return getSampleInfo(
            trackIndex, sampleIndex, &offset, &size, &isKey, sampleTimeUs);
}

status_t AVIExtractor::getSampleIndexAtTime(
        size_t trackIndex,
        int64_t timeUs, MediaSource::ReadOptions::SeekMode mode,
        size_t *sampleIndex) const {
    if (trackIndex >= mTracks.size()) {
        return -ERANGE;
    }

    const Track &track = mTracks.itemAt(trackIndex);

    ssize_t closestSampleIndex;

    if (track.mBytesPerSample > 0) {
        size_t closestByteOffset =
            (timeUs * track.mBytesPerSample)
                / track.mRate * track.mScale / 1000000ll;

        if (closestByteOffset <= track.mFirstChunkSize) {
            closestSampleIndex = 0;
        } else {
            closestSampleIndex =
                (closestByteOffset - track.mFirstChunkSize)
                    / track.mAvgChunkSize;
        }
    } else {
        // Each chunk contains a single sample.
        closestSampleIndex = timeUs / track.mRate * track.mScale / 1000000ll;
    }

    ssize_t numSamples = track.mSamples.size();

    if (closestSampleIndex < 0) {
        closestSampleIndex = 0;
    } else if (closestSampleIndex >= numSamples) {
        closestSampleIndex = numSamples - 1;
    }

    if (mode == MediaSource::ReadOptions::SEEK_CLOSEST) {
        *sampleIndex = closestSampleIndex;

        return OK;
    }

    ssize_t prevSyncSampleIndex = closestSampleIndex;
    while (prevSyncSampleIndex >= 0) {
        const SampleInfo &info =
            track.mSamples.itemAt(prevSyncSampleIndex);

        if (info.mIsKey) {
            break;
        }

        --prevSyncSampleIndex;
    }

    ssize_t nextSyncSampleIndex = closestSampleIndex;
    while (nextSyncSampleIndex < numSamples) {
        const SampleInfo &info =
            track.mSamples.itemAt(nextSyncSampleIndex);

        if (info.mIsKey) {
            break;
        }

        ++nextSyncSampleIndex;
    }

    switch (mode) {
        case MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC:
        {
            *sampleIndex = prevSyncSampleIndex;

            return prevSyncSampleIndex >= 0 ? OK : UNKNOWN_ERROR;
        }

        case MediaSource::ReadOptions::SEEK_NEXT_SYNC:
        {
            *sampleIndex = nextSyncSampleIndex;

            return nextSyncSampleIndex < numSamples ? OK : UNKNOWN_ERROR;
        }

        case MediaSource::ReadOptions::SEEK_CLOSEST_SYNC:
        {
            if (prevSyncSampleIndex < 0 && nextSyncSampleIndex >= numSamples) {
                return UNKNOWN_ERROR;
            }

            if (prevSyncSampleIndex < 0) {
                *sampleIndex = nextSyncSampleIndex;
                return OK;
            }

            if (nextSyncSampleIndex >= numSamples) {
                *sampleIndex = prevSyncSampleIndex;
                return OK;
            }

            size_t dist1 = closestSampleIndex - prevSyncSampleIndex;
            size_t dist2 = nextSyncSampleIndex - closestSampleIndex;

            *sampleIndex =
                (dist1 < dist2) ? prevSyncSampleIndex : nextSyncSampleIndex;

            return OK;
        }

        default:
            TRESPASS();
            break;
    }
}

bool SniffAVI(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *) {
    char tmp[12];
    if (source->readAt(0, tmp, 12) < 12) {
        return false;
    }

    if (!memcmp(tmp, "RIFF", 4) && !memcmp(&tmp[8], "AVI ", 4)) {
        mimeType->setTo(MEDIA_MIMETYPE_CONTAINER_AVI);

        // Just a tad over the mp3 extractor's confidence, since
        // these .avi files may contain .mp3 content that otherwise would
        // mistakenly lead to us identifying the entire file as a .mp3 file.
        *confidence = 0.21;

        return true;
    }

    return false;
}

}  // namespace android
