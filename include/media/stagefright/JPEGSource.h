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

#ifndef JPEG_SOURCE_H_

#define JPEG_SOURCE_H_

#include <media/stagefright/MediaSource.h>

namespace android {

class DataSource;
class MediaBufferGroup;

struct JPEGSource : public MediaSource {
    JPEGSource(const sp<DataSource> &source);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~JPEGSource();

private:
    sp<DataSource> mSource;
    MediaBufferGroup *mGroup;
    bool mStarted;
    off_t mSize;
    int32_t mWidth, mHeight;
    off_t mOffset;

    status_t parseJPEG();

    JPEGSource(const JPEGSource &);
    JPEGSource &operator=(const JPEGSource &);
};

}  // namespace android

#endif  // JPEG_SOURCE_H_

