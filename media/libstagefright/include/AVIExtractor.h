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

#ifndef AVI_EXTRACTOR_H_

#define AVI_EXTRACTOR_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/MediaSource.h>
#include <utils/Vector.h>

namespace android {

struct AVIExtractor : public MediaExtractor {
    AVIExtractor(const sp<DataSource> &dataSource);

    virtual size_t countTracks();

    virtual sp<MediaSource> getTrack(size_t index);

    virtual sp<MetaData> getTrackMetaData(
            size_t index, uint32_t flags);

    virtual sp<MetaData> getMetaData();

protected:
    virtual ~AVIExtractor();

private:
    struct AVISource;
    struct MP3Splitter;

    struct SampleInfo {
        uint32_t mOffset;
        bool mIsKey;
    };

    struct Track {
        sp<MetaData> mMeta;
        Vector<SampleInfo> mSamples;
        uint32_t mRate;
        uint32_t mScale;

        // If bytes per sample == 0, each chunk represents a single sample,
        // otherwise each chunk should me a multiple of bytes-per-sample in
        // size.
        uint32_t mBytesPerSample;

        enum Kind {
            AUDIO,
            VIDEO,
            OTHER

        } mKind;

        size_t mNumSyncSamples;
        size_t mThumbnailSampleSize;
        ssize_t mThumbnailSampleIndex;
        size_t mMaxSampleSize;

        // If mBytesPerSample > 0:
        double mAvgChunkSize;
        size_t mFirstChunkSize;
    };

    sp<DataSource> mDataSource;
    status_t mInitCheck;
    Vector<Track> mTracks;

    off64_t mMovieOffset;
    bool mFoundIndex;
    bool mOffsetsAreAbsolute;

    ssize_t parseChunk(off64_t offset, off64_t size, int depth = 0);
    status_t parseStreamHeader(off64_t offset, size_t size);
    status_t parseStreamFormat(off64_t offset, size_t size);
    status_t parseIndex(off64_t offset, size_t size);

    status_t parseHeaders();

    status_t getSampleInfo(
            size_t trackIndex, size_t sampleIndex,
            off64_t *offset, size_t *size, bool *isKey,
            int64_t *sampleTimeUs);

    status_t getSampleTime(
            size_t trackIndex, size_t sampleIndex, int64_t *sampleTimeUs);

    status_t getSampleIndexAtTime(
            size_t trackIndex,
            int64_t timeUs, MediaSource::ReadOptions::SeekMode mode,
            size_t *sampleIndex) const;

    status_t addMPEG4CodecSpecificData(size_t trackIndex);
    status_t addH264CodecSpecificData(size_t trackIndex);

    static bool IsCorrectChunkType(
        ssize_t trackIndex, Track::Kind kind, uint32_t chunkType);

    DISALLOW_EVIL_CONSTRUCTORS(AVIExtractor);
};

class String8;
struct AMessage;

bool SniffAVI(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *);

}  // namespace android

#endif  // AVI_EXTRACTOR_H_
