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

#include "BlurDrawLooper.h"
#include <SkMaskFilter.h>

namespace android {

BlurDrawLooper::BlurDrawLooper(SkColor4f color, float blurSigma, SkPoint offset)
        : mColor(color), mBlurSigma(blurSigma), mOffset(offset) {}

BlurDrawLooper::~BlurDrawLooper() = default;

SkPoint BlurDrawLooper::apply(SkPaint* paint) const {
    paint->setColor(mColor);
    if (mBlurSigma > 0) {
        paint->setMaskFilter(SkMaskFilter::MakeBlur(kNormal_SkBlurStyle, mBlurSigma, true));
    }
    return mOffset;
}

sk_sp<BlurDrawLooper> BlurDrawLooper::Make(SkColor4f color, SkColorSpace* cs, float blurSigma,
                                           SkPoint offset) {
    if (cs) {
        SkPaint tmp;
        tmp.setColor(color, cs);  // converts color to sRGB
        color = tmp.getColor4f();
    }
    return sk_sp<BlurDrawLooper>(new BlurDrawLooper(color, blurSigma, offset));
}

}  // namespace android
