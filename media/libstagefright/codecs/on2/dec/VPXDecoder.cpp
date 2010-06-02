/*
 * Copyright (C) 2010 The Android Open Source Project
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
#define LOG_TAG "VPXDecoder"
#include <utils/Log.h>

#include "VPXDecoder.h"

#include <OMX_Component.h>

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>

#include "vpx_codec/vpx_decoder.h"
#include "vp8/vp8dx.h"

namespace android {

VPXDecoder::VPXDecoder(const sp<MediaSource> &source)
    : mSource(source),
      mStarted(false),
      mBufferSize(0),
      mCtx(NULL),
      mBufferGroup(NULL) {
    sp<MetaData> inputFormat = source->getFormat();
    const char *mime;
    CHECK(inputFormat->findCString(kKeyMIMEType, &mime));
    CHECK(!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_VPX));

    CHECK(inputFormat->findInt32(kKeyWidth, &mWidth));
    CHECK(inputFormat->findInt32(kKeyHeight, &mHeight));

    mBufferSize = (mWidth * mHeight * 3) / 2;

    mFormat = new MetaData;
    mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);
    mFormat->setInt32(kKeyWidth, mWidth);
    mFormat->setInt32(kKeyHeight, mHeight);
    mFormat->setInt32(kKeyColorFormat, OMX_COLOR_FormatYUV420Planar);
    mFormat->setCString(kKeyDecoderComponent, "VPXDecoder");

    int64_t durationUs;
    if (inputFormat->findInt64(kKeyDuration, &durationUs)) {
        mFormat->setInt64(kKeyDuration, durationUs);
    }
}

VPXDecoder::~VPXDecoder() {
    if (mStarted) {
        stop();
    }
}

status_t VPXDecoder::start(MetaData *) {
    if (mStarted) {
        return UNKNOWN_ERROR;
    }

    status_t err = mSource->start();

    if (err != OK) {
        return err;
    }

    mCtx = new vpx_codec_ctx_t;
    if (vpx_codec_dec_init(
                (vpx_codec_ctx_t *)mCtx, &vpx_codec_vp8_dx_algo, NULL, 0)) {
        LOGE("on2 decoder failed to initialize.");

        mSource->stop();

        return UNKNOWN_ERROR;
    }

    mBufferGroup = new MediaBufferGroup;
    mBufferGroup->add_buffer(new MediaBuffer(mBufferSize));
    mBufferGroup->add_buffer(new MediaBuffer(mBufferSize));

    mStarted = true;

    return OK;
}

status_t VPXDecoder::stop() {
    if (!mStarted) {
        return UNKNOWN_ERROR;
    }

    delete mBufferGroup;
    mBufferGroup = NULL;

    vpx_codec_destroy((vpx_codec_ctx_t *)mCtx);
    delete (vpx_codec_ctx_t *)mCtx;
    mCtx = NULL;

    mSource->stop();

    mStarted = false;

    return OK;
}

sp<MetaData> VPXDecoder::getFormat() {
    return mFormat;
}

status_t VPXDecoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    MediaBuffer *input;
    status_t err = mSource->read(&input, options);

    if (err != OK) {
        return err;
    }

    LOGV("read %d bytes from source\n", input->range_length());

    if (vpx_codec_decode(
                (vpx_codec_ctx_t *)mCtx,
                (uint8_t *)input->data() + input->range_offset(),
                input->range_length(),
                NULL,
                0)) {
        LOGE("on2 decoder failed to decode frame.");
        input->release();
        input = NULL;

        return UNKNOWN_ERROR;
    }

    LOGV("successfully decoded 1 or more frames.");

    int64_t timeUs;
    CHECK(input->meta_data()->findInt64(kKeyTime, &timeUs));

    input->release();
    input = NULL;

    vpx_codec_iter_t iter = NULL;
    vpx_image_t *img = vpx_codec_get_frame((vpx_codec_ctx_t *)mCtx, &iter);

    if (img == NULL) {
        LOGI("on2 decoder did not return a frame.");

        *out = new MediaBuffer(0);
        return OK;
    }

    CHECK_EQ(img->fmt, IMG_FMT_I420);

    int32_t width = img->d_w;
    int32_t height = img->d_h;

    if (width != mWidth || height != mHeight) {
        LOGI("Image dimensions changed, width = %d, height = %d",
             width, height);

        mWidth = width;
        mHeight = height;
        mFormat->setInt32(kKeyWidth, width);
        mFormat->setInt32(kKeyHeight, height);

        mBufferSize = (mWidth * mHeight * 3) / 2;
        delete mBufferGroup;
        mBufferGroup = new MediaBufferGroup;
        mBufferGroup->add_buffer(new MediaBuffer(mBufferSize));
        mBufferGroup->add_buffer(new MediaBuffer(mBufferSize));

        return INFO_FORMAT_CHANGED;
    }

    MediaBuffer *output;
    CHECK_EQ(mBufferGroup->acquire_buffer(&output), OK);

    const uint8_t *srcLine = (const uint8_t *)img->planes[PLANE_Y];
    uint8_t *dst = (uint8_t *)output->data();
    for (size_t i = 0; i < img->d_h; ++i) {
        memcpy(dst, srcLine, img->d_w);

        srcLine += img->stride[PLANE_Y];
        dst += img->d_w;
    }

    srcLine = (const uint8_t *)img->planes[PLANE_U];
    for (size_t i = 0; i < img->d_h / 2; ++i) {
        memcpy(dst, srcLine, img->d_w / 2);

        srcLine += img->stride[PLANE_U];
        dst += img->d_w / 2;
    }

    srcLine = (const uint8_t *)img->planes[PLANE_V];
    for (size_t i = 0; i < img->d_h / 2; ++i) {
        memcpy(dst, srcLine, img->d_w / 2);

        srcLine += img->stride[PLANE_V];
        dst += img->d_w / 2;
    }

    output->set_range(0, (width * height * 3) / 2);

    output->meta_data()->setInt64(kKeyTime, timeUs);

    *out = output;

    return OK;
}

}  // namespace android

