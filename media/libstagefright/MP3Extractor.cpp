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
#define LOG_TAG "MP3Extractor"
#include <utils/Log.h>

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MP3Extractor.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>
#include <utils/String8.h>

namespace android {

static bool get_mp3_frame_size(
        uint32_t header, size_t *frame_size,
        int *out_sampling_rate = NULL, int *out_channels = NULL,
        int *out_bitrate = NULL) {
    *frame_size = 0;

    if (out_sampling_rate) {
        *out_sampling_rate = 0;
    }

    if (out_channels) {
        *out_channels = 0;
    }

    if (out_bitrate) {
        *out_bitrate = 0;
    }

    if ((header & 0xffe00000) != 0xffe00000) {
        return false;
    }

    unsigned version = (header >> 19) & 3;

    if (version == 0x01) {
        return false;
    }
    
    unsigned layer = (header >> 17) & 3;

    if (layer == 0x00) {
        return false;
    }

    unsigned protection = (header >> 16) & 1;

    unsigned bitrate_index = (header >> 12) & 0x0f;

    if (bitrate_index == 0 || bitrate_index == 0x0f) {
        // Disallow "free" bitrate.
        return false;
    }

    unsigned sampling_rate_index = (header >> 10) & 3;

    if (sampling_rate_index == 3) {
        return false;
    }

    static const int kSamplingRateV1[] = { 44100, 48000, 32000 };
    int sampling_rate = kSamplingRateV1[sampling_rate_index];
    if (version == 2 /* V2 */) {
        sampling_rate /= 2;
    } else if (version == 0 /* V2.5 */) {
        sampling_rate /= 4;
    }

    unsigned padding = (header >> 9) & 1;

    if (layer == 3) {
        // layer I

        static const int kBitrateV1[] = {
            32, 64, 96, 128, 160, 192, 224, 256,
            288, 320, 352, 384, 416, 448
        };

        static const int kBitrateV2[] = {
            32, 48, 56, 64, 80, 96, 112, 128,
            144, 160, 176, 192, 224, 256
        };

        int bitrate =
            (version == 3 /* V1 */)
                ? kBitrateV1[bitrate_index - 1]
                : kBitrateV2[bitrate_index - 1];

        if (out_bitrate) {
            *out_bitrate = bitrate;
        }

        *frame_size = (12000 * bitrate / sampling_rate + padding) * 4;
    } else {
        // layer II or III

        static const int kBitrateV1L2[] = {
            32, 48, 56, 64, 80, 96, 112, 128,
            160, 192, 224, 256, 320, 384
        };

        static const int kBitrateV1L3[] = {
            32, 40, 48, 56, 64, 80, 96, 112,
            128, 160, 192, 224, 256, 320
        };

        static const int kBitrateV2[] = {
            8, 16, 24, 32, 40, 48, 56, 64,
            80, 96, 112, 128, 144, 160
        };

        int bitrate;
        if (version == 3 /* V1 */) {
            bitrate = (layer == 2 /* L2 */)
                ? kBitrateV1L2[bitrate_index - 1]
                : kBitrateV1L3[bitrate_index - 1];
        } else {
            // V2 (or 2.5)

            bitrate = kBitrateV2[bitrate_index - 1];
        }

        if (out_bitrate) {
            *out_bitrate = bitrate;
        }

        if (version == 3 /* V1 */) {
            *frame_size = 144000 * bitrate / sampling_rate + padding;
        } else {
            // V2 or V2.5
            *frame_size = 72000 * bitrate / sampling_rate + padding;
        }
    }

    if (out_sampling_rate) {
        *out_sampling_rate = sampling_rate;
    }

    if (out_channels) {
        int channel_mode = (header >> 6) & 3;

        *out_channels = (channel_mode == 3) ? 1 : 2;
    }

    return true;
}

static bool Resync(
        const sp<DataSource> &source, uint32_t match_header,
        off_t *inout_pos, uint32_t *out_header) {
    if (*inout_pos == 0) {
        // Skip an optional ID3 header if syncing at the very beginning
        // of the datasource.

        uint8_t id3header[10];
        if (source->read_at(0, id3header, sizeof(id3header))
                < (ssize_t)sizeof(id3header)) {
            // If we can't even read these 10 bytes, we might as well bail out,
            // even if there _were_ 10 bytes of valid mp3 audio data...
            return false;
        }

        if (id3header[0] == 'I' && id3header[1] == 'D' && id3header[2] == '3') {
            // Skip the ID3v2 header.

            size_t len =
                ((id3header[6] & 0x7f) << 21)
                | ((id3header[7] & 0x7f) << 14)
                | ((id3header[8] & 0x7f) << 7)
                | (id3header[9] & 0x7f);

            len += 10;

            *inout_pos += len;
        }
    }

    // Everything must match except for
    // protection, bitrate, padding, private bits and mode extension.
    const uint32_t kMask = 0xfffe0ccf;

    const size_t kMaxFrameSize = 4096;
    uint8_t *buffer = new uint8_t[kMaxFrameSize];
    
    off_t pos = *inout_pos - kMaxFrameSize;
    size_t buffer_offset = kMaxFrameSize;
    size_t buffer_length = kMaxFrameSize;
    bool valid = false;
    do {
        if (buffer_offset + 3 >= buffer_length) {
            if (buffer_length < kMaxFrameSize) {
                break;
            }

            pos += buffer_offset;

            if (pos >= *inout_pos + 128 * 1024) {
                // Don't scan forever.
                LOGV("giving up at offset %ld", pos);
                break;
            }

            memmove(buffer, &buffer[buffer_offset], buffer_length - buffer_offset);
            buffer_length = buffer_length - buffer_offset;
            buffer_offset = 0;

            ssize_t n = source->read_at(
                    pos, &buffer[buffer_length], kMaxFrameSize - buffer_length);

            if (n <= 0) {
                break;
            }

            buffer_length += (size_t)n;

            continue;
        }

        uint32_t header = U32_AT(&buffer[buffer_offset]);

        if (match_header != 0 && (header & kMask) != (match_header & kMask)) {
            ++buffer_offset;
            continue;
        }

        size_t frame_size;
        int sample_rate, num_channels, bitrate;
        if (!get_mp3_frame_size(header, &frame_size,
                               &sample_rate, &num_channels, &bitrate)) {
            ++buffer_offset;
            continue;
        }

        LOGV("found possible 1st frame at %ld", pos + buffer_offset);

        // We found what looks like a valid frame,
        // now find its successors.

        off_t test_pos = pos + buffer_offset + frame_size;

        valid = true;
        for (int j = 0; j < 3; ++j) {
            uint8_t tmp[4];
            if (source->read_at(test_pos, tmp, 4) < 4) {
                valid = false;
                break;
            }
            
            uint32_t test_header = U32_AT(tmp);

            LOGV("subsequent header is %08x", test_header);

            if ((test_header & kMask) != (header & kMask)) {
                valid = false;
                break;
            }

            size_t test_frame_size;
            if (!get_mp3_frame_size(test_header, &test_frame_size)) {
                valid = false;
                break;
            }

            LOGV("found subsequent frame #%d at %ld", j + 2, test_pos);

            test_pos += test_frame_size;
        }

        if (valid) {
            *inout_pos = pos + buffer_offset;

            if (out_header != NULL) {
                *out_header = header;
            }
        } else {
            LOGV("no dice, no valid sequence of frames found.");
        }

        ++buffer_offset;

    } while (!valid);

    delete[] buffer;
    buffer = NULL;

    return valid;
}

class MP3Source : public MediaSource {
public:
    MP3Source(
            const sp<MetaData> &meta, const sp<DataSource> &source,
            off_t first_frame_pos, uint32_t fixed_header);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~MP3Source();

private:
    sp<MetaData> mMeta;
    sp<DataSource> mDataSource;
    off_t mFirstFramePos;
    uint32_t mFixedHeader;
    off_t mCurrentPos;
    int64_t mCurrentTimeUs;
    bool mStarted;

    MediaBufferGroup *mGroup;

    MP3Source(const MP3Source &);
    MP3Source &operator=(const MP3Source &);
};

MP3Extractor::MP3Extractor(const sp<DataSource> &source)
    : mDataSource(source),
      mFirstFramePos(-1),
      mFixedHeader(0) {
    off_t pos = 0;
    uint32_t header;
    bool success = Resync(mDataSource, 0, &pos, &header);
    CHECK(success);

    if (success) {
        mFirstFramePos = pos;
        mFixedHeader = header;

        size_t frame_size;
        int sample_rate;
        int num_channels;
        int bitrate;
        get_mp3_frame_size(
                header, &frame_size, &sample_rate, &num_channels, &bitrate);

        mMeta = new MetaData;

        mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_MPEG);
        mMeta->setInt32(kKeySampleRate, sample_rate);
        mMeta->setInt32(kKeyBitRate, bitrate);
        mMeta->setInt32(kKeyChannelCount, num_channels);

        off_t fileSize;
        if (mDataSource->getSize(&fileSize) == OK) {
            mMeta->setInt64(
                    kKeyDuration,
                    8000 * (fileSize - mFirstFramePos) / bitrate);
        }
    }
}

MP3Extractor::~MP3Extractor() {
}

size_t MP3Extractor::countTracks() {
    return (mFirstFramePos < 0) ? 0 : 1;
}

sp<MediaSource> MP3Extractor::getTrack(size_t index) {
    if (mFirstFramePos < 0 || index != 0) {
        return NULL;
    }

    return new MP3Source(
            mMeta, mDataSource, mFirstFramePos, mFixedHeader);
}

sp<MetaData> MP3Extractor::getTrackMetaData(size_t index, uint32_t flags) {
    if (mFirstFramePos < 0 || index != 0) {
        return NULL;
    }

    return mMeta;
}

////////////////////////////////////////////////////////////////////////////////

MP3Source::MP3Source(
        const sp<MetaData> &meta, const sp<DataSource> &source,
        off_t first_frame_pos, uint32_t fixed_header)
    : mMeta(meta),
      mDataSource(source),
      mFirstFramePos(first_frame_pos),
      mFixedHeader(fixed_header),
      mCurrentPos(0),
      mCurrentTimeUs(0),
      mStarted(false),
      mGroup(NULL) {
}

MP3Source::~MP3Source() {
    if (mStarted) {
        stop();
    }
}

status_t MP3Source::start(MetaData *) {
    CHECK(!mStarted);

    mGroup = new MediaBufferGroup;

    const size_t kMaxFrameSize = 32768;
    mGroup->add_buffer(new MediaBuffer(kMaxFrameSize));

    mCurrentPos = mFirstFramePos;
    mCurrentTimeUs = 0;

    mStarted = true;

    return OK;
}

status_t MP3Source::stop() {
    CHECK(mStarted);

    delete mGroup;
    mGroup = NULL;

    mStarted = false;

    return OK;
}

sp<MetaData> MP3Source::getFormat() {
    return mMeta;
}

status_t MP3Source::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    if (options != NULL && options->getSeekTo(&seekTimeUs)) {
        int32_t bitrate;
        if (!mMeta->findInt32(kKeyBitRate, &bitrate)) {
            // bitrate is in kbits/sec.
            LOGI("no bitrate");

            return ERROR_UNSUPPORTED;
        }

        mCurrentTimeUs = seekTimeUs;
        mCurrentPos = mFirstFramePos + seekTimeUs * bitrate / 1000000 * 125;
    }

    MediaBuffer *buffer;
    status_t err = mGroup->acquire_buffer(&buffer);
    if (err != OK) {
        return err;
    }

    size_t frame_size;
    for (;;) {
        ssize_t n = mDataSource->read_at(mCurrentPos, buffer->data(), 4);
        if (n < 4) {
            buffer->release();
            buffer = NULL;

            return ERROR_END_OF_STREAM;
        }

        uint32_t header = U32_AT((const uint8_t *)buffer->data());
        
        if (get_mp3_frame_size(header, &frame_size)) {
            break;
        }

        // Lost sync.
        LOGW("lost sync!\n");

        off_t pos = mCurrentPos;
        if (!Resync(mDataSource, mFixedHeader, &pos, NULL)) {
            LOGE("Unable to resync. Signalling end of stream.");

            buffer->release();
            buffer = NULL;

            return ERROR_END_OF_STREAM;
        }

        mCurrentPos = pos;

        // Try again with the new position.
    }

    CHECK(frame_size <= buffer->size());

    ssize_t n = mDataSource->read_at(mCurrentPos, buffer->data(), frame_size);
    if (n < (ssize_t)frame_size) {
        buffer->release();
        buffer = NULL;

        return ERROR_END_OF_STREAM;
    }

    buffer->set_range(0, frame_size);

    buffer->meta_data()->setInt64(kKeyTime, mCurrentTimeUs);

    mCurrentPos += frame_size;
    mCurrentTimeUs += 1152 * 1000000 / 44100;

    *out = buffer;

    return OK;
}

bool SniffMP3(
        const sp<DataSource> &source, String8 *mimeType, float *confidence) {
    off_t pos = 0;
    uint32_t header;
    if (!Resync(source, 0, &pos, &header)) {
        return false;
    }

    *mimeType = MEDIA_MIMETYPE_AUDIO_MPEG;
    *confidence = 0.3f;

    return true;
}

}  // namespace android
