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

#include "include/core/SkMath.h"
#include "include/core/SkScalar.h"
#include "include/core/SkTypes.h"
#include "include/private/SkFixed.h"
#include "include/private/SkMalloc.h"
#include "src/core/SkTSearch.h"

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
    if (x >= SK_Scalar1) {
        return Dot14_ONE;
    }
    return SkScalarToFixed(x) >> 2;
}

static float SkUnitCubicInterp(float value, float bx, float by, float cx, float cy) {
    // pin to the unit-square, and convert to 2.14
    Dot14 x = pin_and_convert(value);

    if (x == 0) return 0;
    if (x == Dot14_ONE) return SK_Scalar1;

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
    return SkFixedToScalar(eval_cubic(t, A, B, C) << 2);
}

///////////////////////////////////////////////////////////////////////////////////////////////////

SkiaInterpolatorBase::SkiaInterpolatorBase() {
    fStorage = nullptr;
    fTimes = nullptr;
}

SkiaInterpolatorBase::~SkiaInterpolatorBase() {
    if (fStorage) {
        sk_free(fStorage);
    }
}

void SkiaInterpolatorBase::reset(int elemCount, int frameCount) {
    fFlags = 0;
    fElemCount = static_cast<uint8_t>(elemCount);
    fFrameCount = static_cast<int16_t>(frameCount);
    fRepeat = SK_Scalar1;
    if (fStorage) {
        sk_free(fStorage);
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

bool SkiaInterpolatorBase::getDuration(SkMSec* startTime, SkMSec* endTime) const {
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

float SkiaInterpolatorBase::ComputeRelativeT(SkMSec time, SkMSec prevTime, SkMSec nextTime,
                                             const float blend[4]) {
    SkASSERT(time > prevTime && time < nextTime);

    float t = (float)(time - prevTime) / (float)(nextTime - prevTime);
    return blend ? SkUnitCubicInterp(t, blend[0], blend[1], blend[2], blend[3]) : t;
}

SkiaInterpolatorBase::Result SkiaInterpolatorBase::timeToT(SkMSec time, float* T, int* indexPtr,
                                                           bool* exactPtr) const {
    SkASSERT(fFrameCount > 0);
    Result result = kNormal_Result;
    if (fRepeat != SK_Scalar1) {
        SkMSec startTime = 0, endTime = 0;  // initialize to avoid warning
        this->getDuration(&startTime, &endTime);
        SkMSec totalTime = endTime - startTime;
        SkMSec offsetTime = time - startTime;
        endTime = SkScalarFloorToInt(fRepeat * totalTime);
        if (offsetTime >= endTime) {
            float fraction = SkScalarFraction(fRepeat);
            offsetTime = fraction == 0 && fRepeat > 0
                                 ? totalTime
                                 : (SkMSec)SkScalarFloorToInt(fraction * totalTime);
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

    int index = SkTSearch<SkMSec>(&fTimes[0].fTime, fFrameCount, time, sizeof(SkTimeCode));

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
            exact = false;
        }
    }
    SkASSERT(index < fFrameCount);
    const SkTimeCode* nextTime = &fTimes[index];
    SkMSec nextT = nextTime[0].fTime;
    if (exact) {
        *T = 0;
    } else {
        SkMSec prevT = nextTime[-1].fTime;
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
    SkASSERT(elemCount > 0);
    this->reset(elemCount, frameCount);
}

void SkiaInterpolator::reset(int elemCount, int frameCount) {
    INHERITED::reset(elemCount, frameCount);
    fStorage = sk_malloc_throw((sizeof(float) * elemCount + sizeof(SkTimeCode)) * frameCount);
    fTimes = (SkTimeCode*)fStorage;
    fValues = (float*)((char*)fStorage + sizeof(SkTimeCode) * frameCount);
}

#define SK_Fixed1Third (SK_Fixed1 / 3)
#define SK_Fixed2Third (SK_Fixed1 * 2 / 3)

static const float gIdentityBlend[4] = {0.33333333f, 0.33333333f, 0.66666667f, 0.66666667f};

bool SkiaInterpolator::setKeyFrame(int index, SkMSec time, const float values[],
                                   const float blend[4]) {
    SkASSERT(values != nullptr);

    if (blend == nullptr) {
        blend = gIdentityBlend;
    }

    bool success = ~index == SkTSearch<SkMSec>(&fTimes->fTime, index, time, sizeof(SkTimeCode));
    SkASSERT(success);
    if (success) {
        SkTimeCode* timeCode = &fTimes[index];
        timeCode->fTime = time;
        memcpy(timeCode->fBlend, blend, sizeof(timeCode->fBlend));
        float* dst = &fValues[fElemCount * index];
        memcpy(dst, values, fElemCount * sizeof(float));
    }
    return success;
}

SkiaInterpolator::Result SkiaInterpolator::timeToValues(SkMSec time, float values[]) const {
    float T;
    int index;
    bool exact;
    Result result = timeToT(time, &T, &index, &exact);
    if (values) {
        const float* nextSrc = &fValues[index * fElemCount];

        if (exact) {
            memcpy(values, nextSrc, fElemCount * sizeof(float));
        } else {
            SkASSERT(index > 0);

            const float* prevSrc = nextSrc - fElemCount;

            for (int i = fElemCount - 1; i >= 0; --i) {
                values[i] = SkScalarInterp(prevSrc[i], nextSrc[i], T);
            }
        }
    }
    return result;
}
