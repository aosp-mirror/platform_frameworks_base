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

#ifndef MEDIA_SOURCE_H_

#define MEDIA_SOURCE_H_

#include <sys/types.h>

#include <utils/RefBase.h>

namespace android {

class MediaBuffer;
class MetaData;

struct MediaSource : public RefBase {
    MediaSource();

    // To be called before any other methods on this object, except
    // getFormat().
    virtual status_t start(MetaData *params = NULL) = 0;

    // Any blocking read call returns immediately with a result of NO_INIT.
    // It is an error to call any methods other than start after this call
    // returns. Any buffers the object may be holding onto at the time of
    // the stop() call are released.
    // Also, it is imperative that any buffers output by this object and
    // held onto by callers be released before a call to stop() !!!
    virtual status_t stop() = 0;

    // Returns the format of the data output by this media source.
    virtual sp<MetaData> getFormat() = 0;

    struct ReadOptions;

    // Returns a new buffer of data. Call blocks until a
    // buffer is available, an error is encountered of the end of the stream
    // is reached.
    // End of stream is signalled by a result of ERROR_END_OF_STREAM.
    // A result of INFO_FORMAT_CHANGED indicates that the format of this
    // MediaSource has changed mid-stream, the client can continue reading
    // but should be prepared for buffers of the new configuration.
    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL) = 0;

    // Options that modify read() behaviour. The default is to
    // a) not request a seek
    // b) not be late, i.e. lateness_us = 0
    struct ReadOptions {
        ReadOptions();

        // Reset everything back to defaults.
        void reset();

        void setSeekTo(int64_t time_us);
        void clearSeekTo();
        bool getSeekTo(int64_t *time_us) const;

        void setLateBy(int64_t lateness_us);
        int64_t getLateBy() const;

    private:
        enum Options {
            kSeekTo_Option      = 1,
        };

        uint32_t mOptions;
        int64_t mSeekTimeUs;
        int64_t mLatenessUs;
    };

protected:
    virtual ~MediaSource();

private:
    MediaSource(const MediaSource &);
    MediaSource &operator=(const MediaSource &);
};

}  // namespace android

#endif  // MEDIA_SOURCE_H_
