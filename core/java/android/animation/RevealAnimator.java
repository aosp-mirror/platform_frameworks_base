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

package android.animation;

import android.view.RenderNodeAnimator;
import android.view.View;

/**
 * Reveals a View with an animated clipping circle.
 * The clipping is implemented efficiently by talking to a private reveal API on View.
 * This hidden class currently only accessed by the {@link android.view.View}.
 *
 * @hide
 */
public class RevealAnimator extends ValueAnimator {

    private View mClipView;
    private int mX, mY;
    private boolean mInverseClip;
    private float mStartRadius, mEndRadius;
    private float mDelta;
    private boolean mMayRunAsync;

    // If this is null, we are running on the UI thread driven by the base
    // ValueAnimator class. If this is not null, forward requests on to this
    // Animator instead.
    private RenderNodeAnimator mRtAnimator;

    public RevealAnimator(View clipView, int x, int y,
            float startRadius, float endRadius, boolean inverseClip) {
        mClipView = clipView;
        mStartRadius = startRadius;
        mEndRadius = endRadius;
        mDelta = endRadius - startRadius;
        mX = x;
        mY = y;
        mInverseClip = inverseClip;
        super.setValues(PropertyValuesHolder.ofFloat("radius", startRadius, endRadius));
    }

    @Override
    void animateValue(float fraction) {
        super.animateValue(fraction);
        fraction = getAnimatedFraction();
        float radius = mStartRadius + (mDelta * fraction);
        mClipView.setRevealClip(true, mInverseClip, mX, mY, radius);
    }

    @Override
    protected void endAnimation(AnimationHandler handler) {
        mClipView.setRevealClip(false, false, 0, 0, 0);
        super.endAnimation(handler);
    }

    @Override
    public void setAllowRunningAsynchronously(boolean mayRunAsync) {
        mMayRunAsync = mayRunAsync;
    }

    private boolean canRunAsync() {
        if (!mMayRunAsync) {
            return false;
        }
        if (mUpdateListeners != null && mUpdateListeners.size() > 0) {
            return false;
        }
        // TODO: Have RNA support this
        if (getRepeatCount() != 0) {
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        if (mRtAnimator != null) {
            mRtAnimator.end();
            mRtAnimator = null;
        }
        if (canRunAsync()) {
            mRtAnimator = new RenderNodeAnimator(mX, mY, mInverseClip, mStartRadius, mEndRadius);
            mRtAnimator.setDuration(getDuration());
            mRtAnimator.setInterpolator(getInterpolator());
            mRtAnimator.setTarget(mClipView);
            // TODO: Listeners
            mRtAnimator.start();
        } else {
            super.start();
        }
    }

    @Override
    public void cancel() {
        if (mRtAnimator != null) {
            mRtAnimator.cancel();
        } else {
            super.cancel();
        }
    }

    @Override
    public void end() {
        if (mRtAnimator != null) {
            mRtAnimator.end();
        } else {
            super.end();
        }
    }

    @Override
    public void resume() {
        if (mRtAnimator != null) {
            // TODO: Support? Reject?
        } else {
            super.resume();
        }
    }

    @Override
    public void pause() {
        if (mRtAnimator != null) {
            // TODO: see resume()
        } else {
            super.pause();
        }
    }

    @Override
    public boolean isRunning() {
        if (mRtAnimator != null) {
            return mRtAnimator.isRunning();
        } else {
            return super.isRunning();
        }
    }

    @Override
    public boolean isStarted() {
        if (mRtAnimator != null) {
            return mRtAnimator.isStarted();
        } else {
            return super.isStarted();
        }
    }

    @Override
    public void reverse() {
        if (mRtAnimator != null) {
            // TODO support
        } else {
            super.reverse();
        }
    }

    @Override
    public RevealAnimator clone() {
        RevealAnimator anim = (RevealAnimator) super.clone();
        anim.mRtAnimator = null;
        return anim;
    }
}
