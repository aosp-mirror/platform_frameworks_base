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

#include <utils/List.h>
#include <utils/RefBase.h>
#include <utils/threads.h>

namespace android {

class MediaBuffer;
class MediaSource;
class MetaData;

class MPEG4Writer : public RefBase {
public:
    MPEG4Writer(const char *filename);

    void addSource(const sp<MediaSource> &source);
    status_t start();
    bool reachedEOS();
    void stop();

    void beginBox(const char *fourcc);
    void writeInt8(int8_t x);
    void writeInt16(int16_t x);
    void writeInt32(int32_t x);
    void writeInt64(int64_t x);
    void writeCString(const char *s);
    void writeFourcc(const char *fourcc);
    void write(const void *data, size_t size);
    void endBox();

protected:
    virtual ~MPEG4Writer();

private:
    class Track;

    FILE *mFile;
    off_t mOffset;
    off_t mMdatOffset;
    Mutex mLock;

    List<Track *> mTracks;

    List<off_t> mBoxes;

    off_t addSample(MediaBuffer *buffer);

    MPEG4Writer(const MPEG4Writer &);
    MPEG4Writer &operator=(const MPEG4Writer &);
};

}  // namespace android

#endif  // MPEG4_WRITER_H_
