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
#define LOG_TAG "M4vH263Decoder"
#include <utils/Log.h>
#include <stdlib.h> // for free
#include "ESDS.h"
#include "M4vH263Decoder.h"

#include "mp4dec_api.h"

#include <OMX_Component.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaBufferGroup.h>
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
      mNumSamplesOutput(0),
      mTargetTimeUs(-1) {

    LOGV("M4vH263Decoder");
    memset(mHandle, 0, sizeof(tagvideoDecControls));
    mFormat = new MetaData;
    mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);

    // CHECK(mSource->getFormat()->findInt32(kKeyWidth, &mWidth));
    // CHECK(mSource->getFormat()->findInt32(kKeyHeight, &mHeight));

    // We'll ignore the dimension advertised by the source, the decoder
    // appears to require us to always start with the default dimensions
    // of 352 x 288 to operate correctly and later react to changes in
    // the dimensions as needed.
    mWidth = 352;
    mHeight = 288;

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

void M4vH263Decoder::allocateFrames(int32_t width, int32_t height) {
    size_t frameSize =
        (((width + 15) & - 16) * ((height + 15) & - 16) * 3) / 2;

    for (uint32_t i = 0; i < 2; ++i) {
        mFrames[i] = new MediaBuffer(frameSize);
        mFrames[i]->setObserver(this);
    }

    PVSetReferenceYUV(
            mHandle,
            (uint8_t *)mFrames[1]->data());
}

status_t M4vH263Decoder::start(MetaData *) {
    CHECK(!mStarted);

    const char *mime = NULL;
    sp<MetaData> meta = mSource->getFormat();
    CHECK(meta->findCString(kKeyMIMEType, &mime));

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
    uint8_t *vol_data[1] = {0};
    int32_t vol_size = 0;
    if (meta->findData(kKeyESDS, &type, &data, &size)) {
        ESDS esds((const uint8_t *)data, size);
        CHECK_EQ(esds.InitCheck(), (status_t)OK);

        const void *codec_specific_data;
        size_t codec_specific_data_size;
        esds.getCodecSpecificInfo(
                &codec_specific_data, &codec_specific_data_size);

        vol_data[0] = (uint8_t *) malloc(codec_specific_data_size);
        memcpy(vol_data[0], codec_specific_data, codec_specific_data_size);
        vol_size = codec_specific_data_size;
    } else {
        vol_data[0] = NULL;
        vol_size = 0;

    }

    Bool success = PVInitVideoDecoder(
            mHandle, vol_data, &vol_size, 1, mWidth, mHeight, mode);
    if (vol_data[0]) free(vol_data[0]);

    if (success != PV_TRUE) {
        LOGW("PVInitVideoDecoder failed. Unsupported content?");
        return ERROR_UNSUPPORTED;
    }

    MP4DecodingMode actualMode = PVGetDecBitstreamMode(mHandle);
    CHECK_EQ((int)mode, (int)actualMode);

    PVSetPostProcType((VideoDecControls *) mHandle, 0);

    int32_t width, height;
    PVGetVideoDimensions(mHandle, &width, &height);
    if (mode == H263_MODE && (width == 0 || height == 0)) {
        width = 352;
        height = 288;
    }
    allocateFrames(width, height);

    mSource->start();

    mNumSamplesOutput = 0;
    mTargetTimeUs = -1;
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

    bool seeking = false;
    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        seeking = true;
        CHECK_EQ((int)PVResetVideoDecoder(mHandle), PV_TRUE);
    }

    MediaBuffer *inputBuffer = NULL;
    status_t err = mSource->read(&inputBuffer, options);
    if (err != OK) {
        return err;
    }

    if (seeking) {
        int64_t targetTimeUs;
        if (inputBuffer->meta_data()->findInt64(kKeyTargetTime, &targetTimeUs)
                && targetTimeUs >= 0) {
            mTargetTimeUs = targetTimeUs;
        } else {
            mTargetTimeUs = -1;
        }
    }

    uint8_t *bitstream =
        (uint8_t *) inputBuffer->data() + inputBuffer->range_offset();

    uint32_t timestamp = 0xFFFFFFFF;
    int32_t bufferSize = inputBuffer->range_length();
    uint32_t useExtTimestamp = 0;
    if (PVDecodeVideoFrame(
                mHandle, &bitstream, &timestamp, &bufferSize,
                &useExtTimestamp,
                (uint8_t *)mFrames[mNumSamplesOutput & 0x01]->data())
            != PV_TRUE) {
        LOGE("failed to decode video frame.");

        inputBuffer->release();
        inputBuffer = NULL;

        return UNKNOWN_ERROR;
    }

    int32_t disp_width, disp_height;
    PVGetVideoDimensions(mHandle, &disp_width, &disp_height);

    int32_t buf_width, buf_height;
    PVGetBufferDimensions(mHandle, &buf_width, &buf_height);

    if (buf_width != mWidth || buf_height != mHeight) {
        ++mNumSamplesOutput;  // The client will never get to see this frame.

        inputBuffer->release();
        inputBuffer = NULL;

        mWidth = buf_width;
        mHeight = buf_height;
        mFormat->setInt32(kKeyWidth, mWidth);
        mFormat->setInt32(kKeyHeight, mHeight);

        CHECK_LE(disp_width, buf_width);
        CHECK_LE(disp_height, buf_height);

        return INFO_FORMAT_CHANGED;
    }

    int64_t timeUs;
    CHECK(inputBuffer->meta_data()->findInt64(kKeyTime, &timeUs));

    inputBuffer->release();
    inputBuffer = NULL;

    bool skipFrame = false;

    if (mTargetTimeUs >= 0) {
        CHECK(timeUs <= mTargetTimeUs);

        if (timeUs < mTargetTimeUs) {
            // We're still waiting for the frame with the matching
            // timestamp and we won't return the current one.
            skipFrame = true;

            LOGV("skipping frame at %lld us", timeUs);
        } else {
            LOGV("found target frame at %lld us", timeUs);

            mTargetTimeUs = -1;
        }
    }

    if (skipFrame) {
        *out = new MediaBuffer(0);
    } else {
        *out = mFrames[mNumSamplesOutput & 0x01];
        (*out)->add_ref();
        (*out)->meta_data()->setInt64(kKeyTime, timeUs);
    }

    ++mNumSamplesOutput;

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
