/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "AnimatedImageDrawable.h"
#include "AnimatedImageThread.h"

#include "utils/TraceUtils.h"

#include <SkPicture.h>
#include <SkRefCnt.h>
#include <SkTLazy.h>
#include <SkTime.h>

namespace android {

AnimatedImageDrawable::AnimatedImageDrawable(sk_sp<SkAnimatedImage> animatedImage)
        : mSkAnimatedImage(std::move(animatedImage)) {
    mTimeToShowNextSnapshot = mSkAnimatedImage->currentFrameDuration();
}

void AnimatedImageDrawable::syncProperties() {
    mAlpha = mStagingAlpha;
    mColorFilter = mStagingColorFilter;
}

bool AnimatedImageDrawable::start() {
    if (mRunning) {
        return false;
    }

    // This will trigger a reset.
    mFinished = true;

    mRunning = true;
    return true;
}

bool AnimatedImageDrawable::stop() {
    bool wasRunning = mRunning;
    mRunning = false;
    return wasRunning;
}

bool AnimatedImageDrawable::isRunning() {
    return mRunning;
}

bool AnimatedImageDrawable::nextSnapshotReady() const {
    return mNextSnapshot.valid() &&
           mNextSnapshot.wait_for(std::chrono::seconds(0)) == std::future_status::ready;
}

// Only called on the RenderThread while UI thread is locked.
bool AnimatedImageDrawable::isDirty() {
    const double currentTime = SkTime::GetMSecs();
    const double lastWallTime = mLastWallTime;

    mLastWallTime = currentTime;
    if (!mRunning) {
        mDidDraw = false;
        return false;
    }

    std::unique_lock lock{mSwapLock};
    if (mDidDraw) {
        mCurrentTime += currentTime - lastWallTime;
        mDidDraw = false;
    }

    if (!mNextSnapshot.valid()) {
        // Need to trigger onDraw in order to start decoding the next frame.
        return true;
    }

    return nextSnapshotReady() && mCurrentTime >= mTimeToShowNextSnapshot;
}

// Only called on the AnimatedImageThread.
AnimatedImageDrawable::Snapshot AnimatedImageDrawable::decodeNextFrame() {
    Snapshot snap;
    {
        std::unique_lock lock{mImageLock};
        snap.mDuration = mSkAnimatedImage->decodeNextFrame();
        snap.mPic.reset(mSkAnimatedImage->newPictureSnapshot());
    }

    return snap;
}

// Only called on the AnimatedImageThread.
AnimatedImageDrawable::Snapshot AnimatedImageDrawable::reset() {
    Snapshot snap;
    {
        std::unique_lock lock{mImageLock};
        mSkAnimatedImage->reset();
        snap.mPic.reset(mSkAnimatedImage->newPictureSnapshot());
        snap.mDuration = mSkAnimatedImage->currentFrameDuration();
    }

    return snap;
}

// Only called on the RenderThread.
void AnimatedImageDrawable::onDraw(SkCanvas* canvas) {
    SkTLazy<SkPaint> lazyPaint;
    if (mAlpha != SK_AlphaOPAQUE || mColorFilter.get()) {
        lazyPaint.init();
        lazyPaint.get()->setAlpha(mAlpha);
        lazyPaint.get()->setColorFilter(mColorFilter);
        lazyPaint.get()->setFilterQuality(kLow_SkFilterQuality);
    }

    mDidDraw = true;

    bool drewDirectly = false;
    if (!mSnapshot.mPic) {
        // The image is not animating, and never was. Draw directly from
        // mSkAnimatedImage.
        SkAutoCanvasRestore acr(canvas, false);
        if (lazyPaint.isValid()) {
            canvas->saveLayer(mSkAnimatedImage->getBounds(), lazyPaint.get());
        }

        std::unique_lock lock{mImageLock};
        mSkAnimatedImage->draw(canvas);
        drewDirectly = true;
    }

    if (mRunning && mFinished) {
        auto& thread = uirenderer::AnimatedImageThread::getInstance();
        mNextSnapshot = thread.reset(sk_ref_sp(this));
        mFinished = false;
    }

    bool finalFrame = false;
    if (mRunning && nextSnapshotReady()) {
        std::unique_lock lock{mSwapLock};
        if (mCurrentTime >= mTimeToShowNextSnapshot) {
            mSnapshot = mNextSnapshot.get();
            const double timeToShowCurrentSnap = mTimeToShowNextSnapshot;
            if (mSnapshot.mDuration == SkAnimatedImage::kFinished) {
                finalFrame = true;
                mRunning = false;
                mFinished = true;
            } else {
                mTimeToShowNextSnapshot += mSnapshot.mDuration;
                if (mCurrentTime >= mTimeToShowNextSnapshot) {
                    // This would mean showing the current frame very briefly. It's
                    // possible that not being displayed for a time resulted in
                    // mCurrentTime being far ahead. Prevent showing many frames
                    // rapidly by going back to the beginning of this frame time.
                    mCurrentTime = timeToShowCurrentSnap;
                }
            }
        }
    }

    if (mRunning && !mNextSnapshot.valid()) {
        auto& thread = uirenderer::AnimatedImageThread::getInstance();
        mNextSnapshot = thread.decodeNextFrame(sk_ref_sp(this));
    }

    if (!drewDirectly) {
        // No other thread will modify mCurrentSnap so this should be safe to
        // use without locking.
        canvas->drawPicture(mSnapshot.mPic, nullptr, lazyPaint.getMaybeNull());
    }

    if (finalFrame) {
        if (mEndListener) {
            mEndListener->onAnimationEnd();
        }
    }
}

double AnimatedImageDrawable::drawStaging(SkCanvas* canvas) {
    SkAutoCanvasRestore acr(canvas, false);
    if (mStagingAlpha != SK_AlphaOPAQUE || mStagingColorFilter.get()) {
        SkPaint paint;
        paint.setAlpha(mStagingAlpha);
        paint.setColorFilter(mStagingColorFilter);
        canvas->saveLayer(mSkAnimatedImage->getBounds(), &paint);
    }

    if (mFinished && !mRunning) {
        // Continue drawing the last frame, and return 0 to indicate no need to
        // redraw.
        std::unique_lock lock{mImageLock};
        canvas->drawDrawable(mSkAnimatedImage.get());
        return 0.0;
    }

    bool update = false;
    {
        const double currentTime = SkTime::GetMSecs();
        std::unique_lock lock{mSwapLock};
        // mLastWallTime starts off at 0. If it is still 0, just update it to
        // the current time and avoid updating
        if (mLastWallTime == 0.0) {
            mCurrentTime = currentTime;
        } else if (mRunning) {
            if (mFinished) {
                mCurrentTime = currentTime;
                {
                    std::unique_lock lock{mImageLock};
                    mSkAnimatedImage->reset();
                }
                mTimeToShowNextSnapshot = currentTime + mSkAnimatedImage->currentFrameDuration();
            } else {
                mCurrentTime += currentTime - mLastWallTime;
                update = mCurrentTime >= mTimeToShowNextSnapshot;
            }
        }
        mLastWallTime = currentTime;
    }

    double duration = 0.0;
    {
        std::unique_lock lock{mImageLock};
        if (update) {
            duration = mSkAnimatedImage->decodeNextFrame();
        }

        canvas->drawDrawable(mSkAnimatedImage.get());
    }

    std::unique_lock lock{mSwapLock};
    if (update) {
        if (duration == SkAnimatedImage::kFinished) {
            mRunning = false;
            mFinished = true;
        } else {
            mTimeToShowNextSnapshot += duration;
        }
    }
    return mTimeToShowNextSnapshot;
}

}  // namespace android
