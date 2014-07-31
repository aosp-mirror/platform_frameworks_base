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
#ifndef OUTLINE_H
#define OUTLINE_H

#include <SkPath.h>

#include "Rect.h"

namespace android {
namespace uirenderer {

class Outline {
public:
    Outline()
            : mShouldClip(false)
            , mType(kOutlineType_None)
            , mRadius(0)
            , mAlpha(0.0f) {}

    void setRoundRect(int left, int top, int right, int bottom, float radius, float alpha) {
        mType = kOutlineType_RoundRect;
        mBounds.set(left, top, right, bottom);
        mRadius = radius;
        mPath.reset();
        mPath.addRoundRect(SkRect::MakeLTRB(left, top, right, bottom),
                radius, radius);
        mAlpha = alpha;
    }

    void setConvexPath(const SkPath* outline, float alpha) {
        if (!outline) {
            setEmpty();
            return;
        }
        mType = kOutlineType_ConvexPath;
        mPath = *outline;
        mBounds.set(outline->getBounds());
        mAlpha = alpha;
    }

    void setEmpty() {
        mType = kOutlineType_Empty;
        mPath.reset();
        mAlpha = 0.0f;
    }

    void setNone() {
        mType = kOutlineType_None;
        mPath.reset();
        mAlpha = 0.0f;
    }

    bool isEmpty() const {
        return mType == kOutlineType_Empty;
    }

    float getAlpha() const {
        return mAlpha;
    }

    void setShouldClip(bool clip) {
        mShouldClip = clip;
    }

    bool getShouldClip() const {
        return mShouldClip;
    }

    bool willClip() const {
        // only round rect outlines can be used for clipping
        return mShouldClip && (mType == kOutlineType_RoundRect);
    }

    bool getAsRoundRect(Rect* outRect, float* outRadius) const {
        if (mType == kOutlineType_RoundRect) {
            outRect->set(mBounds);
            *outRadius = mRadius;
            return true;
        }
        return false;
    }

    const SkPath* getPath() const {
        if (mType == kOutlineType_None || mType == kOutlineType_Empty) return NULL;

        return &mPath;
    }

private:
    enum OutlineType {
        kOutlineType_None = 0,
        kOutlineType_Empty = 1,
        kOutlineType_ConvexPath = 2,
        kOutlineType_RoundRect = 3
    };

    bool mShouldClip;
    OutlineType mType;
    Rect mBounds;
    float mRadius;
    float mAlpha;
    SkPath mPath;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* OUTLINE_H */
