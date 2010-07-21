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

#ifndef VPX_DECODER_H_

#define VPX_DECODER_H_

#include <media/stagefright/MediaSource.h>
#include <utils/Vector.h>

namespace android {

struct MediaBufferGroup;

struct VPXDecoder : public MediaSource {
    VPXDecoder(const sp<MediaSource> &source);

    virtual status_t start(MetaData *params);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

protected:
    virtual ~VPXDecoder();

private:
    sp<MediaSource> mSource;
    bool mStarted;
    int32_t mWidth, mHeight;
    size_t mBufferSize;

    void *mCtx;
    MediaBufferGroup *mBufferGroup;

    int64_t mTargetTimeUs;

    sp<MetaData> mFormat;

    VPXDecoder(const VPXDecoder &);
    VPXDecoder &operator=(const VPXDecoder &);
};

}  // namespace android

#endif  // VPX_DECODER_H_

