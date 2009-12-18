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
#define LOG_NDEBUG 0
#define LOG_TAG "M4vH263Decoder"

#include "ESDS.h"
#include "M4vH263Decoder.h"

#include "mp4dec_api.h"

#include <OMX_Component.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>

namespace android {

M4vH263Decoder::M4vH263Decoder(const sp<MediaSource> &source)
    : mSource(source),
      mStarted(false),
      mHandle(new tagvideoDecControls),
      mInputBuffer(NULL),
      mNumSamplesOutput(0) {
    memset(mHandle, 0, sizeof(tagvideoDecControls));

    mFormat = new MetaData;
    mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);
    CHECK(mSource->getFormat()->findInt32(kKeyWidth, &mWidth));
    CHECK(mSource->getFormat()->findInt32(kKeyHeight, &mHeight));
    mFormat->setInt32(kKeyWidth, mWidth);
    mFormat->setInt32(kKeyHeight, mHeight);
    mFormat->setInt32(kKeyColorFormat, OMX_COLOR_FormatYUV420Planar);
    mFormat->setCString(kKeyDecoderComponent, "M4vH263Decoder");

}

M4vH263Decoder::~M4vH263Decoder() {
    if (mStarted) {
        stop();
    }

    delete mHandle;
    mHandle = NULL;
}

status_t M4vH263Decoder::start(MetaData *) {
    CHECK(!mStarted);

    const char *mime = NULL;
    CHECK(mSource->getFormat()->findCString(kKeyMIMEType, &mime));

    MP4DecodingMode mode;
    if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) {
        mode = MPEG4_MODE;
    } else {
        CHECK(!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime));
        mode = H263_MODE;
    }

    uint32_t type;
    const void *data = NULL;
    size_t size = 0;
    uint8_t *vol_data = NULL;
    int32_t vol_size = 0;
    if (mSource->getFormat()->findData(kKeyESDS, &type, &data, &size)) {
        ESDS esds((const uint8_t *)data, size);
        CHECK_EQ(esds.InitCheck(), OK);

        const void *codec_specific_data;
        size_t codec_specific_data_size;
        esds.getCodecSpecificInfo(&codec_specific_data, &codec_specific_data_size);

        vol_data = (uint8_t *) codec_specific_data;
        vol_size = codec_specific_data_size;
    } else {
        vol_data = NULL;
        vol_size = 0;
    }
    CHECK_EQ(PV_TRUE, PVInitVideoDecoder(
            mHandle, &vol_data, &vol_size, 1, mWidth, mHeight, mode));
    MP4DecodingMode actualMode = PVGetDecBitstreamMode(mHandle);
    CHECK_EQ(mode, actualMode);

    PVSetPostProcType((VideoDecControls *) mHandle, 0);
    size_t frameSize = (((mWidth + 15) & - 16) * ((mHeight + 15) & - 16) * 3) / 2;
    for (uint32_t i = 0; i < 2; ++i) {
        mFrames[i] = new MediaBuffer(frameSize);
        mFrames[i]->setObserver(this);
    }
    PVSetReferenceYUV(mHandle, (uint8_t *)mFrames[1]->data());

    mSource->start();

    mNumSamplesOutput = 0;
    mStarted = true;

    return OK;
}

status_t M4vH263Decoder::stop() {
    CHECK(mStarted);

    if (mInputBuffer) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    mSource->stop();

    releaseFrames();

    mStarted = false;
    return (PVCleanUpVideoDecoder(mHandle) == PV_TRUE)? OK: UNKNOWN_ERROR;
}

sp<MetaData> M4vH263Decoder::getFormat() {
    return mFormat;
}

status_t M4vH263Decoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    if (options && options->getSeekTo(&seekTimeUs)) {
        CHECK_EQ(PVResetVideoDecoder(mHandle), PV_TRUE);
    }

    MediaBuffer *inputBuffer = NULL;
    status_t err = mSource->read(&inputBuffer, options);
    if (err != OK) {
        return err;
    }

    uint8_t *bitstream = (uint8_t *) inputBuffer->data() + inputBuffer->range_offset();
    uint32_t timestamp = 0xFFFFFFFF;
    int32_t bufferSize = inputBuffer->range_length();
    uint32_t useExtTimestamp = 0;
    CHECK_EQ(PV_TRUE, PVDecodeVideoFrame(mHandle, &bitstream, &timestamp, &bufferSize,
            &useExtTimestamp,  (uint8_t *)mFrames[mNumSamplesOutput & 0x01]->data()));

    // Check whether video dimension is changed.
    // If so, notify the client about the change.
    int32_t width, height;
    PVGetVideoDimensions(mHandle, &width, &height);
    if (mWidth != width || mHeight != height) {
        mFormat->setInt32(kKeyWidth, width);
        mFormat->setInt32(kKeyHeight, height);
        return INFO_FORMAT_CHANGED;
    }

    PVSetReferenceYUV(mHandle, (uint8_t *)mFrames[mNumSamplesOutput & 0x01]->data());
    *out = mFrames[mNumSamplesOutput & 0x01];
    (*out)->add_ref();

    int64_t timeUs;
    CHECK(inputBuffer->meta_data()->findInt64(kKeyTime, &timeUs));
    (*out)->meta_data()->setInt64(kKeyTime, timeUs);

    ++mNumSamplesOutput;
    inputBuffer->release();

    return OK;
}

void M4vH263Decoder::releaseFrames() {
    for (size_t i = 0; i < sizeof(mFrames) / sizeof(mFrames[0]); ++i) {
        MediaBuffer *buffer = mFrames[i];

        buffer->setObserver(NULL);
        buffer->release();

        mFrames[i] = NULL;
    }
}

void M4vH263Decoder::signalBufferReturned(MediaBuffer *buffer) {
    LOGV("signalBufferReturned");
}


}  // namespace android
