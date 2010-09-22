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

// VideoSourceDownSampler implements the MediaSource interface,
// downsampling frames provided from a real video source.

#ifndef VIDEO_SOURCE_DOWN_SAMPLER_H_

#define VIDEO_SOURCE_DOWN_SAMPLER_H_

#include <media/stagefright/MediaSource.h>
#include <utils/RefBase.h>

namespace android {

class IMemory;
class MediaBuffer;
class MetaData;

class VideoSourceDownSampler : public MediaSource {
public:
    virtual ~VideoSourceDownSampler();

    // Constructor:
    // videoSource: The real video source which provides the original frames.
    // width, height: The desired width, height. These should be less than or equal
    // to those of the real video source. We then downsample the original frames to
    // this size.
    VideoSourceDownSampler(const sp<MediaSource> &videoSource,
        int32_t width, int32_t height);

    // MediaSource interface
    virtual status_t start(MetaData *params = NULL);

    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

    virtual status_t pause();

private:
    // Reference to the real video source.
    sp<MediaSource> mRealVideoSource;

    // Size of frames to be provided by this source.
    int32_t mWidth;
    int32_t mHeight;

    // Size of frames provided by the real source.
    int32_t mRealSourceWidth;
    int32_t mRealSourceHeight;

    // Down sampling paramters.
    int32_t mDownSampleOffsetX;
    int32_t mDownSampleOffsetY;
    int32_t mDownSampleSkipX;
    int32_t mDownSampleSkipY;

    // True if we need to crop the still video image to get the video frame.
    bool mNeedDownSampling;

    // Meta data. This is a copy of the real source except for the width and
    // height parameters.
    sp<MetaData> mMeta;

    // Computes the offset, skip parameters for downsampling the original frame
    // to the desired size.
    void computeDownSamplingParameters();

    // Downsamples the frame in sourceBuffer to size (mWidth x mHeight). A new
    // buffer is created which stores the downsampled image.
    void downSampleYUVImage(const MediaBuffer &sourceBuffer, MediaBuffer **buffer) const;

    // Disallow these.
    VideoSourceDownSampler(const VideoSourceDownSampler &);
    VideoSourceDownSampler &operator=(const VideoSourceDownSampler &);
};

}  // namespace android

#endif  // VIDEO_SOURCE_DOWN_SAMPLER_H_
