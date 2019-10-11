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
#include "utils/MathUtils.h"

namespace android {
namespace uirenderer {

class Outline {
public:
    enum class Type { None = 0, Empty = 1, ConvexPath = 2, RoundRect = 3 };

    Outline() : mShouldClip(false), mType(Type::None), mRadius(0), mAlpha(0.0f) {}

    void setRoundRect(int left, int top, int right, int bottom, float radius, float alpha) {
        mAlpha = alpha;
        if (mType == Type::RoundRect && left == mBounds.left && right == mBounds.right &&
            top == mBounds.top && bottom == mBounds.bottom && radius == mRadius) {
            // nothing to change, don't do any work
            return;
        }

        mType = Type::RoundRect;
        mBounds.set(left, top, right, bottom);
        mRadius = radius;

        // Reuse memory if previous outline was the same shape (rect or round rect).
        if (mPath.countVerbs() > 10) {
            mPath.reset();
        } else {
            mPath.rewind();
        }

        // update mPath to reflect new outline
        if (MathUtils::isPositive(radius)) {
            mPath.addRoundRect(SkRect::MakeLTRB(left, top, right, bottom), radius, radius);
        } else {
            mPath.addRect(left, top, right, bottom);
        }
    }

    void setConvexPath(const SkPath* outline, float alpha) {
        if (!outline) {
            setEmpty();
            return;
        }
        mType = Type::ConvexPath;
        mPath = *outline;
        mBounds.set(outline->getBounds());
        mAlpha = alpha;
    }

    void setEmpty() {
        mType = Type::Empty;
        mPath.reset();
        mAlpha = 0.0f;
    }

    void setNone() {
        mType = Type::None;
        mPath.reset();
        mAlpha = 0.0f;
    }

    bool isEmpty() const { return mType == Type::Empty; }

    float getAlpha() const { return mAlpha; }

    void setShouldClip(bool clip) { mShouldClip = clip; }

    bool getShouldClip() const { return mShouldClip; }

    bool willClip() const {
        // only round rect outlines can be used for clipping
        return mShouldClip && (mType == Type::RoundRect);
    }

    bool willRoundRectClip() const {
        // only round rect outlines can be used for clipping
        return willClip() && MathUtils::isPositive(mRadius);
    }

    bool getAsRoundRect(Rect* outRect, float* outRadius) const {
        if (mType == Type::RoundRect) {
            outRect->set(mBounds);
            *outRadius = mRadius;
            return true;
        }
        return false;
    }

    const SkPath* getPath() const {
        if (mType == Type::None || mType == Type::Empty) return nullptr;

        return &mPath;
    }

    Type getType() const { return mType; }

    const Rect& getBounds() const { return mBounds; }

    float getRadius() const { return mRadius; }

private:
    bool mShouldClip;
    Type mType;
    Rect mBounds;
    float mRadius;
    float mAlpha;
    SkPath mPath;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* OUTLINE_H */
