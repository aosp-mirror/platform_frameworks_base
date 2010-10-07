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
#include <stdint.h>
#include <string.h>
#include <math.h>

#define LOG_TAG "Echo"
#include <utils/Log.h>

#include "EchoSuppressor.h"

EchoSuppressor::EchoSuppressor(int sampleRate, int sampleCount, int tailLength)
{
    int scale = 1;
    while (tailLength > 200 * scale) {
        scale <<= 1;
    }
    if (scale > sampleCount) {
        scale = sampleCount;
    }

    mScale = scale;
    mSampleCount = sampleCount;
    mWindowSize = sampleCount / scale;
    mTailLength = (tailLength + scale - 1) / scale;
    mRecordLength = (sampleRate + sampleCount - 1) / sampleCount;
    mRecordOffset = 0;

    mXs = new float[mTailLength + mWindowSize];
    memset(mXs, 0, sizeof(float) * (mTailLength + mWindowSize));
    mXYs = new float[mTailLength];
    memset(mXYs, 0, sizeof(float) * mTailLength);
    mXXs = new float[mTailLength];
    memset(mXYs, 0, sizeof(float) * mTailLength);
    mYY = 0;

    mXYRecords = new float[mRecordLength * mTailLength];
    memset(mXYRecords, 0, sizeof(float) * mRecordLength * mTailLength);
    mXXRecords = new float[mRecordLength * mWindowSize];
    memset(mXXRecords, 0, sizeof(float) * mRecordLength * mWindowSize);
    mYYRecords = new float[mRecordLength];
    memset(mYYRecords, 0, sizeof(float) * mRecordLength);

    mLastX = 0;
    mLastY = 0;
}

EchoSuppressor::~EchoSuppressor()
{
    delete [] mXs;
    delete [] mXYs;
    delete [] mXXs;
    delete [] mXYRecords;
    delete [] mXXRecords;
    delete [] mYYRecords;
}

void EchoSuppressor::run(int16_t *playbacked, int16_t *recorded)
{
    float *records;

    // Update Xs.
    for (int i = 0; i < mTailLength; ++i) {
        mXs[i] = mXs[mWindowSize + i];
    }
    for (int i = 0, j = 0; i < mWindowSize; ++i, j += mScale) {
        float sum = 0;
        for (int k = 0; k < mScale; ++k) {
            float x = playbacked[j + k] >> 8;
            mLastX += x;
            sum += (mLastX >= 0) ? mLastX : -mLastX;
            mLastX = 0.005f * mLastX - x;
        }
        mXs[mTailLength - 1 + i] = sum;
    }

    // Update XXs and XXRecords.
    for (int i = 0; i < mTailLength - mWindowSize; ++i) {
        mXXs[i] = mXXs[mWindowSize + i];
    }
    records = &mXXRecords[mRecordOffset * mWindowSize];
    for (int i = 0, j = mTailLength - mWindowSize; i < mWindowSize; ++i, ++j) {
        float xx = mXs[mTailLength - 1 + i] * mXs[mTailLength - 1 + i];
        mXXs[j] = mXXs[j - 1] + xx - records[i];
        records[i] = xx;
        if (mXXs[j] < 0) {
            mXXs[j] = 0;
        }
    }

    // Compute Ys.
    float ys[mWindowSize];
    for (int i = 0, j = 0; i < mWindowSize; ++i, j += mScale) {
        float sum = 0;
        for (int k = 0; k < mScale; ++k) {
            float y = recorded[j + k] >> 8;
            mLastY += y;
            sum += (mLastY >= 0) ? mLastY : -mLastY;
            mLastY = 0.005f * mLastY - y;
        }
        ys[i] = sum;
    }

    // Update YY and YYRecords.
    float yy = 0;
    for (int i = 0; i < mWindowSize; ++i) {
        yy += ys[i] * ys[i];
    }
    mYY += yy - mYYRecords[mRecordOffset];
    mYYRecords[mRecordOffset] = yy;
    if (mYY < 0) {
        mYY = 0;
    }

    // Update XYs and XYRecords.
    records = &mXYRecords[mRecordOffset * mTailLength];
    for (int i = 0; i < mTailLength; ++i) {
        float xy = 0;
        for (int j = 0;j < mWindowSize; ++j) {
            xy += mXs[i + j] * ys[j];
        }
        mXYs[i] += xy - records[i];
        records[i] = xy;
        if (mXYs[i] < 0) {
            mXYs[i] = 0;
        }
    }

    // Computes correlations from XYs, XXs, and YY.
    float weight = 1.0f / (mYY + 1);
    float correlation = 0;
    int latency = 0;
    for (int i = 0; i < mTailLength; ++i) {
        float c = mXYs[i] * mXYs[i] * weight / (mXXs[i] + 1);
        if (c > correlation) {
            correlation = c;
            latency = i;
        }
    }

    correlation = sqrtf(correlation);
    if (correlation > 0.3f) {
        float factor = 1.0f - correlation;
        factor *= factor;
        for (int i = 0; i < mSampleCount; ++i) {
            recorded[i] *= factor;
        }
    }
//    LOGI("latency %5d, correlation %.10f", latency, correlation);


    // Increase RecordOffset.
    ++mRecordOffset;
    if (mRecordOffset == mRecordLength) {
        mRecordOffset = 0;
    }
}
