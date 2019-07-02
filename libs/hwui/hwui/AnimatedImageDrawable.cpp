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

#include <optional>

namespace android {

AnimatedImageDrawable::AnimatedImageDrawable(sk_sp<SkAnimatedImage> animatedImage, size_t bytesUsed)
        : mSkAnimatedImage(std::move(animatedImage)), mBytesUsed(bytesUsed) {
    mTimeToShowNextSnapshot = ms2ns(mSkAnimatedImage->currentFrameDuration());
}

void AnimatedImageDrawable::syncProperties() {
    mProperties = mStagingProperties;
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
bool AnimatedImageDrawable::isDirty(nsecs_t* outDelay) {
    *outDelay = 0;
    const nsecs_t currentTime = systemTime(CLOCK_MONOTONIC);
    const nsecs_t lastWallTime = mLastWallTime;

    mLastWallTime = currentTime;
    if (!mRunning) {
        return false;
    }

    std::unique_lock lock{mSwapLock};
    mCurrentTime += currentTime - lastWallTime;

    if (!mNextSnapshot.valid()) {
        // Need to trigger onDraw in order to start decoding the next frame.
        *outDelay = mTimeToShowNextSnapshot - mCurrentTime;
        return true;
    }

    if (mTimeToShowNextSnapshot > mCurrentTime) {
        *outDelay = mTimeToShowNextSnapshot - mCurrentTime;
    } else if (nextSnapshotReady()) {
        // We have not yet updated mTimeToShowNextSnapshot. Read frame duration
        // directly from mSkAnimatedImage.
        lock.unlock();
        std::unique_lock imageLock{mImageLock};
        *outDelay = ms2ns(mSkAnimatedImage->currentFrameDuration());
        return true;
    } else {
        // The next snapshot has not yet been decoded, but we've already passed
        // time to draw it. There's not a good way to know when decoding will
        // finish, so request an update immediately.
        *outDelay = 0;
    }

    return false;
}

// Only called on the AnimatedImageThread.
AnimatedImageDrawable::Snapshot AnimatedImageDrawable::decodeNextFrame() {
    Snapshot snap;
    {
        std::unique_lock lock{mImageLock};
        snap.mDurationMS = mSkAnimatedImage->decodeNextFrame();
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
        snap.mDurationMS = mSkAnimatedImage->currentFrameDuration();
    }

    return snap;
}

// Only called on the RenderThread.
void AnimatedImageDrawable::onDraw(SkCanvas* canvas) {
    std::optional<SkPaint> lazyPaint;
    SkAutoCanvasRestore acr(canvas, false);
    if (mProperties.mAlpha != SK_AlphaOPAQUE || mProperties.mColorFilter.get()) {
        lazyPaint.emplace();
        lazyPaint->setAlpha(mProperties.mAlpha);
        lazyPaint->setColorFilter(mProperties.mColorFilter);
        lazyPaint->setFilterQuality(kLow_SkFilterQuality);
    }
    if (mProperties.mMirrored) {
        canvas->save();
        canvas->translate(mSkAnimatedImage->getBounds().width(), 0);
        canvas->scale(-1, 1);
    }

    const bool starting = mStarting;
    mStarting = false;

    const bool drawDirectly = !mSnapshot.mPic;
    if (drawDirectly) {
        // The image is not animating, and never was. Draw directly from
        // mSkAnimatedImage.
        if (lazyPaint) {
            canvas->saveLayer(mSkAnimatedImage->getBounds(), &*lazyPaint);
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
            const nsecs_t timeToShowCurrentSnap = mTimeToShowNextSnapshot;
            if (mSnapshot.mDurationMS == SkAnimatedImage::kFinished) {
                finalFrame = true;
                mRunning = false;
            } else {
                mTimeToShowNextSnapshot += ms2ns(mSnapshot.mDurationMS);
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
        canvas->drawPicture(mSnapshot.mPic, nullptr, lazyPaint ? &*lazyPaint : nullptr);
    }

    if (finalFrame) {
        if (mEndListener) {
            mEndListener->onAnimationEnd();
        }
    }
}

int AnimatedImageDrawable::drawStaging(SkCanvas* canvas) {
    SkAutoCanvasRestore acr(canvas, false);
    if (mStagingProperties.mAlpha != SK_AlphaOPAQUE || mStagingProperties.mColorFilter.get()) {
        SkPaint paint;
        paint.setAlpha(mStagingProperties.mAlpha);
        paint.setColorFilter(mStagingProperties.mColorFilter);
        canvas->saveLayer(mSkAnimatedImage->getBounds(), &paint);
    }
    if (mStagingProperties.mMirrored) {
        canvas->save();
        canvas->translate(mSkAnimatedImage->getBounds().width(), 0);
        canvas->scale(-1, 1);
    }

    if (!mRunning) {
        // Continue drawing the current frame, and return 0 to indicate no need
        // to redraw.
        std::unique_lock lock{mImageLock};
        canvas->drawDrawable(mSkAnimatedImage.get());
        return 0;
    }

    if (mStarting) {
        mStarting = false;
        int durationMS = 0;
        {
            std::unique_lock lock{mImageLock};
            mSkAnimatedImage->reset();
            durationMS = mSkAnimatedImage->currentFrameDuration();
        }
        {
            std::unique_lock lock{mSwapLock};
            mLastWallTime = 0;
            // The current time will be added later, below.
            mTimeToShowNextSnapshot = ms2ns(durationMS);
        }
    }

    bool update = false;
    {
        const nsecs_t currentTime = systemTime(CLOCK_MONOTONIC);
        std::unique_lock lock{mSwapLock};
        // mLastWallTime starts off at 0. If it is still 0, just update it to
        // the current time and avoid updating
        if (mLastWallTime == 0) {
            mCurrentTime = currentTime;
            // mTimeToShowNextSnapshot is already set to the duration of the
            // first frame.
            mTimeToShowNextSnapshot += currentTime;
        } else if (mRunning) {
            mCurrentTime += currentTime - mLastWallTime;
            update = mCurrentTime >= mTimeToShowNextSnapshot;
        }
        mLastWallTime = currentTime;
    }

    int durationMS = 0;
    {
        std::unique_lock lock{mImageLock};
        if (update) {
            durationMS = mSkAnimatedImage->decodeNextFrame();
        }

        canvas->drawDrawable(mSkAnimatedImage.get());
    }

    std::unique_lock lock{mSwapLock};
    if (update) {
        if (durationMS == SkAnimatedImage::kFinished) {
            mRunning = false;
            return SkAnimatedImage::kFinished;
        }

        const nsecs_t timeToShowCurrentSnapshot = mTimeToShowNextSnapshot;
        mTimeToShowNextSnapshot += ms2ns(durationMS);
        if (mCurrentTime >= mTimeToShowNextSnapshot) {
            // As in onDraw, prevent speedy catch-up behavior.
            mCurrentTime = timeToShowCurrentSnapshot;
        }
    }

    return ns2ms(mTimeToShowNextSnapshot - mCurrentTime);
}

}  // namespace android
