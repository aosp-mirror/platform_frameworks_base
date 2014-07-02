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

package android.view;

import android.animation.ValueAnimator;

import java.util.ArrayList;

/**
 * This class provides compatibility for things like start listeners &
 * start delays for use by ViewPropertyAnimator and ObjectAnimator
 * @hide
 */
public class RenderNodeAnimatorCompat extends RenderNodeAnimator {

    private long mUnscaledStartDelay = 0;
    private long mStartDelay = 0;
    private long mStartTime;
    private boolean mCanceled;

    public RenderNodeAnimatorCompat(int property, float finalValue) {
        super(property, finalValue);
    }

    @Override
    public void setStartDelay(long startDelay) {
        mUnscaledStartDelay = startDelay;
        mStartDelay = (long) (ValueAnimator.getDurationScale() * startDelay);
    }

    @Override
    public long getStartDelay() {
        return mUnscaledStartDelay;
    }

    @Override
    public void start() {
        if (mStartDelay <= 0) {
            doStart();
        } else {
            getHelper().addDelayedAnimation(this);
        }
    }

    private void doStart() {
        if (!mCanceled) {
            super.start();
        }
    }

    @Override
    public void cancel() {
        mCanceled = true;
        super.cancel();
    }

    /**
     * @return true if the animator was started, false if still delayed
     */
    private boolean processDelayed(long frameTimeMs) {
        if (mCanceled) return true;

        if (mStartTime == 0) {
            mStartTime = frameTimeMs;
        } else if ((frameTimeMs - mStartTime) >= mStartDelay) {
            doStart();
            return true;
        }
        return false;
    }

    private static AnimationHelper getHelper() {
        AnimationHelper helper = sAnimationHelper.get();
        if (helper == null) {
            helper = new AnimationHelper();
            sAnimationHelper.set(helper);
        }
        return helper;
    }

    private static ThreadLocal<AnimationHelper> sAnimationHelper =
            new ThreadLocal<AnimationHelper>();

    private static class AnimationHelper implements Runnable {

        private ArrayList<RenderNodeAnimatorCompat> mDelayedAnims = new ArrayList<RenderNodeAnimatorCompat>();
        private final Choreographer mChoreographer;
        private boolean mCallbackScheduled;

        public AnimationHelper() {
            mChoreographer = Choreographer.getInstance();
        }

        public void addDelayedAnimation(RenderNodeAnimatorCompat animator) {
            mDelayedAnims.add(animator);
            scheduleCallback();
        }

        private void scheduleCallback() {
            if (!mCallbackScheduled) {
                mCallbackScheduled = true;
                mChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION, this, null);
            }
        }

        @Override
        public void run() {
            long frameTimeMs = mChoreographer.getFrameTime();
            mCallbackScheduled = false;

            int end = 0;
            for (int i = 0; i < mDelayedAnims.size(); i++) {
                RenderNodeAnimatorCompat animator = mDelayedAnims.get(i);
                if (!animator.processDelayed(frameTimeMs)) {
                    if (end != i) {
                        mDelayedAnims.set(end, animator);
                    }
                    end++;
                }
            }
            while (mDelayedAnims.size() > end) {
                mDelayedAnims.remove(mDelayedAnims.size() - 1);
            }

            if (mDelayedAnims.size() > 0) {
                scheduleCallback();
            }
        }
    }
}
