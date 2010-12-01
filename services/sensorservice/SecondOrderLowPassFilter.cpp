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

#include <stdint.h>
#include <sys/types.h>
#include <math.h>

#include <cutils/log.h>

#include "SecondOrderLowPassFilter.h"

// ---------------------------------------------------------------------------

namespace android {
// ---------------------------------------------------------------------------

SecondOrderLowPassFilter::SecondOrderLowPassFilter(float Q, float fc)
    : iQ(1.0f / Q), fc(fc)
{
}

void SecondOrderLowPassFilter::setSamplingPeriod(float dT)
{
    K = tanf(float(M_PI) * fc * dT);
    iD = 1.0f / (K*K + K*iQ + 1);
    a0 = K*K*iD;
    a1 = 2.0f * a0;
    b1 = 2.0f*(K*K - 1)*iD;
    b2 = (K*K - K*iQ + 1)*iD;
}

// ---------------------------------------------------------------------------

BiquadFilter::BiquadFilter(const SecondOrderLowPassFilter& s)
    : s(s)
{
}

float BiquadFilter::init(float x)
{
    x1 = x2 = x;
    y1 = y2 = x;
    return x;
}

float BiquadFilter::operator()(float x)
{
    float y = (x + x2)*s.a0 + x1*s.a1 - y1*s.b1 - y2*s.b2;
    x2 = x1;
    y2 = y1;
    x1 = x;
    y1 = y;
    return y;
}

// ---------------------------------------------------------------------------

CascadedBiquadFilter::CascadedBiquadFilter(const SecondOrderLowPassFilter& s)
    : mA(s), mB(s)
{
}

float CascadedBiquadFilter::init(float x)
{
    mA.init(x);
    mB.init(x);
    return x;
}

float CascadedBiquadFilter::operator()(float x)
{
    return mB(mA(x));
}

// ---------------------------------------------------------------------------
}; // namespace android
