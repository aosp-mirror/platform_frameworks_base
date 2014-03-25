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
#ifndef REVEALCLIP_H
#define REVEALCLIP_H

#include <SkPath.h>

#include "Rect.h"

namespace android {
namespace uirenderer {

class RevealClip {
public:
    RevealClip()
            : mShouldClip(false)
            , mInverseClip(false)
            , mX(0)
            , mY(0)
            , mRadius(0) {}

    void set(bool shouldClip, bool inverseClip, float x, float y, float radius) {
        mShouldClip = shouldClip;
        mInverseClip = inverseClip;
        mX = x;
        mY = y;
        mRadius = radius;

        mPath.rewind();
        if (mShouldClip) {
            mPath.addCircle(x, y, radius);
        }
    }

    bool hasConvexClip() const {
        return mShouldClip && !mInverseClip;
    }

    bool isInverseClip() const {
        return mInverseClip;
    }

    bool willClip() const {
        return mShouldClip;
    }

    const SkPath* getPath() const {
        if (!mShouldClip) return NULL;

        return &mPath;
    }

private:
    bool mShouldClip;
    bool mInverseClip;
    float mX;
    float mY;
    float mRadius;
    SkPath mPath;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* REVEALCLIP_H */
