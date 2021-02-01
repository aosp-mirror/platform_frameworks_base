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

#include <SkPoint.h>
#include <SkRect.h>
#include <SkImageFilter.h>

namespace android::uirenderer {

// TODO: Inherit from base RenderEffect type?
class StretchEffect {
public:
    enum class StretchInterpolator {
        SmoothStep,
    };

    bool isEmpty() const {
        return MathUtils::isZero(stretchDirection.x())
                && MathUtils::isZero(stretchDirection.y());
    }

    void setEmpty() {
        *this = StretchEffect{};
    }

    void mergeWith(const StretchEffect& other) {
        if (other.isEmpty()) {
            return;
        }
        if (isEmpty()) {
            *this = other;
            return;
        }
        stretchDirection += other.stretchDirection;
        if (isEmpty()) {
            return setEmpty();
        }
        stretchArea.join(other.stretchArea);
        maxStretchAmount = std::max(maxStretchAmount, other.maxStretchAmount);
    }

    sk_sp<SkImageFilter> getImageFilter() const;

    SkRect stretchArea {0, 0, 0, 0};
    SkVector stretchDirection {0, 0};
    float maxStretchAmount = 0;
};

} // namespace android::uirenderer
