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

#include "include/MP3Extractor.h"

#include "include/ID3.h"

#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
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

// Everything must match except for
// protection, bitrate, padding, private bits, mode extension,
// copyright bit, original bit and emphasis.
// Yes ... there are things that must indeed match...
static const uint32_t kMask = 0xfffe0cc0;

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

static bool parse_xing_header(
        const sp<DataSource> &source, off_t first_frame_pos,
        int32_t *frame_number = NULL, int32_t *byte_number = NULL,
        char *table_of_contents = NULL, int32_t *quality_indicator = NULL,
        int64_t *duration = NULL) {

    if (frame_number) {
        *frame_number = 0;
    }
    if (byte_number) {
        *byte_number = 0;
    }
    if (table_of_contents) {
        table_of_contents[0] = 0;
    }
    if (quality_indicator) {
        *quality_indicator = 0;
    }
    if (duration) {
        *duration = 0;
    }

    uint8_t buffer[4];
    int offset = first_frame_pos;
    if (source->readAt(offset, &buffer, 4) < 4) { // get header
        return false;
    }
    offset += 4;

    uint8_t id, layer, sr_index, mode;
    layer = (buffer[1] >> 1) & 3;
    id = (buffer[1] >> 3) & 3;
    sr_index = (buffer[2] >> 2) & 3;
    mode = (buffer[3] >> 6) & 3;
    if (layer == 0) {
        return false;
    }
    if (id == 1) {
        return false;
    }
    if (sr_index == 3) {
        return false;
    }
    // determine offset of XING header
    if(id&1) { // mpeg1
        if (mode != 3) offset += 32;
        else offset += 17;
    } else { // mpeg2
        if (mode != 3) offset += 17;
        else offset += 9;
    }

    if (source->readAt(offset, &buffer, 4) < 4) { // XING header ID
        return false;
    }
    offset += 4;
    // Check XING ID
    if ((buffer[0] != 'X') || (buffer[1] != 'i')
                || (buffer[2] != 'n') || (buffer[3] != 'g')) {
        if ((buffer[0] != 'I') || (buffer[1] != 'n')
                    || (buffer[2] != 'f') || (buffer[3] != 'o')) {
            return false;
        }
    }

    if (source->readAt(offset, &buffer, 4) < 4) { // flags
        return false;
    }
    offset += 4;
    uint32_t flags = U32_AT(buffer);

    if (flags & 0x0001) {  // Frames field is present
        if (source->readAt(offset, buffer, 4) < 4) {
             return false;
        }
        if (frame_number) {
           *frame_number = U32_AT(buffer);
        }
        int32_t frame = U32_AT(buffer);
        // Samples per Frame: 1. index = MPEG Version ID, 2. index = Layer
        const int samplesPerFrames[2][3] =
        {
            { 384, 1152, 576  }, // MPEG 2, 2.5: layer1, layer2, layer3
            { 384, 1152, 1152 }, // MPEG 1: layer1, layer2, layer3
        };
        // sampling rates in hertz: 1. index = MPEG Version ID, 2. index = sampling rate index
        const int samplingRates[4][3] =
        {
            { 11025, 12000, 8000,  },    // MPEG 2.5
            { 0,     0,     0,     },    // reserved
            { 22050, 24000, 16000, },    // MPEG 2
            { 44100, 48000, 32000, }     // MPEG 1
        };
        if (duration) {
            *duration = (int64_t)frame * samplesPerFrames[id&1][3-layer] * 1000000LL
                / samplingRates[id][sr_index];
        }
        offset += 4;
    }
    if (flags & 0x0002) {  // Bytes field is present
        if (byte_number) {
            if (source->readAt(offset, buffer, 4) < 4) {
                return false;
            }
            *byte_number = U32_AT(buffer);
        }
        offset += 4;
    }
    if (flags & 0x0004) {  // TOC field is present
       if (table_of_contents) {
            if (source->readAt(offset + 1, table_of_contents, 99) < 99) {
                return false;
            }
        }
        offset += 100;
    }
    if (flags & 0x0008) {  // Quality indicator field is present
        if (quality_indicator) {
            if (source->readAt(offset, buffer, 4) < 4) {
                return false;
            }
            *quality_indicator = U32_AT(buffer);
        }
    }
    return true;
}

static bool Resync(
        const sp<DataSource> &source, uint32_t match_header,
        off_t *inout_pos, uint32_t *out_header) {
    if (*inout_pos == 0) {
        // Skip an optional ID3 header if syncing at the very beginning
        // of the datasource.

        for (;;) {
            uint8_t id3header[10];
            if (source->readAt(*inout_pos, id3header, sizeof(id3header))
                    < (ssize_t)sizeof(id3header)) {
                // If we can't even read these 10 bytes, we might as well bail
                // out, even if there _were_ 10 bytes of valid mp3 audio data...
                return false;
            }

            if (memcmp("ID3", id3header, 3)) {
                break;
            }

            // Skip the ID3v2 header.

            size_t len =
                ((id3header[6] & 0x7f) << 21)
                | ((id3header[7] & 0x7f) << 14)
                | ((id3header[8] & 0x7f) << 7)
                | (id3header[9] & 0x7f);

            len += 10;

            *inout_pos += len;

            LOGV("skipped ID3 tag, new starting offset is %ld (0x%08lx)",
                 *inout_pos, *inout_pos);
        }
    }

    off_t pos = *inout_pos;
    bool valid = false;
    do {
        if (pos >= *inout_pos + 128 * 1024) {
            // Don't scan forever.
            LOGV("giving up at offset %ld", pos);
            break;
        }

        uint8_t tmp[4];
        if (source->readAt(pos, tmp, 4) != 4) {
            break;
        }

        uint32_t header = U32_AT(tmp);

        if (match_header != 0 && (header & kMask) != (match_header & kMask)) {
            ++pos;
            continue;
        }

        size_t frame_size;
        int sample_rate, num_channels, bitrate;
        if (!get_mp3_frame_size(header, &frame_size,
                               &sample_rate, &num_channels, &bitrate)) {
            ++pos;
            continue;
        }

        LOGV("found possible 1st frame at %ld (header = 0x%08x)", pos, header);

        // We found what looks like a valid frame,
        // now find its successors.

        off_t test_pos = pos + frame_size;

        valid = true;
        for (int j = 0; j < 3; ++j) {
            uint8_t tmp[4];
            if (source->readAt(test_pos, tmp, 4) < 4) {
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
            *inout_pos = pos;

            if (out_header != NULL) {
                *out_header = header;
            }
        } else {
            LOGV("no dice, no valid sequence of frames found.");
        }

        ++pos;
    } while (!valid);

    return valid;
}

class MP3Source : public MediaSource {
public:
    MP3Source(
            const sp<MetaData> &meta, const sp<DataSource> &source,
            off_t first_frame_pos, uint32_t fixed_header,
            int32_t byte_number, const char *table_of_contents);

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
    int32_t mByteNumber; // total number of bytes in this MP3
    // TOC entries in XING header. Skip the first one since it's always 0.
    char mTableOfContents[99];
    MediaBufferGroup *mGroup;

    MP3Source(const MP3Source &);
    MP3Source &operator=(const MP3Source &);
};

MP3Extractor::MP3Extractor(
        const sp<DataSource> &source, const sp<AMessage> &meta)
    : mInitCheck(NO_INIT),
      mDataSource(source),
      mFirstFramePos(-1),
      mFixedHeader(0),
      mByteNumber(0) {
    off_t pos = 0;
    uint32_t header;
    bool success;

    int64_t meta_offset;
    uint32_t meta_header;
    if (meta != NULL
            && meta->findInt64("offset", &meta_offset)
            && meta->findInt32("header", (int32_t *)&meta_header)) {
        // The sniffer has already done all the hard work for us, simply
        // accept its judgement.
        pos = (off_t)meta_offset;
        header = meta_header;

        success = true;
    } else {
        success = Resync(mDataSource, 0, &pos, &header);
    }

    if (!success) {
        // mInitCheck will remain NO_INIT
        return;
    }

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
    mMeta->setInt32(kKeyBitRate, bitrate * 1000);
    mMeta->setInt32(kKeyChannelCount, num_channels);

    int64_t duration;
    parse_xing_header(
            mDataSource, mFirstFramePos, NULL, &mByteNumber,
            mTableOfContents, NULL, &duration);
    if (duration > 0) {
        mMeta->setInt64(kKeyDuration, duration);
    } else {
        off_t fileSize;
        if (mDataSource->getSize(&fileSize) == OK) {
            mMeta->setInt64(
                    kKeyDuration,
                    8000LL * (fileSize - mFirstFramePos) / bitrate);
        }
    }

    mInitCheck = OK;
}

size_t MP3Extractor::countTracks() {
    return mInitCheck != OK ? 0 : 1;
}

sp<MediaSource> MP3Extractor::getTrack(size_t index) {
    if (mInitCheck != OK || index != 0) {
        return NULL;
    }

    return new MP3Source(
            mMeta, mDataSource, mFirstFramePos, mFixedHeader,
            mByteNumber, mTableOfContents);
}

sp<MetaData> MP3Extractor::getTrackMetaData(size_t index, uint32_t flags) {
    if (mInitCheck != OK || index != 0) {
        return NULL;
    }

    return mMeta;
}

////////////////////////////////////////////////////////////////////////////////

MP3Source::MP3Source(
        const sp<MetaData> &meta, const sp<DataSource> &source,
        off_t first_frame_pos, uint32_t fixed_header,
        int32_t byte_number, const char *table_of_contents)
    : mMeta(meta),
      mDataSource(source),
      mFirstFramePos(first_frame_pos),
      mFixedHeader(fixed_header),
      mCurrentPos(0),
      mCurrentTimeUs(0),
      mStarted(false),
      mByteNumber(byte_number),
      mGroup(NULL) {
    memcpy (mTableOfContents, table_of_contents, sizeof(mTableOfContents));
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
    ReadOptions::SeekMode mode;
    if (options != NULL && options->getSeekTo(&seekTimeUs, &mode)) {
        int32_t bitrate;
        if (!mMeta->findInt32(kKeyBitRate, &bitrate)) {
            // bitrate is in bits/sec.
            LOGI("no bitrate");

            return ERROR_UNSUPPORTED;
        }

        mCurrentTimeUs = seekTimeUs;
        // interpolate in TOC to get file seek point in bytes
        int64_t duration;
        if ((mByteNumber > 0) && (mTableOfContents[0] > 0)
            && mMeta->findInt64(kKeyDuration, &duration)) {
            float percent = (float)seekTimeUs * 100 / duration;
            float fx;
            if( percent <= 0.0f ) {
                fx = 0.0f;
            } else if( percent >= 100.0f ) {
                fx = 256.0f;
            } else {
                int a = (int)percent;
                float fa, fb;
                if ( a == 0 ) {
                    fa = 0.0f;
                } else {
                    fa = (float)mTableOfContents[a-1];
                }
                if ( a < 99 ) {
                    fb = (float)mTableOfContents[a];
                } else {
                    fb = 256.0f;
                }
                fx = fa + (fb-fa)*(percent-a);
            }
            mCurrentPos = mFirstFramePos + (int)((1.0f/256.0f)*fx*mByteNumber);
        } else {
            mCurrentPos = mFirstFramePos + seekTimeUs * bitrate / 8000000;
        }
    }

    MediaBuffer *buffer;
    status_t err = mGroup->acquire_buffer(&buffer);
    if (err != OK) {
        return err;
    }

    size_t frame_size;
    int bitrate;
    for (;;) {
        ssize_t n = mDataSource->readAt(mCurrentPos, buffer->data(), 4);
        if (n < 4) {
            buffer->release();
            buffer = NULL;

            return ERROR_END_OF_STREAM;
        }

        uint32_t header = U32_AT((const uint8_t *)buffer->data());

        if ((header & kMask) == (mFixedHeader & kMask)
            && get_mp3_frame_size(header, &frame_size, NULL, NULL, &bitrate)) {
            break;
        }

        // Lost sync.
        LOGV("lost sync! header = 0x%08x, old header = 0x%08x\n", header, mFixedHeader);

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

    ssize_t n = mDataSource->readAt(mCurrentPos, buffer->data(), frame_size);
    if (n < (ssize_t)frame_size) {
        buffer->release();
        buffer = NULL;

        return ERROR_END_OF_STREAM;
    }

    buffer->set_range(0, frame_size);

    buffer->meta_data()->setInt64(kKeyTime, mCurrentTimeUs);
    buffer->meta_data()->setInt32(kKeyIsSyncFrame, 1);

    mCurrentPos += frame_size;
    mCurrentTimeUs += frame_size * 8000ll / bitrate;

    *out = buffer;

    return OK;
}

sp<MetaData> MP3Extractor::getMetaData() {
    sp<MetaData> meta = new MetaData;

    if (mInitCheck != OK) {
        return meta;
    }

    meta->setCString(kKeyMIMEType, "audio/mpeg");

    ID3 id3(mDataSource);

    if (!id3.isValid()) {
        return meta;
    }

    struct Map {
        int key;
        const char *tag1;
        const char *tag2;
    };
    static const Map kMap[] = {
        { kKeyAlbum, "TALB", "TAL" },
        { kKeyArtist, "TPE1", "TP1" },
        { kKeyAlbumArtist, "TPE2", "TP2" },
        { kKeyComposer, "TCOM", "TCM" },
        { kKeyGenre, "TCON", "TCO" },
        { kKeyTitle, "TIT2", "TT2" },
        { kKeyYear, "TYE", "TYER" },
        { kKeyAuthor, "TXT", "TEXT" },
        { kKeyCDTrackNumber, "TRK", "TRCK" },
        { kKeyDiscNumber, "TPA", "TPOS" },
        { kKeyCompilation, "TCP", "TCMP" },
    };
    static const size_t kNumMapEntries = sizeof(kMap) / sizeof(kMap[0]);

    for (size_t i = 0; i < kNumMapEntries; ++i) {
        ID3::Iterator *it = new ID3::Iterator(id3, kMap[i].tag1);
        if (it->done()) {
            delete it;
            it = new ID3::Iterator(id3, kMap[i].tag2);
        }

        if (it->done()) {
            delete it;
            continue;
        }

        String8 s;
        it->getString(&s);
        delete it;

        meta->setCString(kMap[i].key, s);
    }

    size_t dataSize;
    String8 mime;
    const void *data = id3.getAlbumArt(&dataSize, &mime);

    if (data) {
        meta->setData(kKeyAlbumArt, MetaData::TYPE_NONE, data, dataSize);
        meta->setCString(kKeyAlbumArtMIME, mime.string());
    }

    return meta;
}

bool SniffMP3(
        const sp<DataSource> &source, String8 *mimeType,
        float *confidence, sp<AMessage> *meta) {
    off_t pos = 0;
    uint32_t header;
    if (!Resync(source, 0, &pos, &header)) {
        return false;
    }

    *meta = new AMessage;
    (*meta)->setInt64("offset", pos);
    (*meta)->setInt32("header", header);

    *mimeType = MEDIA_MIMETYPE_AUDIO_MPEG;
    *confidence = 0.2f;

    return true;
}

}  // namespace android
