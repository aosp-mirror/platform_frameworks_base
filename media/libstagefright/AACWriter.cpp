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
#define LOG_TAG "AACWriter"
#include <utils/Log.h>

#include <media/stagefright/AACWriter.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/mediarecorder.h>
#include <sys/prctl.h>
#include <fcntl.h>

namespace android {

AACWriter::AACWriter(const char *filename)
    : mFd(-1),
      mInitCheck(NO_INIT),
      mStarted(false),
      mPaused(false),
      mResumed(false),
      mChannelCount(-1),
      mSampleRate(-1) {

    ALOGV("AACWriter Constructor");

    mFd = open(filename, O_CREAT | O_LARGEFILE | O_TRUNC | O_RDWR);
    if (mFd >= 0) {
        mInitCheck = OK;
    }
}

AACWriter::AACWriter(int fd)
    : mFd(dup(fd)),
      mInitCheck(mFd < 0? NO_INIT: OK),
      mStarted(false),
      mPaused(false),
      mResumed(false),
      mChannelCount(-1),
      mSampleRate(-1) {
}

AACWriter::~AACWriter() {
    if (mStarted) {
        stop();
    }

    if (mFd != -1) {
        close(mFd);
        mFd = -1;
    }
}

status_t AACWriter::initCheck() const {
    return mInitCheck;
}

static int writeInt8(int fd, uint8_t x) {
    return ::write(fd, &x, 1);
}


status_t AACWriter::addSource(const sp<MediaSource> &source) {
    if (mInitCheck != OK) {
        return mInitCheck;
    }

    if (mSource != NULL) {
        LOGE("AAC files only support a single track of audio.");
        return UNKNOWN_ERROR;
    }

    sp<MetaData> meta = source->getFormat();

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    CHECK(!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AAC));
    CHECK(meta->findInt32(kKeyChannelCount, &mChannelCount));
    CHECK(meta->findInt32(kKeySampleRate, &mSampleRate));
    CHECK(mChannelCount >= 1 && mChannelCount <= 2);

    mSource = source;
    return OK;
}

status_t AACWriter::start(MetaData *params) {
    if (mInitCheck != OK) {
        return mInitCheck;
    }

    if (mSource == NULL) {
        return UNKNOWN_ERROR;
    }

    if (mStarted && mPaused) {
        mPaused = false;
        mResumed = true;
        return OK;
    } else if (mStarted) {
        // Already started, does nothing
        return OK;
    }

    mFrameDurationUs = (kSamplesPerFrame * 1000000LL + (mSampleRate >> 1))
                            / mSampleRate;

    status_t err = mSource->start();

    if (err != OK) {
        return err;
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    mReachedEOS = false;
    mDone = false;

    pthread_create(&mThread, &attr, ThreadWrapper, this);
    pthread_attr_destroy(&attr);

    mStarted = true;

    return OK;
}

status_t AACWriter::pause() {
    if (!mStarted) {
        return OK;
    }
    mPaused = true;
    return OK;
}

status_t AACWriter::stop() {
    if (!mStarted) {
        return OK;
    }

    mDone = true;

    void *dummy;
    pthread_join(mThread, &dummy);

    status_t err = (status_t) dummy;
    {
        status_t status = mSource->stop();
        if (err == OK &&
            (status != OK && status != ERROR_END_OF_STREAM)) {
            err = status;
        }
    }

    mStarted = false;
    return err;
}

bool AACWriter::exceedsFileSizeLimit() {
    if (mMaxFileSizeLimitBytes == 0) {
        return false;
    }
    return mEstimatedSizeBytes >= mMaxFileSizeLimitBytes;
}

bool AACWriter::exceedsFileDurationLimit() {
    if (mMaxFileDurationLimitUs == 0) {
        return false;
    }
    return mEstimatedDurationUs >= mMaxFileDurationLimitUs;
}

// static
void *AACWriter::ThreadWrapper(void *me) {
    return (void *) static_cast<AACWriter *>(me)->threadFunc();
}

/*
* Returns an index into the sample rate table if the
* given sample rate is found; otherwise, returns -1.
*/
static bool getSampleRateTableIndex(int sampleRate, uint8_t* tableIndex) {
    static const int kSampleRateTable[] = {
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000
    };
    const int tableSize =
        sizeof(kSampleRateTable) / sizeof(kSampleRateTable[0]);

    *tableIndex = 0;
    for (int index = 0; index < tableSize; ++index) {
        if (sampleRate == kSampleRateTable[index]) {
            ALOGV("Sample rate: %d and index: %d",
                sampleRate, index);
            *tableIndex = index;
            return true;
        }
    }

    LOGE("Sampling rate %d bps is not supported", sampleRate);
    return false;
}

/*
 * ADTS (Audio data transport stream) header structure.
 * It consists of 7 or 9 bytes (with or without CRC):
 * 12 bits of syncword 0xFFF, all bits must be 1
 * 1 bit of field ID. 0 for MPEG-4, and 1 for MPEG-2
 * 2 bits of MPEG layer. If in MPEG-TS, set to 0
 * 1 bit of protection absense. Set to 1 if no CRC.
 * 2 bits of profile code. Set to 1 (The MPEG-4 Audio
 *   object type minus 1. We are using AAC-LC = 2)
 * 4 bits of sampling frequency index code (15 is not allowed)
 * 1 bit of private stream. Set to 0.
 * 3 bits of channel configuration code. 0 resevered for inband PCM
 * 1 bit of originality. Set to 0.
 * 1 bit of home. Set to 0.
 * 1 bit of copyrighted steam. Set to 0.
 * 1 bit of copyright start. Set to 0.
 * 13 bits of frame length. It included 7 ot 9 bytes header length.
 *   it is set to (protection absense? 7: 9) + size(AAC frame)
 * 11 bits of buffer fullness. 0x7FF for VBR.
 * 2 bits of frames count in one packet. Set to 0.
 */
status_t AACWriter::writeAdtsHeader(uint32_t frameLength) {
    uint8_t data = 0xFF;
    write(mFd, &data, 1);

    const uint8_t kFieldId = 0;
    const uint8_t kMpegLayer = 0;
    const uint8_t kProtectionAbsense = 1;  // 1: kAdtsHeaderLength = 7
    data = 0xF0;
    data |= (kFieldId << 3);
    data |= (kMpegLayer << 1);
    data |= kProtectionAbsense;
    write(mFd, &data, 1);

    const uint8_t kProfileCode = 1;  // AAC-LC
    uint8_t kSampleFreqIndex;
    CHECK(getSampleRateTableIndex(mSampleRate, &kSampleFreqIndex));
    const uint8_t kPrivateStream = 0;
    const uint8_t kChannelConfigCode = mChannelCount;
    data = (kProfileCode << 6);
    data |= (kSampleFreqIndex << 2);
    data |= (kPrivateStream << 1);
    data |= (kChannelConfigCode >> 2);
    write(mFd, &data, 1);

    // 4 bits from originality to copyright start
    const uint8_t kCopyright = 0;
    const uint32_t kFrameLength = frameLength;
    data = ((kChannelConfigCode & 3) << 6);
    data |= (kCopyright << 2);
    data |= ((kFrameLength & 0x1800) >> 11);
    write(mFd, &data, 1);

    data = ((kFrameLength & 0x07F8) >> 3);
    write(mFd, &data, 1);

    const uint32_t kBufferFullness = 0x7FF;  // VBR
    data = ((kFrameLength & 0x07) << 5);
    data |= ((kBufferFullness & 0x07C0) >> 6);
    write(mFd, &data, 1);

    const uint8_t kFrameCount = 0;
    data = ((kBufferFullness & 0x03F) << 2);
    data |= kFrameCount;
    write(mFd, &data, 1);

    return OK;
}

status_t AACWriter::threadFunc() {
    mEstimatedDurationUs = 0;
    mEstimatedSizeBytes = 0;
    int64_t previousPausedDurationUs = 0;
    int64_t maxTimestampUs = 0;
    status_t err = OK;

    prctl(PR_SET_NAME, (unsigned long)"AACWriterThread", 0, 0, 0);

    while (!mDone && err == OK) {
        MediaBuffer *buffer;
        err = mSource->read(&buffer);

        if (err != OK) {
            break;
        }

        if (mPaused) {
            buffer->release();
            buffer = NULL;
            continue;
        }

        mEstimatedSizeBytes += kAdtsHeaderLength + buffer->range_length();
        if (exceedsFileSizeLimit()) {
            buffer->release();
            buffer = NULL;
            notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED, 0);
            break;
        }

        int32_t isCodecSpecific = 0;
        if (buffer->meta_data()->findInt32(kKeyIsCodecConfig, &isCodecSpecific) && isCodecSpecific) {
            ALOGV("Drop codec specific info buffer");
            buffer->release();
            buffer = NULL;
            continue;
        }

        int64_t timestampUs;
        CHECK(buffer->meta_data()->findInt64(kKeyTime, &timestampUs));
        if (timestampUs > mEstimatedDurationUs) {
            mEstimatedDurationUs = timestampUs;
        }
        if (mResumed) {
            previousPausedDurationUs += (timestampUs - maxTimestampUs - mFrameDurationUs);
            mResumed = false;
        }
        timestampUs -= previousPausedDurationUs;
        ALOGV("time stamp: %lld, previous paused duration: %lld",
            timestampUs, previousPausedDurationUs);
        if (timestampUs > maxTimestampUs) {
            maxTimestampUs = timestampUs;
        }

        if (exceedsFileDurationLimit()) {
            buffer->release();
            buffer = NULL;
            notify(MEDIA_RECORDER_EVENT_INFO, MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0);
            break;
        }

        // Each output AAC audio frame to the file contains
        // 1. an ADTS header, followed by
        // 2. the compressed audio data.
        ssize_t dataLength = buffer->range_length();
        uint8_t *data = (uint8_t *)buffer->data() + buffer->range_offset();
        if (writeAdtsHeader(kAdtsHeaderLength + dataLength) != OK ||
            dataLength != write(mFd, data, dataLength)) {
            err = ERROR_IO;
        }

        buffer->release();
        buffer = NULL;
    }

    close(mFd);
    mFd = -1;
    mReachedEOS = true;
    if (err == ERROR_END_OF_STREAM) {
        return OK;
    }
    return err;
}

bool AACWriter::reachedEOS() {
    return mReachedEOS;
}

}  // namespace android
