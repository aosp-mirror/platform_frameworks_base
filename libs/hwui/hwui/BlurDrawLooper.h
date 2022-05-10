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

#ifndef ANDROID_GRAPHICS_BLURDRAWLOOPER_H_
#define ANDROID_GRAPHICS_BLURDRAWLOOPER_H_

#include <hwui/Paint.h>
#include <SkRefCnt.h>

class SkColorSpace;

namespace android {

class BlurDrawLooper : public SkRefCnt {
public:
    static sk_sp<BlurDrawLooper> Make(SkColor4f, SkColorSpace*, float blurSigma, SkPoint offset);

    ~BlurDrawLooper() override;

    // proc(SkPoint offset, const Paint& modifiedPaint)
    template <typename DrawProc>
    void apply(const Paint& paint, DrawProc proc) const {
        Paint p(paint);
        proc(this->apply(&p), p);  // draw the shadow
        proc({0, 0}, paint);       // draw the original (on top)
    }

private:
    const SkColor4f mColor;
    const float mBlurSigma;
    const SkPoint mOffset;

    SkPoint apply(Paint* paint) const;

    BlurDrawLooper(SkColor4f, float, SkPoint);
};

}  // namespace android

#endif  // ANDROID_GRAPHICS_BLURDRAWLOOPER_H_
