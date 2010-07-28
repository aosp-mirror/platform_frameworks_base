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
#define LOG_TAG "MPEG4Writer"
#include <utils/Log.h>

#include <arpa/inet.h>

#include <ctype.h>
#include <pthread.h>

#include <media/stagefright/MPEG4Writer.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/Utils.h>
#include <media/mediarecorder.h>
#include <cutils/properties.h>

#include "include/ESDS.h"

namespace android {

class MPEG4Writer::Track {
public:
    Track(MPEG4Writer *owner, const sp<MediaSource> &source);

    ~Track();

    status_t start(MetaData *params);
    void stop();
    void pause();
    bool reachedEOS();

    int64_t getDurationUs() const;
    int64_t getEstimatedTrackSizeBytes() const;
    void writeTrackHeader(int32_t trackID, bool use32BitOffset = true);

private:
    MPEG4Writer *mOwner;
    sp<MetaData> mMeta;
    sp<MediaSource> mSource;
    volatile bool mDone;
    volatile bool mPaused;
    volatile bool mResumed;
    int64_t mMaxTimeStampUs;
    int64_t mEstimatedTrackSizeBytes;
    int32_t mTimeScale;

    pthread_t mThread;

    // mNumSamples is used to track how many samples in mSampleSizes List.
    // This is to reduce the cost associated with mSampleSizes.size() call,
    // since it is O(n). Ideally, the fix should be in List class.
    size_t              mNumSamples;
    List<size_t>        mSampleSizes;
    bool                mSamplesHaveSameSize;

    List<MediaBuffer *> mChunkSamples;
    List<off_t>         mChunkOffsets;

    struct StscTableEntry {

        StscTableEntry(uint32_t chunk, uint32_t samples, uint32_t id)
            : firstChunk(chunk),
              samplesPerChunk(samples),
              sampleDescriptionId(id) {}

        uint32_t firstChunk;
        uint32_t samplesPerChunk;
        uint32_t sampleDescriptionId;
    };
    List<StscTableEntry> mStscTableEntries;

    List<int32_t> mStssTableEntries;
    List<int64_t> mChunkDurations;

    struct SttsTableEntry {

        SttsTableEntry(uint32_t count, uint32_t durationUs)
            : sampleCount(count), sampleDurationUs(durationUs) {}

        uint32_t sampleCount;
        uint32_t sampleDurationUs;
    };
    List<SttsTableEntry> mSttsTableEntries;

    void *mCodecSpecificData;
    size_t mCodecSpecificDataSize;
    bool mGotAllCodecSpecificData;
    bool mTrackingProgressStatus;

    bool mReachedEOS;
    int64_t mStartTimestampUs;
    int64_t mPreviousTrackTimeUs;
    int64_t mTrackEveryTimeDurationUs;

    static void *ThreadWrapper(void *me);
    void threadEntry();

    status_t makeAVCCodecSpecificData(
            const uint8_t *data, size_t size);
    void writeOneChunk(bool isAvc);

    // Track authoring progress status
    void trackProgressStatus(int64_t timeUs, status_t err = OK);
    void initTrackingProgressStatus(MetaData *params);

    // Utilities for collecting statistical data
    void logStatisticalData(bool isAudio);
    void findMinAvgMaxSampleDurationMs(
            int32_t *min, int32_t *avg, int32_t *max);
    void findMinMaxChunkDurations(int64_t *min, int64_t *max);

    void getCodecSpecificDataFromInputFormatIfPossible();

    Track(const Track &);
    Track &operator=(const Track &);
};

#define USE_NALLEN_FOUR         1

MPEG4Writer::MPEG4Writer(const char *filename)
    : mFile(fopen(filename, "wb")),
      mUse32BitOffset(true),
      mPaused(false),
      mStarted(false),
      mOffset(0),
      mMdatOffset(0),
      mEstimatedMoovBoxSize(0),
      mInterleaveDurationUs(1000000) {
    CHECK(mFile != NULL);
}

MPEG4Writer::MPEG4Writer(int fd)
    : mFile(fdopen(fd, "wb")),
      mUse32BitOffset(true),
      mPaused(false),
      mStarted(false),
      mOffset(0),
      mMdatOffset(0),
      mEstimatedMoovBoxSize(0),
      mInterleaveDurationUs(1000000) {
    CHECK(mFile != NULL);
}

MPEG4Writer::~MPEG4Writer() {
    stop();

    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        delete *it;
    }
    mTracks.clear();
}

status_t MPEG4Writer::addSource(const sp<MediaSource> &source) {
    Track *track = new Track(this, source);
    mTracks.push_back(track);

    return OK;
}

status_t MPEG4Writer::startTracks(MetaData *params) {
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        status_t err = (*it)->start(params);

        if (err != OK) {
            for (List<Track *>::iterator it2 = mTracks.begin();
                 it2 != it; ++it2) {
                (*it2)->stop();
            }

            return err;
        }
    }
    return OK;
}

int64_t MPEG4Writer::estimateMoovBoxSize(int32_t bitRate) {
    // This implementation is highly experimental/heurisitic.
    //
    // Statistical analysis shows that metadata usually accounts
    // for a small portion of the total file size, usually < 0.6%.
    // Currently, lets set to 0.4% for now.

    // The default MIN_MOOV_BOX_SIZE is set to 0.4% x 1MB,
    // where 1MB is the common file size limit for MMS application.
    // The default MAX _MOOV_BOX_SIZE value is based on about 4
    // minute video recording with a bit rate about 3 Mbps, because
    // statistics also show that most of the video captured are going
    // to be less than 3 minutes.

    // If the estimation is wrong, we will pay the price of wasting
    // some reserved space. This should not happen so often statistically.
    static const int32_t factor = mUse32BitOffset? 1: 2;
    static const int64_t MIN_MOOV_BOX_SIZE = 4 * 1024;  // 4 KB
    static const int64_t MAX_MOOV_BOX_SIZE = (180 * 3000000 * 6LL / 8000);
    int64_t size = MIN_MOOV_BOX_SIZE;

    if (mMaxFileSizeLimitBytes != 0) {
        size = mMaxFileSizeLimitBytes * 4 / 1000;
    } else if (mMaxFileDurationLimitUs != 0) {
        if (bitRate <= 0) {
            // We could not estimate the file size since bitRate is not set.
            size = MIN_MOOV_BOX_SIZE;
        } else {
            size = ((mMaxFileDurationLimitUs * bitRate * 4) / 1000 / 8000000);
        }
    }
    if (size < MIN_MOOV_BOX_SIZE) {
        size = MIN_MOOV_BOX_SIZE;
    }

    // Any long duration recording will be probably end up with
    // non-streamable mp4 file.
    if (size > MAX_MOOV_BOX_SIZE) {
        size = MAX_MOOV_BOX_SIZE;
    }

    LOGI("limits: %lld/%lld bytes/us, bit rate: %d bps and the estimated"
         " moov size %lld bytes",
         mMaxFileSizeLimitBytes, mMaxFileDurationLimitUs, bitRate, size);
    return factor * size;
}

status_t MPEG4Writer::start(MetaData *param) {
    if (mFile == NULL) {
        return UNKNOWN_ERROR;
    }

    int32_t use64BitOffset;
    if (param &&
        param->findInt32(kKey64BitFileOffset, &use64BitOffset) &&
        use64BitOffset) {
        mUse32BitOffset = false;
    }

    // System property can overwrite the file offset bits parameter
    char value[PROPERTY_VALUE_MAX];
    if (property_get("media.stagefright.record-64bits", value, NULL)
        && (!strcmp(value, "1") || !strcasecmp(value, "true"))) {
        mUse32BitOffset = false;
    }

    mStartTimestampUs = -1;

    if (mStarted) {
        if (mPaused) {
            mPaused = false;
            return startTracks(param);
        }
        return OK;
    }

    if (!param ||
        !param->findInt32(kKeyTimeScale, &mTimeScale)) {
        mTimeScale = 1000;
    }
    CHECK(mTimeScale > 0);
    LOGV("movie time scale: %d", mTimeScale);

    mStreamableFile = true;
    mWriteMoovBoxToMemory = false;
    mMoovBoxBuffer = NULL;
    mMoovBoxBufferOffset = 0;

    beginBox("ftyp");
      {
        int32_t fileType;
        if (param && param->findInt32(kKeyFileType, &fileType) &&
            fileType != OUTPUT_FORMAT_MPEG_4) {
            writeFourcc("3gp4");
        } else {
            writeFourcc("isom");
        }
      }
      writeInt32(0);
      writeFourcc("isom");
      writeFourcc("3gp4");
    endBox();

    mFreeBoxOffset = mOffset;

    if (mEstimatedMoovBoxSize == 0) {
        int32_t bitRate = -1;
        if (param) {
            param->findInt32(kKeyBitRate, &bitRate);
        }
        mEstimatedMoovBoxSize = estimateMoovBoxSize(bitRate);
    }
    CHECK(mEstimatedMoovBoxSize >= 8);
    fseeko(mFile, mFreeBoxOffset, SEEK_SET);
    writeInt32(mEstimatedMoovBoxSize);
    write("free", 4);

    mMdatOffset = mFreeBoxOffset + mEstimatedMoovBoxSize;
    mOffset = mMdatOffset;
    fseeko(mFile, mMdatOffset, SEEK_SET);
    if (mUse32BitOffset) {
        write("????mdat", 8);
    } else {
        write("\x00\x00\x00\x01mdat????????", 16);
    }
    status_t err = startTracks(param);
    if (err != OK) {
        return err;
    }
    mStarted = true;
    return OK;
}

void MPEG4Writer::pause() {
    if (mFile == NULL) {
        return;
    }
    mPaused = true;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        (*it)->pause();
    }
}

void MPEG4Writer::stop() {
    if (mFile == NULL) {
        return;
    }

    int64_t maxDurationUs = 0;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        (*it)->stop();

        int64_t durationUs = (*it)->getDurationUs();
        if (durationUs > maxDurationUs) {
            maxDurationUs = durationUs;
        }
    }


    // Fix up the size of the 'mdat' chunk.
    if (mUse32BitOffset) {
        fseeko(mFile, mMdatOffset, SEEK_SET);
        int32_t size = htonl(static_cast<int32_t>(mOffset - mMdatOffset));
        fwrite(&size, 1, 4, mFile);
    } else {
        fseeko(mFile, mMdatOffset + 8, SEEK_SET);
        int64_t size = mOffset - mMdatOffset;
        size = hton64(size);
        fwrite(&size, 1, 8, mFile);
    }
    fseeko(mFile, mOffset, SEEK_SET);

    time_t now = time(NULL);
    const off_t moovOffset = mOffset;
    mWriteMoovBoxToMemory = true;
    mMoovBoxBuffer = (uint8_t *) malloc(mEstimatedMoovBoxSize);
    mMoovBoxBufferOffset = 0;
    CHECK(mMoovBoxBuffer != NULL);
    int32_t duration = (maxDurationUs * mTimeScale) / 1E6;

    beginBox("moov");

      beginBox("mvhd");
        writeInt32(0);             // version=0, flags=0
        writeInt32(now);           // creation time
        writeInt32(now);           // modification time
        writeInt32(mTimeScale);    // mvhd timescale
        writeInt32(duration);
        writeInt32(0x10000);       // rate: 1.0
        writeInt16(0x100);         // volume
        writeInt16(0);             // reserved
        writeInt32(0);             // reserved
        writeInt32(0);             // reserved
        writeInt32(0x10000);       // matrix
        writeInt32(0);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0x10000);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0x40000000);
        writeInt32(0);             // predefined
        writeInt32(0);             // predefined
        writeInt32(0);             // predefined
        writeInt32(0);             // predefined
        writeInt32(0);             // predefined
        writeInt32(0);             // predefined
        writeInt32(mTracks.size() + 1);  // nextTrackID
      endBox();  // mvhd

      int32_t id = 1;
      for (List<Track *>::iterator it = mTracks.begin();
           it != mTracks.end(); ++it, ++id) {
          (*it)->writeTrackHeader(id, mUse32BitOffset);
      }
    endBox();  // moov

    mWriteMoovBoxToMemory = false;
    if (mStreamableFile) {
        CHECK(mMoovBoxBufferOffset + 8 <= mEstimatedMoovBoxSize);

        // Moov box
        fseeko(mFile, mFreeBoxOffset, SEEK_SET);
        mOffset = mFreeBoxOffset;
        write(mMoovBoxBuffer, 1, mMoovBoxBufferOffset, mFile);

        // Free box
        fseeko(mFile, mOffset, SEEK_SET);
        writeInt32(mEstimatedMoovBoxSize - mMoovBoxBufferOffset);
        write("free", 4);

        // Free temp memory
        free(mMoovBoxBuffer);
        mMoovBoxBuffer = NULL;
        mMoovBoxBufferOffset = 0;
    } else {
        LOGI("The mp4 file will not be streamable.");
    }

    CHECK(mBoxes.empty());

    fflush(mFile);
    fclose(mFile);
    mFile = NULL;
    mStarted = false;
}

status_t MPEG4Writer::setInterleaveDuration(uint32_t durationUs) {
    mInterleaveDurationUs = durationUs;
    return OK;
}

void MPEG4Writer::lock() {
    mLock.lock();
}

void MPEG4Writer::unlock() {
    mLock.unlock();
}

off_t MPEG4Writer::addSample_l(MediaBuffer *buffer) {
    off_t old_offset = mOffset;

    fwrite((const uint8_t *)buffer->data() + buffer->range_offset(),
           1, buffer->range_length(), mFile);

    mOffset += buffer->range_length();

    return old_offset;
}

static void StripStartcode(MediaBuffer *buffer) {
    if (buffer->range_length() < 4) {
        return;
    }

    const uint8_t *ptr =
        (const uint8_t *)buffer->data() + buffer->range_offset();

    if (!memcmp(ptr, "\x00\x00\x00\x01", 4)) {
        buffer->set_range(
                buffer->range_offset() + 4, buffer->range_length() - 4);
    }
}

off_t MPEG4Writer::addLengthPrefixedSample_l(MediaBuffer *buffer) {
    off_t old_offset = mOffset;

    size_t length = buffer->range_length();

#if USE_NALLEN_FOUR
    uint8_t x = length >> 24;
    fwrite(&x, 1, 1, mFile);
    x = (length >> 16) & 0xff;
    fwrite(&x, 1, 1, mFile);
    x = (length >> 8) & 0xff;
    fwrite(&x, 1, 1, mFile);
    x = length & 0xff;
    fwrite(&x, 1, 1, mFile);
#else
    CHECK(length < 65536);

    uint8_t x = length >> 8;
    fwrite(&x, 1, 1, mFile);
    x = length & 0xff;
    fwrite(&x, 1, 1, mFile);
#endif

    fwrite((const uint8_t *)buffer->data() + buffer->range_offset(),
           1, length, mFile);

#if USE_NALLEN_FOUR
    mOffset += length + 4;
#else
    mOffset += length + 2;
#endif

    return old_offset;
}

size_t MPEG4Writer::write(
        const void *ptr, size_t size, size_t nmemb, FILE *stream) {

    const size_t bytes = size * nmemb;
    if (mWriteMoovBoxToMemory) {
        off_t moovBoxSize = 8 + mMoovBoxBufferOffset + bytes;
        if (moovBoxSize > mEstimatedMoovBoxSize) {
            for (List<off_t>::iterator it = mBoxes.begin();
                 it != mBoxes.end(); ++it) {
                (*it) += mOffset;
            }
            fseeko(mFile, mOffset, SEEK_SET);
            fwrite(mMoovBoxBuffer, 1, mMoovBoxBufferOffset, stream);
            fwrite(ptr, size, nmemb, stream);
            mOffset += (bytes + mMoovBoxBufferOffset);
            free(mMoovBoxBuffer);
            mMoovBoxBuffer = NULL;
            mMoovBoxBufferOffset = 0;
            mWriteMoovBoxToMemory = false;
            mStreamableFile = false;
        } else {
            memcpy(mMoovBoxBuffer + mMoovBoxBufferOffset, ptr, bytes);
            mMoovBoxBufferOffset += bytes;
        }
    } else {
        fwrite(ptr, size, nmemb, stream);
        mOffset += bytes;
    }
    return bytes;
}

void MPEG4Writer::beginBox(const char *fourcc) {
    CHECK_EQ(strlen(fourcc), 4);

    mBoxes.push_back(mWriteMoovBoxToMemory?
            mMoovBoxBufferOffset: mOffset);

    writeInt32(0);
    writeFourcc(fourcc);
}

void MPEG4Writer::endBox() {
    CHECK(!mBoxes.empty());

    off_t offset = *--mBoxes.end();
    mBoxes.erase(--mBoxes.end());

    if (mWriteMoovBoxToMemory) {
       int32_t x = htonl(mMoovBoxBufferOffset - offset);
       memcpy(mMoovBoxBuffer + offset, &x, 4);
    } else {
        fseeko(mFile, offset, SEEK_SET);
        writeInt32(mOffset - offset);
        mOffset -= 4;
        fseeko(mFile, mOffset, SEEK_SET);
    }
}

void MPEG4Writer::writeInt8(int8_t x) {
    write(&x, 1, 1, mFile);
}

void MPEG4Writer::writeInt16(int16_t x) {
    x = htons(x);
    write(&x, 1, 2, mFile);
}

void MPEG4Writer::writeInt32(int32_t x) {
    x = htonl(x);
    write(&x, 1, 4, mFile);
}

void MPEG4Writer::writeInt64(int64_t x) {
    x = hton64(x);
    write(&x, 1, 8, mFile);
}

void MPEG4Writer::writeCString(const char *s) {
    size_t n = strlen(s);
    write(s, 1, n + 1, mFile);
}

void MPEG4Writer::writeFourcc(const char *s) {
    CHECK_EQ(strlen(s), 4);
    write(s, 1, 4, mFile);
}

void MPEG4Writer::write(const void *data, size_t size) {
    write(data, 1, size, mFile);
}

bool MPEG4Writer::exceedsFileSizeLimit() {
    // No limit
    if (mMaxFileSizeLimitBytes == 0) {
        return false;
    }

    int64_t nTotalBytesEstimate = static_cast<int64_t>(mEstimatedMoovBoxSize);
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        nTotalBytesEstimate += (*it)->getEstimatedTrackSizeBytes();
    }
    return (nTotalBytesEstimate >= mMaxFileSizeLimitBytes);
}

bool MPEG4Writer::exceedsFileDurationLimit() {
    // No limit
    if (mMaxFileDurationLimitUs == 0) {
        return false;
    }

    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        if ((*it)->getDurationUs() >= mMaxFileDurationLimitUs) {
            return true;
        }
    }
    return false;
}

bool MPEG4Writer::reachedEOS() {
    bool allDone = true;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        if (!(*it)->reachedEOS()) {
            allDone = false;
            break;
        }
    }

    return allDone;
}

void MPEG4Writer::setStartTimestampUs(int64_t timeUs) {
    LOGI("setStartTimestampUs: %lld", timeUs);
    CHECK(timeUs >= 0);
    Mutex::Autolock autoLock(mLock);
    if (mStartTimestampUs < 0 || mStartTimestampUs > timeUs) {
        mStartTimestampUs = timeUs;
        LOGI("Earliest track starting time: %lld", mStartTimestampUs);
    }
}

int64_t MPEG4Writer::getStartTimestampUs() {
    Mutex::Autolock autoLock(mLock);
    return mStartTimestampUs;
}

size_t MPEG4Writer::numTracks() {
    Mutex::Autolock autolock(mLock);
    return mTracks.size();
}

////////////////////////////////////////////////////////////////////////////////

MPEG4Writer::Track::Track(
        MPEG4Writer *owner, const sp<MediaSource> &source)
    : mOwner(owner),
      mMeta(source->getFormat()),
      mSource(source),
      mDone(false),
      mPaused(false),
      mResumed(false),
      mMaxTimeStampUs(0),
      mEstimatedTrackSizeBytes(0),
      mSamplesHaveSameSize(true),
      mCodecSpecificData(NULL),
      mCodecSpecificDataSize(0),
      mGotAllCodecSpecificData(false),
      mReachedEOS(false) {
    getCodecSpecificDataFromInputFormatIfPossible();

    if (!mMeta->findInt32(kKeyTimeScale, &mTimeScale)) {
        mTimeScale = 1000;
    }
    CHECK(mTimeScale > 0);
}

void MPEG4Writer::Track::getCodecSpecificDataFromInputFormatIfPossible() {
    const char *mime;
    CHECK(mMeta->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
        uint32_t type;
        const void *data;
        size_t size;
        if (mMeta->findData(kKeyAVCC, &type, &data, &size)) {
            mCodecSpecificData = malloc(size);
            mCodecSpecificDataSize = size;
            memcpy(mCodecSpecificData, data, size);
            mGotAllCodecSpecificData = true;
        }
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4)
            || !strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC)) {
        uint32_t type;
        const void *data;
        size_t size;
        if (mMeta->findData(kKeyESDS, &type, &data, &size)) {
            ESDS esds(data, size);
            if (esds.getCodecSpecificInfo(&data, &size) == OK) {
                mCodecSpecificData = malloc(size);
                mCodecSpecificDataSize = size;
                memcpy(mCodecSpecificData, data, size);
                mGotAllCodecSpecificData = true;
            }
        }
    }
}

MPEG4Writer::Track::~Track() {
    stop();

    if (mCodecSpecificData != NULL) {
        free(mCodecSpecificData);
        mCodecSpecificData = NULL;
    }
}

void MPEG4Writer::Track::initTrackingProgressStatus(MetaData *params) {
    LOGV("initTrackingProgressStatus");
    mPreviousTrackTimeUs = -1;
    mTrackingProgressStatus = false;
    mTrackEveryTimeDurationUs = 0;
    {
        int64_t timeUs;
        if (params && params->findInt64(kKeyTrackTimeStatus, &timeUs)) {
            LOGV("Receive request to track progress status for every %lld us", timeUs);
            mTrackEveryTimeDurationUs = timeUs;
            mTrackingProgressStatus = true;
        }
    }
}

status_t MPEG4Writer::Track::start(MetaData *params) {
    if (!mDone && mPaused) {
        mPaused = false;
        mResumed = true;
        return OK;
    }

    int64_t startTimeUs;
    if (params == NULL || !params->findInt64(kKeyTime, &startTimeUs)) {
        startTimeUs = 0;
    }

    initTrackingProgressStatus(params);

    sp<MetaData> meta = new MetaData;
    meta->setInt64(kKeyTime, startTimeUs);
    status_t err = mSource->start(meta.get());
    if (err != OK) {
        mDone = mReachedEOS = true;
        return err;
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    mDone = false;
    mMaxTimeStampUs = 0;
    mReachedEOS = false;
    mEstimatedTrackSizeBytes = 0;

    pthread_create(&mThread, &attr, ThreadWrapper, this);
    pthread_attr_destroy(&attr);

    return OK;
}

void MPEG4Writer::Track::pause() {
    mPaused = true;
}

void MPEG4Writer::Track::stop() {
    if (mDone) {
        return;
    }

    mDone = true;

    void *dummy;
    pthread_join(mThread, &dummy);

    mSource->stop();
}

bool MPEG4Writer::Track::reachedEOS() {
    return mReachedEOS;
}

// static
void *MPEG4Writer::Track::ThreadWrapper(void *me) {
    Track *track = static_cast<Track *>(me);

    track->threadEntry();

    return NULL;
}

#include <ctype.h>
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


status_t MPEG4Writer::Track::makeAVCCodecSpecificData(
        const uint8_t *data, size_t size) {
    // hexdump(data, size);

    if (mCodecSpecificData != NULL) {
        LOGE("Already have codec specific data");
        return ERROR_MALFORMED;
    }

    if (size < 4 || memcmp("\x00\x00\x00\x01", data, 4)) {
        LOGE("Must start with a start code");
        return ERROR_MALFORMED;
    }

    size_t picParamOffset = 4;
    while (picParamOffset + 3 < size
            && memcmp("\x00\x00\x00\x01", &data[picParamOffset], 4)) {
        ++picParamOffset;
    }

    if (picParamOffset + 3 >= size) {
        LOGE("Could not find start-code for pictureParameterSet");
        return ERROR_MALFORMED;
    }

    size_t seqParamSetLength = picParamOffset - 4;
    size_t picParamSetLength = size - picParamOffset - 4;

    mCodecSpecificDataSize =
        6 + 1 + seqParamSetLength + 2 + picParamSetLength + 2;

    mCodecSpecificData = malloc(mCodecSpecificDataSize);
    uint8_t *header = (uint8_t *)mCodecSpecificData;
    header[0] = 1;
    header[1] = 0x42;  // profile
    header[2] = 0x80;
    header[3] = 0x1e;  // level

#if USE_NALLEN_FOUR
    header[4] = 0xfc | 3;  // length size == 4 bytes
#else
    header[4] = 0xfc | 1;  // length size == 2 bytes
#endif

    header[5] = 0xe0 | 1;
    header[6] = seqParamSetLength >> 8;
    header[7] = seqParamSetLength & 0xff;
    memcpy(&header[8], &data[4], seqParamSetLength);
    header += 8 + seqParamSetLength;
    header[0] = 1;
    header[1] = picParamSetLength >> 8;
    header[2] = picParamSetLength & 0xff;
    memcpy(&header[3], &data[picParamOffset + 4], picParamSetLength);

    return OK;
}

static bool collectStatisticalData() {
    char value[PROPERTY_VALUE_MAX];
    if (property_get("media.stagefright.record-stats", value, NULL)
        && (!strcmp(value, "1") || !strcasecmp(value, "true"))) {
        return true;
    }
    return false;
}

void MPEG4Writer::Track::threadEntry() {
    sp<MetaData> meta = mSource->getFormat();
    const char *mime;
    meta->findCString(kKeyMIMEType, &mime);
    bool is_mpeg4 = !strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4) ||
                    !strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC);
    bool is_avc = !strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC);
    bool is_audio = !strncasecmp(mime, "audio/", 6);
    int32_t count = 0;
    const int64_t interleaveDurationUs = mOwner->interleaveDuration();
    int64_t chunkTimestampUs = 0;
    int32_t nChunks = 0;
    int32_t nZeroLengthFrames = 0;
    int64_t lastTimestampUs = 0;  // Previous sample time stamp in ms
    int64_t lastDurationUs = 0;   // Between the previous two samples in ms
    int32_t sampleCount = 1;      // Sample count in the current stts table entry
    uint32_t previousSampleSize = 0;  // Size of the previous sample
    int64_t previousPausedDurationUs = 0;
    sp<MetaData> meta_data;
    bool collectStats = collectStatisticalData();

    mNumSamples = 0;
    status_t err = OK;
    MediaBuffer *buffer;
    while (!mDone && (err = mSource->read(&buffer)) == OK) {
        if (buffer->range_length() == 0) {
            buffer->release();
            buffer = NULL;
            ++nZeroLengthFrames;
            continue;
        }

        // If the codec specific data has not been received yet, delay pause.
        // After the codec specific data is received, discard what we received
        // when the track is to be paused.
        if (mPaused && !mResumed) {
            buffer->release();
            buffer = NULL;
            continue;
        }

        ++count;

        int32_t isCodecConfig;
        if (buffer->meta_data()->findInt32(kKeyIsCodecConfig, &isCodecConfig)
                && isCodecConfig) {
            CHECK(!mGotAllCodecSpecificData);

            if (is_avc) {
                status_t err = makeAVCCodecSpecificData(
                        (const uint8_t *)buffer->data()
                            + buffer->range_offset(),
                        buffer->range_length());
                CHECK_EQ(OK, err);
            } else if (is_mpeg4) {
                mCodecSpecificDataSize = buffer->range_length();
                mCodecSpecificData = malloc(mCodecSpecificDataSize);
                memcpy(mCodecSpecificData,
                        (const uint8_t *)buffer->data()
                            + buffer->range_offset(),
                       buffer->range_length());
            }

            buffer->release();
            buffer = NULL;

            mGotAllCodecSpecificData = true;
            continue;
        } else if (!mGotAllCodecSpecificData &&
                count == 1 && is_mpeg4 && mCodecSpecificData == NULL) {
            // The TI mpeg4 encoder does not properly set the
            // codec-specific-data flag.

            const uint8_t *data =
                (const uint8_t *)buffer->data() + buffer->range_offset();

            const size_t size = buffer->range_length();

            size_t offset = 0;
            while (offset + 3 < size) {
                if (data[offset] == 0x00 && data[offset + 1] == 0x00
                    && data[offset + 2] == 0x01 && data[offset + 3] == 0xb6) {
                    break;
                }

                ++offset;
            }

            // CHECK(offset + 3 < size);
            if (offset + 3 >= size) {
                // XXX assume the entire first chunk of data is the codec specific
                // data.
                offset = size;
            }

            mCodecSpecificDataSize = offset;
            mCodecSpecificData = malloc(offset);
            memcpy(mCodecSpecificData, data, offset);

            buffer->set_range(buffer->range_offset() + offset, size - offset);

            if (size == offset) {
                buffer->release();
                buffer = NULL;

                continue;
            }

            mGotAllCodecSpecificData = true;
        } else if (!mGotAllCodecSpecificData && is_avc && count < 3) {
            // The TI video encoder does not flag codec specific data
            // as such and also splits up SPS and PPS across two buffers.

            const uint8_t *data =
                (const uint8_t *)buffer->data() + buffer->range_offset();

            size_t size = buffer->range_length();

            CHECK(count == 2 || mCodecSpecificData == NULL);

            size_t offset = mCodecSpecificDataSize;
            mCodecSpecificDataSize += size + 4;
            mCodecSpecificData =
                realloc(mCodecSpecificData, mCodecSpecificDataSize);

            memcpy((uint8_t *)mCodecSpecificData + offset,
                   "\x00\x00\x00\x01", 4);

            memcpy((uint8_t *)mCodecSpecificData + offset + 4, data, size);

            buffer->release();
            buffer = NULL;

            if (count == 2) {
                void *tmp = mCodecSpecificData;
                size = mCodecSpecificDataSize;
                mCodecSpecificData = NULL;
                mCodecSpecificDataSize = 0;

                status_t err = makeAVCCodecSpecificData(
                        (const uint8_t *)tmp, size);
                free(tmp);
                tmp = NULL;
                CHECK_EQ(OK, err);

                mGotAllCodecSpecificData = true;
            }

            continue;
        }

        if (!mGotAllCodecSpecificData) {
            mGotAllCodecSpecificData = true;
        }

        // Make a deep copy of the MediaBuffer and Metadata and release
        // the original as soon as we can
        MediaBuffer *copy = new MediaBuffer(buffer->range_length());
        memcpy(copy->data(), (uint8_t *)buffer->data() + buffer->range_offset(),
                buffer->range_length());
        copy->set_range(0, buffer->range_length());
        meta_data = new MetaData(*buffer->meta_data().get());
        buffer->release();
        buffer = NULL;

        if (is_avc) StripStartcode(copy);

        size_t sampleSize;
        sampleSize = is_avc
#if USE_NALLEN_FOUR
                ? copy->range_length() + 4
#else
                ? copy->range_length() + 2
#endif
                : copy->range_length();

        // Max file size or duration handling
        mEstimatedTrackSizeBytes += sampleSize;
        if (mOwner->exceedsFileSizeLimit()) {
            mOwner->notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, 0);
            break;
        }
        if (mOwner->exceedsFileDurationLimit()) {
            mOwner->notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0);
            break;
        }


        int32_t isSync = false;
        meta_data->findInt32(kKeyIsSyncFrame, &isSync);

        int64_t timestampUs;
        CHECK(meta_data->findInt64(kKeyTime, &timestampUs));

////////////////////////////////////////////////////////////////////////////////
        if (mSampleSizes.empty()) {
            mStartTimestampUs = timestampUs;
            mOwner->setStartTimestampUs(mStartTimestampUs);
        }

        if (mResumed) {
            previousPausedDurationUs += (timestampUs - mMaxTimeStampUs - lastDurationUs);
            mResumed = false;
        }

        timestampUs -= previousPausedDurationUs;
        LOGV("time stamp: %lld and previous paused duration %lld",
                timestampUs, previousPausedDurationUs);
        if (timestampUs > mMaxTimeStampUs) {
            mMaxTimeStampUs = timestampUs;
        }

        mSampleSizes.push_back(sampleSize);
        ++mNumSamples;
        if (mNumSamples > 2) {
            if (lastDurationUs != timestampUs - lastTimestampUs) {
                SttsTableEntry sttsEntry(sampleCount, lastDurationUs);
                mSttsTableEntries.push_back(sttsEntry);
                sampleCount = 1;
            } else {
                ++sampleCount;
            }
        }
        if (mSamplesHaveSameSize) {
            if (mNumSamples >= 2 && previousSampleSize != sampleSize) {
                mSamplesHaveSameSize = false;
            }
            previousSampleSize = sampleSize;
        }
        lastDurationUs = timestampUs - lastTimestampUs;
        lastTimestampUs = timestampUs;

        if (isSync != 0) {
            mStssTableEntries.push_back(mNumSamples);
        }

        if (mTrackingProgressStatus) {
            if (mPreviousTrackTimeUs <= 0) {
                mPreviousTrackTimeUs = mStartTimestampUs;
            }
            trackProgressStatus(timestampUs);
        }
        if (mOwner->numTracks() == 1) {
            off_t offset = is_avc? mOwner->addLengthPrefixedSample_l(copy)
                                 : mOwner->addSample_l(copy);
            if (mChunkOffsets.empty()) {
                mChunkOffsets.push_back(offset);
            }
            copy->release();
            copy = NULL;
            continue;
        }

        mChunkSamples.push_back(copy);
        if (interleaveDurationUs == 0) {
            StscTableEntry stscEntry(++nChunks, 1, 1);
            mStscTableEntries.push_back(stscEntry);
            writeOneChunk(is_avc);
        } else {
            if (chunkTimestampUs == 0) {
                chunkTimestampUs = timestampUs;
            } else {
                if (timestampUs - chunkTimestampUs > interleaveDurationUs) {
                    ++nChunks;
                    if (collectStats) {
                        mChunkDurations.push_back(timestampUs - chunkTimestampUs);
                    }
                    if (nChunks == 1 ||  // First chunk
                        (--(mStscTableEntries.end()))->samplesPerChunk !=
                         mChunkSamples.size()) {
                        StscTableEntry stscEntry(nChunks,
                                mChunkSamples.size(), 1);
                        mStscTableEntries.push_back(stscEntry);
                    }
                    writeOneChunk(is_avc);
                    chunkTimestampUs = timestampUs;
                }
            }
        }

    }

    if (mSampleSizes.empty()) {
        err = UNKNOWN_ERROR;
    }
    mOwner->trackProgressStatus(this, -1, err);

    // Last chunk
    if (mOwner->numTracks() == 1) {
        StscTableEntry stscEntry(1, mNumSamples, 1);
        mStscTableEntries.push_back(stscEntry);
    } else if (!mChunkSamples.empty()) {
        ++nChunks;
        StscTableEntry stscEntry(nChunks, mChunkSamples.size(), 1);
        mStscTableEntries.push_back(stscEntry);
        writeOneChunk(is_avc);
    }

    // We don't really know how long the last frame lasts, since
    // there is no frame time after it, just repeat the previous
    // frame's duration.
    if (mNumSamples == 1) {
        lastDurationUs = 0;  // A single sample's duration
    } else {
        ++sampleCount;  // Count for the last sample
    }
    SttsTableEntry sttsEntry(sampleCount, lastDurationUs);
    mSttsTableEntries.push_back(sttsEntry);
    mReachedEOS = true;
    LOGI("Received total/0-length (%d/%d) buffers and encoded %d frames - %s",
            count, nZeroLengthFrames, mNumSamples, is_audio? "audio": "video");

    logStatisticalData(is_audio);
}

void MPEG4Writer::Track::trackProgressStatus(int64_t timeUs, status_t err) {
    LOGV("trackProgressStatus: %lld us", timeUs);
    if (mTrackEveryTimeDurationUs > 0 &&
        timeUs - mPreviousTrackTimeUs >= mTrackEveryTimeDurationUs) {
        LOGV("Fire time tracking progress status at %lld us", timeUs);
        mOwner->trackProgressStatus(this, timeUs - mPreviousTrackTimeUs, err);
        mPreviousTrackTimeUs = timeUs;
    }
}

void MPEG4Writer::trackProgressStatus(
        const MPEG4Writer::Track* track, int64_t timeUs, status_t err) {
    Mutex::Autolock lock(mLock);
    int32_t nTracks = mTracks.size();
    CHECK(nTracks >= 1);
    CHECK(nTracks < 64);  // Arbitrary number

    int32_t trackNum = 0;
#if 0
    // In the worst case, we can put the trackNum
    // along with MEDIA_RECORDER_INFO_COMPLETION_STATUS
    // to report the progress.
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it, ++trackNum) {
        if (track == (*it)) {
            break;
        }
    }
#endif
    CHECK(trackNum < nTracks);
    trackNum <<= 16;

    // Error notification
    // Do not consider ERROR_END_OF_STREAM an error
    if (err != OK && err != ERROR_END_OF_STREAM) {
        notify(MEDIA_RECORDER_EVENT_ERROR,
               trackNum | MEDIA_RECORDER_ERROR_UNKNOWN,
               err);
        return;
    }

    if (timeUs == -1) {
        // Send completion notification
        notify(MEDIA_RECORDER_EVENT_INFO,
               trackNum | MEDIA_RECORDER_INFO_COMPLETION_STATUS,
               err);
        return;
    } else {
        // Send progress status
        notify(MEDIA_RECORDER_EVENT_INFO,
               trackNum | MEDIA_RECORDER_INFO_PROGRESS_TIME_STATUS,
               timeUs / 1000);
    }
}

void MPEG4Writer::Track::findMinAvgMaxSampleDurationMs(
        int32_t *min, int32_t *avg, int32_t *max) {
    CHECK(!mSampleSizes.empty());
    int32_t avgSampleDurationMs = mMaxTimeStampUs / 1000 / mNumSamples;
    int32_t minSampleDurationMs = 0x7FFFFFFF;
    int32_t maxSampleDurationMs = 0;
    for (List<SttsTableEntry>::iterator it = mSttsTableEntries.begin();
        it != mSttsTableEntries.end(); ++it) {
        int32_t sampleDurationMs =
            (static_cast<int32_t>(it->sampleDurationUs) + 500) / 1000;
        if (sampleDurationMs > maxSampleDurationMs) {
            maxSampleDurationMs = sampleDurationMs;
        } else if (sampleDurationMs < minSampleDurationMs) {
            minSampleDurationMs = sampleDurationMs;
        }
        LOGI("sample duration: %d ms", sampleDurationMs);
    }
    CHECK(minSampleDurationMs != 0);
    CHECK(avgSampleDurationMs != 0);
    CHECK(maxSampleDurationMs != 0);
    *min = minSampleDurationMs;
    *avg = avgSampleDurationMs;
    *max = maxSampleDurationMs;
}

// Don't count the last duration
void MPEG4Writer::Track::findMinMaxChunkDurations(int64_t *min, int64_t *max) {
    int64_t duration = mOwner->interleaveDuration();
    int64_t minChunkDuration = duration;
    int64_t maxChunkDuration = duration;
    if (mChunkDurations.size() > 1) {
        for (List<int64_t>::iterator it = mChunkDurations.begin();
            it != --mChunkDurations.end(); ++it) {
            if (minChunkDuration > (*it)) {
                minChunkDuration = (*it);
            } else if (maxChunkDuration < (*it)) {
                maxChunkDuration = (*it);
            }
        }
    }
    *min = minChunkDuration;
    *max = maxChunkDuration;
}

void MPEG4Writer::Track::logStatisticalData(bool isAudio) {
    if (mMaxTimeStampUs <= 0 || mSampleSizes.empty()) {
        LOGI("nothing is recorded");
        return;
    }

    bool collectStats = collectStatisticalData();

    if (collectStats) {
        LOGI("%s track - duration %lld us, total %d frames",
                isAudio? "audio": "video", mMaxTimeStampUs,
                mNumSamples);
        int32_t min, avg, max;
        findMinAvgMaxSampleDurationMs(&min, &avg, &max);
        LOGI("min/avg/max sample duration (ms): %d/%d/%d", min, avg, max);
        if (!isAudio) {
            float avgFps = 1000.0 / avg;
            float minFps = 1000.0 / max;
            float maxFps = 1000.0 / min;
            LOGI("min/avg/max frame rate (fps): %.2f/%.2f/%.2f",
                minFps, avgFps, maxFps);
        }

        int64_t totalBytes = 0;
        for (List<size_t>::iterator it = mSampleSizes.begin();
            it != mSampleSizes.end(); ++it) {
            totalBytes += (*it);
        }
        float bitRate = (totalBytes * 8000000.0) / mMaxTimeStampUs;
        LOGI("avg bit rate (bps): %.2f", bitRate);

        int64_t duration = mOwner->interleaveDuration();
        if (duration != 0) {  // If interleaving is enabled
            int64_t minChunk, maxChunk;
            findMinMaxChunkDurations(&minChunk, &maxChunk);
            LOGI("min/avg/max chunk duration (ms): %lld/%lld/%lld",
                minChunk, duration, maxChunk);
        }
    }
}

void MPEG4Writer::Track::writeOneChunk(bool isAvc) {
    mOwner->lock();
    for (List<MediaBuffer *>::iterator it = mChunkSamples.begin();
         it != mChunkSamples.end(); ++it) {
        off_t offset = isAvc? mOwner->addLengthPrefixedSample_l(*it)
                            : mOwner->addSample_l(*it);
        if (it == mChunkSamples.begin()) {
            mChunkOffsets.push_back(offset);
        }
    }
    mOwner->unlock();
    while (!mChunkSamples.empty()) {
        List<MediaBuffer *>::iterator it = mChunkSamples.begin();
        (*it)->release();
        (*it) = NULL;
        mChunkSamples.erase(it);
    }
    mChunkSamples.clear();
}

int64_t MPEG4Writer::Track::getDurationUs() const {
    return mMaxTimeStampUs;
}

int64_t MPEG4Writer::Track::getEstimatedTrackSizeBytes() const {
    return mEstimatedTrackSizeBytes;
}

void MPEG4Writer::Track::writeTrackHeader(
        int32_t trackID, bool use32BitOffset) {
    const char *mime;
    bool success = mMeta->findCString(kKeyMIMEType, &mime);
    CHECK(success);

    bool is_audio = !strncasecmp(mime, "audio/", 6);
    LOGV("%s track time scale: %d",
        is_audio? "Audio": "Video", mTimeScale);


    time_t now = time(NULL);
    int32_t mvhdTimeScale = mOwner->getTimeScale();
    int64_t trakDurationUs = getDurationUs();

    mOwner->beginBox("trak");

      mOwner->beginBox("tkhd");
        // Flags = 7 to indicate that the track is enabled, and
        // part of the presentation
        mOwner->writeInt32(0x07);          // version=0, flags=7
        mOwner->writeInt32(now);           // creation time
        mOwner->writeInt32(now);           // modification time
        mOwner->writeInt32(trackID);
        mOwner->writeInt32(0);             // reserved
        int32_t tkhdDuration =
            (trakDurationUs * mvhdTimeScale + 5E5) / 1E6;
        mOwner->writeInt32(tkhdDuration);  // in mvhd timescale
        mOwner->writeInt32(0);             // reserved
        mOwner->writeInt32(0);             // reserved
        mOwner->writeInt16(0);             // layer
        mOwner->writeInt16(0);             // alternate group
        mOwner->writeInt16(is_audio ? 0x100 : 0);  // volume
        mOwner->writeInt16(0);             // reserved

        mOwner->writeInt32(0x10000);       // matrix
        mOwner->writeInt32(0);
        mOwner->writeInt32(0);
        mOwner->writeInt32(0);
        mOwner->writeInt32(0x10000);
        mOwner->writeInt32(0);
        mOwner->writeInt32(0);
        mOwner->writeInt32(0);
        mOwner->writeInt32(0x40000000);

        if (is_audio) {
            mOwner->writeInt32(0);
            mOwner->writeInt32(0);
        } else {
            int32_t width, height;
            bool success = mMeta->findInt32(kKeyWidth, &width);
            success = success && mMeta->findInt32(kKeyHeight, &height);
            CHECK(success);

            mOwner->writeInt32(width << 16);   // 32-bit fixed-point value
            mOwner->writeInt32(height << 16);  // 32-bit fixed-point value
        }
      mOwner->endBox();  // tkhd

      int64_t moovStartTimeUs = mOwner->getStartTimestampUs();
      if (mStartTimestampUs != moovStartTimeUs) {
        mOwner->beginBox("edts");
          mOwner->beginBox("elst");
            mOwner->writeInt32(0);           // version=0, flags=0: 32-bit time
            mOwner->writeInt32(2);           // never ends with an empty list

            // First elst entry: specify the starting time offset
            int64_t offsetUs = mStartTimestampUs - moovStartTimeUs;
            int32_t seg = (offsetUs * mvhdTimeScale + 5E5) / 1E6;
            mOwner->writeInt32(seg);         // in mvhd timecale
            mOwner->writeInt32(-1);          // starting time offset
            mOwner->writeInt32(1 << 16);     // rate = 1.0

            // Second elst entry: specify the track duration
            seg = (trakDurationUs * mvhdTimeScale + 5E5) / 1E6;
            mOwner->writeInt32(seg);         // in mvhd timescale
            mOwner->writeInt32(0);
            mOwner->writeInt32(1 << 16);
          mOwner->endBox();
        mOwner->endBox();
      }

      mOwner->beginBox("mdia");

        mOwner->beginBox("mdhd");
          mOwner->writeInt32(0);             // version=0, flags=0
          mOwner->writeInt32(now);           // creation time
          mOwner->writeInt32(now);           // modification time
          mOwner->writeInt32(mTimeScale);    // media timescale
          int32_t mdhdDuration = (trakDurationUs * mTimeScale + 5E5) / 1E6;
          mOwner->writeInt32(mdhdDuration);  // use media timescale
          // Language follows the three letter standard ISO-639-2/T
          // 'e', 'n', 'g' for "English", for instance.
          // Each character is packed as the difference between its ASCII value and 0x60.
          // For "English", these are 00101, 01110, 00111.
          // XXX: Where is the padding bit located: 0x15C7?
          mOwner->writeInt16(0);             // language code
          mOwner->writeInt16(0);             // predefined
        mOwner->endBox();

        mOwner->beginBox("hdlr");
          mOwner->writeInt32(0);             // version=0, flags=0
          mOwner->writeInt32(0);             // component type: should be mhlr
          mOwner->writeFourcc(is_audio ? "soun" : "vide");  // component subtype
          mOwner->writeInt32(0);             // reserved
          mOwner->writeInt32(0);             // reserved
          mOwner->writeInt32(0);             // reserved
          // Removing "r" for the name string just makes the string 4 byte aligned
          mOwner->writeCString(is_audio ? "SoundHandle": "VideoHandle");  // name
        mOwner->endBox();

        mOwner->beginBox("minf");
          if (is_audio) {
              mOwner->beginBox("smhd");
              mOwner->writeInt32(0);           // version=0, flags=0
              mOwner->writeInt16(0);           // balance
              mOwner->writeInt16(0);           // reserved
              mOwner->endBox();
          } else {
              mOwner->beginBox("vmhd");
              mOwner->writeInt32(0x01);        // version=0, flags=1
              mOwner->writeInt16(0);           // graphics mode
              mOwner->writeInt16(0);           // opcolor
              mOwner->writeInt16(0);
              mOwner->writeInt16(0);
              mOwner->endBox();
          }

          mOwner->beginBox("dinf");
            mOwner->beginBox("dref");
              mOwner->writeInt32(0);  // version=0, flags=0
              mOwner->writeInt32(1);  // entry count (either url or urn)
              // The table index here refers to the sample description index
              // in the sample table entries.
              mOwner->beginBox("url ");
                mOwner->writeInt32(1);  // version=0, flags=1 (self-contained)
              mOwner->endBox();  // url
            mOwner->endBox();  // dref
          mOwner->endBox();  // dinf

        mOwner->beginBox("stbl");

          mOwner->beginBox("stsd");
            mOwner->writeInt32(0);               // version=0, flags=0
            mOwner->writeInt32(1);               // entry count
            if (is_audio) {
                const char *fourcc = NULL;
                if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_NB, mime)) {
                    fourcc = "samr";
                } else if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_WB, mime)) {
                    fourcc = "sawb";
                } else if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AAC, mime)) {
                    fourcc = "mp4a";
                } else {
                    LOGE("Unknown mime type '%s'.", mime);
                    CHECK(!"should not be here, unknown mime type.");
                }

                mOwner->beginBox(fourcc);          // audio format
                  mOwner->writeInt32(0);           // reserved
                  mOwner->writeInt16(0);           // reserved
                  mOwner->writeInt16(0x1);         // data ref index
                  mOwner->writeInt32(0);           // reserved
                  mOwner->writeInt32(0);           // reserved
                  int32_t nChannels;
                  CHECK_EQ(true, mMeta->findInt32(kKeyChannelCount, &nChannels));
                  mOwner->writeInt16(nChannels);   // channel count
                  mOwner->writeInt16(16);          // sample size
                  mOwner->writeInt16(0);           // predefined
                  mOwner->writeInt16(0);           // reserved

                  int32_t samplerate;
                  bool success = mMeta->findInt32(kKeySampleRate, &samplerate);
                  CHECK(success);

                  mOwner->writeInt32(samplerate << 16);
                  if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AAC, mime)) {
                    mOwner->beginBox("esds");

                        mOwner->writeInt32(0);     // version=0, flags=0
                        mOwner->writeInt8(0x03);   // ES_DescrTag
                        mOwner->writeInt8(23 + mCodecSpecificDataSize);
                        mOwner->writeInt16(0x0000);// ES_ID
                        mOwner->writeInt8(0x00);

                        mOwner->writeInt8(0x04);   // DecoderConfigDescrTag
                        mOwner->writeInt8(15 + mCodecSpecificDataSize);
                        mOwner->writeInt8(0x40);   // objectTypeIndication ISO/IEC 14492-2
                        mOwner->writeInt8(0x15);   // streamType AudioStream

                        mOwner->writeInt16(0x03);  // XXX
                        mOwner->writeInt8(0x00);   // buffer size 24-bit
                        mOwner->writeInt32(96000); // max bit rate
                        mOwner->writeInt32(96000); // avg bit rate

                        mOwner->writeInt8(0x05);   // DecoderSpecificInfoTag
                        mOwner->writeInt8(mCodecSpecificDataSize);
                        mOwner->write(mCodecSpecificData, mCodecSpecificDataSize);

                        static const uint8_t kData2[] = {
                            0x06,  // SLConfigDescriptorTag
                            0x01,
                            0x02
                        };
                        mOwner->write(kData2, sizeof(kData2));

                    mOwner->endBox();  // esds
                  }
                mOwner->endBox();
            } else {
                if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) {
                    mOwner->beginBox("mp4v");
                } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime)) {
                    mOwner->beginBox("s263");
                } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime)) {
                    mOwner->beginBox("avc1");
                } else {
                    LOGE("Unknown mime type '%s'.", mime);
                    CHECK(!"should not be here, unknown mime type.");
                }

                  mOwner->writeInt32(0);           // reserved
                  mOwner->writeInt16(0);           // reserved
                  mOwner->writeInt16(1);           // data ref index
                  mOwner->writeInt16(0);           // predefined
                  mOwner->writeInt16(0);           // reserved
                  mOwner->writeInt32(0);           // predefined
                  mOwner->writeInt32(0);           // predefined
                  mOwner->writeInt32(0);           // predefined

                  int32_t width, height;
                  bool success = mMeta->findInt32(kKeyWidth, &width);
                  success = success && mMeta->findInt32(kKeyHeight, &height);
                  CHECK(success);

                  mOwner->writeInt16(width);
                  mOwner->writeInt16(height);
                  mOwner->writeInt32(0x480000);    // horiz resolution
                  mOwner->writeInt32(0x480000);    // vert resolution
                  mOwner->writeInt32(0);           // reserved
                  mOwner->writeInt16(1);           // frame count
                  mOwner->write("                                ", 32);
                  mOwner->writeInt16(0x18);        // depth
                  mOwner->writeInt16(-1);          // predefined

                  CHECK(23 + mCodecSpecificDataSize < 128);

                  if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) {
                      mOwner->beginBox("esds");

                        mOwner->writeInt32(0);           // version=0, flags=0

                        mOwner->writeInt8(0x03);  // ES_DescrTag
                        mOwner->writeInt8(23 + mCodecSpecificDataSize);
                        mOwner->writeInt16(0x0000);  // ES_ID
                        mOwner->writeInt8(0x1f);

                        mOwner->writeInt8(0x04);  // DecoderConfigDescrTag
                        mOwner->writeInt8(15 + mCodecSpecificDataSize);
                        mOwner->writeInt8(0x20);  // objectTypeIndication ISO/IEC 14492-2
                        mOwner->writeInt8(0x11);  // streamType VisualStream

                        static const uint8_t kData[] = {
                            0x01, 0x77, 0x00,
                            0x00, 0x03, 0xe8, 0x00,
                            0x00, 0x03, 0xe8, 0x00
                        };
                        mOwner->write(kData, sizeof(kData));

                        mOwner->writeInt8(0x05);  // DecoderSpecificInfoTag

                        mOwner->writeInt8(mCodecSpecificDataSize);
                        mOwner->write(mCodecSpecificData, mCodecSpecificDataSize);

                        static const uint8_t kData2[] = {
                            0x06,  // SLConfigDescriptorTag
                            0x01,
                            0x02
                        };
                        mOwner->write(kData2, sizeof(kData2));

                      mOwner->endBox();  // esds
                  } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime)) {
                      mOwner->beginBox("d263");

                          mOwner->writeInt32(0);  // vendor
                          mOwner->writeInt8(0);   // decoder version
                          mOwner->writeInt8(10);  // level: 10
                          mOwner->writeInt8(0);   // profile: 0

                      mOwner->endBox();  // d263
                  } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime)) {
                      mOwner->beginBox("avcC");
                        mOwner->write(mCodecSpecificData, mCodecSpecificDataSize);
                      mOwner->endBox();  // avcC
                  }

                  mOwner->beginBox("pasp");
                    // This is useful if the pixel is not square
                    mOwner->writeInt32(1 << 16);  // hspacing
                    mOwner->writeInt32(1 << 16);  // vspacing
                  mOwner->endBox();  // pasp
                mOwner->endBox();  // mp4v, s263 or avc1
            }
          mOwner->endBox();  // stsd

          mOwner->beginBox("stts");
            mOwner->writeInt32(0);  // version=0, flags=0
            mOwner->writeInt32(mSttsTableEntries.size());
            for (List<SttsTableEntry>::iterator it = mSttsTableEntries.begin();
                 it != mSttsTableEntries.end(); ++it) {
                mOwner->writeInt32(it->sampleCount);
                int32_t dur = (it->sampleDurationUs * mTimeScale + 5E5) / 1E6;
                mOwner->writeInt32(dur);
            }
          mOwner->endBox();  // stts

          if (!is_audio) {
            mOwner->beginBox("stss");
              mOwner->writeInt32(0);  // version=0, flags=0
              mOwner->writeInt32(mStssTableEntries.size());  // number of sync frames
              for (List<int32_t>::iterator it = mStssTableEntries.begin();
                   it != mStssTableEntries.end(); ++it) {
                  mOwner->writeInt32(*it);
              }
            mOwner->endBox();  // stss
          }

          mOwner->beginBox("stsz");
            mOwner->writeInt32(0);  // version=0, flags=0
            if (mSamplesHaveSameSize) {
                List<size_t>::iterator it = mSampleSizes.begin();
                mOwner->writeInt32(*it);  // default sample size
            } else {
                mOwner->writeInt32(0);
            }
            mOwner->writeInt32(mNumSamples);
            if (!mSamplesHaveSameSize) {
                for (List<size_t>::iterator it = mSampleSizes.begin();
                     it != mSampleSizes.end(); ++it) {
                    mOwner->writeInt32(*it);
                }
            }
          mOwner->endBox();  // stsz

          mOwner->beginBox("stsc");
            mOwner->writeInt32(0);  // version=0, flags=0
            mOwner->writeInt32(mStscTableEntries.size());
            for (List<StscTableEntry>::iterator it = mStscTableEntries.begin();
                 it != mStscTableEntries.end(); ++it) {
                mOwner->writeInt32(it->firstChunk);
                mOwner->writeInt32(it->samplesPerChunk);
                mOwner->writeInt32(it->sampleDescriptionId);
            }
          mOwner->endBox();  // stsc
          mOwner->beginBox(use32BitOffset? "stco": "co64");
            mOwner->writeInt32(0);  // version=0, flags=0
            mOwner->writeInt32(mChunkOffsets.size());
            for (List<off_t>::iterator it = mChunkOffsets.begin();
                 it != mChunkOffsets.end(); ++it) {
                if (use32BitOffset) {
                    mOwner->writeInt32(static_cast<int32_t>(*it));
                } else {
                    mOwner->writeInt64((*it));
                }
            }
          mOwner->endBox();  // stco or co64

        mOwner->endBox();  // stbl
       mOwner->endBox();  // minf
      mOwner->endBox();  // mdia
    mOwner->endBox();  // trak
}

}  // namespace android
