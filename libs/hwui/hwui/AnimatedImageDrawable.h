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

#pragma once

#include <cutils/compiler.h>
#include <utils/Macros.h>
#include <utils/RefBase.h>
#include <utils/Timers.h>

#include <SkAnimatedImage.h>
#include <SkCanvas.h>
#include <SkColorFilter.h>
#include <SkDrawable.h>
#include <SkPicture.h>

#include <future>
#include <mutex>

namespace android {

class OnAnimationEndListener {
public:
    virtual ~OnAnimationEndListener() {}

    virtual void onAnimationEnd() = 0;
};

/**
 * Native component of android.graphics.drawable.AnimatedImageDrawables.java.
 * This class can be drawn into Canvas.h and maintains the state needed to drive
 * the animation from the RenderThread.
 */
class ANDROID_API AnimatedImageDrawable : public SkDrawable {
public:
    // bytesUsed includes the approximate sizes of the SkAnimatedImage and the SkPictures in the
    // Snapshots.
    AnimatedImageDrawable(sk_sp<SkAnimatedImage> animatedImage, size_t bytesUsed);

    /**
     * This updates the internal time and returns true if the image needs
     * to be redrawn this frame.
     *
     * This is called on RenderThread, while the UI thread is locked.
     *
     * @param outDelay Nanoseconds in the future when the following frame
     *      will need to be drawn. 0 if not running.
     */
    bool isDirty(nsecs_t* outDelay);

    int getStagingAlpha() const { return mStagingProperties.mAlpha; }
    void setStagingAlpha(int alpha) { mStagingProperties.mAlpha = alpha; }
    void setStagingColorFilter(sk_sp<SkColorFilter> filter) {
        mStagingProperties.mColorFilter = filter;
    }
    void setStagingMirrored(bool mirrored) { mStagingProperties.mMirrored = mirrored; }
    void syncProperties();

    virtual SkRect onGetBounds() override { return mSkAnimatedImage->getBounds(); }

    // Draw to software canvas, and return time to next draw.
    // 0 means the animation is not running.
    // -1 means the animation advanced to the final frame.
    int drawStaging(SkCanvas* canvas);

    // Returns true if the animation was started; false otherwise (e.g. it was
    // already running)
    bool start();
    // Returns true if the animation was stopped; false otherwise (e.g. it was
    // already stopped)
    bool stop();
    bool isRunning();
    int getRepetitionCount() const { return mSkAnimatedImage->getRepetitionCount(); }
    void setRepetitionCount(int count) { mSkAnimatedImage->setRepetitionCount(count); }

    void setOnAnimationEndListener(std::unique_ptr<OnAnimationEndListener> listener) {
        mEndListener = std::move(listener);
    }

    struct Snapshot {
        sk_sp<SkPicture> mPic;
        int mDurationMS;

        Snapshot() = default;

        Snapshot(Snapshot&&) = default;
        Snapshot& operator=(Snapshot&&) = default;

        PREVENT_COPY_AND_ASSIGN(Snapshot);
    };

    // These are only called on AnimatedImageThread.
    Snapshot decodeNextFrame();
    Snapshot reset();

    size_t byteSize() const { return sizeof(*this) + mBytesUsed; }

protected:
    virtual void onDraw(SkCanvas* canvas) override;

private:
    sk_sp<SkAnimatedImage> mSkAnimatedImage;
    const size_t mBytesUsed;

    bool mRunning = false;
    bool mStarting = false;

    // A snapshot of the current frame to draw.
    Snapshot mSnapshot;

    std::future<Snapshot> mNextSnapshot;

    bool nextSnapshotReady() const;

    // When to switch from mSnapshot to mNextSnapshot.
    nsecs_t mTimeToShowNextSnapshot = 0;

    // The current time for the drawable itself.
    nsecs_t mCurrentTime = 0;

    // The wall clock of the last time we called isDirty.
    nsecs_t mLastWallTime = 0;

    // Locked when assigning snapshots and times. Operations while this is held
    // should be short.
    std::mutex mSwapLock;

    // Locked when mSkAnimatedImage is being updated or drawn.
    std::mutex mImageLock;

    struct Properties {
        int mAlpha = SK_AlphaOPAQUE;
        sk_sp<SkColorFilter> mColorFilter;
        bool mMirrored = false;

        Properties() = default;
        Properties(Properties&) = default;
        Properties& operator=(Properties&) = default;
    };

    Properties mStagingProperties;
    Properties mProperties;

    std::unique_ptr<OnAnimationEndListener> mEndListener;
};

}  // namespace android
