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

#ifndef M4V_H263_DECODER_H_

#define M4V_H263_DECODER_H_

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaSource.h>

struct tagvideoDecControls;

namespace android {

struct M4vH263Decoder : public MediaSource,
                        public MediaBufferObserver {
    M4vH263Decoder(const sp<MediaSource> &source);

    virtual status_t start(MetaData *params);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

    virtual void signalBufferReturned(MediaBuffer *buffer);

protected:
    virtual ~M4vH263Decoder();

private:
    sp<MediaSource> mSource;
    bool mStarted;
    int32_t mWidth, mHeight;

    sp<MetaData> mFormat;

    tagvideoDecControls *mHandle;
    MediaBuffer *mFrames[2];
    MediaBuffer *mInputBuffer;

    int64_t mNumSamplesOutput;
    int64_t mTargetTimeUs;

    void allocateFrames(int32_t width, int32_t height);
    void releaseFrames();

    M4vH263Decoder(const M4vH263Decoder &);
    M4vH263Decoder &operator=(const M4vH263Decoder &);
};

}  // namespace android

#endif  // M4V_H263_DECODER_H_
