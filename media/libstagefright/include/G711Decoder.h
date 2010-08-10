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

#ifndef G711_DECODER_H_

#define G711_DECODER_H_

#include <media/stagefright/MediaSource.h>

namespace android {

struct MediaBufferGroup;

struct G711Decoder : public MediaSource {
    G711Decoder(const sp<MediaSource> &source);

    virtual status_t start(MetaData *params);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

protected:
    virtual ~G711Decoder();

private:
    sp<MediaSource> mSource;
    bool mStarted;
    bool mIsMLaw;

    MediaBufferGroup *mBufferGroup;

    static void DecodeALaw(int16_t *out, const uint8_t *in, size_t inSize);
    static void DecodeMLaw(int16_t *out, const uint8_t *in, size_t inSize);

    G711Decoder(const G711Decoder &);
    G711Decoder &operator=(const G711Decoder &);
};

}  // namespace android

#endif  // G711_DECODER_H_
