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

#include "thread/Task.h"
#include "thread/TaskManager.h"
#include "thread/TaskProcessor.h"
#include "utils/TraceUtils.h"

#include <SkPicture.h>
#include <SkRefCnt.h>
#include <SkTime.h>
#include <SkTLazy.h>

namespace android {

AnimatedImageDrawable::AnimatedImageDrawable(sk_sp<SkAnimatedImage> animatedImage)
    : mSkAnimatedImage(std::move(animatedImage)) { }

void AnimatedImageDrawable::syncProperties() {
    mAlpha = mStagingAlpha;
    mColorFilter = mStagingColorFilter;
}

bool AnimatedImageDrawable::start() {
    SkAutoExclusive lock(mLock);
    if (mSkAnimatedImage->isRunning()) {
        return false;
    }

    if (!mSnapshot) {
        mSnapshot.reset(mSkAnimatedImage->newPictureSnapshot());
    }

    // While stopped, update() does not decode, but it does advance the time.
    // This prevents us from skipping ahead when we resume.
    const double currentTime = SkTime::GetMSecs();
    mSkAnimatedImage->update(currentTime);
    mSkAnimatedImage->start();
    return mSkAnimatedImage->isRunning();
}

void AnimatedImageDrawable::stop() {
    SkAutoExclusive lock(mLock);
    mSkAnimatedImage->stop();
}

bool AnimatedImageDrawable::isRunning() {
    return mSkAnimatedImage->isRunning();
}

// This is really a Task<void> but that doesn't really work when Future<>
// expects to be able to get/set a value
class AnimatedImageDrawable::AnimatedImageTask : public uirenderer::Task<bool> {
public:
    AnimatedImageTask(AnimatedImageDrawable* animatedImageDrawable)
            : mAnimatedImageDrawable(sk_ref_sp(animatedImageDrawable)) {}

    sk_sp<AnimatedImageDrawable> mAnimatedImageDrawable;
    bool mIsCompleted = false;
};

class AnimatedImageDrawable::AnimatedImageTaskProcessor : public uirenderer::TaskProcessor<bool> {
public:
    explicit AnimatedImageTaskProcessor(uirenderer::TaskManager* taskManager)
            : uirenderer::TaskProcessor<bool>(taskManager) {}
    ~AnimatedImageTaskProcessor() {}

    virtual void onProcess(const sp<uirenderer::Task<bool>>& task) override {
        ATRACE_NAME("Updating AnimatedImageDrawables");
        AnimatedImageTask* t = static_cast<AnimatedImageTask*>(task.get());
        t->mAnimatedImageDrawable->update();
        t->mIsCompleted = true;
        task->setResult(true);
    };
};

void AnimatedImageDrawable::scheduleUpdate(uirenderer::TaskManager* taskManager) {
    if (!mSkAnimatedImage->isRunning()
            || (mDecodeTask.get() != nullptr && !mDecodeTask->mIsCompleted)) {
        return;
    }

    if (!mDecodeTaskProcessor.get()) {
        mDecodeTaskProcessor = new AnimatedImageTaskProcessor(taskManager);
    }

    // TODO get one frame ahead and only schedule updates when you need to replenish
    mDecodeTask = new AnimatedImageTask(this);
    mDecodeTaskProcessor->add(mDecodeTask);
}

void AnimatedImageDrawable::update() {
    SkAutoExclusive lock(mLock);

    if (!mSkAnimatedImage->isRunning()) {
        return;
    }

    const double currentTime = SkTime::GetMSecs();
    if (currentTime >= mNextFrameTime) {
        mNextFrameTime = mSkAnimatedImage->update(currentTime);
        mSnapshot.reset(mSkAnimatedImage->newPictureSnapshot());
        mIsDirty = true;
    }
}

void AnimatedImageDrawable::onDraw(SkCanvas* canvas) {
    SkTLazy<SkPaint> lazyPaint;
    if (mAlpha != SK_AlphaOPAQUE || mColorFilter.get()) {
        lazyPaint.init();
        lazyPaint.get()->setAlpha(mAlpha);
        lazyPaint.get()->setColorFilter(mColorFilter);
        lazyPaint.get()->setFilterQuality(kLow_SkFilterQuality);
    }

    SkAutoExclusive lock(mLock);
    if (mSnapshot) {
        canvas->drawPicture(mSnapshot, nullptr, lazyPaint.getMaybeNull());
    } else {
        // TODO: we could potentially keep the cached surface around if there is a paint and we know
        // the drawable is attached to the view system
        SkAutoCanvasRestore acr(canvas, false);
        if (lazyPaint.isValid()) {
            canvas->saveLayer(mSkAnimatedImage->getBounds(), lazyPaint.get());
        }
        mSkAnimatedImage->draw(canvas);
    }

    mIsDirty = false;
}

double AnimatedImageDrawable::drawStaging(SkCanvas* canvas) {
    // update the drawable with the current time
    double nextUpdate = mSkAnimatedImage->update(SkTime::GetMSecs());
    SkAutoCanvasRestore acr(canvas, false);
    if (mStagingAlpha != SK_AlphaOPAQUE || mStagingColorFilter.get()) {
        SkPaint paint;
        paint.setAlpha(mStagingAlpha);
        paint.setColorFilter(mStagingColorFilter);
        canvas->saveLayer(mSkAnimatedImage->getBounds(), &paint);
    }
    canvas->drawDrawable(mSkAnimatedImage.get());
    return nextUpdate;
}

};  // namespace android
