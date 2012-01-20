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

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/DataSource.h>
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

struct SampleTable::CompositionDeltaLookup {
    CompositionDeltaLookup();

    void setEntries(
            const uint32_t *deltaEntries, size_t numDeltaEntries);

    uint32_t getCompositionTimeOffset(uint32_t sampleIndex);

private:
    Mutex mLock;

    const uint32_t *mDeltaEntries;
    size_t mNumDeltaEntries;

    size_t mCurrentDeltaEntry;
    size_t mCurrentEntrySampleIndex;

    DISALLOW_EVIL_CONSTRUCTORS(CompositionDeltaLookup);
};

SampleTable::CompositionDeltaLookup::CompositionDeltaLookup()
    : mDeltaEntries(NULL),
      mNumDeltaEntries(0),
      mCurrentDeltaEntry(0),
      mCurrentEntrySampleIndex(0) {
}

void SampleTable::CompositionDeltaLookup::setEntries(
        const uint32_t *deltaEntries, size_t numDeltaEntries) {
    Mutex::Autolock autolock(mLock);

    mDeltaEntries = deltaEntries;
    mNumDeltaEntries = numDeltaEntries;
    mCurrentDeltaEntry = 0;
    mCurrentEntrySampleIndex = 0;
}

uint32_t SampleTable::CompositionDeltaLookup::getCompositionTimeOffset(
        uint32_t sampleIndex) {
    Mutex::Autolock autolock(mLock);

    if (mDeltaEntries == NULL) {
        return 0;
    }

    if (sampleIndex < mCurrentEntrySampleIndex) {
        mCurrentDeltaEntry = 0;
        mCurrentEntrySampleIndex = 0;
    }

    while (mCurrentDeltaEntry < mNumDeltaEntries) {
        uint32_t sampleCount = mDeltaEntries[2 * mCurrentDeltaEntry];
        if (sampleIndex < mCurrentEntrySampleIndex + sampleCount) {
            return mDeltaEntries[2 * mCurrentDeltaEntry + 1];
        }

        mCurrentEntrySampleIndex += sampleCount;
        ++mCurrentDeltaEntry;
    }

    return 0;
}

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
      mSampleTimeEntries(NULL),
      mCompositionTimeDeltaEntries(NULL),
      mNumCompositionTimeDeltaEntries(0),
      mCompositionDeltaLookup(new CompositionDeltaLookup),
      mSyncSampleOffset(-1),
      mNumSyncSamples(0),
      mSyncSamples(NULL),
      mLastSyncSampleIndex(0),
      mSampleToChunkEntries(NULL) {
    mSampleIterator = new SampleIterator(this);
}

SampleTable::~SampleTable() {
    delete[] mSampleToChunkEntries;
    mSampleToChunkEntries = NULL;

    delete[] mSyncSamples;
    mSyncSamples = NULL;

    delete mCompositionDeltaLookup;
    mCompositionDeltaLookup = NULL;

    delete[] mCompositionTimeDeltaEntries;
    mCompositionTimeDeltaEntries = NULL;

    delete[] mSampleTimeEntries;
    mSampleTimeEntries = NULL;

    delete[] mTimeToSample;
    mTimeToSample = NULL;

    delete mSampleIterator;
    mSampleIterator = NULL;
}

bool SampleTable::isValid() const {
    return mChunkOffsetOffset >= 0
        && mSampleToChunkOffset >= 0
        && mSampleSizeOffset >= 0
        && mTimeToSample != NULL;
}

status_t SampleTable::setChunkOffsetParams(
        uint32_t type, off64_t data_offset, size_t data_size) {
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
        off64_t data_offset, size_t data_size) {
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
        uint32_t type, off64_t data_offset, size_t data_size) {
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

        mSampleSizeFieldSize = mDefaultSampleSize & 0xff;
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
        off64_t data_offset, size_t data_size) {
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

status_t SampleTable::setCompositionTimeToSampleParams(
        off64_t data_offset, size_t data_size) {
    LOGI("There are reordered frames present.");

    if (mCompositionTimeDeltaEntries != NULL || data_size < 8) {
        return ERROR_MALFORMED;
    }

    uint8_t header[8];
    if (mDataSource->readAt(
                data_offset, header, sizeof(header))
            < (ssize_t)sizeof(header)) {
        return ERROR_IO;
    }

    if (U32_AT(header) != 0) {
        // Expected version = 0, flags = 0.
        return ERROR_MALFORMED;
    }

    size_t numEntries = U32_AT(&header[4]);

    if (data_size != (numEntries + 1) * 8) {
        return ERROR_MALFORMED;
    }

    mNumCompositionTimeDeltaEntries = numEntries;
    mCompositionTimeDeltaEntries = new uint32_t[2 * numEntries];

    if (mDataSource->readAt(
                data_offset + 8, mCompositionTimeDeltaEntries, numEntries * 8)
            < (ssize_t)numEntries * 8) {
        delete[] mCompositionTimeDeltaEntries;
        mCompositionTimeDeltaEntries = NULL;

        return ERROR_IO;
    }

    for (size_t i = 0; i < 2 * numEntries; ++i) {
        mCompositionTimeDeltaEntries[i] = ntohl(mCompositionTimeDeltaEntries[i]);
    }

    mCompositionDeltaLookup->setEntries(
            mCompositionTimeDeltaEntries, mNumCompositionTimeDeltaEntries);

    return OK;
}

status_t SampleTable::setSyncSampleParams(off64_t data_offset, size_t data_size) {
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
        ALOGV("Table of sync samples is empty or has only a single entry!");
    }

    mSyncSamples = new uint32_t[mNumSyncSamples];
    size_t size = mNumSyncSamples * sizeof(uint32_t);
    if (mDataSource->readAt(mSyncSampleOffset + 8, mSyncSamples, size)
            != (ssize_t)size) {
        return ERROR_IO;
    }

    for (size_t i = 0; i < mNumSyncSamples; ++i) {
        mSyncSamples[i] = ntohl(mSyncSamples[i]) - 1;
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

// static
int SampleTable::CompareIncreasingTime(const void *_a, const void *_b) {
    const SampleTimeEntry *a = (const SampleTimeEntry *)_a;
    const SampleTimeEntry *b = (const SampleTimeEntry *)_b;

    if (a->mCompositionTime < b->mCompositionTime) {
        return -1;
    } else if (a->mCompositionTime > b->mCompositionTime) {
        return 1;
    }

    return 0;
}

void SampleTable::buildSampleEntriesTable() {
    Mutex::Autolock autoLock(mLock);

    if (mSampleTimeEntries != NULL) {
        return;
    }

    mSampleTimeEntries = new SampleTimeEntry[mNumSampleSizes];

    uint32_t sampleIndex = 0;
    uint32_t sampleTime = 0;

    for (uint32_t i = 0; i < mTimeToSampleCount; ++i) {
        uint32_t n = mTimeToSample[2 * i];
        uint32_t delta = mTimeToSample[2 * i + 1];

        for (uint32_t j = 0; j < n; ++j) {
            if (sampleIndex < mNumSampleSizes) {
                // Technically this should always be the case if the file
                // is well-formed, but you know... there's (gasp) malformed
                // content out there.

                mSampleTimeEntries[sampleIndex].mSampleIndex = sampleIndex;

                uint32_t compTimeDelta =
                    mCompositionDeltaLookup->getCompositionTimeOffset(
                            sampleIndex);

                mSampleTimeEntries[sampleIndex].mCompositionTime =
                    sampleTime + compTimeDelta;
            }

            ++sampleIndex;
            sampleTime += delta;
        }
    }

    qsort(mSampleTimeEntries, mNumSampleSizes, sizeof(SampleTimeEntry),
          CompareIncreasingTime);
}

status_t SampleTable::findSampleAtTime(
        uint32_t req_time, uint32_t *sample_index, uint32_t flags) {
    buildSampleEntriesTable();

    uint32_t left = 0;
    uint32_t right = mNumSampleSizes;
    while (left < right) {
        uint32_t center = (left + right) / 2;
        uint32_t centerTime = mSampleTimeEntries[center].mCompositionTime;

        if (req_time < centerTime) {
            right = center;
        } else if (req_time > centerTime) {
            left = center + 1;
        } else {
            left = center;
            break;
        }
    }

    if (left == mNumSampleSizes) {
        if (flags == kFlagAfter) {
            return ERROR_OUT_OF_RANGE;
        }

        --left;
    }

    uint32_t closestIndex = left;

    switch (flags) {
        case kFlagBefore:
        {
            while (closestIndex > 0
                    && mSampleTimeEntries[closestIndex].mCompositionTime
                            > req_time) {
                --closestIndex;
            }
            break;
        }

        case kFlagAfter:
        {
            while (closestIndex + 1 < mNumSampleSizes
                    && mSampleTimeEntries[closestIndex].mCompositionTime
                            < req_time) {
                ++closestIndex;
            }
            break;
        }

        default:
        {
            CHECK(flags == kFlagClosest);

            if (closestIndex > 0) {
                // Check left neighbour and pick closest.
                uint32_t absdiff1 =
                    abs_difference(
                            mSampleTimeEntries[closestIndex].mCompositionTime,
                            req_time);

                uint32_t absdiff2 =
                    abs_difference(
                            mSampleTimeEntries[closestIndex - 1].mCompositionTime,
                            req_time);

                if (absdiff1 > absdiff2) {
                    closestIndex = closestIndex - 1;
                }
            }

            break;
        }
    }

    *sample_index = mSampleTimeEntries[closestIndex].mSampleIndex;

    return OK;
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
        uint32_t x = mSyncSamples[left];

        if (x >= start_sample_index) {
            break;
        }

        ++left;
    }

    if (left == mNumSyncSamples) {
        if (flags == kFlagAfter) {
            LOGE("tried to find a sync frame after the last one: %d", left);
            return ERROR_OUT_OF_RANGE;
        }
    }

    if (left > 0) {
        --left;
    }

    uint32_t x = mSyncSamples[left];

    if (left + 1 < mNumSyncSamples) {
        uint32_t y = mSyncSamples[left + 1];

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

                x = mSyncSamples[left - 1];

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

                x = mSyncSamples[left + 1];

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
        uint32_t x = mSyncSamples[i];

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
        off64_t *offset,
        size_t *size,
        uint32_t *compositionTime,
        bool *isSyncSample) {
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

    if (compositionTime) {
        *compositionTime = mSampleIterator->getSampleTime();
    }

    if (isSyncSample) {
        *isSyncSample = false;
        if (mSyncSampleOffset < 0) {
            // Every sample is a sync sample.
            *isSyncSample = true;
        } else {
            size_t i = (mLastSyncSampleIndex < mNumSyncSamples)
                    && (mSyncSamples[mLastSyncSampleIndex] <= sampleIndex)
                ? mLastSyncSampleIndex : 0;

            while (i < mNumSyncSamples && mSyncSamples[i] < sampleIndex) {
                ++i;
            }

            if (i < mNumSyncSamples && mSyncSamples[i] == sampleIndex) {
                *isSyncSample = true;
            }

            mLastSyncSampleIndex = i;
        }
    }

    return OK;
}

uint32_t SampleTable::getCompositionTimeOffset(uint32_t sampleIndex) {
    return mCompositionDeltaLookup->getCompositionTimeOffset(sampleIndex);
}

}  // namespace android

