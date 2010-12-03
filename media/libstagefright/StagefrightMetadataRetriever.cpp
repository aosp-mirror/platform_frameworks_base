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
#define LOG_TAG "StagefrightMetadataRetriever"
#include <utils/Log.h>

#include "include/StagefrightMetadataRetriever.h"

#include <media/stagefright/ColorConverter.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXCodec.h>

namespace android {

StagefrightMetadataRetriever::StagefrightMetadataRetriever()
    : mParsedMetaData(false),
      mAlbumArt(NULL) {
    LOGV("StagefrightMetadataRetriever()");

    DataSource::RegisterDefaultSniffers();
    CHECK_EQ(mClient.connect(), OK);
}

StagefrightMetadataRetriever::~StagefrightMetadataRetriever() {
    LOGV("~StagefrightMetadataRetriever()");

    delete mAlbumArt;
    mAlbumArt = NULL;

    mClient.disconnect();
}

status_t StagefrightMetadataRetriever::setDataSource(const char *uri) {
    LOGV("setDataSource(%s)", uri);

    mParsedMetaData = false;
    mMetaData.clear();
    delete mAlbumArt;
    mAlbumArt = NULL;

    mSource = DataSource::CreateFromURI(uri);

    if (mSource == NULL) {
        return UNKNOWN_ERROR;
    }

    mExtractor = MediaExtractor::Create(mSource);

    if (mExtractor == NULL) {
        mSource.clear();

        return UNKNOWN_ERROR;
    }

    return OK;
}

// Warning caller retains ownership of the filedescriptor! Dup it if necessary.
status_t StagefrightMetadataRetriever::setDataSource(
        int fd, int64_t offset, int64_t length) {
    fd = dup(fd);

    LOGV("setDataSource(%d, %lld, %lld)", fd, offset, length);

    mParsedMetaData = false;
    mMetaData.clear();
    delete mAlbumArt;
    mAlbumArt = NULL;

    mSource = new FileSource(fd, offset, length);

    status_t err;
    if ((err = mSource->initCheck()) != OK) {
        mSource.clear();

        return err;
    }

    mExtractor = MediaExtractor::Create(mSource);

    if (mExtractor == NULL) {
        mSource.clear();

        return UNKNOWN_ERROR;
    }

    return OK;
}

static VideoFrame *extractVideoFrameWithCodecFlags(
        OMXClient *client,
        const sp<MetaData> &trackMeta,
        const sp<MediaSource> &source,
        uint32_t flags,
        int64_t frameTimeUs,
        int seekMode) {
    sp<MediaSource> decoder =
        OMXCodec::Create(
                client->interface(), source->getFormat(), false, source,
                NULL, flags | OMXCodec::kClientNeedsFramebuffer);

    if (decoder.get() == NULL) {
        LOGV("unable to instantiate video decoder.");

        return NULL;
    }

    status_t err = decoder->start();
    if (err != OK) {
        LOGW("OMXCodec::start returned error %d (0x%08x)\n", err, err);
        return NULL;
    }

    // Read one output buffer, ignore format change notifications
    // and spurious empty buffers.

    MediaSource::ReadOptions options;
    if (seekMode < MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC ||
        seekMode > MediaSource::ReadOptions::SEEK_CLOSEST) {

        LOGE("Unknown seek mode: %d", seekMode);
        return NULL;
    }

    MediaSource::ReadOptions::SeekMode mode =
            static_cast<MediaSource::ReadOptions::SeekMode>(seekMode);

    int64_t thumbNailTime;
    if (frameTimeUs < 0 && trackMeta->findInt64(kKeyThumbnailTime, &thumbNailTime)) {
        options.setSeekTo(thumbNailTime, mode);
    } else {
        thumbNailTime = -1;
        options.setSeekTo(frameTimeUs, mode);
    }

    MediaBuffer *buffer = NULL;
    do {
        if (buffer != NULL) {
            buffer->release();
            buffer = NULL;
        }
        err = decoder->read(&buffer, &options);
        options.clearSeekTo();
    } while (err == INFO_FORMAT_CHANGED
             || (buffer != NULL && buffer->range_length() == 0));

    if (err != OK) {
        CHECK_EQ(buffer, NULL);

        LOGV("decoding frame failed.");
        decoder->stop();

        return NULL;
    }

    LOGV("successfully decoded video frame.");

    int32_t unreadable;
    if (buffer->meta_data()->findInt32(kKeyIsUnreadable, &unreadable)
            && unreadable != 0) {
        LOGV("video frame is unreadable, decoder does not give us access "
             "to the video data.");

        buffer->release();
        buffer = NULL;

        decoder->stop();

        return NULL;
    }

    int64_t timeUs;
    CHECK(buffer->meta_data()->findInt64(kKeyTime, &timeUs));
    if (thumbNailTime >= 0) {
        if (timeUs != thumbNailTime) {
            const char *mime;
            CHECK(trackMeta->findCString(kKeyMIMEType, &mime));

            LOGV("thumbNailTime = %lld us, timeUs = %lld us, mime = %s",
                 thumbNailTime, timeUs, mime);
        }
    }

    sp<MetaData> meta = decoder->getFormat();

    int32_t width, height;
    CHECK(meta->findInt32(kKeyWidth, &width));
    CHECK(meta->findInt32(kKeyHeight, &height));

    int32_t rotationAngle;
    if (!trackMeta->findInt32(kKeyRotation, &rotationAngle)) {
        rotationAngle = 0;  // By default, no rotation
    }

    VideoFrame *frame = new VideoFrame;
    frame->mWidth = width;
    frame->mHeight = height;
    frame->mDisplayWidth = width;
    frame->mDisplayHeight = height;
    frame->mSize = width * height * 2;
    frame->mData = new uint8_t[frame->mSize];
    frame->mRotationAngle = rotationAngle;

    int32_t srcFormat;
    CHECK(meta->findInt32(kKeyColorFormat, &srcFormat));

    ColorConverter converter(
            (OMX_COLOR_FORMATTYPE)srcFormat, OMX_COLOR_Format16bitRGB565);
    CHECK(converter.isValid());

    converter.convert(
            width, height,
            (const uint8_t *)buffer->data() + buffer->range_offset(),
            0,
            frame->mData, width * 2);

    buffer->release();
    buffer = NULL;

    decoder->stop();

    return frame;
}

VideoFrame *StagefrightMetadataRetriever::getFrameAtTime(
        int64_t timeUs, int option) {

    LOGV("getFrameAtTime: %lld us option: %d", timeUs, option);

    if (mExtractor.get() == NULL) {
        LOGV("no extractor.");
        return NULL;
    }

    size_t n = mExtractor->countTracks();
    size_t i;
    for (i = 0; i < n; ++i) {
        sp<MetaData> meta = mExtractor->getTrackMetaData(i);

        const char *mime;
        CHECK(meta->findCString(kKeyMIMEType, &mime));

        if (!strncasecmp(mime, "video/", 6)) {
            break;
        }
    }

    if (i == n) {
        LOGV("no video track found.");
        return NULL;
    }

    sp<MetaData> trackMeta = mExtractor->getTrackMetaData(
            i, MediaExtractor::kIncludeExtensiveMetaData);

    sp<MediaSource> source = mExtractor->getTrack(i);

    if (source.get() == NULL) {
        LOGV("unable to instantiate video track.");
        return NULL;
    }

    VideoFrame *frame =
        extractVideoFrameWithCodecFlags(
                &mClient, trackMeta, source, OMXCodec::kPreferSoftwareCodecs,
                timeUs, option);

    if (frame == NULL) {
        LOGV("Software decoder failed to extract thumbnail, "
             "trying hardware decoder.");

        frame = extractVideoFrameWithCodecFlags(&mClient, trackMeta, source, 0,
                        timeUs, option);
    }

    return frame;
}

MediaAlbumArt *StagefrightMetadataRetriever::extractAlbumArt() {
    LOGV("extractAlbumArt (extractor: %s)", mExtractor.get() != NULL ? "YES" : "NO");

    if (mExtractor == NULL) {
        return NULL;
    }

    if (!mParsedMetaData) {
        parseMetaData();

        mParsedMetaData = true;
    }

    if (mAlbumArt) {
        return new MediaAlbumArt(*mAlbumArt);
    }

    return NULL;
}

const char *StagefrightMetadataRetriever::extractMetadata(int keyCode) {
    if (mExtractor == NULL) {
        return NULL;
    }

    if (!mParsedMetaData) {
        parseMetaData();

        mParsedMetaData = true;
    }

    ssize_t index = mMetaData.indexOfKey(keyCode);

    if (index < 0) {
        return NULL;
    }

    return strdup(mMetaData.valueAt(index).string());
}

void StagefrightMetadataRetriever::parseMetaData() {
    sp<MetaData> meta = mExtractor->getMetaData();

    struct Map {
        int from;
        int to;
    };
    static const Map kMap[] = {
        { kKeyMIMEType, METADATA_KEY_MIMETYPE },
        { kKeyCDTrackNumber, METADATA_KEY_CD_TRACK_NUMBER },
        { kKeyDiscNumber, METADATA_KEY_DISC_NUMBER },
        { kKeyAlbum, METADATA_KEY_ALBUM },
        { kKeyArtist, METADATA_KEY_ARTIST },
        { kKeyAlbumArtist, METADATA_KEY_ALBUMARTIST },
        { kKeyAuthor, METADATA_KEY_AUTHOR },
        { kKeyComposer, METADATA_KEY_COMPOSER },
        { kKeyDate, METADATA_KEY_DATE },
        { kKeyGenre, METADATA_KEY_GENRE },
        { kKeyTitle, METADATA_KEY_TITLE },
        { kKeyYear, METADATA_KEY_YEAR },
        { kKeyWriter, METADATA_KEY_WRITER },
        { kKeyCompilation, METADATA_KEY_COMPILATION },
    };
    static const size_t kNumMapEntries = sizeof(kMap) / sizeof(kMap[0]);

    for (size_t i = 0; i < kNumMapEntries; ++i) {
        const char *value;
        if (meta->findCString(kMap[i].from, &value)) {
            mMetaData.add(kMap[i].to, String8(value));
        }
    }

    const void *data;
    uint32_t type;
    size_t dataSize;
    if (meta->findData(kKeyAlbumArt, &type, &data, &dataSize)) {
        mAlbumArt = new MediaAlbumArt;
        mAlbumArt->mSize = dataSize;
        mAlbumArt->mData = new uint8_t[dataSize];
        memcpy(mAlbumArt->mData, data, dataSize);
    }

    size_t numTracks = mExtractor->countTracks();

    char tmp[32];
    sprintf(tmp, "%d", numTracks);

    mMetaData.add(METADATA_KEY_NUM_TRACKS, String8(tmp));

    // The overall duration is the duration of the longest track.
    int64_t maxDurationUs = 0;
    for (size_t i = 0; i < numTracks; ++i) {
        sp<MetaData> trackMeta = mExtractor->getTrackMetaData(i);

        int64_t durationUs;
        if (trackMeta->findInt64(kKeyDuration, &durationUs)) {
            if (durationUs > maxDurationUs) {
                maxDurationUs = durationUs;
            }
        }
    }

    // The duration value is a string representing the duration in ms.
    sprintf(tmp, "%lld", (maxDurationUs + 500) / 1000);
    mMetaData.add(METADATA_KEY_DURATION, String8(tmp));

    if (numTracks == 1) {
        const char *fileMIME;
        CHECK(meta->findCString(kKeyMIMEType, &fileMIME));

        if (!strcasecmp(fileMIME, "video/x-matroska")) {
            sp<MetaData> trackMeta = mExtractor->getTrackMetaData(0);
            const char *trackMIME;
            CHECK(trackMeta->findCString(kKeyMIMEType, &trackMIME));

            if (!strncasecmp("audio/", trackMIME, 6)) {
                // The matroska file only contains a single audio track,
                // rewrite its mime type.
                mMetaData.add(
                        METADATA_KEY_MIMETYPE, String8("audio/x-matroska"));
            }
        }
    }
}


}  // namespace android
