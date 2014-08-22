/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "Interpolator"

#include "Interpolator.h"

#include <cmath>
#include <cutils/log.h>

#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {

Interpolator* Interpolator::createDefaultInterpolator() {
    return new AccelerateDecelerateInterpolator();
}

float AccelerateDecelerateInterpolator::interpolate(float input) {
    return (float)(cosf((input + 1) * M_PI) / 2.0f) + 0.5f;
}

float AccelerateInterpolator::interpolate(float input) {
    if (mFactor == 1.0f) {
        return input * input;
    } else {
        return pow(input, mDoubleFactor);
    }
}

float AnticipateInterpolator::interpolate(float t) {
    return t * t * ((mTension + 1) * t - mTension);
}

static float a(float t, float s) {
    return t * t * ((s + 1) * t - s);
}

static float o(float t, float s) {
    return t * t * ((s + 1) * t + s);
}

float AnticipateOvershootInterpolator::interpolate(float t) {
    if (t < 0.5f) return 0.5f * a(t * 2.0f, mTension);
    else return 0.5f * (o(t * 2.0f - 2.0f, mTension) + 2.0f);
}

static float bounce(float t) {
    return t * t * 8.0f;
}

float BounceInterpolator::interpolate(float t) {
    t *= 1.1226f;
    if (t < 0.3535f) return bounce(t);
    else if (t < 0.7408f) return bounce(t - 0.54719f) + 0.7f;
    else if (t < 0.9644f) return bounce(t - 0.8526f) + 0.9f;
    else return bounce(t - 1.0435f) + 0.95f;
}

float CycleInterpolator::interpolate(float input) {
    return sinf(2 * mCycles * M_PI * input);
}

float DecelerateInterpolator::interpolate(float input) {
    float result;
    if (mFactor == 1.0f) {
        result = 1.0f - (1.0f - input) * (1.0f - input);
    } else {
        result = 1.0f - pow((1.0f - input), 2 * mFactor);
    }
    return result;
}

float OvershootInterpolator::interpolate(float t) {
    t -= 1.0f;
    return t * t * ((mTension + 1) * t + mTension) + 1.0f;
}

LUTInterpolator::LUTInterpolator(float* values, size_t size) {
    mValues = values;
    mSize = size;
}

LUTInterpolator::~LUTInterpolator() {
    delete mValues;
    mValues = 0;
}

float LUTInterpolator::interpolate(float input) {
    float lutpos = input * mSize;
    if (lutpos >= (mSize - 1)) {
        return mValues[mSize - 1];
    }

    float ipart, weight;
    weight = modff(lutpos, &ipart);

    int i1 = (int) ipart;
    int i2 = MathUtils::min(i1 + 1, (int) mSize - 1);

    LOG_ALWAYS_FATAL_IF(i1 < 0 || i2 < 0, "negatives in interpolation!"
            " i1=%d, i2=%d, input=%f, lutpos=%f, size=%zu, values=%p, ipart=%f, weight=%f",
            i1, i2, input, lutpos, mSize, mValues, ipart, weight);

    float v1 = mValues[i1];
    float v2 = mValues[i2];

    return MathUtils::lerp(v1, v2, weight);
}


} /* namespace uirenderer */
} /* namespace android */
