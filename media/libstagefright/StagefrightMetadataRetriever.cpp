/*
**
** Copyright 2009, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

//#define LOG_NDEBUG 0
#define LOG_TAG "StagefrightMetadataRetriever"
#include <utils/Log.h>

#include "include/StagefrightMetadataRetriever.h"

#include <media/stagefright/CachingDataSource.h>
#include <media/stagefright/ColorConverter.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/FileSource.h>
#include <media/stagefright/HTTPDataSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXCodec.h>

namespace android {

StagefrightMetadataRetriever::StagefrightMetadataRetriever() {
    LOGV("StagefrightMetadataRetriever()");

    DataSource::RegisterDefaultSniffers();
    CHECK_EQ(mClient.connect(), OK);
}

StagefrightMetadataRetriever::~StagefrightMetadataRetriever() {
    LOGV("~StagefrightMetadataRetriever()");
    mClient.disconnect();
}

status_t StagefrightMetadataRetriever::setDataSource(const char *uri) {
    LOGV("setDataSource(%s)", uri);

    mExtractor = MediaExtractor::CreateFromURI(uri);

    return mExtractor.get() != NULL ? OK : UNKNOWN_ERROR;
}

status_t StagefrightMetadataRetriever::setDataSource(
        int fd, int64_t offset, int64_t length) {
    LOGV("setDataSource(%d, %lld, %lld)", fd, offset, length);

    mExtractor = MediaExtractor::Create(
            new FileSource(fd, offset, length));

    return OK;
}

VideoFrame *StagefrightMetadataRetriever::captureFrame() {
    LOGV("captureFrame");

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

    sp<MetaData> meta = source->getFormat();

    sp<MediaSource> decoder =
        OMXCodec::Create(
                mClient.interface(), meta, false, source,
                NULL, OMXCodec::kPreferSoftwareCodecs);

    if (decoder.get() == NULL) {
        LOGV("unable to instantiate video decoder.");

        return NULL;
    }

    decoder->start();

    // Read one output buffer, ignore format change notifications
    // and spurious empty buffers.

    MediaSource::ReadOptions options;
    int64_t thumbNailTime;
    if (trackMeta->findInt64(kKeyThumbnailTime, &thumbNailTime)) {
        options.setSeekTo(thumbNailTime);
    }

    MediaBuffer *buffer = NULL;
    status_t err;
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

    meta = decoder->getFormat();

    int32_t width, height;
    CHECK(meta->findInt32(kKeyWidth, &width));
    CHECK(meta->findInt32(kKeyHeight, &height));

    VideoFrame *frame = new VideoFrame;
    frame->mWidth = width;
    frame->mHeight = height;
    frame->mDisplayWidth = width;
    frame->mDisplayHeight = height;
    frame->mSize = width * height * 2;
    frame->mData = new uint8_t[frame->mSize];

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

MediaAlbumArt *StagefrightMetadataRetriever::extractAlbumArt() {
    LOGV("extractAlbumArt (extractor: %s)", mExtractor.get() != NULL ? "YES" : "NO");

    return NULL;
}

const char *StagefrightMetadataRetriever::extractMetadata(int keyCode) {
    LOGV("extractMetadata %d (extractor: %s)",
         keyCode, mExtractor.get() != NULL ? "YES" : "NO");

    return NULL;
}

}  // namespace android
