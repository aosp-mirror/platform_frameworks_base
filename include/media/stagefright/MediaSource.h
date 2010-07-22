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

#include <media/stagefright/MediaErrors.h>
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
        enum SeekMode {
            SEEK_PREVIOUS_SYNC,
            SEEK_NEXT_SYNC,
            SEEK_CLOSEST_SYNC,
            SEEK_CLOSEST,
        };

        ReadOptions();

        // Reset everything back to defaults.
        void reset();

        void setSeekTo(int64_t time_us, SeekMode mode = SEEK_CLOSEST_SYNC);
        void clearSeekTo();
        bool getSeekTo(int64_t *time_us, SeekMode *mode) const;

        // Option allows encoder to skip some frames until the specified
        // time stamp.
        // To prevent from being abused, when the skipFrame timestamp is
        // found to be more than 1 second later than the current timestamp,
        // an error will be returned from read().
        void clearSkipFrame();
        bool getSkipFrame(int64_t *timeUs) const;
        void setSkipFrame(int64_t timeUs);

        void setLateBy(int64_t lateness_us);
        int64_t getLateBy() const;

    private:
        enum Options {
            // Bit map
            kSeekTo_Option      = 1,
            kSkipFrame_Option   = 2,
        };

        uint32_t mOptions;
        int64_t mSeekTimeUs;
        SeekMode mSeekMode;
        int64_t mLatenessUs;

        int64_t mSkipFrameUntilTimeUs;
    };

    // Causes this source to suspend pulling data from its upstream source
    // until a subsequent read-with-seek. Currently only supported by
    // OMXCodec.
    virtual status_t pause() {
        return ERROR_UNSUPPORTED;
    }

protected:
    virtual ~MediaSource();

private:
    MediaSource(const MediaSource &);
    MediaSource &operator=(const MediaSource &);
};

}  // namespace android

#endif  // MEDIA_SOURCE_H_
