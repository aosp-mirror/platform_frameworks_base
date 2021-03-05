/*
 * Copyright (C) 2021 The Android Open Source Project
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

#pragma once

#include "utils/MathUtils.h"

#include <SkImage.h>
#include <SkImageFilter.h>
#include <SkPoint.h>
#include <SkRect.h>
#include <SkRuntimeEffect.h>

namespace android::uirenderer {

// TODO: Inherit from base RenderEffect type?
class StretchEffect {
public:
    enum class StretchInterpolator {
        SmoothStep,
    };

    StretchEffect(const SkRect& area, const SkVector& direction, float maxStretchAmount)
            : stretchArea(area), maxStretchAmount(maxStretchAmount), mStretchDirection(direction) {}

    StretchEffect() {}

    bool isEmpty() const {
        return MathUtils::isZero(mStretchDirection.x()) && MathUtils::isZero(mStretchDirection.y());
    }

    void setEmpty() {
        *this = StretchEffect{};
    }

    StretchEffect& operator=(const StretchEffect& other) {
        this->stretchArea = other.stretchArea;
        this->mStretchDirection = other.mStretchDirection;
        this->mStretchFilter = nullptr;
        this->maxStretchAmount = other.maxStretchAmount;
        return *this;
    }

    void mergeWith(const StretchEffect& other) {
        if (other.isEmpty()) {
            return;
        }
        if (isEmpty()) {
            *this = other;
            return;
        }
        setStretchDirection(mStretchDirection + other.mStretchDirection);
        if (isEmpty()) {
            return setEmpty();
        }
        stretchArea.join(other.stretchArea);
        maxStretchAmount = std::max(maxStretchAmount, other.maxStretchAmount);
    }

    sk_sp<SkImageFilter> getImageFilter(const sk_sp<SkImage>& snapshotImage) const;

    SkRect stretchArea {0, 0, 0, 0};
    float maxStretchAmount = 0;

    void setStretchDirection(const SkVector& direction) {
        mStretchFilter = nullptr;
        mStretchDirection = direction;
    }

    const SkVector getStretchDirection() const { return mStretchDirection; }

private:
    static sk_sp<SkRuntimeEffect> getStretchEffect();
    mutable SkVector mStretchDirection{0, 0};
    mutable std::unique_ptr<SkRuntimeShaderBuilder> mBuilder;
    mutable sk_sp<SkImageFilter> mStretchFilter;
};

} // namespace android::uirenderer
