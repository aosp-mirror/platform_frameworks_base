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
#include <utils/RefBase.h>

#include <SkAnimatedImage.h>
#include <SkCanvas.h>
#include <SkColorFilter.h>
#include <SkDrawable.h>
#include <SkMutex.h>

class SkPicture;

namespace android {

namespace uirenderer {
class TaskManager;
}

class OnAnimationEndListener {
public:
    virtual ~OnAnimationEndListener() {}

    virtual void onAnimationEnd() = 0;
};

/**
 * Native component of android.graphics.drawable.AnimatedImageDrawables.java.  This class can be
 * drawn into Canvas.h and maintains the state needed to drive the animation from the RenderThread.
 */
class ANDROID_API AnimatedImageDrawable : public SkDrawable {
public:
    AnimatedImageDrawable(sk_sp<SkAnimatedImage> animatedImage);

    /**
     * This returns true if the animation has updated and signals that the next draw will contain
     * new content.
     */
    bool isDirty() const { return mIsDirty; }

    int getStagingAlpha() const { return mStagingAlpha; }
    void setStagingAlpha(int alpha) { mStagingAlpha = alpha; }
    void setStagingColorFilter(sk_sp<SkColorFilter> filter) { mStagingColorFilter = filter; }
    void syncProperties();

    virtual SkRect onGetBounds() override {
        return mSkAnimatedImage->getBounds();
    }

    double drawStaging(SkCanvas* canvas);

    // Returns true if the animation was started; false otherwise (e.g. it was already running)
    bool start();
    void stop();
    bool isRunning();
    void setRepetitionCount(int count) {
        mSkAnimatedImage->setRepetitionCount(count);
    }

    void setOnAnimationEndListener(std::unique_ptr<OnAnimationEndListener> listener) {
        mEndListener = std::move(listener);
    }

    void scheduleUpdate(uirenderer::TaskManager* taskManager);

protected:
    virtual void onDraw(SkCanvas* canvas) override;

private:
    void update();

    sk_sp<SkAnimatedImage> mSkAnimatedImage;
    sk_sp<SkPicture> mSnapshot;
    SkMutex mLock;

    int mStagingAlpha = SK_AlphaOPAQUE;
    sk_sp<SkColorFilter> mStagingColorFilter;

    int mAlpha = SK_AlphaOPAQUE;
    sk_sp<SkColorFilter> mColorFilter;
    double mNextFrameTime = 0.0;
    bool mIsDirty = false;

    class AnimatedImageTask;
    class AnimatedImageTaskProcessor;
    sp<AnimatedImageTask> mDecodeTask;
    sp<AnimatedImageTaskProcessor> mDecodeTaskProcessor;

    std::unique_ptr<OnAnimationEndListener> mEndListener;
};

};  // namespace android
