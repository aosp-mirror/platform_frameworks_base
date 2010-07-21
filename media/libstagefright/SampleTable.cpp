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
//#define LOG_NDEBUG 0
#include <utils/Log.h>

#include "include/SampleTable.h"
#include "include/SampleIterator.h"

#include <arpa/inet.h>

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/Utils.h>

namespace android {

// static
const uint32_t SampleTable::kChunkOffsetType32 = FOURCC('s', 't', 'c', 'o');
// static
const uint32_t SampleTable::kChunkOffsetType64 = FOURCC('c', 'o', '6', '4');
// static
const uint32_t SampleTable::kSampleSizeType32 = FOURCC('s', 't', 's', 'z');
// static
const uint32_t SampleTable::kSampleSizeTypeCompact = FOURCC('s', 't', 'z', '2');

////////////////////////////////////////////////////////////////////////////////

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
      mNumSyncSamples(0),
      mSampleToChunkEntries(NULL) {
    mSampleIterator = new SampleIterator(this);
}

SampleTable::~SampleTable() {
    delete[] mSampleToChunkEntries;
    mSampleToChunkEntries = NULL;

    delete[] mTimeToSample;
    mTimeToSample = NULL;

    delete mSampleIterator;
    mSampleIterator = NULL;
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

    mSampleToChunkEntries =
        new SampleToChunkEntry[mNumSampleToChunkOffsets];

    for (uint32_t i = 0; i < mNumSampleToChunkOffsets; ++i) {
        uint8_t buffer[12];
        if (mDataSource->readAt(
                    mSampleToChunkOffset + 8 + i * 12, buffer, sizeof(buffer))
                != (ssize_t)sizeof(buffer)) {
            return ERROR_IO;
        }

        CHECK(U32_AT(buffer) >= 1);  // chunk index is 1 based in the spec.

        // We want the chunk index to be 0-based.
        mSampleToChunkEntries[i].startChunk = U32_AT(buffer) - 1;
        mSampleToChunkEntries[i].samplesPerChunk = U32_AT(&buffer[4]);
        mSampleToChunkEntries[i].chunkDesc = U32_AT(&buffer[8]);
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

uint32_t SampleTable::countSamples() const {
    return mNumSampleSizes;
}

status_t SampleTable::getMaxSampleSize(size_t *max_size) {
    Mutex::Autolock autoLock(mLock);

    *max_size = 0;

    for (uint32_t i = 0; i < mNumSampleSizes; ++i) {
        size_t sample_size;
        status_t err = getSampleSize_l(i, &sample_size);

        if (err != OK) {
            return err;
        }

        if (sample_size > *max_size) {
            *max_size = sample_size;
        }
    }

    return OK;
}

uint32_t abs_difference(uint32_t time1, uint32_t time2) {
    return time1 > time2 ? time1 - time2 : time2 - time1;
}

status_t SampleTable::findSampleAtTime(
        uint32_t req_time, uint32_t *sample_index, uint32_t flags) {
    *sample_index = 0;

    Mutex::Autolock autoLock(mLock);

    uint32_t cur_sample = 0;
    uint32_t time = 0;
    for (uint32_t i = 0; i < mTimeToSampleCount; ++i) {
        uint32_t n = mTimeToSample[2 * i];
        uint32_t delta = mTimeToSample[2 * i + 1];

        if (req_time < time + n * delta) {
            int j = (req_time - time) / delta;

            uint32_t time1 = time + j * delta;
            uint32_t time2 = time1 + delta;

            uint32_t sampleTime;
            if (i+1 == mTimeToSampleCount
                    || (abs_difference(req_time, time1)
                        < abs_difference(req_time, time2))) {
                *sample_index = cur_sample + j;
                sampleTime = time1;
            } else {
                *sample_index = cur_sample + j + 1;
                sampleTime = time2;
            }

            switch (flags) {
                case kFlagBefore:
                {
                    if (sampleTime > req_time && *sample_index > 0) {
                        --*sample_index;
                    }
                    break;
                }

                case kFlagAfter:
                {
                    if (sampleTime < req_time
                            && *sample_index + 1 < mNumSampleSizes) {
                        ++*sample_index;
                    }
                    break;
                }

                default:
                    break;
            }

            return OK;
        }

        time += delta * n;
        cur_sample += n;
    }

    return ERROR_OUT_OF_RANGE;
}

status_t SampleTable::findSyncSampleNear(
        uint32_t start_sample_index, uint32_t *sample_index, uint32_t flags) {
    Mutex::Autolock autoLock(mLock);

    *sample_index = 0;

    if (mSyncSampleOffset < 0) {
        // All samples are sync-samples.
        *sample_index = start_sample_index;
        return OK;
    }

    if (mNumSyncSamples == 0) {
        *sample_index = 0;
        return OK;
    }

    uint32_t left = 0;
    while (left < mNumSyncSamples) {
        uint32_t x;
        if (mDataSource->readAt(
                    mSyncSampleOffset + 8 + left * 4, &x, 4) != 4) {
            return ERROR_IO;
        }

        x = ntohl(x);
        --x;

        if (x >= start_sample_index) {
            break;
        }

        ++left;
    }

    --left;
    uint32_t x;
    if (mDataSource->readAt(
                mSyncSampleOffset + 8 + left * 4, &x, 4) != 4) {
        return ERROR_IO;
    }

    x = ntohl(x);
    --x;

    if (left + 1 < mNumSyncSamples) {
        uint32_t y;
        if (mDataSource->readAt(
                    mSyncSampleOffset + 8 + (left + 1) * 4, &y, 4) != 4) {
            return ERROR_IO;
        }

        y = ntohl(y);
        --y;

        // our sample lies between sync samples x and y.

        status_t err = mSampleIterator->seekTo(start_sample_index);
        if (err != OK) {
            return err;
        }

        uint32_t sample_time = mSampleIterator->getSampleTime();

        err = mSampleIterator->seekTo(x);
        if (err != OK) {
            return err;
        }
        uint32_t x_time = mSampleIterator->getSampleTime();

        err = mSampleIterator->seekTo(y);
        if (err != OK) {
            return err;
        }

        uint32_t y_time = mSampleIterator->getSampleTime();

        if (abs_difference(x_time, sample_time)
                > abs_difference(y_time, sample_time)) {
            // Pick the sync sample closest (timewise) to the start-sample.
            x = y;
            ++left;
        }
    }

    switch (flags) {
        case kFlagBefore:
        {
            if (x > start_sample_index) {
                CHECK(left > 0);

                if (mDataSource->readAt(
                            mSyncSampleOffset + 8 + (left - 1) * 4, &x, 4) != 4) {
                    return ERROR_IO;
                }

                x = ntohl(x);
                --x;

                CHECK(x <= start_sample_index);
            }
            break;
        }

        case kFlagAfter:
        {
            if (x < start_sample_index) {
                if (left + 1 >= mNumSyncSamples) {
                    return ERROR_OUT_OF_RANGE;
                }

                if (mDataSource->readAt(
                            mSyncSampleOffset + 8 + (left + 1) * 4, &x, 4) != 4) {
                    return ERROR_IO;
                }

                x = ntohl(x);
                --x;

                CHECK(x >= start_sample_index);
            }

            break;
        }

        default:
            break;
    }

    *sample_index = x;

    return OK;
}

status_t SampleTable::findThumbnailSample(uint32_t *sample_index) {
    Mutex::Autolock autoLock(mLock);

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
        status_t err = getSampleSize_l(x, &sampleSize);
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

status_t SampleTable::getSampleSize_l(
        uint32_t sampleIndex, size_t *sampleSize) {
    return mSampleIterator->getSampleSizeDirect(
            sampleIndex, sampleSize);
}

status_t SampleTable::getMetaDataForSample(
        uint32_t sampleIndex,
        off_t *offset,
        size_t *size,
        uint32_t *decodingTime) {
    Mutex::Autolock autoLock(mLock);

    status_t err;
    if ((err = mSampleIterator->seekTo(sampleIndex)) != OK) {
        return err;
    }

    if (offset) {
        *offset = mSampleIterator->getSampleOffset();
    }

    if (size) {
        *size = mSampleIterator->getSampleSize();
    }

    if (decodingTime) {
        *decodingTime = mSampleIterator->getSampleTime();
    }

    return OK;
}

}  // namespace android

