/*
 * Copyright 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "NuMediaExtractor"
#include <utils/Log.h>

#include <media/stagefright/NuMediaExtractor.h>

#include "include/ESDS.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>

namespace android {

NuMediaExtractor::NuMediaExtractor() {
}

NuMediaExtractor::~NuMediaExtractor() {
    releaseTrackSamples();

    for (size_t i = 0; i < mSelectedTracks.size(); ++i) {
        TrackInfo *info = &mSelectedTracks.editItemAt(i);

        CHECK_EQ((status_t)OK, info->mSource->stop());
    }

    mSelectedTracks.clear();
}

status_t NuMediaExtractor::setDataSource(const char *path) {
    sp<DataSource> dataSource = DataSource::CreateFromURI(path);

    if (dataSource == NULL) {
        return -ENOENT;
    }

    mImpl = MediaExtractor::Create(dataSource);

    if (mImpl == NULL) {
        return ERROR_UNSUPPORTED;
    }

    return OK;
}

size_t NuMediaExtractor::countTracks() const {
    return mImpl == NULL ? 0 : mImpl->countTracks();
}

status_t NuMediaExtractor::getTrackFormat(
        size_t index, sp<AMessage> *format) const {
    *format = NULL;

    if (mImpl == NULL) {
        return -EINVAL;
    }

    if (index >= mImpl->countTracks()) {
        return -ERANGE;
    }

    sp<MetaData> meta = mImpl->getTrackMetaData(index);

    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));

    sp<AMessage> msg = new AMessage;
    msg->setString("mime", mime);

    if (!strncasecmp("video/", mime, 6)) {
        int32_t width, height;
        CHECK(meta->findInt32(kKeyWidth, &width));
        CHECK(meta->findInt32(kKeyHeight, &height));

        msg->setInt32("width", width);
        msg->setInt32("height", height);
    } else {
        CHECK(!strncasecmp("audio/", mime, 6));

        int32_t numChannels, sampleRate;
        CHECK(meta->findInt32(kKeyChannelCount, &numChannels));
        CHECK(meta->findInt32(kKeySampleRate, &sampleRate));

        msg->setInt32("channel-count", numChannels);
        msg->setInt32("sample-rate", sampleRate);

        int32_t isADTS;
        if (meta->findInt32(kKeyIsADTS, &isADTS)) {
            msg->setInt32("is-adts", true);
        }
    }

    int32_t maxInputSize;
    if (meta->findInt32(kKeyMaxInputSize, &maxInputSize)) {
        msg->setInt32("max-input-size", maxInputSize);
    }

    uint32_t type;
    const void *data;
    size_t size;
    if (meta->findData(kKeyAVCC, &type, &data, &size)) {
        // Parse the AVCDecoderConfigurationRecord

        const uint8_t *ptr = (const uint8_t *)data;

        CHECK(size >= 7);
        CHECK_EQ((unsigned)ptr[0], 1u);  // configurationVersion == 1
        uint8_t profile = ptr[1];
        uint8_t level = ptr[3];

        // There is decodable content out there that fails the following
        // assertion, let's be lenient for now...
        // CHECK((ptr[4] >> 2) == 0x3f);  // reserved

        size_t lengthSize = 1 + (ptr[4] & 3);

        // commented out check below as H264_QVGA_500_NO_AUDIO.3gp
        // violates it...
        // CHECK((ptr[5] >> 5) == 7);  // reserved

        size_t numSeqParameterSets = ptr[5] & 31;

        ptr += 6;
        size -= 6;

        sp<ABuffer> buffer = new ABuffer(1024);
        buffer->setRange(0, 0);

        for (size_t i = 0; i < numSeqParameterSets; ++i) {
            CHECK(size >= 2);
            size_t length = U16_AT(ptr);

            ptr += 2;
            size -= 2;

            CHECK(size >= length);

            memcpy(buffer->data() + buffer->size(), "\x00\x00\x00\x01", 4);
            memcpy(buffer->data() + buffer->size() + 4, ptr, length);
            buffer->setRange(0, buffer->size() + 4 + length);

            ptr += length;
            size -= length;
        }

        buffer->meta()->setInt32("csd", true);
        buffer->meta()->setInt64("timeUs", 0);

        msg->setBuffer("csd-0", buffer);

        buffer = new ABuffer(1024);
        buffer->setRange(0, 0);

        CHECK(size >= 1);
        size_t numPictureParameterSets = *ptr;
        ++ptr;
        --size;

        for (size_t i = 0; i < numPictureParameterSets; ++i) {
            CHECK(size >= 2);
            size_t length = U16_AT(ptr);

            ptr += 2;
            size -= 2;

            CHECK(size >= length);

            memcpy(buffer->data() + buffer->size(), "\x00\x00\x00\x01", 4);
            memcpy(buffer->data() + buffer->size() + 4, ptr, length);
            buffer->setRange(0, buffer->size() + 4 + length);

            ptr += length;
            size -= length;
        }

        buffer->meta()->setInt32("csd", true);
        buffer->meta()->setInt64("timeUs", 0);
        msg->setBuffer("csd-1", buffer);
    } else if (meta->findData(kKeyESDS, &type, &data, &size)) {
        ESDS esds((const char *)data, size);
        CHECK_EQ(esds.InitCheck(), (status_t)OK);

        const void *codec_specific_data;
        size_t codec_specific_data_size;
        esds.getCodecSpecificInfo(
                &codec_specific_data, &codec_specific_data_size);

        sp<ABuffer> buffer = new ABuffer(codec_specific_data_size);

        memcpy(buffer->data(), codec_specific_data,
               codec_specific_data_size);

        buffer->meta()->setInt32("csd", true);
        buffer->meta()->setInt64("timeUs", 0);
        msg->setBuffer("csd-0", buffer);
    } else if (meta->findData(kKeyVorbisInfo, &type, &data, &size)) {
        sp<ABuffer> buffer = new ABuffer(size);
        memcpy(buffer->data(), data, size);

        buffer->meta()->setInt32("csd", true);
        buffer->meta()->setInt64("timeUs", 0);
        msg->setBuffer("csd-0", buffer);

        if (!meta->findData(kKeyVorbisBooks, &type, &data, &size)) {
            return -EINVAL;
        }

        buffer = new ABuffer(size);
        memcpy(buffer->data(), data, size);

        buffer->meta()->setInt32("csd", true);
        buffer->meta()->setInt64("timeUs", 0);
        msg->setBuffer("csd-1", buffer);
    }

    if (meta->findData(kKeyEMM, &type, &data, &size)) {
        sp<ABuffer> emm = new ABuffer(size);
        memcpy(emm->data(), data, size);

        msg->setBuffer("emm", emm);
    }

    if (meta->findData(kKeyECM, &type, &data, &size)) {
        sp<ABuffer> ecm = new ABuffer(size);
        memcpy(ecm->data(), data, size);

        msg->setBuffer("ecm", ecm);
    }

    *format = msg;

    return OK;
}

status_t NuMediaExtractor::selectTrack(size_t index) {
    if (mImpl == NULL) {
        return -EINVAL;
    }

    if (index >= mImpl->countTracks()) {
        return -ERANGE;
    }

    for (size_t i = 0; i < mSelectedTracks.size(); ++i) {
        TrackInfo *info = &mSelectedTracks.editItemAt(i);

        if (info->mTrackIndex == index) {
            // This track has already been selected.
            return OK;
        }
    }

    sp<MediaSource> source = mImpl->getTrack(index);

    CHECK_EQ((status_t)OK, source->start());

    mSelectedTracks.push();
    TrackInfo *info = &mSelectedTracks.editItemAt(mSelectedTracks.size() - 1);

    info->mSource = source;
    info->mTrackIndex = index;
    info->mFinalResult = OK;
    info->mSample = NULL;
    info->mSampleTimeUs = -1ll;
    info->mSampleFlags = 0;
    info->mTrackFlags = 0;

    const char *mime;
    CHECK(source->getFormat()->findCString(kKeyMIMEType, &mime));

    if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_VORBIS)) {
        info->mTrackFlags |= kIsVorbis;
    }

    return OK;
}

void NuMediaExtractor::releaseTrackSamples() {
    for (size_t i = 0; i < mSelectedTracks.size(); ++i) {
        TrackInfo *info = &mSelectedTracks.editItemAt(i);

        if (info->mSample != NULL) {
            info->mSample->release();
            info->mSample = NULL;

            info->mSampleTimeUs = -1ll;
            info->mSampleFlags = 0;
        }
    }
}

ssize_t NuMediaExtractor::fetchTrackSamples(int64_t seekTimeUs) {
    TrackInfo *minInfo = NULL;
    ssize_t minIndex = -1;

    for (size_t i = 0; i < mSelectedTracks.size(); ++i) {
        TrackInfo *info = &mSelectedTracks.editItemAt(i);

        if (seekTimeUs >= 0ll) {
            info->mFinalResult = OK;

            if (info->mSample != NULL) {
                info->mSample->release();
                info->mSample = NULL;
                info->mSampleTimeUs = -1ll;
                info->mSampleFlags = 0;
            }
        } else if (info->mFinalResult != OK) {
            continue;
        }

        if (info->mSample == NULL) {
            MediaSource::ReadOptions options;
            if (seekTimeUs >= 0ll) {
                options.setSeekTo(seekTimeUs);
            }
            status_t err = info->mSource->read(&info->mSample, &options);

            if (err != OK) {
                CHECK(info->mSample == NULL);

                info->mFinalResult = err;
                info->mSampleTimeUs = -1ll;
                info->mSampleFlags = 0;
                continue;
            } else {
                CHECK(info->mSample != NULL);
                CHECK(info->mSample->meta_data()->findInt64(
                            kKeyTime, &info->mSampleTimeUs));

                info->mSampleFlags = 0;

                int32_t val;
                if (info->mSample->meta_data()->findInt32(
                            kKeyIsSyncFrame, &val) && val != 0) {
                    info->mSampleFlags |= SAMPLE_FLAG_SYNC;
                }

                if (info->mSample->meta_data()->findInt32(
                            kKeyScrambling, &val) && val != 0) {
                    info->mSampleFlags |= SAMPLE_FLAG_ENCRYPTED;
                }
            }
        }

        if (minInfo == NULL  || info->mSampleTimeUs < minInfo->mSampleTimeUs) {
            minInfo = info;
            minIndex = i;
        }
    }

    return minIndex;
}

status_t NuMediaExtractor::seekTo(int64_t timeUs) {
    return fetchTrackSamples(timeUs);
}

status_t NuMediaExtractor::advance() {
    ssize_t minIndex = fetchTrackSamples();

    if (minIndex < 0) {
        return ERROR_END_OF_STREAM;
    }

    TrackInfo *info = &mSelectedTracks.editItemAt(minIndex);

    info->mSample->release();
    info->mSample = NULL;
    info->mSampleTimeUs = -1ll;

    return OK;
}

status_t NuMediaExtractor::readSampleData(const sp<ABuffer> &buffer) {
    ssize_t minIndex = fetchTrackSamples();

    if (minIndex < 0) {
        return ERROR_END_OF_STREAM;
    }

    TrackInfo *info = &mSelectedTracks.editItemAt(minIndex);

    size_t sampleSize = info->mSample->range_length();

    if (info->mTrackFlags & kIsVorbis) {
        // Each sample's data is suffixed by the number of page samples
        // or -1 if not available.
        sampleSize += sizeof(int32_t);
    }

    if (buffer->capacity() < sampleSize) {
        return -ENOMEM;
    }

    const uint8_t *src =
        (const uint8_t *)info->mSample->data()
            + info->mSample->range_offset();

    memcpy((uint8_t *)buffer->data(), src, info->mSample->range_length());

    if (info->mTrackFlags & kIsVorbis) {
        int32_t numPageSamples;
        if (!info->mSample->meta_data()->findInt32(
                    kKeyValidSamples, &numPageSamples)) {
            numPageSamples = -1;
        }

        memcpy((uint8_t *)buffer->data() + info->mSample->range_length(),
               &numPageSamples,
               sizeof(numPageSamples));
    }

    buffer->setRange(0, sampleSize);

    return OK;
}

status_t NuMediaExtractor::getSampleTrackIndex(size_t *trackIndex) {
    ssize_t minIndex = fetchTrackSamples();

    if (minIndex < 0) {
        return ERROR_END_OF_STREAM;
    }

    TrackInfo *info = &mSelectedTracks.editItemAt(minIndex);
    *trackIndex = info->mTrackIndex;

    return OK;
}

status_t NuMediaExtractor::getSampleTime(int64_t *sampleTimeUs) {
    ssize_t minIndex = fetchTrackSamples();

    if (minIndex < 0) {
        return ERROR_END_OF_STREAM;
    }

    TrackInfo *info = &mSelectedTracks.editItemAt(minIndex);
    *sampleTimeUs = info->mSampleTimeUs;

    return OK;
}

status_t NuMediaExtractor::getSampleFlags(uint32_t *sampleFlags) {
    ssize_t minIndex = fetchTrackSamples();

    if (minIndex < 0) {
        return ERROR_END_OF_STREAM;
    }

    TrackInfo *info = &mSelectedTracks.editItemAt(minIndex);
    *sampleFlags = info->mSampleFlags;

    return OK;
}

}  // namespace android
