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

    mStarting = true;

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

    const bool starting = mStarting;
    mStarting = false;

    const bool drawDirectly = !mSnapshot.mPic;
    if (drawDirectly) {
        // The image is not animating, and never was. Draw directly from
        // mSkAnimatedImage.
        SkAutoCanvasRestore acr(canvas, false);
        if (lazyPaint.isValid()) {
            canvas->saveLayer(mSkAnimatedImage->getBounds(), lazyPaint.get());
        }

        std::unique_lock lock{mImageLock};
        mSkAnimatedImage->draw(canvas);
        if (!mRunning) {
            return;
        }
    } else if (starting) {
        // The image has animated, and now is being reset. Queue up the first
        // frame, but keep showing the current frame until the first is ready.
        auto& thread = uirenderer::AnimatedImageThread::getInstance();
        mNextSnapshot = thread.reset(sk_ref_sp(this));
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

    if (!drawDirectly) {
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

    if (!mRunning) {
        // Continue drawing the current frame, and return 0 to indicate no need
        // to redraw.
        std::unique_lock lock{mImageLock};
        canvas->drawDrawable(mSkAnimatedImage.get());
        return 0.0;
    }

    if (mStarting) {
        mStarting = false;
        double duration = 0.0;
        {
            std::unique_lock lock{mImageLock};
            mSkAnimatedImage->reset();
            duration = mSkAnimatedImage->currentFrameDuration();
        }
        {
            std::unique_lock lock{mSwapLock};
            mLastWallTime = 0.0;
            mTimeToShowNextSnapshot = duration;
        }
    }

    bool update = false;
    {
        const double currentTime = SkTime::GetMSecs();
        std::unique_lock lock{mSwapLock};
        // mLastWallTime starts off at 0. If it is still 0, just update it to
        // the current time and avoid updating
        if (mLastWallTime == 0.0) {
            mCurrentTime = currentTime;
            // mTimeToShowNextSnapshot is already set to the duration of the
            // first frame.
            mTimeToShowNextSnapshot += currentTime;
        } else if (mRunning && mDidDraw) {
            mCurrentTime += currentTime - mLastWallTime;
            update = mCurrentTime >= mTimeToShowNextSnapshot;
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

    mDidDraw = true;

    std::unique_lock lock{mSwapLock};
    if (update) {
        if (duration == SkAnimatedImage::kFinished) {
            mRunning = false;
            return duration;
        }

        const double timeToShowCurrentSnapshot = mTimeToShowNextSnapshot;
        mTimeToShowNextSnapshot += duration;
        if (mCurrentTime >= mTimeToShowNextSnapshot) {
            // As in onDraw, prevent speedy catch-up behavior.
            mCurrentTime = timeToShowCurrentSnapshot;
        }
    }
    return mTimeToShowNextSnapshot;
}

}  // namespace android
