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

#ifndef MPEG4_WRITER_H_

#define MPEG4_WRITER_H_

#include <stdio.h>

#include <media/stagefright/MediaWriter.h>
#include <utils/List.h>
#include <utils/threads.h>

namespace android {

class MediaBuffer;
class MediaSource;
class MetaData;

class MPEG4Writer : public MediaWriter {
public:
    MPEG4Writer(const char *filename);
    MPEG4Writer(int fd);

    virtual status_t addSource(const sp<MediaSource> &source);
    virtual status_t start(MetaData *param = NULL);
    virtual bool reachedEOS();
    virtual void stop();
    virtual void pause();

    void beginBox(const char *fourcc);
    void writeInt8(int8_t x);
    void writeInt16(int16_t x);
    void writeInt32(int32_t x);
    void writeInt64(int64_t x);
    void writeCString(const char *s);
    void writeFourcc(const char *fourcc);
    void write(const void *data, size_t size);
    void endBox();
    uint32_t interleaveDuration() const { return mInterleaveDurationUs; }
    status_t setInterleaveDuration(uint32_t duration);

protected:
    virtual ~MPEG4Writer();

private:
    class Track;

    FILE *mFile;
    bool mUse32BitOffset;
    bool mPaused;
    bool mStarted;
    off_t mOffset;
    off_t mMdatOffset;
    uint8_t *mMoovBoxBuffer;
    off_t mMoovBoxBufferOffset;
    bool  mWriteMoovBoxToMemory;
    off_t mFreeBoxOffset;
    bool mStreamableFile;
    off_t mEstimatedMoovBoxSize;
    uint32_t mInterleaveDurationUs;
    int64_t mStartTimestampUs;
    Mutex mLock;

    List<Track *> mTracks;

    List<off_t> mBoxes;

    void setStartTimestampUs(int64_t timeUs);
    int64_t getStartTimestampUs();  // Not const
    status_t startTracks();
    size_t numTracks();
    int64_t estimateMoovBoxSize(int32_t bitRate);

    void lock();
    void unlock();

    // Acquire lock before calling these methods
    off_t addSample_l(MediaBuffer *buffer);
    off_t addLengthPrefixedSample_l(MediaBuffer *buffer);

    inline size_t write(const void *ptr, size_t size, size_t nmemb, FILE* stream);
    bool exceedsFileSizeLimit();
    bool exceedsFileDurationLimit();

    MPEG4Writer(const MPEG4Writer &);
    MPEG4Writer &operator=(const MPEG4Writer &);
};

}  // namespace android

#endif  // MPEG4_WRITER_H_
