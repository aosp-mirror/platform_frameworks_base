/*
 * Copyright (C) 2019 The Android Open Source Project
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
#define LOG_TAG "SoundPool::Sound"
#include <utils/Log.h>

#include "Sound.h"

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaFormat.h>

namespace android::soundpool {

constexpr uint32_t kMaxSampleRate = 192000;
constexpr size_t   kDefaultHeapSize = 1024 * 1024; // 1MB (compatible with low mem devices)

Sound::Sound(int32_t soundID, int fd, int64_t offset, int64_t length)
    : mSoundID(soundID)
    , mFd(fcntl(fd, F_DUPFD_CLOEXEC, (int)0 /* arg */)) // dup(fd) + close on exec to prevent leaks.
    , mOffset(offset)
    , mLength(length)
{
    ALOGV("%s(soundID=%d, fd=%d, offset=%lld, length=%lld)",
            __func__, soundID, fd, (long long)offset, (long long)length);
    ALOGW_IF(mFd == -1, "Unable to dup descriptor %d", fd);
}

Sound::~Sound()
{
    ALOGV("%s(soundID=%d, fd=%d)", __func__, mSoundID, mFd.get());
}

static status_t decode(int fd, int64_t offset, int64_t length,
        uint32_t *rate, int32_t *channelCount, audio_format_t *audioFormat,
        audio_channel_mask_t *channelMask, const sp<MemoryHeapBase>& heap,
        size_t *sizeInBytes) {
    ALOGV("%s(fd=%d, offset=%lld, length=%lld, ...)",
            __func__, fd, (long long)offset, (long long)length);
    std::unique_ptr<AMediaExtractor, decltype(&AMediaExtractor_delete)> ex{
            AMediaExtractor_new(), &AMediaExtractor_delete};
    status_t err = AMediaExtractor_setDataSourceFd(ex.get(), fd, offset, length);

    if (err != AMEDIA_OK) {
        return err;
    }

    *audioFormat = AUDIO_FORMAT_PCM_16_BIT;  // default format for audio codecs.
    const size_t numTracks = AMediaExtractor_getTrackCount(ex.get());
    for (size_t i = 0; i < numTracks; i++) {
        std::unique_ptr<AMediaFormat, decltype(&AMediaFormat_delete)> format{
                AMediaExtractor_getTrackFormat(ex.get(), i), &AMediaFormat_delete};
        const char *mime;
        if (!AMediaFormat_getString(format.get(),  AMEDIAFORMAT_KEY_MIME, &mime)) {
            return UNKNOWN_ERROR;
        }
        if (strncmp(mime, "audio/", 6) == 0) {
            std::unique_ptr<AMediaCodec, decltype(&AMediaCodec_delete)> codec{
                    AMediaCodec_createDecoderByType(mime), &AMediaCodec_delete};
            if (codec == nullptr
                    || AMediaCodec_configure(codec.get(), format.get(),
                            nullptr /* window */, nullptr /* drm */, 0 /* flags */) != AMEDIA_OK
                    || AMediaCodec_start(codec.get()) != AMEDIA_OK
                    || AMediaExtractor_selectTrack(ex.get(), i) != AMEDIA_OK) {
                return UNKNOWN_ERROR;
            }

            bool sawInputEOS = false;
            bool sawOutputEOS = false;
            auto writePos = static_cast<uint8_t*>(heap->getBase());
            size_t available = heap->getSize();
            size_t written = 0;
            format.reset(AMediaCodec_getOutputFormat(codec.get())); // update format.

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    ssize_t bufidx = AMediaCodec_dequeueInputBuffer(codec.get(), 5000);
                    ALOGV("%s: input buffer %zd", __func__, bufidx);
                    if (bufidx >= 0) {
                        size_t bufsize;
                        uint8_t * const buf = AMediaCodec_getInputBuffer(
                                codec.get(), bufidx, &bufsize);
                        if (buf == nullptr) {
                            ALOGE("%s: AMediaCodec_getInputBuffer returned nullptr, short decode",
                                    __func__);
                            break;
                        }
                        ssize_t sampleSize = AMediaExtractor_readSampleData(ex.get(), buf, bufsize);
                        ALOGV("%s: read %zd", __func__, sampleSize);
                        if (sampleSize < 0) {
                            sampleSize = 0;
                            sawInputEOS = true;
                            ALOGV("%s: EOS", __func__);
                        }
                        const int64_t presentationTimeUs = AMediaExtractor_getSampleTime(ex.get());

                        const media_status_t mstatus = AMediaCodec_queueInputBuffer(
                                codec.get(), bufidx,
                                0 /* offset */, sampleSize, presentationTimeUs,
                                sawInputEOS ? AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM : 0);
                        if (mstatus != AMEDIA_OK) {
                            // AMEDIA_ERROR_UNKNOWN == { -ERANGE -EINVAL -EACCES }
                            ALOGE("%s: AMediaCodec_queueInputBuffer returned status %d,"
                                    "short decode",
                                    __func__, (int)mstatus);
                            break;
                        }
                        (void)AMediaExtractor_advance(ex.get());
                    }
                }

                AMediaCodecBufferInfo info;
                const ssize_t status = AMediaCodec_dequeueOutputBuffer(codec.get(), &info, 1);
                ALOGV("%s: dequeueoutput returned: %zd", __func__, status);
                if (status >= 0) {
                    if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) {
                        ALOGV("%s: output EOS", __func__);
                        sawOutputEOS = true;
                    }
                    ALOGV("%s: got decoded buffer size %d", __func__, info.size);

                    const uint8_t * const buf = AMediaCodec_getOutputBuffer(
                            codec.get(), status, nullptr /* out_size */);
                    if (buf == nullptr) {
                        ALOGE("%s: AMediaCodec_getOutputBuffer returned nullptr, short decode",
                                __func__);
                        break;
                    }
                    const size_t dataSize = std::min((size_t)info.size, available);
                    memcpy(writePos, buf + info.offset, dataSize);
                    writePos += dataSize;
                    written += dataSize;
                    available -= dataSize;
                    const media_status_t mstatus = AMediaCodec_releaseOutputBuffer(
                            codec.get(), status, false /* render */);
                    if (mstatus != AMEDIA_OK) {
                        // AMEDIA_ERROR_UNKNOWN == { -ERANGE -EINVAL -EACCES }
                        ALOGE("%s: AMediaCodec_releaseOutputBuffer"
                                " returned status %d, short decode",
                                __func__, (int)mstatus);
                        break;
                    }
                    if (available == 0) {
                        // there might be more data, but there's no space for it
                        sawOutputEOS = true;
                    }
                } else if (status == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) {
                    ALOGV("%s: output buffers changed", __func__);
                } else if (status == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                    format.reset(AMediaCodec_getOutputFormat(codec.get())); // update format
                    ALOGV("%s: format changed to: %s",
                           __func__, AMediaFormat_toString(format.get()));
                } else if (status == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                    ALOGV("%s: no output buffer right now", __func__);
                } else if (status <= AMEDIA_ERROR_BASE) {
                    ALOGE("%s: decode error: %zd", __func__, status);
                    break;
                } else {
                    ALOGV("%s: unexpected info code: %zd", __func__, status);
                }
            }

            (void)AMediaCodec_stop(codec.get());
            if (!AMediaFormat_getInt32(
                    format.get(), AMEDIAFORMAT_KEY_SAMPLE_RATE, (int32_t*) rate) ||
                !AMediaFormat_getInt32(
                    format.get(), AMEDIAFORMAT_KEY_CHANNEL_COUNT, channelCount)) {
                return UNKNOWN_ERROR;
            }
            if (!AMediaFormat_getInt32(format.get(), AMEDIAFORMAT_KEY_CHANNEL_MASK,
                    (int32_t*) channelMask)) {
                *channelMask = AUDIO_CHANNEL_NONE;
            }
            *sizeInBytes = written;
            return OK;
        }
    }
    return UNKNOWN_ERROR;
}

status_t Sound::doLoad()
{
    ALOGV("%s()", __func__);
    status_t status = NO_INIT;
    if (mFd.get() != -1) {
        mHeap = new MemoryHeapBase(kDefaultHeapSize);

        ALOGV("%s: start decode", __func__);
        uint32_t sampleRate;
        int32_t channelCount;
        audio_format_t format;
        audio_channel_mask_t channelMask;
        status = decode(mFd.get(), mOffset, mLength, &sampleRate, &channelCount, &format,
                        &channelMask, mHeap, &mSizeInBytes);
        ALOGV("%s: close(%d)", __func__, mFd.get());
        mFd.reset();  // close

        if (status != NO_ERROR) {
            ALOGE("%s: unable to load sound", __func__);
        } else if (sampleRate > kMaxSampleRate) {
            ALOGE("%s: sample rate (%u) out of range", __func__, sampleRate);
            status = BAD_VALUE;
        } else if (channelCount < 1 || channelCount > FCC_LIMIT) {
            ALOGE("%s: sample channel count (%d) out of range", __func__, channelCount);
            status = BAD_VALUE;
        } else {
            // Correctly loaded, proper parameters
            ALOGV("%s: pointer = %p, sizeInBytes = %zu, sampleRate = %u, channelCount = %d",
                  __func__, mHeap->getBase(), mSizeInBytes, sampleRate, channelCount);
            mData = new MemoryBase(mHeap, 0, mSizeInBytes);
            mSampleRate = sampleRate;
            mChannelCount = channelCount;
            mFormat = format;
            mChannelMask = channelMask;
            mState = READY;  // this should be last, as it is an atomic sync point
            return NO_ERROR;
        }
    } else {
        ALOGE("%s: uninitialized fd, dup failed", __func__);
    }
    // ERROR handling
    mHeap.clear();
    mState = DECODE_ERROR; // this should be last, as it is an atomic sync point
    return status;
}

} // namespace android::soundpool
