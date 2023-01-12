/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "LottieDrawable.h"

#include <SkTime.h>
#include <log/log.h>
#include <pipeline/skia/SkiaUtils.h>

namespace android {

sk_sp<LottieDrawable> LottieDrawable::Make(sk_sp<skottie::Animation> animation, size_t bytesUsed) {
    if (animation) {
        return sk_sp<LottieDrawable>(new LottieDrawable(std::move(animation), bytesUsed));
    }
    return nullptr;
}
LottieDrawable::LottieDrawable(sk_sp<skottie::Animation> animation, size_t bytesUsed)
        : mAnimation(std::move(animation)), mBytesUsed(bytesUsed) {}

bool LottieDrawable::start() {
    if (mRunning) {
        return false;
    }

    mRunning = true;
    return true;
}

bool LottieDrawable::stop() {
    bool wasRunning = mRunning;
    mRunning = false;
    return wasRunning;
}

bool LottieDrawable::isRunning() {
    return mRunning;
}

// TODO: Check to see if drawable is actually dirty
bool LottieDrawable::isDirty() {
    return true;
}

void LottieDrawable::onDraw(SkCanvas* canvas) {
    if (mRunning) {
        const nsecs_t currentTime = systemTime(SYSTEM_TIME_MONOTONIC);

        nsecs_t t = 0;
        if (mStartTime == 0) {
            mStartTime = currentTime;
        } else {
            t = currentTime - mStartTime;
        }
        double seekTime = std::fmod((double)t * 1e-9, mAnimation->duration());
        mAnimation->seekFrameTime(seekTime);
        mAnimation->render(canvas);
    }
}

void LottieDrawable::drawStaging(SkCanvas* canvas) {
    onDraw(canvas);
}

SkRect LottieDrawable::onGetBounds() {
    // We do not actually know the bounds, so give a conservative answer.
    return SkRectMakeLargest();
}

}  // namespace android
