/*
 * Copyrightm (C) 2010 The Android Open Source Project
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

#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <string.h>
#include <math.h>

#define LOG_TAG "Echo"
#include <utils/Log.h>

#include "EchoSuppressor.h"

// It is very difficult to do echo cancellation at this level due to the lack of
// the timing information of the samples being played and recorded. Therefore,
// for the first release only echo suppression is implemented.

// The algorithm is derived from the "previous works" summarized in
//   A new class of doubletalk detectors based on cross-correlation,
//   J Benesty, DR Morgan, JH Cho, IEEE Trans. on Speech and Audio Processing.
// The method proposed in that paper is not used because of its high complexity.

// It is well known that cross-correlation can be computed using convolution,
// but unfortunately not every mobile processor has a (fast enough) FPU. Thus
// we use integer arithmetic as much as possible and do lots of bookkeeping.
// Again, parameters and thresholds are chosen by experiments.

EchoSuppressor::EchoSuppressor(int sampleCount, int tailLength)
{
    tailLength += sampleCount * 4;

    int shift = 0;
    while ((sampleCount >> shift) > 1 && (tailLength >> shift) > 256) {
        ++shift;
    }

    mShift = shift + 4;
    mScale = 1 << shift;
    mSampleCount = sampleCount;
    mWindowSize = sampleCount >> shift;
    mTailLength = tailLength >> shift;
    mRecordLength = tailLength * 2 / sampleCount;
    mRecordOffset = 0;

    mXs = new uint16_t[mTailLength + mWindowSize];
    memset(mXs, 0, sizeof(*mXs) * (mTailLength + mWindowSize));
    mXSums = new uint32_t[mTailLength];
    memset(mXSums, 0, sizeof(*mXSums) * mTailLength);
    mX2Sums = new uint32_t[mTailLength];
    memset(mX2Sums, 0, sizeof(*mX2Sums) * mTailLength);
    mXRecords = new uint16_t[mRecordLength * mWindowSize];
    memset(mXRecords, 0, sizeof(*mXRecords) * mRecordLength * mWindowSize);

    mYSum = 0;
    mY2Sum = 0;
    mYRecords = new uint32_t[mRecordLength];
    memset(mYRecords, 0, sizeof(*mYRecords) * mRecordLength);
    mY2Records = new uint32_t[mRecordLength];
    memset(mY2Records, 0, sizeof(*mY2Records) * mRecordLength);

    mXYSums = new uint32_t[mTailLength];
    memset(mXYSums, 0, sizeof(*mXYSums) * mTailLength);
    mXYRecords = new uint32_t[mRecordLength * mTailLength];
    memset(mXYRecords, 0, sizeof(*mXYRecords) * mRecordLength * mTailLength);

    mLastX = 0;
    mLastY = 0;
    mWeight = 1.0f / (mRecordLength * mWindowSize);
}

EchoSuppressor::~EchoSuppressor()
{
    delete [] mXs;
    delete [] mXSums;
    delete [] mX2Sums;
    delete [] mXRecords;
    delete [] mYRecords;
    delete [] mY2Records;
    delete [] mXYSums;
    delete [] mXYRecords;
}

void EchoSuppressor::run(int16_t *playbacked, int16_t *recorded)
{
    // Update Xs.
    for (int i = mTailLength - 1; i >= 0; --i) {
        mXs[i + mWindowSize] = mXs[i];
    }
    for (int i = mWindowSize - 1, j = 0; i >= 0; --i, j += mScale) {
        uint32_t sum = 0;
        for (int k = 0; k < mScale; ++k) {
            int32_t x = playbacked[j + k] << 15;
            mLastX += x;
            sum += ((mLastX >= 0) ? mLastX : -mLastX) >> 15;
            mLastX -= (mLastX >> 10) + x;
        }
        mXs[i] = sum >> mShift;
    }

    // Update XSums, X2Sums, and XRecords.
    for (int i = mTailLength - mWindowSize - 1; i >= 0; --i) {
        mXSums[i + mWindowSize] = mXSums[i];
        mX2Sums[i + mWindowSize] = mX2Sums[i];
    }
    uint16_t *xRecords = &mXRecords[mRecordOffset * mWindowSize];
    for (int i = mWindowSize - 1; i >= 0; --i) {
        uint16_t x = mXs[i];
        mXSums[i] = mXSums[i + 1] + x - xRecords[i];
        mX2Sums[i] = mX2Sums[i + 1] + x * x - xRecords[i] * xRecords[i];
        xRecords[i] = x;
    }

    // Compute Ys.
    uint16_t ys[mWindowSize];
    for (int i = mWindowSize - 1, j = 0; i >= 0; --i, j += mScale) {
        uint32_t sum = 0;
        for (int k = 0; k < mScale; ++k) {
            int32_t y = recorded[j + k] << 15;
            mLastY += y;
            sum += ((mLastY >= 0) ? mLastY : -mLastY) >> 15;
            mLastY -= (mLastY >> 10) + y;
        }
        ys[i] = sum >> mShift;
    }

    // Update YSum, Y2Sum, YRecords, and Y2Records.
    uint32_t ySum = 0;
    uint32_t y2Sum = 0;
    for (int i = mWindowSize - 1; i >= 0; --i) {
        ySum += ys[i];
        y2Sum += ys[i] * ys[i];
    }
    mYSum += ySum - mYRecords[mRecordOffset];
    mY2Sum += y2Sum - mY2Records[mRecordOffset];
    mYRecords[mRecordOffset] = ySum;
    mY2Records[mRecordOffset] = y2Sum;

    // Update XYSums and XYRecords.
    uint32_t *xyRecords = &mXYRecords[mRecordOffset * mTailLength];
    for (int i = mTailLength - 1; i >= 0; --i) {
        uint32_t xySum = 0;
        for (int j = mWindowSize - 1; j >= 0; --j) {
            xySum += mXs[i + j] * ys[j];
        }
        mXYSums[i] += xySum - xyRecords[i];
        xyRecords[i] = xySum;
    }

    // Compute correlations.
    int latency = 0;
    float corr2 = 0.0f;
    float varX = 0.0f;
    float varY = mY2Sum - mWeight * mYSum * mYSum;
    for (int i = mTailLength - 1; i >= 0; --i) {
        float cov = mXYSums[i] - mWeight * mXSums[i] * mYSum;
        if (cov > 0.0f) {
            float varXi = mX2Sums[i] - mWeight * mXSums[i] * mXSums[i];
            float corr2i = cov * cov / (varXi * varY + 1);
            if (corr2i > corr2) {
                varX = varXi;
                corr2 = corr2i;
                latency = i;
            }
        }
    }
    //ALOGI("corr^2 %.5f, var %8.0f %8.0f, latency %d", corr2, varX, varY,
    //        latency * mScale);

    // Do echo suppression.
    if (corr2 > 0.1f && varX > 10000.0f) {
        int factor = (corr2 > 1.0f) ? 0 : (1.0f - sqrtf(corr2)) * 4096;
        for (int i = 0; i < mSampleCount; ++i) {
            recorded[i] = recorded[i] * factor >> 16;
        }
    }

    // Increase RecordOffset.
    ++mRecordOffset;
    if (mRecordOffset == mRecordLength) {
        mRecordOffset = 0;
    }
}
