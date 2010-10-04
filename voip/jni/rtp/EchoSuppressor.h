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

#ifndef __ECHO_SUPPRESSOR_H__
#define __ECHO_SUPPRESSOR_H__

#include <stdint.h>

class EchoSuppressor
{
public:
    // The sampleCount must be power of 2.
    EchoSuppressor(int sampleRate, int sampleCount, int tailLength);
    ~EchoSuppressor();
    void run(int16_t *playbacked, int16_t *recorded);

private:
    int mScale;
    int mSampleCount;
    int mWindowSize;
    int mTailLength;
    int mRecordLength;
    int mRecordOffset;

    float *mXs;
    float *mXYs;
    float *mXXs;
    float mYY;

    float *mXYRecords;
    float *mXXRecords;
    float *mYYRecords;

    float mLastX;
    float mLastY;
};

#endif
