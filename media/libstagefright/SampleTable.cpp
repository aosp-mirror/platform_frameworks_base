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

#define LOG_TAG "SampleTable"
#include <utils/Log.h>

#include "include/SampleTable.h"

#include <arpa/inet.h>

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/Utils.h>

namespace android {

static const uint32_t kChunkOffsetType32 = FOURCC('s', 't', 'c', 'o');
static const uint32_t kChunkOffsetType64 = FOURCC('c', 'o', '6', '4');
static const uint32_t kSampleSizeType32 = FOURCC('s', 't', 's', 'z');
static const uint32_t kSampleSizeTypeCompact = FOURCC('s', 't', 'z', '2');

SampleTable::SampleTable(const sp<DataSource> &source)
    : mDataSource(source),
      mChunkOffsetOffset(-1),
      mChunkOffsetType(0),
      mNumChunkOffsets(0),
      mSampleToChunkOffset(-1),
      mNumSampleToChunkOffsets(0),
      mSampleSizeOffset(-1),
      mSampleSizeFieldSize(0),
      mDefaultSampleSize(0),
      mNumSampleSizes(0),
      mTimeToSampleCount(0),
      mTimeToSample(NULL),
      mSyncSampleOffset(-1),
      mNumSyncSamples(0) {
}

SampleTable::~SampleTable() {
    delete[] mTimeToSample;
    mTimeToSample = NULL;
}

status_t SampleTable::setChunkOffsetParams(
        uint32_t type, off_t data_offset, size_t data_size) {
    if (mChunkOffsetOffset >= 0) {
        return ERROR_MALFORMED;
    }

    CHECK(type == kChunkOffsetType32 || type == kChunkOffsetType64);

    mChunkOffsetOffset = data_offset;
    mChunkOffsetType = type;

    if (data_size < 8) {
        return ERROR_MALFORMED;
    }

    uint8_t header[8];
    if (mDataSource->readAt(
                data_offset, header, sizeof(header)) < (ssize_t)sizeof(header)) {
        return ERROR_IO;
    }

    if (U32_AT(header) != 0) {
        // Expected version = 0, flags = 0.
        return ERROR_MALFORMED;
    }

    mNumChunkOffsets = U32_AT(&header[4]);

    if (mChunkOffsetType == kChunkOffsetType32) {
        if (data_size < 8 + mNumChunkOffsets * 4) {
            return ERROR_MALFORMED;
        }
    } else {
        if (data_size < 8 + mNumChunkOffsets * 8) {
            return ERROR_MALFORMED;
        }
    }

    return OK;
}

status_t SampleTable::setSampleToChunkParams(
        off_t data_offset, size_t data_size) {
    if (mSampleToChunkOffset >= 0) {
        return ERROR_MALFORMED;
    }

    mSampleToChunkOffset = data_offset;

    if (data_size < 8) {
        return ERROR_MALFORMED;
    }

    uint8_t header[8];
    if (mDataSource->readAt(
                data_offset, header, sizeof(header)) < (ssize_t)sizeof(header)) {
        return ERROR_IO;
    }

    if (U32_AT(header) != 0) {
        // Expected version = 0, flags = 0.
        return ERROR_MALFORMED;
    }

    mNumSampleToChunkOffsets = U32_AT(&header[4]);

    if (data_size < 8 + mNumSampleToChunkOffsets * 12) {
        return ERROR_MALFORMED;
    }

    return OK;
}

status_t SampleTable::setSampleSizeParams(
        uint32_t type, off_t data_offset, size_t data_size) {
    if (mSampleSizeOffset >= 0) {
        return ERROR_MALFORMED;
    }

    CHECK(type == kSampleSizeType32 || type == kSampleSizeTypeCompact);

    mSampleSizeOffset = data_offset;

    if (data_size < 12) {
        return ERROR_MALFORMED;
    }

    uint8_t header[12];
    if (mDataSource->readAt(
                data_offset, header, sizeof(header)) < (ssize_t)sizeof(header)) {
        return ERROR_IO;
    }

    if (U32_AT(header) != 0) {
        // Expected version = 0, flags = 0.
        return ERROR_MALFORMED;
    }

    mDefaultSampleSize = U32_AT(&header[4]);
    mNumSampleSizes = U32_AT(&header[8]);

    if (type == kSampleSizeType32) {
        mSampleSizeFieldSize = 32;

        if (mDefaultSampleSize != 0) {
            return OK;
        }

        if (data_size < 12 + mNumSampleSizes * 4) {
            return ERROR_MALFORMED;
        }
    } else {
        if ((mDefaultSampleSize & 0xffffff00) != 0) {
            // The high 24 bits are reserved and must be 0.
            return ERROR_MALFORMED;
        }

        mSampleSizeFieldSize = mDefaultSampleSize & 0xf;
        mDefaultSampleSize = 0;

        if (mSampleSizeFieldSize != 4 && mSampleSizeFieldSize != 8
            && mSampleSizeFieldSize != 16) {
            return ERROR_MALFORMED;
        }

        if (data_size < 12 + (mNumSampleSizes * mSampleSizeFieldSize + 4) / 8) {
            return ERROR_MALFORMED;
        }
    }

    return OK;
}

status_t SampleTable::setTimeToSampleParams(
        off_t data_offset, size_t data_size) {
    if (mTimeToSample != NULL || data_size < 8) {
        return ERROR_MALFORMED;
    }

    uint8_t header[8];
    if (mDataSource->readAt(
                data_offset, header, sizeof(header)) < (ssize_t)sizeof(header)) {
        return ERROR_IO;
    }

    if (U32_AT(header) != 0) {
        // Expected version = 0, flags = 0.
        return ERROR_MALFORMED;
    }

    mTimeToSampleCount = U32_AT(&header[4]);
    mTimeToSample = new uint32_t[mTimeToSampleCount * 2];

    size_t size = sizeof(uint32_t) * mTimeToSampleCount * 2;
    if (mDataSource->readAt(
                data_offset + 8, mTimeToSample, size) < (ssize_t)size) {
        return ERROR_IO;
    }

    for (uint32_t i = 0; i < mTimeToSampleCount * 2; ++i) {
        mTimeToSample[i] = ntohl(mTimeToSample[i]);
    }

    return OK;
}

status_t SampleTable::setSyncSampleParams(off_t data_offset, size_t data_size) {
    if (mSyncSampleOffset >= 0 || data_size < 8) {
        return ERROR_MALFORMED;
    }

    mSyncSampleOffset = data_offset;

    uint8_t header[8];
    if (mDataSource->readAt(
                data_offset, header, sizeof(header)) < (ssize_t)sizeof(header)) {
        return ERROR_IO;
    }

    if (U32_AT(header) != 0) {
        // Expected version = 0, flags = 0.
        return ERROR_MALFORMED;
    }

    mNumSyncSamples = U32_AT(&header[4]);

    if (mNumSyncSamples < 2) {
        LOGW("Table of sync samples is empty or has only a single entry!");
    }
    return OK;
}

uint32_t SampleTable::countChunkOffsets() const {
    return mNumChunkOffsets;
}

status_t SampleTable::getChunkOffset(uint32_t chunk_index, off_t *offset) {
    *offset = 0;

    if (mChunkOffsetOffset < 0) {
        return ERROR_MALFORMED;
    }

    if (chunk_index >= mNumChunkOffsets) {
        return ERROR_OUT_OF_RANGE;
    }

    if (mChunkOffsetType == kChunkOffsetType32) {
        uint32_t offset32;

        if (mDataSource->readAt(
                    mChunkOffsetOffset + 8 + 4 * chunk_index,
                    &offset32,
                    sizeof(offset32)) < (ssize_t)sizeof(offset32)) {
            return ERROR_IO;
        }

        *offset = ntohl(offset32);
    } else {
        CHECK_EQ(mChunkOffsetType, kChunkOffsetType64);

        uint64_t offset64;
        if (mDataSource->readAt(
                    mChunkOffsetOffset + 8 + 8 * chunk_index,
                    &offset64,
                    sizeof(offset64)) < (ssize_t)sizeof(offset64)) {
            return ERROR_IO;
        }

        *offset = ntoh64(offset64);
    }

    return OK;
}

status_t SampleTable::getChunkForSample(
        uint32_t sample_index,
        uint32_t *chunk_index, 
        uint32_t *chunk_relative_sample_index,
        uint32_t *desc_index) {
    *chunk_index = 0;
    *chunk_relative_sample_index = 0;
    *desc_index = 0;

    if (mSampleToChunkOffset < 0) {
        return ERROR_MALFORMED;
    }

    if (sample_index >= countSamples()) {
        return ERROR_END_OF_STREAM;
    }

    uint32_t first_chunk = 0;
    uint32_t samples_per_chunk = 0;
    uint32_t chunk_desc_index = 0;

    uint32_t index = 0;
    while (index < mNumSampleToChunkOffsets) {
        uint8_t buffer[12];
        if (mDataSource->readAt(mSampleToChunkOffset + 8 + index * 12,
                                 buffer, sizeof(buffer)) < (ssize_t)sizeof(buffer)) {
            return ERROR_IO;
        }

        uint32_t stop_chunk = U32_AT(buffer);
        if (sample_index < (stop_chunk - first_chunk) * samples_per_chunk) {
            break;
        }

        sample_index -= (stop_chunk - first_chunk) * samples_per_chunk;
        first_chunk = stop_chunk;
        samples_per_chunk = U32_AT(&buffer[4]);
        chunk_desc_index = U32_AT(&buffer[8]);

        ++index;
    }

    *chunk_index = sample_index / samples_per_chunk + first_chunk - 1;
    *chunk_relative_sample_index = sample_index % samples_per_chunk;
    *desc_index = chunk_desc_index;

    return OK;
}

uint32_t SampleTable::countSamples() const {
    return mNumSampleSizes;
}

status_t SampleTable::getSampleSize(
        uint32_t sample_index, size_t *sample_size) {
    *sample_size = 0;

    if (mSampleSizeOffset < 0) {
        return ERROR_MALFORMED;
    }

    if (sample_index >= mNumSampleSizes) {
        return ERROR_OUT_OF_RANGE;
    }

    if (mDefaultSampleSize > 0) {
        *sample_size = mDefaultSampleSize;
        return OK;
    }

    switch (mSampleSizeFieldSize) {
        case 32:
        {
            if (mDataSource->readAt(
                        mSampleSizeOffset + 12 + 4 * sample_index,
                        sample_size, sizeof(*sample_size)) < (ssize_t)sizeof(*sample_size)) {
                return ERROR_IO;
            }

            *sample_size = ntohl(*sample_size);
            break;
        }

        case 16:
        {
            uint16_t x;
            if (mDataSource->readAt(
                        mSampleSizeOffset + 12 + 2 * sample_index,
                        &x, sizeof(x)) < (ssize_t)sizeof(x)) {
                return ERROR_IO;
            }

            *sample_size = ntohs(x);
            break;
        }

        case 8:
        {
            uint8_t x;
            if (mDataSource->readAt(
                        mSampleSizeOffset + 12 + sample_index,
                        &x, sizeof(x)) < (ssize_t)sizeof(x)) {
                return ERROR_IO;
            }

            *sample_size = x;
            break;
        }

        default:
        {
            CHECK_EQ(mSampleSizeFieldSize, 4);

            uint8_t x;
            if (mDataSource->readAt(
                        mSampleSizeOffset + 12 + sample_index / 2,
                        &x, sizeof(x)) < (ssize_t)sizeof(x)) {
                return ERROR_IO;
            }

            *sample_size = (sample_index & 1) ? x & 0x0f : x >> 4;
            break;
        }
    }

    return OK;
}

status_t SampleTable::getSampleOffsetAndSize(
        uint32_t sample_index, off_t *offset, size_t *size) {
    Mutex::Autolock autoLock(mLock);

    *offset = 0;
    *size = 0;

    uint32_t chunk_index;
    uint32_t chunk_relative_sample_index;
    uint32_t desc_index;
    status_t err = getChunkForSample(
            sample_index, &chunk_index, &chunk_relative_sample_index,
            &desc_index);

    if (err != OK) {
        return err;
    }

    err = getChunkOffset(chunk_index, offset);

    if (err != OK) {
        return err;
    }

    for (uint32_t j = 0; j < chunk_relative_sample_index; ++j) {
        size_t sample_size;
        err = getSampleSize(sample_index - j - 1, &sample_size);

        if (err != OK) {
            return err;
        }

        *offset += sample_size;
    }

    err = getSampleSize(sample_index, size);

    if (err != OK) {
        return err;
    }

    return OK;
}

status_t SampleTable::getMaxSampleSize(size_t *max_size) {
    Mutex::Autolock autoLock(mLock);

    *max_size = 0;

    for (uint32_t i = 0; i < mNumSampleSizes; ++i) {
        size_t sample_size;
        status_t err = getSampleSize(i, &sample_size);
        
        if (err != OK) {
            return err;
        }

        if (sample_size > *max_size) {
            *max_size = sample_size;
        }
    }

    return OK;
}

status_t SampleTable::getDecodingTime(uint32_t sample_index, uint32_t *time) {
    // XXX FIXME idiotic (for the common use-case) O(n) algorithm below...

    Mutex::Autolock autoLock(mLock);

    if (sample_index >= mNumSampleSizes) {
        return ERROR_OUT_OF_RANGE;
    }

    uint32_t cur_sample = 0;
    *time = 0;
    for (uint32_t i = 0; i < mTimeToSampleCount; ++i) {
        uint32_t n = mTimeToSample[2 * i];
        uint32_t delta = mTimeToSample[2 * i + 1];

        if (sample_index < cur_sample + n) {
            *time += delta * (sample_index - cur_sample);

            return OK;
        }
        
        *time += delta * n;
        cur_sample += n;
    }

    return ERROR_OUT_OF_RANGE;
}

status_t SampleTable::findClosestSample(
        uint32_t req_time, uint32_t *sample_index, uint32_t flags) {
    Mutex::Autolock autoLock(mLock);

    uint32_t cur_sample = 0;
    uint32_t time = 0;
    for (uint32_t i = 0; i < mTimeToSampleCount; ++i) {
        uint32_t n = mTimeToSample[2 * i];
        uint32_t delta = mTimeToSample[2 * i + 1];

        if (req_time < time + n * delta) {
            int j = (req_time - time) / delta;

            *sample_index = cur_sample + j;

            if (flags & kSyncSample_Flag) {
                return findClosestSyncSample(*sample_index, sample_index);
            }

            return OK;
        }

        time += delta * n;
        cur_sample += n;
    }

    return ERROR_OUT_OF_RANGE;
}

status_t SampleTable::findClosestSyncSample(
        uint32_t start_sample_index, uint32_t *sample_index) {
    *sample_index = 0;

    if (mSyncSampleOffset < 0) {
        // All samples are sync-samples.
        *sample_index = start_sample_index;
        return OK;
    }

    uint32_t x;
    uint32_t left = 0;
    uint32_t right = mNumSyncSamples;
    while (left < right) {
        uint32_t mid = (left + right) / 2;
        if (mDataSource->readAt(
                    mSyncSampleOffset + 8 + (mid - 1) * 4, &x, 4) != 4) {
            return ERROR_IO;
        }

        x = ntohl(x);

        if (x < (start_sample_index + 1)) {
            left = mid + 1;
        } else if (x > (start_sample_index + 1)) {
            right = mid;
        } else {
            break;
        }
    }

    *sample_index = x - 1;

    return OK;
}

status_t SampleTable::findThumbnailSample(uint32_t *sample_index) {
    if (mSyncSampleOffset < 0) {
        // All samples are sync-samples.
        *sample_index = 0;
        return OK;
    }

    uint32_t bestSampleIndex = 0;
    size_t maxSampleSize = 0;

    static const size_t kMaxNumSyncSamplesToScan = 20;

    // Consider the first kMaxNumSyncSamplesToScan sync samples and
    // pick the one with the largest (compressed) size as the thumbnail.

    size_t numSamplesToScan = mNumSyncSamples;
    if (numSamplesToScan > kMaxNumSyncSamplesToScan) {
        numSamplesToScan = kMaxNumSyncSamplesToScan;
    }

    for (size_t i = 0; i < numSamplesToScan; ++i) {
        uint32_t x;
        if (mDataSource->readAt(
                    mSyncSampleOffset + 8 + i * 4, &x, 4) != 4) {
            return ERROR_IO;
        }
        x = ntohl(x);
        --x;

        // Now x is a sample index.
        size_t sampleSize;
        status_t err = getSampleSize(x, &sampleSize);
        if (err != OK) {
            return err;
        }

        if (i == 0 || sampleSize > maxSampleSize) {
            bestSampleIndex = x;
            maxSampleSize = sampleSize;
        }
    }

    *sample_index = bestSampleIndex;

    return OK;
}

}  // namespace android

