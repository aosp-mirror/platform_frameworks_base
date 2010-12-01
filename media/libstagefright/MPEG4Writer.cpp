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

#include <pthread.h>
#include <sys/prctl.h>
#include <sys/resource.h>

#include <media/stagefright/MPEG4Writer.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/Utils.h>
#include <media/mediarecorder.h>

#include "include/ESDS.h"

namespace android {

static const int64_t kMax32BitFileSize = 0x007fffffffLL;
static const uint8_t kNalUnitTypeSeqParamSet = 0x07;
static const uint8_t kNalUnitTypePicParamSet = 0x08;

// Using longer adjustment period to suppress fluctuations in
// the audio encoding paths
static const int64_t kVideoMediaTimeAdjustPeriodTimeUs = 600000000LL;  // 10 minutes

class MPEG4Writer::Track {
public:
    Track(MPEG4Writer *owner, const sp<MediaSource> &source);

    ~Track();

    status_t start(MetaData *params);
    status_t stop();
    status_t pause();
    bool reachedEOS();

    int64_t getDurationUs() const;
    int64_t getEstimatedTrackSizeBytes() const;
    void writeTrackHeader(int32_t trackID, bool use32BitOffset = true);
    void bufferChunk(int64_t timestampUs);
    bool isAvc() const { return mIsAvc; }
    bool isAudio() const { return mIsAudio; }
    bool isMPEG4() const { return mIsMPEG4; }
    void addChunkOffset(off_t offset);
    status_t dump(int fd, const Vector<String16>& args) const;

private:
    MPEG4Writer *mOwner;
    sp<MetaData> mMeta;
    sp<MediaSource> mSource;
    volatile bool mDone;
    volatile bool mPaused;
    volatile bool mResumed;
    bool mIsAvc;
    bool mIsAudio;
    bool mIsMPEG4;
    int64_t mTrackDurationUs;

    // For realtime applications, we need to adjust the media clock
    // for video track based on the audio media clock
    bool mIsRealTimeRecording;
    int64_t mMaxTimeStampUs;
    int64_t mEstimatedTrackSizeBytes;
    int64_t mMdatSizeBytes;
    int32_t mTimeScale;

    pthread_t mThread;

    // mNumSamples is used to track how many samples in mSampleSizes List.
    // This is to reduce the cost associated with mSampleSizes.size() call,
    // since it is O(n). Ideally, the fix should be in List class.
    size_t              mNumSamples;
    List<size_t>        mSampleSizes;
    bool                mSamplesHaveSameSize;

    List<MediaBuffer *> mChunkSamples;

    size_t              mNumStcoTableEntries;
    List<off_t>         mChunkOffsets;

    size_t              mNumStscTableEntries;
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

    size_t        mNumStssTableEntries;
    List<int32_t> mStssTableEntries;

    size_t        mNumSttsTableEntries;
    struct SttsTableEntry {

        SttsTableEntry(uint32_t count, uint32_t durationUs)
            : sampleCount(count), sampleDurationUs(durationUs) {}

        uint32_t sampleCount;
        uint32_t sampleDurationUs;
    };
    List<SttsTableEntry> mSttsTableEntries;

    // Sequence parameter set or picture parameter set
    struct AVCParamSet {
        AVCParamSet(uint16_t length, const uint8_t *data)
            : mLength(length), mData(data) {}

        uint16_t mLength;
        const uint8_t *mData;
    };
    List<AVCParamSet> mSeqParamSets;
    List<AVCParamSet> mPicParamSets;
    uint8_t mProfileIdc;
    uint8_t mProfileCompatible;
    uint8_t mLevelIdc;

    void *mCodecSpecificData;
    size_t mCodecSpecificDataSize;
    bool mGotAllCodecSpecificData;
    bool mTrackingProgressStatus;

    bool mReachedEOS;
    int64_t mStartTimestampUs;
    int64_t mPreviousTrackTimeUs;
    int64_t mTrackEveryTimeDurationUs;

    // Has the media time adjustment for video started?
    bool    mIsMediaTimeAdjustmentOn;
    // The time stamp when previous media time adjustment period starts
    int64_t mPrevMediaTimeAdjustTimestampUs;
    // Number of vidoe frames whose time stamp may be adjusted
    int64_t mMediaTimeAdjustNumFrames;
    // The sample number when previous meida time adjustmnet period starts
    int64_t mPrevMediaTimeAdjustSample;
    // The total accumulated drift time within a period of
    // kVideoMediaTimeAdjustPeriodTimeUs.
    int64_t mTotalDriftTimeToAdjustUs;
    // The total accumalated drift time since the start of the recording
    // excluding the current time adjustment period
    int64_t mPrevTotalAccumDriftTimeUs;

    // Update the audio track's drift information.
    void updateDriftTime(const sp<MetaData>& meta);

    // Adjust the time stamp of the video track according to
    // the drift time information from the audio track.
    void adjustMediaTime(int64_t *timestampUs);

    static void *ThreadWrapper(void *me);
    status_t threadEntry();

    const uint8_t *parseParamSet(
        const uint8_t *data, size_t length, int type, size_t *paramSetLen);

    status_t makeAVCCodecSpecificData(
            const uint8_t *data, size_t size);
    status_t copyAVCCodecSpecificData(
            const uint8_t *data, size_t size);
    status_t parseAVCCodecSpecificData(
            const uint8_t *data, size_t size);

    // Track authoring progress status
    void trackProgressStatus(int64_t timeUs, status_t err = OK);
    void initTrackingProgressStatus(MetaData *params);

    void getCodecSpecificDataFromInputFormatIfPossible();

    // Determine the track time scale
    // If it is an audio track, try to use the sampling rate as
    // the time scale; however, if user chooses the overwrite
    // value, the user-supplied time scale will be used.
    void setTimeScale();

    // Simple validation on the codec specific data
    status_t checkCodecSpecificData() const;
    int32_t mRotation;

    void updateTrackSizeEstimate();
    void addOneStscTableEntry(size_t chunkId, size_t sampleId);
    void addOneStssTableEntry(size_t sampleId);
    void addOneSttsTableEntry(size_t sampleCount, int64_t durationUs);

    Track(const Track &);
    Track &operator=(const Track &);
};

MPEG4Writer::MPEG4Writer(const char *filename)
    : mFile(fopen(filename, "wb")),
      mUse4ByteNalLength(true),
      mUse32BitOffset(true),
      mIsFileSizeLimitExplicitlyRequested(false),
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
      mUse4ByteNalLength(true),
      mUse32BitOffset(true),
      mIsFileSizeLimitExplicitlyRequested(false),
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

    while (!mTracks.empty()) {
        List<Track *>::iterator it = mTracks.begin();
        delete *it;
        (*it) = NULL;
        mTracks.erase(it);
    }
    mTracks.clear();
}

status_t MPEG4Writer::dump(
        int fd, const Vector<String16>& args) {
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "   MPEG4Writer %p\n", this);
    result.append(buffer);
    snprintf(buffer, SIZE, "     mStarted: %s\n", mStarted? "true": "false");
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        (*it)->dump(fd, args);
    }
    return OK;
}

status_t MPEG4Writer::Track::dump(
        int fd, const Vector<String16>& args) const {
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "     %s track\n", mIsAudio? "Audio": "Video");
    result.append(buffer);
    snprintf(buffer, SIZE, "       reached EOS: %s\n",
            mReachedEOS? "true": "false");
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return OK;
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

    // The default MIN_MOOV_BOX_SIZE is set to 0.6% x 1MB / 2,
    // where 1MB is the common file size limit for MMS application.
    // The default MAX _MOOV_BOX_SIZE value is based on about 3
    // minute video recording with a bit rate about 3 Mbps, because
    // statistics also show that most of the video captured are going
    // to be less than 3 minutes.

    // If the estimation is wrong, we will pay the price of wasting
    // some reserved space. This should not happen so often statistically.
    static const int32_t factor = mUse32BitOffset? 1: 2;
    static const int64_t MIN_MOOV_BOX_SIZE = 3 * 1024;  // 3 KB
    static const int64_t MAX_MOOV_BOX_SIZE = (180 * 3000000 * 6LL / 8000);
    int64_t size = MIN_MOOV_BOX_SIZE;

    // Max file size limit is set
    if (mMaxFileSizeLimitBytes != 0 && mIsFileSizeLimitExplicitlyRequested) {
        size = mMaxFileSizeLimitBytes * 6 / 1000;
    }

    // Max file duration limit is set
    if (mMaxFileDurationLimitUs != 0) {
        if (bitRate > 0) {
            int64_t size2 =
                ((mMaxFileDurationLimitUs * bitRate * 6) / 1000 / 8000000);
            if (mMaxFileSizeLimitBytes != 0 && mIsFileSizeLimitExplicitlyRequested) {
                // When both file size and duration limits are set,
                // we use the smaller limit of the two.
                if (size > size2) {
                    size = size2;
                }
            } else {
                // Only max file duration limit is set
                size = size2;
            }
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

    /*
     * Check mMaxFileSizeLimitBytes at the beginning
     * since mMaxFileSizeLimitBytes may be implicitly
     * changed later for 32-bit file offset even if
     * user does not ask to set it explicitly.
     */
    if (mMaxFileSizeLimitBytes != 0) {
        mIsFileSizeLimitExplicitlyRequested = true;
    }

    int32_t use64BitOffset;
    if (param &&
        param->findInt32(kKey64BitFileOffset, &use64BitOffset) &&
        use64BitOffset) {
        mUse32BitOffset = false;
    }

    if (mUse32BitOffset) {
        // Implicit 32 bit file size limit
        if (mMaxFileSizeLimitBytes == 0) {
            mMaxFileSizeLimitBytes = kMax32BitFileSize;
        }

        // If file size is set to be larger than the 32 bit file
        // size limit, treat it as an error.
        if (mMaxFileSizeLimitBytes > kMax32BitFileSize) {
            LOGW("32-bit file size limit (%lld bytes) too big. "
                 "It is changed to %lld bytes",
                mMaxFileSizeLimitBytes, kMax32BitFileSize);
            mMaxFileSizeLimitBytes = kMax32BitFileSize;
        }
    }

    int32_t use2ByteNalLength;
    if (param &&
        param->findInt32(kKey2ByteNalLength, &use2ByteNalLength) &&
        use2ByteNalLength) {
        mUse4ByteNalLength = false;
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

    status_t err = startWriterThread();
    if (err != OK) {
        return err;
    }

    err = startTracks(param);
    if (err != OK) {
        return err;
    }

    mStarted = true;
    return OK;
}

bool MPEG4Writer::use32BitFileOffset() const {
    return mUse32BitOffset;
}

status_t MPEG4Writer::pause() {
    if (mFile == NULL) {
        return OK;
    }
    mPaused = true;
    status_t err = OK;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        status_t status = (*it)->pause();
        if (status != OK) {
            err = status;
        }
    }
    return err;
}

void MPEG4Writer::stopWriterThread() {
    LOGV("stopWriterThread");

    {
        Mutex::Autolock autolock(mLock);

        mDone = true;
        mChunkReadyCondition.signal();
    }

    void *dummy;
    pthread_join(mThread, &dummy);
}

/*
 * MP4 file standard defines a composition matrix:
 * | a  b  u |
 * | c  d  v |
 * | x  y  w |
 *
 * the element in the matrix is stored in the following
 * order: {a, b, u, c, d, v, x, y, w},
 * where a, b, c, d, x, and y is in 16.16 format, while
 * u, v and w is in 2.30 format.
 */
void MPEG4Writer::writeCompositionMatrix(int degrees) {
    LOGV("writeCompositionMatrix");
    uint32_t a = 0x00010000;
    uint32_t b = 0;
    uint32_t c = 0;
    uint32_t d = 0x00010000;
    switch (degrees) {
        case 0:
            break;
        case 90:
            a = 0;
            b = 0x00010000;
            c = 0xFFFF0000;
            d = 0;
            break;
        case 180:
            a = 0xFFFF0000;
            d = 0xFFFF0000;
            break;
        case 270:
            a = 0;
            b = 0xFFFF0000;
            c = 0x00010000;
            d = 0;
            break;
        default:
            CHECK(!"Should never reach this unknown rotation");
            break;
    }

    writeInt32(a);           // a
    writeInt32(b);           // b
    writeInt32(0);           // u
    writeInt32(c);           // c
    writeInt32(d);           // d
    writeInt32(0);           // v
    writeInt32(0);           // x
    writeInt32(0);           // y
    writeInt32(0x40000000);  // w
}

status_t MPEG4Writer::stop() {
    if (mFile == NULL) {
        return OK;
    }

    status_t err = OK;
    int64_t maxDurationUs = 0;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        status_t status = (*it)->stop();
        if (err == OK && status != OK) {
            err = status;
        }

        int64_t durationUs = (*it)->getDurationUs();
        if (durationUs > maxDurationUs) {
            maxDurationUs = durationUs;
        }
    }

    stopWriterThread();

    // Do not write out movie header on error.
    if (err != OK) {
        fflush(mFile);
        fclose(mFile);
        mFile = NULL;
        mStarted = false;
        return err;
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
    int32_t duration = (maxDurationUs * mTimeScale + 5E5) / 1E6;

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
        writeCompositionMatrix(0);
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
    return err;
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

    if (mUse4ByteNalLength) {
        uint8_t x = length >> 24;
        fwrite(&x, 1, 1, mFile);
        x = (length >> 16) & 0xff;
        fwrite(&x, 1, 1, mFile);
        x = (length >> 8) & 0xff;
        fwrite(&x, 1, 1, mFile);
        x = length & 0xff;
        fwrite(&x, 1, 1, mFile);

        fwrite((const uint8_t *)buffer->data() + buffer->range_offset(),
                1, length, mFile);
        mOffset += length + 4;
    } else {
        CHECK(length < 65536);

        uint8_t x = length >> 8;
        fwrite(&x, 1, 1, mFile);
        x = length & 0xff;
        fwrite(&x, 1, 1, mFile);
        fwrite((const uint8_t *)buffer->data() + buffer->range_offset(),
                1, length, mFile);
        mOffset += length + 2;
    }

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

bool MPEG4Writer::isFileStreamable() const {
    return mStreamableFile;
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

    // Be conservative in the estimate: do not exceed 95% of
    // the target file limit. For small target file size limit, though,
    // this will not help.
    return (nTotalBytesEstimate >= (95 * mMaxFileSizeLimitBytes) / 100);
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
      mTrackDurationUs(0),
      mEstimatedTrackSizeBytes(0),
      mSamplesHaveSameSize(true),
      mCodecSpecificData(NULL),
      mCodecSpecificDataSize(0),
      mGotAllCodecSpecificData(false),
      mReachedEOS(false),
      mRotation(0) {
    getCodecSpecificDataFromInputFormatIfPossible();

    const char *mime;
    mMeta->findCString(kKeyMIMEType, &mime);
    mIsAvc = !strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC);
    mIsAudio = !strncasecmp(mime, "audio/", 6);
    mIsMPEG4 = !strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4) ||
               !strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC);

    setTimeScale();
}

void MPEG4Writer::Track::updateTrackSizeEstimate() {

    int64_t stcoBoxSizeBytes = mOwner->use32BitFileOffset()
                                ? mNumStcoTableEntries * 4
                                : mNumStcoTableEntries * 8;

    int64_t stszBoxSizeBytes = mSamplesHaveSameSize? 4: (mNumSamples * 4);

    mEstimatedTrackSizeBytes = mMdatSizeBytes;  // media data size
    if (!mOwner->isFileStreamable()) {
        // Reserved free space is not large enough to hold
        // all meta data and thus wasted.
        mEstimatedTrackSizeBytes += mNumStscTableEntries * 12 +  // stsc box size
                                    mNumStssTableEntries * 4 +   // stss box size
                                    mNumSttsTableEntries * 8 +   // stts box size
                                    stcoBoxSizeBytes +           // stco box size
                                    stszBoxSizeBytes;            // stsz box size
    }
}

void MPEG4Writer::Track::addOneStscTableEntry(
        size_t chunkId, size_t sampleId) {

        StscTableEntry stscEntry(chunkId, sampleId, 1);
        mStscTableEntries.push_back(stscEntry);
        ++mNumStscTableEntries;
}

void MPEG4Writer::Track::addOneStssTableEntry(size_t sampleId) {
    mStssTableEntries.push_back(sampleId);
    ++mNumStssTableEntries;
}

void MPEG4Writer::Track::addOneSttsTableEntry(
        size_t sampleCount, int64_t durationUs) {

    SttsTableEntry sttsEntry(sampleCount, durationUs);
    mSttsTableEntries.push_back(sttsEntry);
    ++mNumSttsTableEntries;
}

void MPEG4Writer::Track::addChunkOffset(off_t offset) {
    ++mNumStcoTableEntries;
    mChunkOffsets.push_back(offset);
}

void MPEG4Writer::Track::setTimeScale() {
    LOGV("setTimeScale");
    // Default time scale
    mTimeScale = 90000;

    if (mIsAudio) {
        // Use the sampling rate as the default time scale for audio track.
        int32_t sampleRate;
        bool success = mMeta->findInt32(kKeySampleRate, &sampleRate);
        CHECK(success);
        mTimeScale = sampleRate;
    }

    // If someone would like to overwrite the timescale, use user-supplied value.
    int32_t timeScale;
    if (mMeta->findInt32(kKeyTimeScale, &timeScale)) {
        mTimeScale = timeScale;
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

// static
void *MPEG4Writer::ThreadWrapper(void *me) {
    LOGV("ThreadWrapper: %p", me);
    MPEG4Writer *writer = static_cast<MPEG4Writer *>(me);
    writer->threadFunc();
    return NULL;
}

void MPEG4Writer::bufferChunk(const Chunk& chunk) {
    LOGV("bufferChunk: %p", chunk.mTrack);
    Mutex::Autolock autolock(mLock);
    CHECK_EQ(mDone, false);

    for (List<ChunkInfo>::iterator it = mChunkInfos.begin();
         it != mChunkInfos.end(); ++it) {

        if (chunk.mTrack == it->mTrack) {  // Found owner
            it->mChunks.push_back(chunk);
            mChunkReadyCondition.signal();
            return;
        }
    }

    CHECK("Received a chunk for a unknown track" == 0);
}

void MPEG4Writer::writeFirstChunk(ChunkInfo* info) {
    LOGV("writeFirstChunk: %p", info->mTrack);

    List<Chunk>::iterator chunkIt = info->mChunks.begin();
    for (List<MediaBuffer *>::iterator it = chunkIt->mSamples.begin();
         it != chunkIt->mSamples.end(); ++it) {

        off_t offset = info->mTrack->isAvc()
                            ? addLengthPrefixedSample_l(*it)
                            : addSample_l(*it);
        if (it == chunkIt->mSamples.begin()) {
            info->mTrack->addChunkOffset(offset);
        }
    }

    // Done with the current chunk.
    // Release all the samples in this chunk.
    while (!chunkIt->mSamples.empty()) {
        List<MediaBuffer *>::iterator it = chunkIt->mSamples.begin();
        (*it)->release();
        (*it) = NULL;
        chunkIt->mSamples.erase(it);
    }
    chunkIt->mSamples.clear();
    info->mChunks.erase(chunkIt);
}

void MPEG4Writer::writeChunks() {
    LOGV("writeChunks");
    size_t outstandingChunks = 0;
    while (!mChunkInfos.empty()) {
        List<ChunkInfo>::iterator it = mChunkInfos.begin();
        while (!it->mChunks.empty()) {
            CHECK_EQ(OK, writeOneChunk());
            ++outstandingChunks;
        }
        it->mTrack = NULL;
        mChunkInfos.erase(it);
    }
    mChunkInfos.clear();
    LOGD("%d chunks are written in the last batch", outstandingChunks);
}

status_t MPEG4Writer::writeOneChunk() {
    LOGV("writeOneChunk");

    // Find the smallest timestamp, and write that chunk out
    // XXX: What if some track is just too slow?
    int64_t minTimestampUs = 0x7FFFFFFFFFFFFFFFLL;
    Track *track = NULL;
    for (List<ChunkInfo>::iterator it = mChunkInfos.begin();
         it != mChunkInfos.end(); ++it) {
        if (!it->mChunks.empty()) {
            List<Chunk>::iterator chunkIt = it->mChunks.begin();
            if (chunkIt->mTimeStampUs < minTimestampUs) {
                minTimestampUs = chunkIt->mTimeStampUs;
                track = it->mTrack;
            }
        }
    }

    if (track == NULL) {
        LOGV("Nothing to be written after all");
        return OK;
    }

    if (mIsFirstChunk) {
        mIsFirstChunk = false;
    }
    for (List<ChunkInfo>::iterator it = mChunkInfos.begin();
         it != mChunkInfos.end(); ++it) {
        if (it->mTrack == track) {
            writeFirstChunk(&(*it));
        }
    }
    return OK;
}

void MPEG4Writer::threadFunc() {
    LOGV("threadFunc");

    prctl(PR_SET_NAME, (unsigned long)"MPEG4Writer", 0, 0, 0);
    while (!mDone) {
        {
            Mutex::Autolock autolock(mLock);
            mChunkReadyCondition.wait(mLock);
            CHECK_EQ(writeOneChunk(), OK);
        }
    }

    {
        // Write ALL samples
        Mutex::Autolock autolock(mLock);
        writeChunks();
    }
}

status_t MPEG4Writer::startWriterThread() {
    LOGV("startWriterThread");

    mDone = false;
    mIsFirstChunk = true;
    mDriftTimeUs = 0;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        ChunkInfo info;
        info.mTrack = *it;
        mChunkInfos.push_back(info);
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);
    pthread_create(&mThread, &attr, ThreadWrapper, this);
    pthread_attr_destroy(&attr);
    return OK;
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

    int32_t rotationDegrees;
    if (!mIsAudio && params && params->findInt32(kKeyRotationDegree, &rotationDegrees)) {
        mRotation = rotationDegrees;
    }

    mIsRealTimeRecording = true;
    {
        int32_t isNotRealTime;
        if (params && params->findInt32(kKeyNotRealTime, &isNotRealTime)) {
            mIsRealTimeRecording = (isNotRealTime == 0);
        }
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
    mTrackDurationUs = 0;
    mReachedEOS = false;
    mEstimatedTrackSizeBytes = 0;
    mNumStcoTableEntries = 0;
    mNumStssTableEntries = 0;
    mNumStscTableEntries = 0;
    mNumSttsTableEntries = 0;
    mMdatSizeBytes = 0;
    mIsMediaTimeAdjustmentOn = false;
    mPrevMediaTimeAdjustTimestampUs = 0;
    mMediaTimeAdjustNumFrames = 0;
    mPrevMediaTimeAdjustSample = 0;
    mTotalDriftTimeToAdjustUs = 0;
    mPrevTotalAccumDriftTimeUs = 0;

    pthread_create(&mThread, &attr, ThreadWrapper, this);
    pthread_attr_destroy(&attr);

    return OK;
}

status_t MPEG4Writer::Track::pause() {
    mPaused = true;
    return OK;
}

status_t MPEG4Writer::Track::stop() {
    if (mDone) {
        return OK;
    }

    mDone = true;

    void *dummy;
    pthread_join(mThread, &dummy);

    status_t err = (status_t) dummy;

    {
        status_t status = mSource->stop();
        if (err == OK && status != OK && status != ERROR_END_OF_STREAM) {
            err = status;
        }
    }

    return err;
}

bool MPEG4Writer::Track::reachedEOS() {
    return mReachedEOS;
}

// static
void *MPEG4Writer::Track::ThreadWrapper(void *me) {
    Track *track = static_cast<Track *>(me);

    status_t err = track->threadEntry();
    return (void *) err;
}

static void getNalUnitType(uint8_t byte, uint8_t* type) {
    LOGV("getNalUnitType: %d", byte);

    // nal_unit_type: 5-bit unsigned integer
    *type = (byte & 0x1F);
}

static const uint8_t *findNextStartCode(
        const uint8_t *data, size_t length) {

    LOGV("findNextStartCode: %p %d", data, length);

    size_t bytesLeft = length;
    while (bytesLeft > 4  &&
            memcmp("\x00\x00\x00\x01", &data[length - bytesLeft], 4)) {
        --bytesLeft;
    }
    if (bytesLeft <= 4) {
        bytesLeft = 0; // Last parameter set
    }
    return &data[length - bytesLeft];
}

const uint8_t *MPEG4Writer::Track::parseParamSet(
        const uint8_t *data, size_t length, int type, size_t *paramSetLen) {

    LOGV("parseParamSet");
    CHECK(type == kNalUnitTypeSeqParamSet ||
          type == kNalUnitTypePicParamSet);

    const uint8_t *nextStartCode = findNextStartCode(data, length);
    *paramSetLen = nextStartCode - data;
    if (*paramSetLen == 0) {
        LOGE("Param set is malformed, since its length is 0");
        return NULL;
    }

    AVCParamSet paramSet(*paramSetLen, data);
    if (type == kNalUnitTypeSeqParamSet) {
        if (*paramSetLen < 4) {
            LOGE("Seq parameter set malformed");
            return NULL;
        }
        if (mSeqParamSets.empty()) {
            mProfileIdc = data[1];
            mProfileCompatible = data[2];
            mLevelIdc = data[3];
        } else {
            if (mProfileIdc != data[1] ||
                mProfileCompatible != data[2] ||
                mLevelIdc != data[3]) {
                LOGE("Inconsistent profile/level found in seq parameter sets");
                return NULL;
            }
        }
        mSeqParamSets.push_back(paramSet);
    } else {
        mPicParamSets.push_back(paramSet);
    }
    return nextStartCode;
}

status_t MPEG4Writer::Track::copyAVCCodecSpecificData(
        const uint8_t *data, size_t size) {
    LOGV("copyAVCCodecSpecificData");

    // 2 bytes for each of the parameter set length field
    // plus the 7 bytes for the header
    if (size < 4 + 7) {
        LOGE("Codec specific data length too short: %d", size);
        return ERROR_MALFORMED;
    }

    mCodecSpecificDataSize = size;
    mCodecSpecificData = malloc(size);
    memcpy(mCodecSpecificData, data, size);
    return OK;
}

status_t MPEG4Writer::Track::parseAVCCodecSpecificData(
        const uint8_t *data, size_t size) {

    LOGV("parseAVCCodecSpecificData");
    // Data starts with a start code.
    // SPS and PPS are separated with start codes.
    // Also, SPS must come before PPS
    uint8_t type = kNalUnitTypeSeqParamSet;
    bool gotSps = false;
    bool gotPps = false;
    const uint8_t *tmp = data;
    const uint8_t *nextStartCode = data;
    size_t bytesLeft = size;
    size_t paramSetLen = 0;
    mCodecSpecificDataSize = 0;
    while (bytesLeft > 4 && !memcmp("\x00\x00\x00\x01", tmp, 4)) {
        getNalUnitType(*(tmp + 4), &type);
        if (type == kNalUnitTypeSeqParamSet) {
            if (gotPps) {
                LOGE("SPS must come before PPS");
                return ERROR_MALFORMED;
            }
            if (!gotSps) {
                gotSps = true;
            }
            nextStartCode = parseParamSet(tmp + 4, bytesLeft - 4, type, &paramSetLen);
        } else if (type == kNalUnitTypePicParamSet) {
            if (!gotSps) {
                LOGE("SPS must come before PPS");
                return ERROR_MALFORMED;
            }
            if (!gotPps) {
                gotPps = true;
            }
            nextStartCode = parseParamSet(tmp + 4, bytesLeft - 4, type, &paramSetLen);
        } else {
            LOGE("Only SPS and PPS Nal units are expected");
            return ERROR_MALFORMED;
        }

        if (nextStartCode == NULL) {
            return ERROR_MALFORMED;
        }

        // Move on to find the next parameter set
        bytesLeft -= nextStartCode - tmp;
        tmp = nextStartCode;
        mCodecSpecificDataSize += (2 + paramSetLen);
    }

    {
        // Check on the number of seq parameter sets
        size_t nSeqParamSets = mSeqParamSets.size();
        if (nSeqParamSets == 0) {
            LOGE("Cound not find sequence parameter set");
            return ERROR_MALFORMED;
        }

        if (nSeqParamSets > 0x1F) {
            LOGE("Too many seq parameter sets (%d) found", nSeqParamSets);
            return ERROR_MALFORMED;
        }
    }

    {
        // Check on the number of pic parameter sets
        size_t nPicParamSets = mPicParamSets.size();
        if (nPicParamSets == 0) {
            LOGE("Cound not find picture parameter set");
            return ERROR_MALFORMED;
        }
        if (nPicParamSets > 0xFF) {
            LOGE("Too many pic parameter sets (%d) found", nPicParamSets);
            return ERROR_MALFORMED;
        }
    }

    {
        // Check on the profiles
        // These profiles requires additional parameter set extensions
        if (mProfileIdc == 100 || mProfileIdc == 110 ||
            mProfileIdc == 122 || mProfileIdc == 144) {
            LOGE("Sorry, no support for profile_idc: %d!", mProfileIdc);
            return BAD_VALUE;
        }
    }

    return OK;
}

status_t MPEG4Writer::Track::makeAVCCodecSpecificData(
        const uint8_t *data, size_t size) {

    if (mCodecSpecificData != NULL) {
        LOGE("Already have codec specific data");
        return ERROR_MALFORMED;
    }

    if (size < 4) {
        LOGE("Codec specific data length too short: %d", size);
        return ERROR_MALFORMED;
    }

    // Data is in the form of AVCCodecSpecificData
    if (memcmp("\x00\x00\x00\x01", data, 4)) {
        return copyAVCCodecSpecificData(data, size);
    }

    if (parseAVCCodecSpecificData(data, size) != OK) {
        return ERROR_MALFORMED;
    }

    // ISO 14496-15: AVC file format
    mCodecSpecificDataSize += 7;  // 7 more bytes in the header
    mCodecSpecificData = malloc(mCodecSpecificDataSize);
    uint8_t *header = (uint8_t *)mCodecSpecificData;
    header[0] = 1;                     // version
    header[1] = mProfileIdc;           // profile indication
    header[2] = mProfileCompatible;    // profile compatibility
    header[3] = mLevelIdc;

    // 6-bit '111111' followed by 2-bit to lengthSizeMinuusOne
    if (mOwner->useNalLengthFour()) {
        header[4] = 0xfc | 3;  // length size == 4 bytes
    } else {
        header[4] = 0xfc | 1;  // length size == 2 bytes
    }

    // 3-bit '111' followed by 5-bit numSequenceParameterSets
    int nSequenceParamSets = mSeqParamSets.size();
    header[5] = 0xe0 | nSequenceParamSets;
    header += 6;
    for (List<AVCParamSet>::iterator it = mSeqParamSets.begin();
         it != mSeqParamSets.end(); ++it) {
        // 16-bit sequence parameter set length
        uint16_t seqParamSetLength = it->mLength;
        header[0] = seqParamSetLength >> 8;
        header[1] = seqParamSetLength & 0xff;

        // SPS NAL unit (sequence parameter length bytes)
        memcpy(&header[2], it->mData, seqParamSetLength);
        header += (2 + seqParamSetLength);
    }

    // 8-bit nPictureParameterSets
    int nPictureParamSets = mPicParamSets.size();
    header[0] = nPictureParamSets;
    header += 1;
    for (List<AVCParamSet>::iterator it = mPicParamSets.begin();
         it != mPicParamSets.end(); ++it) {
        // 16-bit picture parameter set length
        uint16_t picParamSetLength = it->mLength;
        header[0] = picParamSetLength >> 8;
        header[1] = picParamSetLength & 0xff;

        // PPS Nal unit (picture parameter set length bytes)
        memcpy(&header[2], it->mData, picParamSetLength);
        header += (2 + picParamSetLength);
    }

    return OK;
}

/*
* The video track's media time adjustment for real-time applications
* is described as follows:
*
* First, the media time adjustment is done for every period of
* kVideoMediaTimeAdjustPeriodTimeUs. kVideoMediaTimeAdjustPeriodTimeUs
* is currently a fixed value chosen heuristically. The value of
* kVideoMediaTimeAdjustPeriodTimeUs should not be very large or very small
* for two considerations: on one hand, a relatively large value
* helps reduce large fluctuation of drift time in the audio encoding
* path; while on the other hand, a relatively small value helps keep
* restoring synchronization in audio/video more frequently. Note for the
* very first period of kVideoMediaTimeAdjustPeriodTimeUs, there is
* no media time adjustment for the video track.
*
* Second, the total accumulated audio track time drift found
* in a period of kVideoMediaTimeAdjustPeriodTimeUs is distributed
* over a stream of incoming video frames. The number of video frames
* affected is determined based on the number of recorded video frames
* within the past kVideoMediaTimeAdjustPeriodTimeUs period.
* We choose to distribute the drift time over only a portion
* (rather than all) of the total number of recorded video frames
* in order to make sure that the video track media time adjustment is
* completed for the current period before the next video track media
* time adjustment period starts. Currently, the portion chosen is a
* half (0.5).
*
* Last, various additional checks are performed to ensure that
* the actual audio encoding path does not have too much drift.
* In particular, 1) we want to limit the average incremental time
* adjustment for each video frame to be less than a threshold
* for a single period of kVideoMediaTimeAdjustPeriodTimeUs.
* Currently, the threshold is set to 5 ms. If the average incremental
* media time adjustment for a video frame is larger than the
* threshold, the audio encoding path has too much time drift.
* 2) We also want to limit the total time drift in the audio
* encoding path to be less than a threshold for a period of
* kVideoMediaTimeAdjustPeriodTimeUs. Currently, the threshold
* is 0.5% of kVideoMediaTimeAdjustPeriodTimeUs. If the time drift of
* the audio encoding path is larger than the threshold, the audio
* encoding path has too much time drift. We treat the large time
* drift of the audio encoding path as errors, since there is no
* way to keep audio/video in synchronization for real-time
* applications if the time drift is too large unless we drop some
* video frames, which has its own problems that we don't want
* to get into for the time being.
*/
void MPEG4Writer::Track::adjustMediaTime(int64_t *timestampUs) {
    if (*timestampUs - mPrevMediaTimeAdjustTimestampUs >=
        kVideoMediaTimeAdjustPeriodTimeUs) {

        LOGV("New media time adjustment period at %lld us", *timestampUs);
        mIsMediaTimeAdjustmentOn = true;
        mMediaTimeAdjustNumFrames =
                (mNumSamples - mPrevMediaTimeAdjustSample) >> 1;

        mPrevMediaTimeAdjustTimestampUs = *timestampUs;
        mPrevMediaTimeAdjustSample = mNumSamples;
        int64_t totalAccumDriftTimeUs = mOwner->getDriftTimeUs();
        mTotalDriftTimeToAdjustUs =
                totalAccumDriftTimeUs - mPrevTotalAccumDriftTimeUs;

        mPrevTotalAccumDriftTimeUs = totalAccumDriftTimeUs;

        // Check on incremental adjusted time per frame
        int64_t adjustTimePerFrameUs =
                mTotalDriftTimeToAdjustUs / mMediaTimeAdjustNumFrames;

        if (adjustTimePerFrameUs < 0) {
            adjustTimePerFrameUs = -adjustTimePerFrameUs;
        }
        if (adjustTimePerFrameUs >= 5000) {
            LOGE("Adjusted time per video frame is %lld us",
                adjustTimePerFrameUs);
            CHECK(!"Video frame time adjustment is too large!");
        }

        // Check on total accumulated time drift within a period of
        // kVideoMediaTimeAdjustPeriodTimeUs.
        int64_t driftPercentage = (mTotalDriftTimeToAdjustUs * 1000)
                / kVideoMediaTimeAdjustPeriodTimeUs;

        if (driftPercentage < 0) {
            driftPercentage = -driftPercentage;
        }
        if (driftPercentage > 5) {
            LOGE("Audio track has time drift %lld us over %lld us",
                mTotalDriftTimeToAdjustUs,
                kVideoMediaTimeAdjustPeriodTimeUs);

            CHECK(!"The audio track media time drifts too much!");
        }

    }

    if (mIsMediaTimeAdjustmentOn) {
        if (mNumSamples - mPrevMediaTimeAdjustSample <=
            mMediaTimeAdjustNumFrames) {

            // Do media time incremental adjustment
            int64_t incrementalAdjustTimeUs =
                        (mTotalDriftTimeToAdjustUs *
                            (mNumSamples - mPrevMediaTimeAdjustSample))
                                / mMediaTimeAdjustNumFrames;

            *timestampUs +=
                (incrementalAdjustTimeUs + mPrevTotalAccumDriftTimeUs);

            LOGV("Incremental video frame media time adjustment: %lld us",
                (incrementalAdjustTimeUs + mPrevTotalAccumDriftTimeUs));
        } else {
            // Within the remaining adjustment period,
            // no incremental adjustment is needed.
            *timestampUs +=
                (mTotalDriftTimeToAdjustUs + mPrevTotalAccumDriftTimeUs);

            LOGV("Fixed video frame media time adjustment: %lld us",
                (mTotalDriftTimeToAdjustUs + mPrevTotalAccumDriftTimeUs));
        }
    }
}

/*
 * Updates the drift time from the audio track so that
 * the video track can get the updated drift time information
 * from the file writer. The fluctuation of the drift time of the audio
 * encoding path is smoothed out with a simple filter by giving a larger
 * weight to more recently drift time. The filter coefficients, 0.5 and 0.5,
 * are heuristically determined.
 */
void MPEG4Writer::Track::updateDriftTime(const sp<MetaData>& meta) {
    int64_t driftTimeUs = 0;
    if (meta->findInt64(kKeyDriftTime, &driftTimeUs)) {
        int64_t prevDriftTimeUs = mOwner->getDriftTimeUs();
        int64_t timeUs = (driftTimeUs + prevDriftTimeUs) >> 1;
        mOwner->setDriftTimeUs(timeUs);
    }
}

status_t MPEG4Writer::Track::threadEntry() {
    int32_t count = 0;
    const int64_t interleaveDurationUs = mOwner->interleaveDuration();
    int64_t chunkTimestampUs = 0;
    int32_t nChunks = 0;
    int32_t nZeroLengthFrames = 0;
    int64_t lastTimestampUs = 0;  // Previous sample time stamp in ms
    int64_t lastDurationUs = 0;   // Between the previous two samples in ms
    int64_t currDurationTicks = 0;  // Timescale based ticks
    int64_t lastDurationTicks = 0;  // Timescale based ticks
    int32_t sampleCount = 1;      // Sample count in the current stts table entry
    uint32_t previousSampleSize = 0;  // Size of the previous sample
    int64_t previousPausedDurationUs = 0;
    int64_t timestampUs;

    if (mIsAudio) {
        prctl(PR_SET_NAME, (unsigned long)"AudioTrackEncoding", 0, 0, 0);
    } else {
        prctl(PR_SET_NAME, (unsigned long)"VideoTrackEncoding", 0, 0, 0);
    }
    sp<MetaData> meta_data;

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

            if (mIsAvc) {
                status_t err = makeAVCCodecSpecificData(
                        (const uint8_t *)buffer->data()
                            + buffer->range_offset(),
                        buffer->range_length());
                CHECK_EQ(OK, err);
            } else if (mIsMPEG4) {
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

        if (mIsAvc) StripStartcode(copy);

        size_t sampleSize = copy->range_length();
        if (mIsAvc) {
            if (mOwner->useNalLengthFour()) {
                sampleSize += 4;
            } else {
                sampleSize += 2;
            }
        }

        // Max file size or duration handling
        mMdatSizeBytes += sampleSize;
        updateTrackSizeEstimate();

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

        /*
         * The original timestamp found in the data buffer will be modified as below:
         *
         * There is a playback offset into this track if the track's start time
         * is not the same as the movie start time, which will be recorded in edst
         * box of the output file. The playback offset is to make sure that the
         * starting time of the audio/video tracks are synchronized. Although the
         * track's media timestamp may be subject to various modifications
         * as outlined below, the track's playback offset time remains unchanged
         * once the first data buffer of the track is received.
         *
         * The media time stamp will be calculated by subtracting the playback offset
         * (and potential pause durations) from the original timestamp in the buffer.
         *
         * If this track is a video track for a real-time recording application with
         * both audio and video tracks, its media timestamp will subject to further
         * modification based on the media clock of the audio track. This modification
         * is needed for the purpose of maintaining good audio/video synchronization.
         *
         * If the recording session is paused and resumed multiple times, the track
         * media timestamp will be modified as if the  recording session had never been
         * paused at all during playback of the recorded output file. In other words,
         * the output file will have no memory of pause/resume durations.
         *
         */
        CHECK(meta_data->findInt64(kKeyTime, &timestampUs));
        LOGV("%s timestampUs: %lld", mIsAudio? "Audio": "Video", timestampUs);

////////////////////////////////////////////////////////////////////////////////
        if (mSampleSizes.empty()) {
            mStartTimestampUs = timestampUs;
            mOwner->setStartTimestampUs(mStartTimestampUs);
            previousPausedDurationUs = mStartTimestampUs;
        }

        if (mResumed) {
            int64_t durExcludingEarlierPausesUs = timestampUs - previousPausedDurationUs;
            CHECK(durExcludingEarlierPausesUs >= 0);
            int64_t pausedDurationUs = durExcludingEarlierPausesUs - mTrackDurationUs;
            CHECK(pausedDurationUs >= lastDurationUs);
            previousPausedDurationUs += pausedDurationUs - lastDurationUs;
            mResumed = false;
        }

        timestampUs -= previousPausedDurationUs;
        CHECK(timestampUs >= 0);

        // Media time adjustment for real-time applications
        if (mIsRealTimeRecording) {
            if (mIsAudio) {
                updateDriftTime(meta_data);
            } else {
                adjustMediaTime(&timestampUs);
            }
        }

        CHECK(timestampUs >= 0);
        if (mNumSamples > 1) {
            if (timestampUs <= lastTimestampUs) {
                LOGW("Frame arrives too late!");
                // Don't drop the late frame, since dropping a frame may cause
                // problems later during playback

                // The idea here is to avoid having two or more samples with the
                // same timestamp in the output file.
                if (mTimeScale >= 1000000LL) {
                    timestampUs = lastTimestampUs + 1;
                } else {
                    timestampUs = lastTimestampUs + (1000000LL + (mTimeScale >> 1)) / mTimeScale;
                }
            }
        }

        LOGV("%s media time stamp: %lld and previous paused duration %lld",
                mIsAudio? "Audio": "Video", timestampUs, previousPausedDurationUs);
        if (timestampUs > mTrackDurationUs) {
            mTrackDurationUs = timestampUs;
        }

        mSampleSizes.push_back(sampleSize);
        ++mNumSamples;
        if (mNumSamples > 2) {
            // We need to use the time scale based ticks, rather than the
            // timestamp itself to determine whether we have to use a new
            // stts entry, since we may have rounding errors.
            // The calculation is intended to reduce the accumulated
            // rounding errors.
            currDurationTicks =
                     ((timestampUs * mTimeScale + 500000LL) / 1000000LL -
                     (lastTimestampUs * mTimeScale + 500000LL) / 1000000LL);

            if (currDurationTicks != lastDurationTicks) {
                addOneSttsTableEntry(sampleCount, lastDurationUs);
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
        lastDurationTicks = currDurationTicks;
        lastTimestampUs = timestampUs;

        if (isSync != 0) {
            addOneStssTableEntry(mNumSamples);
        }

        if (mTrackingProgressStatus) {
            if (mPreviousTrackTimeUs <= 0) {
                mPreviousTrackTimeUs = mStartTimestampUs;
            }
            trackProgressStatus(timestampUs);
        }
        if (mOwner->numTracks() == 1) {
            off_t offset = mIsAvc? mOwner->addLengthPrefixedSample_l(copy)
                                 : mOwner->addSample_l(copy);
            if (mChunkOffsets.empty()) {
                addChunkOffset(offset);
            }
            copy->release();
            copy = NULL;
            continue;
        }

        mChunkSamples.push_back(copy);
        if (interleaveDurationUs == 0) {
            addOneStscTableEntry(++nChunks, 1);
            bufferChunk(timestampUs);
        } else {
            if (chunkTimestampUs == 0) {
                chunkTimestampUs = timestampUs;
            } else {
                if (timestampUs - chunkTimestampUs > interleaveDurationUs) {
                    ++nChunks;
                    if (nChunks == 1 ||  // First chunk
                        (--(mStscTableEntries.end()))->samplesPerChunk !=
                         mChunkSamples.size()) {
                        addOneStscTableEntry(nChunks, mChunkSamples.size());
                    }
                    bufferChunk(timestampUs);
                    chunkTimestampUs = timestampUs;
                }
            }
        }

    }

    if (mSampleSizes.empty() ||                      // no samples written
        (!mIsAudio && mNumStssTableEntries == 0) ||  // no sync frames for video
        (OK != checkCodecSpecificData())) {          // no codec specific data
        err = ERROR_MALFORMED;
    }
    mOwner->trackProgressStatus(this, -1, err);

    // Last chunk
    if (mOwner->numTracks() == 1) {
        addOneStscTableEntry(1, mNumSamples);
    } else if (!mChunkSamples.empty()) {
        addOneStscTableEntry(++nChunks, mChunkSamples.size());
        bufferChunk(timestampUs);
    }

    // We don't really know how long the last frame lasts, since
    // there is no frame time after it, just repeat the previous
    // frame's duration.
    if (mNumSamples == 1) {
        lastDurationUs = 0;  // A single sample's duration
    } else {
        ++sampleCount;  // Count for the last sample
    }
    addOneSttsTableEntry(sampleCount, lastDurationUs);
    mTrackDurationUs += lastDurationUs;
    mReachedEOS = true;
    LOGI("Received total/0-length (%d/%d) buffers and encoded %d frames. - %s",
            count, nZeroLengthFrames, mNumSamples, mIsAudio? "audio": "video");
    if (mIsAudio) {
        LOGI("Audio track drift time: %lld us", mOwner->getDriftTimeUs());
    }

    if (err == ERROR_END_OF_STREAM) {
        return OK;
    }
    return err;
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

void MPEG4Writer::setDriftTimeUs(int64_t driftTimeUs) {
    LOGV("setDriftTimeUs: %lld us", driftTimeUs);
    Mutex::Autolock autolock(mLock);
    mDriftTimeUs = driftTimeUs;
}

int64_t MPEG4Writer::getDriftTimeUs() {
    LOGV("getDriftTimeUs: %lld us", mDriftTimeUs);
    Mutex::Autolock autolock(mLock);
    return mDriftTimeUs;
}

bool MPEG4Writer::useNalLengthFour() {
    return mUse4ByteNalLength;
}

void MPEG4Writer::Track::bufferChunk(int64_t timestampUs) {
    LOGV("bufferChunk");

    Chunk chunk(this, timestampUs, mChunkSamples);
    mOwner->bufferChunk(chunk);
    mChunkSamples.clear();
}

int64_t MPEG4Writer::Track::getDurationUs() const {
    return mTrackDurationUs;
}

int64_t MPEG4Writer::Track::getEstimatedTrackSizeBytes() const {
    return mEstimatedTrackSizeBytes;
}

status_t MPEG4Writer::Track::checkCodecSpecificData() const {
    const char *mime;
    CHECK(mMeta->findCString(kKeyMIMEType, &mime));
    if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AAC, mime) ||
        !strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime) ||
        !strcasecmp(MEDIA_MIMETYPE_VIDEO_AVC, mime)) {
        if (!mCodecSpecificData ||
            mCodecSpecificDataSize <= 0) {
            LOGE("Missing codec specific data");
            return ERROR_MALFORMED;
        }
    } else {
        if (mCodecSpecificData ||
            mCodecSpecificDataSize > 0) {
            LOGE("Unexepected codec specific data found");
            return ERROR_MALFORMED;
        }
    }
    return OK;
}

void MPEG4Writer::Track::writeTrackHeader(
        int32_t trackID, bool use32BitOffset) {
    const char *mime;
    bool success = mMeta->findCString(kKeyMIMEType, &mime);
    CHECK(success);

    LOGV("%s track time scale: %d",
        mIsAudio? "Audio": "Video", mTimeScale);

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
        mOwner->writeInt16(mIsAudio ? 0x100 : 0);  // volume
        mOwner->writeInt16(0);             // reserved

        mOwner->writeCompositionMatrix(mRotation);

        if (mIsAudio) {
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
            LOGV("OffsetUs: %lld", offsetUs);
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
          mOwner->writeFourcc(mIsAudio ? "soun" : "vide");  // component subtype
          mOwner->writeInt32(0);             // reserved
          mOwner->writeInt32(0);             // reserved
          mOwner->writeInt32(0);             // reserved
          // Removing "r" for the name string just makes the string 4 byte aligned
          mOwner->writeCString(mIsAudio ? "SoundHandle": "VideoHandle");  // name
        mOwner->endBox();

        mOwner->beginBox("minf");
          if (mIsAudio) {
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
            if (mIsAudio) {
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
                        CHECK(mCodecSpecificData);
                        CHECK(mCodecSpecificDataSize > 0);

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
                  } else if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_NB, mime) ||
                             !strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_WB, mime)) {
                    // 3gpp2 Spec AMRSampleEntry fields
                    mOwner->beginBox("damr");
                      mOwner->writeCString("   ");  // vendor: 4 bytes
                      mOwner->writeInt8(0);         // decoder version
                      mOwner->writeInt16(0x83FF);   // mode set: all enabled
                      mOwner->writeInt8(0);         // mode change period
                      mOwner->writeInt8(1);         // frames per sample
                    mOwner->endBox();
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
                      CHECK(mCodecSpecificData);
                      CHECK(mCodecSpecificDataSize > 0);
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
                      CHECK(mCodecSpecificData);
                      CHECK(mCodecSpecificDataSize >= 5);

                      // Patch avcc's lengthSize field to match the number
                      // of bytes we use to indicate the size of a nal unit.
                      uint8_t *ptr = (uint8_t *)mCodecSpecificData;
                      ptr[4] =
                          (ptr[4] & 0xfc)
                            | (mOwner->useNalLengthFour() ? 3 : 1);

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
            mOwner->writeInt32(mNumSttsTableEntries);
            int64_t prevTimestampUs = 0;
            for (List<SttsTableEntry>::iterator it = mSttsTableEntries.begin();
                 it != mSttsTableEntries.end(); ++it) {
                mOwner->writeInt32(it->sampleCount);

                // Make sure that we are calculating the sample duration the exactly
                // same way as we made decision on how to create stts entries.
                int64_t currTimestampUs = prevTimestampUs + it->sampleDurationUs;
                int32_t dur = ((currTimestampUs * mTimeScale + 500000LL) / 1000000LL -
                               (prevTimestampUs * mTimeScale + 500000LL) / 1000000LL);
                prevTimestampUs += (it->sampleCount * it->sampleDurationUs);

                mOwner->writeInt32(dur);
            }
          mOwner->endBox();  // stts

          if (!mIsAudio) {
            mOwner->beginBox("stss");
              mOwner->writeInt32(0);  // version=0, flags=0
              mOwner->writeInt32(mNumStssTableEntries);  // number of sync frames
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
            mOwner->writeInt32(mNumStscTableEntries);
            for (List<StscTableEntry>::iterator it = mStscTableEntries.begin();
                 it != mStscTableEntries.end(); ++it) {
                mOwner->writeInt32(it->firstChunk);
                mOwner->writeInt32(it->samplesPerChunk);
                mOwner->writeInt32(it->sampleDescriptionId);
            }
          mOwner->endBox();  // stsc
          mOwner->beginBox(use32BitOffset? "stco": "co64");
            mOwner->writeInt32(0);  // version=0, flags=0
            mOwner->writeInt32(mNumStcoTableEntries);
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
