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

#ifndef VIDEO_SOURCE_H_

#define VIDEO_SOURCE_H_

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

namespace android {

class VideoSource : public MediaSource {
    static const int32_t kFramerate = 24;  // fps

public:
    VideoSource(int width, int height)
        : mWidth(width),
          mHeight(height),
          mSize((width * height * 3) / 2) {
        mGroup.add_buffer(new MediaBuffer(mSize));
    }

    virtual sp<MetaData> getFormat() {
        sp<MetaData> meta = new MetaData;
        meta->setInt32(kKeyWidth, mWidth);
        meta->setInt32(kKeyHeight, mHeight);
        meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);

        return meta;
    }

    virtual status_t start(MetaData *params) {
        mNumFramesOutput = 0;
        return OK;
    }

    virtual status_t stop() {
        return OK;
    }

    virtual status_t read(
            MediaBuffer **buffer, const MediaSource::ReadOptions *options) {
        if (mNumFramesOutput == kFramerate * 100) {
            // Stop returning data after 10 secs.
            return ERROR_END_OF_STREAM;
        }

        // printf("VideoSource::read\n");
        status_t err = mGroup.acquire_buffer(buffer);
        if (err != OK) {
            return err;
        }

        char x = (char)((double)rand() / RAND_MAX * 255);
        memset((*buffer)->data(), x, mSize);
        (*buffer)->set_range(0, mSize);
        (*buffer)->meta_data()->clear();
        (*buffer)->meta_data()->setInt64(
                kKeyTime, (mNumFramesOutput * 1000000) / kFramerate);
        ++mNumFramesOutput;

        // printf("VideoSource::read - returning buffer\n");
        // LOG(INFO)("VideoSource::read - returning buffer");
        return OK;
    }

protected:
    virtual ~VideoSource() {}

private:
    MediaBufferGroup mGroup;
    int mWidth, mHeight;
    size_t mSize;
    int64_t mNumFramesOutput;;

    VideoSource(const VideoSource &);
    VideoSource &operator=(const VideoSource &);
};

}  // namespace android

#endif  // VIDEO_SOURCE_H_
