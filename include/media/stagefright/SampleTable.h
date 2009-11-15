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

#ifndef SAMPLE_TABLE_H_

#define SAMPLE_TABLE_H_

#include <sys/types.h>
#include <stdint.h>

#include <media/stagefright/MediaErrors.h>
#include <utils/RefBase.h>
#include <utils/threads.h>

namespace android {

class DataSource;

class SampleTable : public RefBase {
public:
    SampleTable(const sp<DataSource> &source);

    // type can be 'stco' or 'co64'.
    status_t setChunkOffsetParams(
            uint32_t type, off_t data_offset, off_t data_size);

    status_t setSampleToChunkParams(off_t data_offset, off_t data_size);

    // type can be 'stsz' or 'stz2'.
    status_t setSampleSizeParams(
            uint32_t type, off_t data_offset, off_t data_size);

    status_t setTimeToSampleParams(off_t data_offset, off_t data_size);

    status_t setSyncSampleParams(off_t data_offset, off_t data_size);

    ////////////////////////////////////////////////////////////////////////////

    uint32_t countChunkOffsets() const;
    status_t getChunkOffset(uint32_t chunk_index, off_t *offset);

    status_t getChunkForSample(
            uint32_t sample_index, uint32_t *chunk_index,
            uint32_t *chunk_relative_sample_index, uint32_t *desc_index);

    uint32_t countSamples() const;
    status_t getSampleSize(uint32_t sample_index, size_t *sample_size);

    status_t getSampleOffsetAndSize(
            uint32_t sample_index, off_t *offset, size_t *size);

    status_t getMaxSampleSize(size_t *size);

    status_t getDecodingTime(uint32_t sample_index, uint32_t *time);

    enum {
        kSyncSample_Flag = 1
    };
    status_t findClosestSample(
            uint32_t req_time, uint32_t *sample_index, uint32_t flags);

    status_t findClosestSyncSample(
            uint32_t start_sample_index, uint32_t *sample_index);

protected:
    ~SampleTable();

private:
    sp<DataSource> mDataSource;
    Mutex mLock;

    off_t mChunkOffsetOffset;
    uint32_t mChunkOffsetType;
    uint32_t mNumChunkOffsets;

    off_t mSampleToChunkOffset;
    uint32_t mNumSampleToChunkOffsets;

    off_t mSampleSizeOffset;
    uint32_t mSampleSizeFieldSize;
    uint32_t mDefaultSampleSize;
    uint32_t mNumSampleSizes;

    uint32_t mTimeToSampleCount;
    uint32_t *mTimeToSample;

    off_t mSyncSampleOffset;
    uint32_t mNumSyncSamples;

    SampleTable(const SampleTable &);
    SampleTable &operator=(const SampleTable &);
};

}  // namespace android

#endif  // SAMPLE_TABLE_H_
