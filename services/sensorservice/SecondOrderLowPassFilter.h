/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_SECOND_ORDER_LOW_PASS_FILTER_H
#define ANDROID_SECOND_ORDER_LOW_PASS_FILTER_H

#include <stdint.h>
#include <sys/types.h>

// ---------------------------------------------------------------------------

namespace android {
// ---------------------------------------------------------------------------

class BiquadFilter;

/*
 * State of a 2nd order low-pass IIR filter
 */
class SecondOrderLowPassFilter {
    friend class BiquadFilter;
    float iQ, fc;
    float K, iD;
    float a0, a1;
    float b1, b2;
public:
    SecondOrderLowPassFilter(float Q, float fc);
    void setSamplingPeriod(float dT);
};

/*
 * Implements a Biquad IIR filter
 */
class BiquadFilter {
    float x1, x2;
    float y1, y2;
    const SecondOrderLowPassFilter& s;
public:
    BiquadFilter(const SecondOrderLowPassFilter& s);
    float init(float in);
    float operator()(float in);
};

/*
 * Two cascaded biquad IIR filters
 * (4-poles IIR)
 */
class CascadedBiquadFilter {
    BiquadFilter mA;
    BiquadFilter mB;
public:
    CascadedBiquadFilter(const SecondOrderLowPassFilter& s);
    float init(float in);
    float operator()(float in);
};

// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_SECOND_ORDER_LOW_PASS_FILTER_H
