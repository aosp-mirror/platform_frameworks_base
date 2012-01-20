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
#define LOG_TAG "VideoSourceDownSampler"

#include <media/stagefright/VideoSourceDownSampler.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/YUVImage.h>
#include <media/stagefright/YUVCanvas.h>
#include "OMX_Video.h"

namespace android {

VideoSourceDownSampler::VideoSourceDownSampler(const sp<MediaSource> &videoSource,
        int32_t width, int32_t height) {
    ALOGV("Construct VideoSourceDownSampler");
    CHECK(width > 0);
    CHECK(height > 0);

    mRealVideoSource = videoSource;
    mWidth = width;
    mHeight = height;

    mMeta = new MetaData(*(mRealVideoSource->getFormat()));
    CHECK(mMeta->findInt32(kKeyWidth, &mRealSourceWidth));
    CHECK(mMeta->findInt32(kKeyHeight, &mRealSourceHeight));

    if ((mWidth != mRealSourceWidth) || (mHeight != mRealSourceHeight)) {
        // Change meta data for width and height.
        CHECK(mWidth <= mRealSourceWidth);
        CHECK(mHeight <= mRealSourceHeight);

        mNeedDownSampling = true;
        computeDownSamplingParameters();
        mMeta->setInt32(kKeyWidth, mWidth);
        mMeta->setInt32(kKeyHeight, mHeight);
    } else {
        mNeedDownSampling = false;
    }
}

VideoSourceDownSampler::~VideoSourceDownSampler() {
}

void VideoSourceDownSampler::computeDownSamplingParameters() {
    mDownSampleSkipX = mRealSourceWidth / mWidth;
    mDownSampleSkipY = mRealSourceHeight / mHeight;

    mDownSampleOffsetX = mRealSourceWidth - mDownSampleSkipX * mWidth;
    mDownSampleOffsetY = mRealSourceHeight - mDownSampleSkipY * mHeight;
}

void VideoSourceDownSampler::downSampleYUVImage(
        const MediaBuffer &sourceBuffer, MediaBuffer **buffer) const {
    // find the YUV format
    int32_t srcFormat;
    CHECK(mMeta->findInt32(kKeyColorFormat, &srcFormat));
    YUVImage::YUVFormat yuvFormat;
    if (srcFormat == OMX_COLOR_FormatYUV420SemiPlanar) {
        yuvFormat = YUVImage::YUV420SemiPlanar;
    } else if (srcFormat == OMX_COLOR_FormatYUV420Planar) {
        yuvFormat = YUVImage::YUV420Planar;
    }

    // allocate mediaBuffer for down sampled image and setup a canvas.
    *buffer = new MediaBuffer(YUVImage::bufferSize(yuvFormat, mWidth, mHeight));
    YUVImage yuvDownSampledImage(yuvFormat,
            mWidth, mHeight,
            (uint8_t *)(*buffer)->data());
    YUVCanvas yuvCanvasDownSample(yuvDownSampledImage);

    YUVImage yuvImageSource(yuvFormat,
            mRealSourceWidth, mRealSourceHeight,
            (uint8_t *)sourceBuffer.data());
    yuvCanvasDownSample.downsample(mDownSampleOffsetX, mDownSampleOffsetY,
            mDownSampleSkipX, mDownSampleSkipY,
            yuvImageSource);
}

status_t VideoSourceDownSampler::start(MetaData *params) {
    ALOGV("start");
    return mRealVideoSource->start();
}

status_t VideoSourceDownSampler::stop() {
    ALOGV("stop");
    return mRealVideoSource->stop();
}

sp<MetaData> VideoSourceDownSampler::getFormat() {
    ALOGV("getFormat");
    return mMeta;
}

status_t VideoSourceDownSampler::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    ALOGV("read");
    MediaBuffer *realBuffer;
    status_t err = mRealVideoSource->read(&realBuffer, options);

    if (mNeedDownSampling) {
        downSampleYUVImage(*realBuffer, buffer);

        int64_t frameTime;
        realBuffer->meta_data()->findInt64(kKeyTime, &frameTime);
        (*buffer)->meta_data()->setInt64(kKeyTime, frameTime);

        // We just want this buffer to be deleted when the encoder releases it.
        // So don't add a reference to it and set the observer to NULL.
        (*buffer)->setObserver(NULL);

        // The original buffer is no longer required. Release it.
        realBuffer->release();
    } else {
        *buffer = realBuffer;
    }

    return err;
}

status_t VideoSourceDownSampler::pause() {
    ALOGV("pause");
    return mRealVideoSource->pause();
}

}  // namespace android
