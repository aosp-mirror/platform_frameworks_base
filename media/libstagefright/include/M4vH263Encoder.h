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

#ifndef M4V_H263_ENCODER_H_

#define M4V_H263_ENCODER_H_

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>

struct tagvideoEncControls;
struct tagvideoEncOptions;

namespace android {

struct MediaBuffer;
struct MediaBufferGroup;

struct M4vH263Encoder : public MediaSource,
                    public MediaBufferObserver {
    M4vH263Encoder(const sp<MediaSource> &source,
            const sp<MetaData>& meta);

    virtual status_t start(MetaData *params);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

    virtual void signalBufferReturned(MediaBuffer *buffer);

protected:
    virtual ~M4vH263Encoder();

private:
    sp<MediaSource> mSource;
    sp<MetaData>    mFormat;
    sp<MetaData>    mMeta;

    int32_t  mVideoWidth;
    int32_t  mVideoHeight;
    int32_t  mVideoFrameRate;
    int32_t  mVideoBitRate;
    int32_t  mVideoColorFormat;
    int64_t  mNumInputFrames;
    int64_t  mNextModTimeUs;
    int64_t  mPrevTimestampUs;
    status_t mInitCheck;
    bool     mStarted;

    tagvideoEncControls   *mHandle;
    tagvideoEncOptions    *mEncParams;
    MediaBuffer           *mInputBuffer;
    uint8_t               *mInputFrameData;
    MediaBufferGroup      *mGroup;

    status_t initCheck(const sp<MetaData>& meta);
    void releaseOutputBuffers();

    M4vH263Encoder(const M4vH263Encoder &);
    M4vH263Encoder &operator=(const M4vH263Encoder &);
};

}  // namespace android

#endif  // M4V_H263_ENCODER_H_
