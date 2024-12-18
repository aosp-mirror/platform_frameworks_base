/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include "SkiaInterpolator.h"

#include "include/core/SkScalar.h"
#include "include/core/SkTypes.h"

#include <cstdlib>
#include <cstring>
#include <log/log.h>

typedef int Dot14;
#define Dot14_ONE (1 << 14)
#define Dot14_HALF (1 << 13)

#define Dot14ToFloat(x) ((x) / 16384.f)

static inline Dot14 Dot14Mul(Dot14 a, Dot14 b) {
    return (a * b + Dot14_HALF) >> 14;
}

static inline Dot14 eval_cubic(Dot14 t, Dot14 A, Dot14 B, Dot14 C) {
    return Dot14Mul(Dot14Mul(Dot14Mul(C, t) + B, t) + A, t);
}

static inline Dot14 pin_and_convert(float x) {
    if (x <= 0) {
        return 0;
    }
    if (x >= 1.0f) {
        return Dot14_ONE;
    }
    return static_cast<Dot14>(x * Dot14_ONE);
}

using MSec = uint32_t;  // millisecond duration

static float SkUnitCubicInterp(float value, float bx, float by, float cx, float cy) {
    // pin to the unit-square, and convert to 2.14
    Dot14 x = pin_and_convert(value);

    if (x == 0) return 0.0f;
    if (x == Dot14_ONE) return 1.0f;

    Dot14 b = pin_and_convert(bx);
    Dot14 c = pin_and_convert(cx);

    // Now compute our coefficients from the control points
    //  t   -> 3b
    //  t^2 -> 3c - 6b
    //  t^3 -> 3b - 3c + 1
    Dot14 A = 3 * b;
    Dot14 B = 3 * (c - 2 * b);
    Dot14 C = 3 * (b - c) + Dot14_ONE;

    // Now search for a t value given x
    Dot14 t = Dot14_HALF;
    Dot14 dt = Dot14_HALF;
    for (int i = 0; i < 13; i++) {
        dt >>= 1;
        Dot14 guess = eval_cubic(t, A, B, C);
        if (x < guess) {
            t -= dt;
        } else {
            t += dt;
        }
    }

    // Now we have t, so compute the coeff for Y and evaluate
    b = pin_and_convert(by);
    c = pin_and_convert(cy);
    A = 3 * b;
    B = 3 * (c - 2 * b);
    C = 3 * (b - c) + Dot14_ONE;
    return Dot14ToFloat(eval_cubic(t, A, B, C));
}

///////////////////////////////////////////////////////////////////////////////////////////////////

SkiaInterpolatorBase::SkiaInterpolatorBase() {
    fStorage = nullptr;
    fTimes = nullptr;
}

SkiaInterpolatorBase::~SkiaInterpolatorBase() {
    if (fStorage) {
        free(fStorage);
    }
}

void SkiaInterpolatorBase::reset(int elemCount, int frameCount) {
    fFlags = 0;
    fElemCount = static_cast<uint8_t>(elemCount);
    fFrameCount = static_cast<int16_t>(frameCount);
    fRepeat = 1.0f;
    if (fStorage) {
        free(fStorage);
        fStorage = nullptr;
        fTimes = nullptr;
    }
}

/*  Each value[] run is formatted as:
        <time (in msec)>
        <blend>
        <data[fElemCount]>

    Totaling fElemCount+2 entries per keyframe
*/

bool SkiaInterpolatorBase::getDuration(MSec* startTime, MSec* endTime) const {
    if (fFrameCount == 0) {
        return false;
    }

    if (startTime) {
        *startTime = fTimes[0].fTime;
    }
    if (endTime) {
        *endTime = fTimes[fFrameCount - 1].fTime;
    }
    return true;
}

float SkiaInterpolatorBase::ComputeRelativeT(MSec time, MSec prevTime, MSec nextTime,
                                             const float blend[4]) {
    LOG_FATAL_IF(time < prevTime || time > nextTime);

    float t = (float)(time - prevTime) / (float)(nextTime - prevTime);
    return blend ? SkUnitCubicInterp(t, blend[0], blend[1], blend[2], blend[3]) : t;
}

// Returns the index of where the item is or the bit not of the index
// where the item should go in order to keep arr sorted in ascending order.
int SkiaInterpolatorBase::binarySearch(const SkTimeCode* arr, int count, MSec target) {
    if (count <= 0) {
        return ~0;
    }

    int lo = 0;
    int hi = count - 1;

    while (lo < hi) {
        int mid = (hi + lo) / 2;
        MSec elem = arr[mid].fTime;
        if (elem == target) {
            return mid;
        } else if (elem < target) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    // Check to see if target is greater or less than where we stopped
    if (target < arr[lo].fTime) {
        return ~lo;
    }
    // e.g. it should go at the end.
    return ~(lo + 1);
}

SkiaInterpolatorBase::Result SkiaInterpolatorBase::timeToT(MSec time, float* T, int* indexPtr,
                                                           bool* exactPtr) const {
    LOG_FATAL_IF(fFrameCount <= 0);
    Result result = kNormal_Result;
    if (fRepeat != 1.0f) {
        MSec startTime = 0, endTime = 0;  // initialize to avoid warning
        this->getDuration(&startTime, &endTime);
        MSec totalTime = endTime - startTime;
        MSec offsetTime = time - startTime;
        endTime = SkScalarFloorToInt(fRepeat * totalTime);
        if (offsetTime >= endTime) {
            float fraction = SkScalarFraction(fRepeat);
            offsetTime = fraction == 0 && fRepeat > 0
                                 ? totalTime
                                 : (MSec)SkScalarFloorToInt(fraction * totalTime);
            result = kFreezeEnd_Result;
        } else {
            int mirror = fFlags & kMirror;
            offsetTime = offsetTime % (totalTime << mirror);
            if (offsetTime > totalTime) {  // can only be true if fMirror is true
                offsetTime = (totalTime << 1) - offsetTime;
            }
        }
        time = offsetTime + startTime;
    }

    int index = SkiaInterpolatorBase::binarySearch(fTimes, fFrameCount, time);
    bool exact = true;
    if (index < 0) {
        index = ~index;
        if (index == 0) {
            result = kFreezeStart_Result;
        } else if (index == fFrameCount) {
            if (fFlags & kReset) {
                index = 0;
            } else {
                index -= 1;
            }
            result = kFreezeEnd_Result;
        } else {
            // Need to interpolate between two frames.
            exact = false;
        }
    }
    LOG_FATAL_IF(index >= fFrameCount);
    const SkTimeCode* nextTime = &fTimes[index];
    MSec nextT = nextTime[0].fTime;
    if (exact) {
        *T = 0;
    } else {
        MSec prevT = nextTime[-1].fTime;
        *T = ComputeRelativeT(time, prevT, nextT, nextTime[-1].fBlend);
    }
    *indexPtr = index;
    *exactPtr = exact;
    return result;
}

SkiaInterpolator::SkiaInterpolator() {
    INHERITED::reset(0, 0);
    fValues = nullptr;
}

SkiaInterpolator::SkiaInterpolator(int elemCount, int frameCount) {
    LOG_FATAL_IF(elemCount <= 0);
    this->reset(elemCount, frameCount);
}

void SkiaInterpolator::reset(int elemCount, int frameCount) {
    INHERITED::reset(elemCount, frameCount);
    size_t numBytes = (sizeof(float) * elemCount + sizeof(SkTimeCode)) * frameCount;
    fStorage = malloc(numBytes);
    LOG_ALWAYS_FATAL_IF(!fStorage, "Failed to allocate %zu bytes in %s",
                        numBytes, __func__);
    fTimes = (SkTimeCode*)fStorage;
    fValues = (float*)((char*)fStorage + sizeof(SkTimeCode) * frameCount);
}

static const float gIdentityBlend[4] = {0.33333333f, 0.33333333f, 0.66666667f, 0.66666667f};

bool SkiaInterpolator::setKeyFrame(int index, MSec time, const float values[],
                                   const float blend[4]) {
    LOG_FATAL_IF(values == nullptr);

    if (blend == nullptr) {
        blend = gIdentityBlend;
    }

    // Verify the time should go after all the frames before index
    bool success = ~index == SkiaInterpolatorBase::binarySearch(fTimes, index, time);
    LOG_FATAL_IF(!success);
    if (success) {
        SkTimeCode* timeCode = &fTimes[index];
        timeCode->fTime = time;
        memcpy(timeCode->fBlend, blend, sizeof(timeCode->fBlend));
        float* dst = &fValues[fElemCount * index];
        memcpy(dst, values, fElemCount * sizeof(float));
    }
    return success;
}

SkiaInterpolator::Result SkiaInterpolator::timeToValues(MSec time, float values[]) const {
    float T;
    int index;
    bool exact;
    Result result = timeToT(time, &T, &index, &exact);
    if (values) {
        const float* nextSrc = &fValues[index * fElemCount];

        if (exact) {
            memcpy(values, nextSrc, fElemCount * sizeof(float));
        } else {
            LOG_FATAL_IF(index <= 0);

            const float* prevSrc = nextSrc - fElemCount;

            for (int i = fElemCount - 1; i >= 0; --i) {
                values[i] = SkScalarInterp(prevSrc[i], nextSrc[i], T);
            }
        }
    }
    return result;
}
