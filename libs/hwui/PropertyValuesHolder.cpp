/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "PropertyValuesHolder.h"

#include "utils/Color.h"
#include "utils/VectorDrawableUtils.h"

#include <utils/Log.h>

namespace android {
namespace uirenderer {

using namespace VectorDrawable;

inline constexpr float lerp(float fromValue, float toValue, float fraction) {
    return float(fromValue * (1 - fraction) + toValue * fraction);
}

inline constexpr float linearize(U8CPU component) {
    return EOCF_sRGB(component / 255.0f);
}

// TODO: Add a test for this
void ColorEvaluator::evaluate(SkColor* outColor, const SkColor& fromColor, const SkColor& toColor,
                              float fraction) const {
    float a = lerp(SkColorGetA(fromColor) / 255.0f, SkColorGetA(toColor) / 255.0f, fraction);
    float r = lerp(linearize(SkColorGetR(fromColor)), linearize(SkColorGetR(toColor)), fraction);
    float g = lerp(linearize(SkColorGetG(fromColor)), linearize(SkColorGetG(toColor)), fraction);
    float b = lerp(linearize(SkColorGetB(fromColor)), linearize(SkColorGetB(toColor)), fraction);
    *outColor = SkColorSetARGB((U8CPU)roundf(a * 255.0f), (U8CPU)roundf(OECF_sRGB(r) * 255.0f),
                               (U8CPU)roundf(OECF_sRGB(g) * 255.0f),
                               (U8CPU)roundf(OECF_sRGB(b) * 255.0f));
}

void PathEvaluator::evaluate(PathData* out, const PathData& from, const PathData& to,
                             float fraction) const {
    VectorDrawableUtils::interpolatePaths(out, from, to, fraction);
}

template <typename T>
const T PropertyValuesHolderImpl<T>::getValueFromData(float fraction) const {
    if (mDataSource.size() == 0) {
        LOG_ALWAYS_FATAL("No data source is defined");
        return 0;
    }
    if (fraction <= 0.0f) {
        return mDataSource.front();
    }
    if (fraction >= 1.0f) {
        return mDataSource.back();
    }

    fraction *= mDataSource.size() - 1;
    int lowIndex = floor(fraction);
    fraction -= lowIndex;

    T value;
    mEvaluator->evaluate(&value, mDataSource[lowIndex], mDataSource[lowIndex + 1], fraction);
    return value;
}

template <typename T>
const T PropertyValuesHolderImpl<T>::calculateAnimatedValue(float fraction) const {
    if (mDataSource.size() > 0) {
        return getValueFromData(fraction);
    } else {
        T value;
        mEvaluator->evaluate(&value, mStartValue, mEndValue, fraction);
        return value;
    }
}

void GroupPropertyValuesHolder::setFraction(float fraction) {
    float animatedValue = calculateAnimatedValue(fraction);
    mGroup->mutateProperties()->setPropertyValue(mPropertyId, animatedValue);
}

void FullPathColorPropertyValuesHolder::setFraction(float fraction) {
    SkColor animatedValue = calculateAnimatedValue(fraction);
    mFullPath->mutateProperties()->setColorPropertyValue(mPropertyId, animatedValue);
}

void FullPathPropertyValuesHolder::setFraction(float fraction) {
    float animatedValue = calculateAnimatedValue(fraction);
    mFullPath->mutateProperties()->setPropertyValue(mPropertyId, animatedValue);
}

void PathDataPropertyValuesHolder::setFraction(float fraction) {
    mEvaluator->evaluate(&mPathData, mStartValue, mEndValue, fraction);
    mPath->mutateProperties()->setData(mPathData);
}

void RootAlphaPropertyValuesHolder::setFraction(float fraction) {
    float animatedValue = calculateAnimatedValue(fraction);
    mTree->mutateProperties()->setRootAlpha(animatedValue);
}

}  // namepace uirenderer
}  // namespace android
