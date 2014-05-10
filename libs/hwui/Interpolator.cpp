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

#include <math.h>
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
    int i2 = MathUtils::min(i1 + 1, mSize - 1);

    float v1 = mValues[i1];
    float v2 = mValues[i2];

    return MathUtils::lerp(v1, v2, weight);
}


} /* namespace uirenderer */
} /* namespace android */
