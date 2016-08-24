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

#include "utils/VectorDrawableUtils.h"

#include <utils/Log.h>

namespace android {
namespace uirenderer {

using namespace VectorDrawable;

float PropertyValuesHolder::getValueFromData(float fraction) {
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

    float value = mDataSource[lowIndex] * (1.0f - fraction)
            + mDataSource[lowIndex + 1] * fraction;
    return value;
}

void GroupPropertyValuesHolder::setFraction(float fraction) {
    float animatedValue;
    if (mDataSource.size() > 0) {
        animatedValue = getValueFromData(fraction);
    } else {
        animatedValue = mStartValue * (1 - fraction) + mEndValue * fraction;
    }
    mGroup->mutateProperties()->setPropertyValue(mPropertyId, animatedValue);
}

inline U8CPU lerp(U8CPU fromValue, U8CPU toValue, float fraction) {
    return (U8CPU) (fromValue * (1 - fraction) + toValue * fraction);
}

// TODO: Add a test for this
SkColor FullPathColorPropertyValuesHolder::interpolateColors(SkColor fromColor, SkColor toColor,
        float fraction) {
    U8CPU alpha = lerp(SkColorGetA(fromColor), SkColorGetA(toColor), fraction);
    U8CPU red = lerp(SkColorGetR(fromColor), SkColorGetR(toColor), fraction);
    U8CPU green = lerp(SkColorGetG(fromColor), SkColorGetG(toColor), fraction);
    U8CPU blue = lerp(SkColorGetB(fromColor), SkColorGetB(toColor), fraction);
    return SkColorSetARGB(alpha, red, green, blue);
}

void FullPathColorPropertyValuesHolder::setFraction(float fraction) {
    SkColor animatedValue = interpolateColors(mStartValue, mEndValue, fraction);
    mFullPath->mutateProperties()->setColorPropertyValue(mPropertyId, animatedValue);
}

void FullPathPropertyValuesHolder::setFraction(float fraction) {
    float animatedValue;
    if (mDataSource.size() > 0) {
        animatedValue = getValueFromData(fraction);
    } else {
        animatedValue = mStartValue * (1 - fraction) + mEndValue * fraction;
    }
    mFullPath->mutateProperties()->setPropertyValue(mPropertyId, animatedValue);
}

void PathDataPropertyValuesHolder::setFraction(float fraction) {
    VectorDrawableUtils::interpolatePaths(&mPathData, mStartValue, mEndValue, fraction);
    mPath->mutateProperties()->setData(mPathData);
}

void RootAlphaPropertyValuesHolder::setFraction(float fraction) {
    float animatedValue = mStartValue * (1 - fraction) + mEndValue * fraction;
    mTree->mutateProperties()->setRootAlpha(animatedValue);
}

} // namepace uirenderer
} // namespace android
