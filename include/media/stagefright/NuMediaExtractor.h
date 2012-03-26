/*
 * Copyright 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef NU_MEDIA_EXTRACTOR_H_
#define NU_MEDIA_EXTRACTOR_H_

#include <media/stagefright/foundation/ABase.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/Vector.h>

namespace android {

struct ABuffer;
struct AMessage;
struct MediaBuffer;
struct MediaExtractor;
struct MediaSource;

struct NuMediaExtractor : public RefBase {
    enum SampleFlags {
        SAMPLE_FLAG_SYNC        = 1,
        SAMPLE_FLAG_ENCRYPTED   = 2,
    };

    NuMediaExtractor();

    status_t setDataSource(const char *path);

    size_t countTracks() const;
    status_t getTrackFormat(size_t index, sp<AMessage> *format) const;

    status_t selectTrack(size_t index);

    status_t seekTo(int64_t timeUs);

    status_t advance();
    status_t readSampleData(const sp<ABuffer> &buffer);
    status_t getSampleTrackIndex(size_t *trackIndex);
    status_t getSampleTime(int64_t *sampleTimeUs);
    status_t getSampleFlags(uint32_t *sampleFlags);

protected:
    virtual ~NuMediaExtractor();

private:
    enum TrackFlags {
        kIsVorbis       = 1,
    };

    struct TrackInfo {
        sp<MediaSource> mSource;
        size_t mTrackIndex;
        status_t mFinalResult;
        MediaBuffer *mSample;
        int64_t mSampleTimeUs;
        uint32_t mSampleFlags;

        uint32_t mTrackFlags;  // bitmask of "TrackFlags"
    };

    sp<MediaExtractor> mImpl;

    Vector<TrackInfo> mSelectedTracks;

    ssize_t fetchTrackSamples(int64_t seekTimeUs = -1ll);
    void releaseTrackSamples();

    DISALLOW_EVIL_CONSTRUCTORS(NuMediaExtractor);
};

}  // namespace android

#endif  // NU_MEDIA_EXTRACTOR_H_

