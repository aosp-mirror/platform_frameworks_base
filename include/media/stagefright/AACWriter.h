/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef AAC_WRITER_H_
#define AAC_WRITER_H_

#include <media/stagefright/MediaWriter.h>
#include <utils/threads.h>

namespace android {

struct MediaSource;
struct MetaData;

struct AACWriter : public MediaWriter {
    AACWriter(const char *filename);
    AACWriter(int fd);

    status_t initCheck() const;

    virtual status_t addSource(const sp<MediaSource> &source);
    virtual bool reachedEOS();
    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual status_t pause();

protected:
    virtual ~AACWriter();

private:
    enum {
        kAdtsHeaderLength = 7,     // # of bytes for the adts header
        kSamplesPerFrame  = 1024,  // # of samples in a frame
    };

    int   mFd;
    status_t mInitCheck;
    sp<MediaSource> mSource;
    bool mStarted;
    volatile bool mPaused;
    volatile bool mResumed;
    volatile bool mDone;
    volatile bool mReachedEOS;
    pthread_t mThread;
    int64_t mEstimatedSizeBytes;
    int64_t mEstimatedDurationUs;
    int32_t mChannelCount;
    int32_t mSampleRate;
    int32_t mFrameDurationUs;

    static void *ThreadWrapper(void *);
    status_t threadFunc();
    bool exceedsFileSizeLimit();
    bool exceedsFileDurationLimit();
    status_t writeAdtsHeader(uint32_t frameLength);

    DISALLOW_EVIL_CONSTRUCTORS(AACWriter);
};

}  // namespace android

#endif  // AAC_WRITER_H_
